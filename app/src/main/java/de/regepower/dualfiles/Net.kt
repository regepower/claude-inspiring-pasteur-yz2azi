package de.regepower.dualfiles

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

internal enum class NetType(val label: Int, val defaultPort: Int) {
    FTP(R.string.net_ftp, 21),
    FTPS(R.string.net_ftps, 21),
    FTPS_IMPLICIT(R.string.net_ftps_implicit, 990),
    DAVS(R.string.net_davs, 443),
    DAV(R.string.net_dav, 80),
}

internal class NetConfig(
    val id: String,
    val type: NetType,
    val host: String,
    val port: Int,
    val path: String,        // start folder on the server, "/" or "/remote.php/dav/files/me"
    val user: String,
    val password: String,
    val name: String,
    val insecure: Boolean,   // accept any TLS certificate (home NAS with its own certificate)
)

/** What a server connection can do; paths are absolute on the server. */
internal interface NetClient : Closeable {
    fun list(path: String): List<Saf.Doc>
    fun read(path: String, done: () -> Unit): InputStream
    fun write(path: String, done: () -> Unit): OutputStream
    fun delete(path: String, isDir: Boolean)
    fun mkdir(path: String)
    fun rename(from: String, to: String)
    fun alive(): Boolean
}

/**
 * Network storages (FTP, FTPS, WebDAV) as virtual folders "/net/<id>/…", like the Saf folders.
 * Connections are kept per server and reused; listings are cached for a few seconds because one
 * screen asks for the same folder several times (tree, list, item counts).
 */
internal object Net {
    const val PREFIX = "/net/"
    private const val PREFS = "netroots"
    private const val LIST_TTL = 10_000L

    private val idle = HashMap<String, ArrayDeque<NetClient>>()   // guarded by itself
    private val lists = HashMap<String, Pair<Long, List<Saf.Doc>>>()   // guarded by itself
    @Volatile var lastError: String? = null

    fun isNet(f: File) = f.path.startsWith(PREFIX)

    private fun idOf(f: File) = f.path.removePrefix(PREFIX).substringBefore('/')

