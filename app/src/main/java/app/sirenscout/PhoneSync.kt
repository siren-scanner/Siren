package app.sirenscout

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Keeps the user's phones in step over Tailscale: one phone lets others sync with it (serving its
 * shared data, see [DataShare]), and another, given its address and code, sends its own data and
 * fetches the first one's every few minutes while scanning. Both ends only ever add, as with a
 * shared-data file. Only Tailscale devices that know the code get an answer.
 */
object PhoneSync {
    /** 8180 for Siren, 8181 for Siren Test - next to the car display's ports. */
    val PORT = BuildConfig.CAR_PORT + 100
    private const val INTERVAL_MS = 10 * 60_000L
    private const val MAX_BODY = 20 * 1024 * 1024

    /** Last result, shown in Settings. */
    val status = MutableStateFlow("")

    private var server: ServerSocket? = null
    private var job: Job? = null
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun newCode(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..12).map { alphabet[rnd.nextInt(alphabet.length)] }.chunked(4).joinToString("-") { it.joinToString("") }
    }

    /** "abcd-efgh-jkmn", however it was typed. */
    fun normalize(code: String) = code.lowercase().filter { it.isLetterOrDigit() }

    /** This phone's Tailscale address, for the other phone to sync with. */
    fun tailscaleAddress(): String? = CarDisplay.addresses().firstOrNull { it.first.startsWith("Tailscale") }?.second

    // ---- Serving --------------------------------------------------------------------------

    @Synchronized
    fun start(context: Context, port: Int = PORT) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = ownScope.launch {
            // Listen on the Tailscale address only, never on Wi-Fi, mobile data or a hotspot.
            // Some carriers and public networks also use 100.64.x.x, so the address check alone isn't enough.
            var address = tailscaleAddress()
            while (address == null) {
                status.value = "Waiting for Tailscale to connect on this phone…"
                delay(30_000)
                address = tailscaleAddress()
            }
            val socket = runCatching { ServerSocket(port, 50, InetAddress.getByName(address)) }.getOrElse {
                AppLog.w("Phone sync couldn't open port $port: $it")
                return@launch
            }
            server = socket
            if (status.value.startsWith("Waiting for Tailscale")) status.value = ""
            AppLog.i("Phone sync: serving on Tailscale, port $port")
            try {
                while (isActive) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    launch { runCatching { client.use { handle(app, it) } }.onFailure { e -> AppLog.w("Phone sync request failed: $e") } }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        job?.cancel()
        job = null
    }

    /** Wrong codes per address; after a few, that address is ignored until the next restart. */
    private val failures = HashMap<String, Int>()

    private fun handle(context: Context, client: Socket) {
        client.soTimeout = 20_000
        val from = client.inetAddress
        if (!isTailnet(from)) return
        val key = from.hostAddress ?: "?"
        if ((synchronized(failures) { failures[key] } ?: 0) >= 5) return
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        var length = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val out = client.getOutputStream()
        fun send(status: String, body: String) {
            val bytes = body.toByteArray()
            out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }
        val prefs = Prefs(context)
        if (!prefs.syncServe || !path.startsWith("/sync/") || !sameCode(path.removePrefix("/sync/"), prefs.syncCode)) {
            synchronized(failures) { failures[key] = (failures[key] ?: 0) + 1 }
            AppLog.w("Phone sync: refused $method from $key")
            return send("404 Not Found", "{}")
        }
        when (method) {
            "GET" -> {
                send("200 OK", DataShare.toJson(DataShare.current(context, prefs.syncHistory)).toString())
                AppLog.i("Phone sync: sent data to $key")
            }
            "POST" -> {
                if (length !in 1..MAX_BODY) return send("413 Payload Too Large", "{}")
                val body = String(readExactly(input, length))
                val added = DataShare.import(context, DataShare.fromJson(JSONObject(body)))
                status.value = "Last synced ${clock()} with $key: got $added"
                send("200 OK", JSONObject().put("added", added.toString()).toString())
            }
            else -> send("405 Method Not Allowed", "{}")
        }
    }

    private fun sameCode(given: String, expected: String) =
        expected.isNotEmpty() && MessageDigest.isEqual(normalize(given).toByteArray(), normalize(expected).toByteArray())

    /** Loopback and Tailscale's 100.64.0.0/10 range - not the local Wi-Fi or hotspot. */
    fun isTailnet(a: InetAddress): Boolean {
        if (a.isLoopbackAddress) return true
        val b = a.address
        return b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
    }

    // ---- Syncing with the other phone ------------------------------------------------------

    /** Runs while scanning: sync now, then every few minutes. */
    suspend fun loop(context: Context) {
        while (true) {
            syncOnce(context)
            delay(INTERVAL_MS)
        }
    }

    /** Sends this phone's data to the partner and adds the partner's here. Returns a short summary. */
    suspend fun syncOnce(context: Context): String = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        val host = prefs.syncPartner.trim()
        val code = normalize(prefs.syncPartnerCode)
        if (host.isEmpty() || code.isEmpty()) return@withContext "".also { status.value = "" }
        val message = runCatching {
            val mine = DataShare.toJson(DataShare.current(context, prefs.syncHistory)).toString()
            val (sendStatus, _) = request(host, "POST", "/sync/$code", mine)
            if (sendStatus == 404) throw IOException("the other phone didn't accept the code, or isn't letting phones sync")
            if (sendStatus != 200) throw IOException("the other phone answered $sendStatus")
            val (getStatus, body) = request(host, "GET", "/sync/$code", null)
            if (getStatus != 200) throw IOException("the other phone answered $getStatus")
            val added = DataShare.import(context, DataShare.fromJson(JSONObject(body)))
            "Last synced ${clock()}: got $added"
        }.getOrElse {
            AppLog.w("Phone sync with $host failed: $it")
            "Couldn't sync at ${clock()}: ${it.message ?: it.javaClass.simpleName}. Is the other phone scanning, with Tailscale on?"
        }
        AppLog.i("Phone sync: $message")
        status.value = message
        message
    }

    /**
     * A bare HTTP request over a socket (Android's HTTP client won't do plain HTTP to arbitrary
     * tailnet addresses). Only to Tailscale addresses, where WireGuard encrypts the traffic.
     */
    private fun request(host: String, method: String, path: String, body: String?): Pair<Int, String> {
        val (name, port) = host.substringBefore(':') to (host.substringAfter(':', "").toIntOrNull() ?: PORT)
        val target = InetAddress.getByName(name)
        if (target.isLoopbackAddress || !isTailnet(target)) {
            throw IOException("$name isn't a Tailscale address (100.x.x.x or a .ts.net name) - Siren only syncs over Tailscale")
        }
        Socket().use { s ->
            s.connect(InetSocketAddress(target, port), 10_000)
            s.soTimeout = 60_000
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val out = s.getOutputStream()
            out.write(("$method $path HTTP/1.1\r\nHost: $name\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
            val input = s.getInputStream()
            val code = readLine(input)?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw IOException("no answer")
            var length = -1
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: -1
            }
            val text = if (length in 0..MAX_BODY) String(readExactly(input, length)) else String(input.readBytes())
            return code to text
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString()
            if (c == '\n'.code) return buf.toString().trimEnd('\r')
            if (buf.size() > 8192) throw IOException("line too long")
            buf.write(c)
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r < 0) throw IOException("connection closed early")
            read += r
        }
        return out
    }

    private fun clock() = java.time.LocalTime.now().withNano(0).withSecond(0).toString()
}
