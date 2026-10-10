package de.regepower.dualfiles

import android.net.Uri
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLSocket

/**
 * Small WebDAV client (RFC 4918) on its own minimal HTTP/1.1 (Android's HttpURLConnection refuses
 * PROPFIND, MKCOL and MOVE). One connection per request, Basic authentication. Uploads are buffered in
 * the cache first, so every PUT has a Content-Length (some servers refuse chunked uploads).
 */
internal class DavClient(private val cfg: NetConfig, private val cacheDir: File) : NetClient {
    private val https get() = cfg.type == NetType.DAVS

    private class Response(val code: Int, val headers: Map<String, String>, val body: InputStream, val socket: Socket) {
        fun text(): String = body.use { it.readBytes().toString(Charsets.UTF_8) }.also { socket.close() }
        fun close() = socket.close()
    }

    /** Percent-encoded absolute path on the server. */
    private fun url(path: String): String = path.split('/').joinToString("/") { Uri.encode(it) }

    private fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: InputStream? = null, length: Long = 0): Response {
        val plain = Socket()
        plain.connect(InetSocketAddress(cfg.host, cfg.port), TIMEOUT)
        plain.soTimeout = TIMEOUT
        val s = if (https) (Net.sslFactory(cfg).createSocket(plain, cfg.host, cfg.port, true) as SSLSocket).also { it.startHandshake() } else plain
        val out = s.getOutputStream().buffered(64 * 1024)
        val sb = StringBuilder()
        sb.append(method).append(' ').append(url(path)).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(cfg.host).append(if (cfg.port != (if (https) 443 else 80)) ":${cfg.port}" else "").append("\r\n")
        if (cfg.user.isNotEmpty()) {
            val token = Base64.encodeToString("${cfg.user}:${cfg.password}".toByteArray(), Base64.NO_WRAP)
            sb.append("Authorization: Basic ").append(token).append("\r\n")
        }
        sb.append("User-Agent: DualFiles\r\nConnection: close\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(if (body != null) length else 0).append("\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        body?.copyTo(out)
        out.flush()

        val inp = BufferedInputStream(s.getInputStream())
        val status = readLine(inp)
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad response: $status")
        val h = HashMap<String, String>()
        while (true) {
            val l = readLine(inp)
            if (l.isEmpty()) break
            h[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
        }
        val content: InputStream = when {
            h["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> Chunked(inp)
            h["content-length"] != null -> Limited(inp, h["content-length"]!!.toLong())
            else -> inp
        }
        return Response(code, h, content, s)
    }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun check(r: Response, vararg ok: Int) {
        if (r.code !in ok) {
            r.close()
            throw IOException(if (r.code == 401) "401: user or password wrong" else "HTTP ${r.code}")
        }
    }

    override fun list(path: String): List<Saf.Doc> {
        val dir = path.trimEnd('/') + "/"
        val body = PROPFIND.toByteArray()
        val r = request("PROPFIND", dir, mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8"), body.inputStream(), body.size.toLong())
        check(r, 207)
        return parse(r.text(), dir)
    }

    private fun parse(xml: String, dir: String): List<Saf.Doc> {
        val out = ArrayList<Saf.Doc>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(xml.reader())
        var href = ""
        var isDir = false
        var size = 0L
        var modified = 0L
        var text = StringBuilder()
        val dates = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "response" -> { href = ""; isDir = false; size = 0; modified = 0 }
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: 0
                    "getlastmodified" -> modified = try { dates.parse(text.toString().trim())?.time ?: 0 } catch (e: Exception) { 0 }
                    "response" -> {
                        // href may be a full URL or a path, always percent-encoded
                        val decoded = Uri.decode(Uri.parse(href).path ?: href)
                        if (decoded.trimEnd('/') != dir.trimEnd('/')) {
                            val name = decoded.trimEnd('/').substringAfterLast('/')
                            if (name.isNotEmpty()) out.add(Saf.Doc(name, name, isDir, size, modified))
                        }
                    }
                }
            }
        }
        return out
    }

    override fun read(path: String, done: () -> Unit): InputStream {
        val r = try {
            request("GET", path)
        } catch (e: IOException) {
            done()
            throw e
        }
        if (r.code != 200) {
            done()
            check(r, 200)
        }
        return object : FilterInputStream(r.body) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                r.close()
                done()
            }
        }
    }

    override fun write(path: String, done: () -> Unit): OutputStream {
        val tmp = File.createTempFile("put", null, cacheDir)
        return object : FilterOutputStream(FileOutputStream(tmp).buffered(64 * 1024)) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                try {
                    out.close()
                    val r = tmp.inputStream().use { request("PUT", path, emptyMap(), it, tmp.length()) }
                    check(r, 200, 201, 204)
                    r.close()
                } finally {
                    tmp.delete()
                    done()
                }
            }
        }
    }

    override fun delete(path: String, isDir: Boolean) {
        val r = request("DELETE", if (isDir) path.trimEnd('/') + "/" else path)
        check(r, 200, 204)
        r.close()
    }

    override fun mkdir(path: String) {
        val r = request("MKCOL", path.trimEnd('/') + "/")
        check(r, 201)
        r.close()
    }

    override fun rename(from: String, to: String) {
        val scheme = if (https) "https" else "http"
        val r = request("MOVE", from, mapOf("Destination" to "$scheme://${cfg.host}:${cfg.port}${url(to)}", "Overwrite" to "F"))
        check(r, 201, 204)
        r.close()
    }

    override fun alive() = true
    override fun close() = Unit

    /** Body with a known length. */
    private class Limited(i: InputStream, private var left: Long) : FilterInputStream(i) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    /** "Transfer-Encoding: chunked" body. */
    private class Chunked(private val i: InputStream) : InputStream() {
        private var left = 0L
        private var end = false

        private fun next(): Boolean {
            if (end) return false
            if (left == 0L) {
                var l = line()
                if (l.isEmpty()) l = line()   // CRLF after the previous chunk
                left = l.substringBefore(';').trim().toLongOrNull(16) ?: 0
                if (left == 0L) {
                    end = true
                    return false
                }
            }
            return true
        }

        private fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val b = i.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }

        override fun read(): Int {
            if (!next()) return -1
            val b = i.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!next()) return -1
            val n = i.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private companion object {
        const val TIMEOUT = 20_000
        const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}