    /** Path of [f] on its server. */
    private fun serverPath(cfg: NetConfig, f: File): String {
        val rest = f.path.removePrefix("$PREFIX${cfg.id}")
        return (cfg.path.trimEnd('/') + rest).ifEmpty { "/" }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rootFiles(ctx: Context): List<File> = prefs(ctx).all.keys.sorted().map { File("$PREFIX$it") }

    fun config(ctx: Context, f: File): NetConfig? {
        val json = prefs(ctx).getString(idOf(f), null) ?: return null
        return try {
            val o = JSONObject(json)
            NetConfig(
                idOf(f), NetType.valueOf(o.getString("type")), o.getString("host"), o.getInt("port"),
                o.optString("path", "/"), o.optString("user"), Secret.decrypt(o.optString("pass")),
                o.optString("name"), o.optBoolean("insecure"),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun name(ctx: Context, root: File): String = config(ctx, root)?.let { it.name.ifEmpty { it.host } } ?: idOf(root)

    /** Stores [cfg] (password encrypted) and returns its root folder. */
    fun save(ctx: Context, cfg: NetConfig): File {
        val o = JSONObject().put("type", cfg.type.name).put("host", cfg.host).put("port", cfg.port).put("path", cfg.path)
            .put("user", cfg.user).put("pass", Secret.encrypt(cfg.password)).put("name", cfg.name).put("insecure", cfg.insecure)
        prefs(ctx).edit().putString(cfg.id, o.toString()).apply()
        return File("$PREFIX${cfg.id}")
    }

    fun remove(ctx: Context, root: File) {
        val id = idOf(root)
        prefs(ctx).edit().remove(id).apply()
        synchronized(idle) { idle.remove(id)?.forEach { it.close() } }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    fun sslFactory(cfg: NetConfig): SSLSocketFactory {
        if (!cfg.insecure) return SSLSocketFactory.getDefault() as SSLSocketFactory
        // Only when the user switched certificate checks off for this server
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }.socketFactory
    }

    // ---- Connections ----

    private fun open(ctx: Context, cfg: NetConfig): NetClient = when (cfg.type) {
        NetType.DAV, NetType.DAVS -> DavClient(cfg, ctx.cacheDir)
        else -> FtpClient(cfg).also { it.connect() }
    }

    private fun borrow(ctx: Context, cfg: NetConfig): NetClient {
        while (true) {
            val c = synchronized(idle) { idle[cfg.id]?.removeFirstOrNull() } ?: return open(ctx, cfg)
            if (c.alive()) return c
            c.close()
        }
    }

    private fun giveBack(cfg: NetConfig, c: NetClient) {
        synchronized(idle) {
            val q = idle.getOrPut(cfg.id) { ArrayDeque() }
            if (q.size < 3) q.addLast(c) else c.close()
        }
    }

    /** Runs [op] with a connection; a broken connection is dropped. */
    private fun <T> withClient(ctx: Context, f: File, op: (NetClient, String) -> T): T {
        val cfg = config(ctx, f) ?: throw IOException("unknown server")
        val c = borrow(ctx, cfg)
        try {
            return op(c, serverPath(cfg, f)).also { giveBack(cfg, c) }
        } catch (e: IOException) {
            c.close()
            throw e
        }
    }

    /** Checks that the server answers and the start folder can be listed; null = fine, else the reason. */
    fun test(ctx: Context, cfg: NetConfig): String? = try {
        val c = open(ctx, cfg)
        c.list(cfg.path.ifEmpty { "/" })
        c.close()
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    // ---- File operations (the same set as Saf) ----

    fun list(ctx: Context, dir: File): List<Saf.Doc> {
        val now = System.currentTimeMillis()
        synchronized(lists) { lists[dir.path]?.takeIf { now - it.first < LIST_TTL }?.let { return it.second } }
        return try {
            val l = withClient(ctx, dir) { c, p -> c.list(p) }
            synchronized(lists) { lists[dir.path] = Pair(now, l) }
            l
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            emptyList()
        }
    }

    private fun changed(dir: File?) {
        synchronized(lists) { if (dir == null) lists.clear() else lists.remove(dir.path) }
    }

    fun stat(ctx: Context, f: File): Saf.Doc? {
        val parent = f.parentFile ?: return null
        if (!isNet(parent) || parent.path == PREFIX.trimEnd('/')) {
            // the server's start folder itself
            return Saf.Doc("", name(ctx, f), true, 0, 0)
        }
        return list(ctx, parent).firstOrNull { it.name == f.name }
    }

    /** Creates a folder, or reserves a free name for a file (it is written by [openOutput]). */
    fun createChild(ctx: Context, dir: File, name: String, isDir: Boolean): File? {
        val taken = list(ctx, dir).map { it.name }.toHashSet()
        var target = name
        var n = 1
        val dot = name.lastIndexOf('.')
        while (target in taken) {
            target = if (dot > 0 && !isDir) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
            n++
        }
        val f = File(dir, target)
        return try {
            if (isDir) withClient(ctx, f) { c, p -> c.mkdir(p) }
            changed(dir)
            f
        } catch (e: IOException) {
            null
        }
    }

    fun delete(ctx: Context, f: File): Boolean = try {
        val dir = stat(ctx, f)?.isDir ?: false
        withClient(ctx, f) { c, p -> c.delete(p, dir) }
        changed(f.parentFile)
        true
    } catch (e: IOException) {
        false
    }

    fun rename(ctx: Context, f: File, name: String): Boolean = try {
        val cfg = config(ctx, f) ?: throw IOException()
        val to = serverPath(cfg, File(f.parentFile, name))
        withClient(ctx, f) { c, p -> c.rename(p, to) }
        changed(f.parentFile)
        true
    } catch (e: IOException) {
        false
    }

    fun openInput(ctx: Context, f: File): InputStream? {
        val cfg = config(ctx, f) ?: return null
        val c = borrow(ctx, cfg)
        return try {
            c.read(serverPath(cfg, f)) { giveBack(cfg, c) }
        } catch (e: IOException) {
            c.close()
            null
        }
    }

    fun openOutput(ctx: Context, f: File): OutputStream? {
        val cfg = config(ctx, f) ?: return null
        val c = borrow(ctx, cfg)
        return try {
            c.write(serverPath(cfg, f)) { giveBack(cfg, c); changed(f.parentFile) }
        } catch (e: IOException) {
            c.close()
            null
        }
    }

    /** Forget cached listings (pull to refresh, after the app comes back). */
    fun refresh() = changed(null)
}
