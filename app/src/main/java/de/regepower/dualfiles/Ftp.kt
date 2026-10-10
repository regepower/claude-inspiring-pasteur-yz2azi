package de.regepower.dualfiles

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLSocket

/**
 * Small FTP client (RFC 959 with passive mode, MLSD from RFC 3659, FTPS from RFC 4217).
 * One object is one control connection; [Net] keeps a few of them per server.
 */
internal class FtpClient(private val cfg: NetConfig) : NetClient {
    private lateinit var sock: Socket
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var mlsd = false
    private val tls get() = cfg.type == NetType.FTPS || cfg.type == NetType.FTPS_IMPLICIT

    fun connect() {
        val plain = Socket()
        plain.connect(InetSocketAddress(cfg.host, cfg.port), TIMEOUT)
        plain.soTimeout = TIMEOUT
        sock = if (cfg.type == NetType.FTPS_IMPLICIT) wrap(plain) else plain
        io()
        expect(reply(), 220)
        if (cfg.type == NetType.FTPS) {
            expect(cmd("AUTH TLS"), 234)
            sock = wrap(sock)
            io()
        }
        val user = cfg.user.ifEmpty { "anonymous" }
        var r = cmd("USER $user")
        if (r.first == 331) r = cmd("PASS " + cfg.password.ifEmpty { "anonymous@" })
        if (r.first != 230 && r.first != 202) throw IOException("Login: ${r.second}")
        if (tls) {
            cmd("PBSZ 0")
            expect(cmd("PROT P"), 200)
        }
        expect(cmd("TYPE I"), 200)
        cmd("OPTS UTF8 ON")
        mlsd = cmd("FEAT").second.uppercase().contains("MLSD")
    }

    private fun wrap(s: Socket): Socket {
        val ssl = Net.sslFactory(cfg).createSocket(s, cfg.host, cfg.port, true) as SSLSocket
        ssl.startHandshake()
        return ssl
    }

    private fun io() {
        input = sock.getInputStream().buffered()
        output = sock.getOutputStream()
    }

    private fun line(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("connection closed")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("UTF-8")
    }

    /** One reply, multi-line replies ("123-" … "123 ") joined. */
    private fun reply(): Pair<Int, String> {
        var l = line()
        val code = l.take(3).toIntOrNull() ?: throw IOException("bad reply: $l")
        val text = StringBuilder(l)
        if (l.length > 3 && l[3] == '-') {
            do {
                l = line()
                text.append('\n').append(l)
            } while (!(l.startsWith("$code ") || l == "$code"))
        }
        return Pair(code, text.toString())
    }

    private fun cmd(c: String): Pair<Int, String> {
        output.write((c + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
        return reply()
    }

    private fun expect(r: Pair<Int, String>, vararg ok: Int) {
        if (r.first !in ok) throw IOException(r.second)
    }

    /** Opens the data connection for [command] (passive mode; the host of the control connection is used). */
    private fun data(command: String): Socket {
        val port = cmd("EPSV").let { r ->
            if (r.first == 229) Regex("\\|\\|\\|(\\d+)\\|").find(r.second)?.groupValues?.get(1)?.toInt()
            else null
        } ?: cmd("PASV").let { r ->
            expect(r, 227)
            val n = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(r.second)?.groupValues ?: throw IOException(r.second)
            n[5].toInt() * 256 + n[6].toInt()
        }
        val d = Socket()
        d.connect(InetSocketAddress(cfg.host, port), TIMEOUT)
        d.soTimeout = TIMEOUT
        val r = cmd(command)
        if (r.first != 150 && r.first != 125) {
            d.close()
            throw IOException(r.second)
        }
        if (!tls) return d
        val ssl = Net.sslFactory(cfg).createSocket(d, cfg.host, port, true) as SSLSocket
        ssl.startHandshake()
        return ssl
    }

    override fun list(path: String): List<Saf.Doc> {
        val text = data((if (mlsd) "MLSD " else "LIST -a ") + path).use { d -> d.getInputStream().readBytes().toString(Charsets.UTF_8) }
        expect(reply(), 226, 250)
        return text.lineSequence().filter { it.isNotBlank() }.mapNotNull { if (mlsd) parseMlsd(it) else parseList(it) }
            .filter { it.name != "." && it.name != ".." }.toList()
    }

    private fun parseMlsd(l: String): Saf.Doc? {
        val sp = l.indexOf(' ')
        if (sp < 0) return null
        val facts = l.substring(0, sp).split(';').filter { it.contains('=') }
            .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
        val type = facts["type"]?.lowercase() ?: return null
        if (type == "cdir" || type == "pdir") return null
        val modified = facts["modify"]?.let { parseTime(it) } ?: 0L
        return Saf.Doc(l.substring(sp + 1), l.substring(sp + 1), type == "dir", facts["size"]?.toLongOrNull() ?: 0, modified)
    }

    // "drwxr-xr-x 2 user group 4096 Jan 01 12:00 name" (Unix style; the name may contain spaces)
    private fun parseList(l: String): Saf.Doc? {
        val m = Regex("^([dl-])\\S*\\s+\\S+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\w{3}\\s+\\d+\\s+[\\d:]+)\\s+(.+)$").find(l) ?: return null
        var name = m.groupValues[4]
        if (m.groupValues[1] == "l") name = name.substringBefore(" -> ")
        return Saf.Doc(name, name, m.groupValues[1] == "d", m.groupValues[2].toLongOrNull() ?: 0, 0)
    }

    private fun parseTime(t: String): Long? = try {
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(t.take(14))?.time
    } catch (e: Exception) {
        null
    }

    /** Download; closing the stream finishes the transfer. [done] gets this client back. */
    override fun read(path: String, done: () -> Unit): InputStream {
        val d = data("RETR $path")
        return object : FilterInputStream(d.getInputStream()) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                try {
                    d.close()
                    reply()
                } finally {
                    done()
                }
            }
        }
    }

    /** Upload; closing the stream finishes the transfer and throws if the server refused it. */
    override fun write(path: String, done: () -> Unit): OutputStream {
        val d = data("STOR $path")
        return object : FilterOutputStream(d.getOutputStream().buffered(64 * 1024)) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                try {
                    out.flush()
                    d.close()
                    expect(reply(), 226, 250)
                } finally {
                    done()
                }
            }
        }
    }

    override fun delete(path: String, isDir: Boolean) = expect(cmd((if (isDir) "RMD " else "DELE ") + path), 250, 200)

    override fun mkdir(path: String) = expect(cmd("MKD $path"), 257, 250)

    override fun rename(from: String, to: String) {
        expect(cmd("RNFR $from"), 350)
        expect(cmd("RNTO $to"), 250)
    }

    override fun alive(): Boolean = try {
        !sock.isClosed && cmd("NOOP").first == 200
    } catch (e: IOException) {
        false
    }

    override fun close() {
        try {
            cmd("QUIT")
        } catch (e: Exception) {
            // closing anyway
        }
        try {
            sock.close()
        } catch (e: IOException) {
            // already gone
        }
    }

    private companion object {
        const val TIMEOUT = 15_000
    }
}
