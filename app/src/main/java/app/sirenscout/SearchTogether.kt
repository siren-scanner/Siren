package app.sirenscout

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Two or more people searching for the same device (an AirTag, say), each phone sharing where
 * it is and how strong it hears the target, so every map shows one combined estimate.
 * Messages are encrypted with a key made from the session code, and travel over whatever links
 * are available: the local network (one phone's hotspot) fast and short-range, mesh radios
 * slowly over miles. No servers, nothing through the internet.
 */
object SearchTogether {
    data class Target(val source: Source, val id: String, val label: String) {
        val key get() = "${source.name}|$id"
    }

    data class Peer(val id: Int, val name: String, val lat: Double?, val lon: Double?, val lastHeard: Long, val via: String)

    data class Session(val code: String, val myId: Int, val myName: String, val target: Target?)

    /** One way of reaching the other searchers. */
    interface Link {
        val name: String
        /** Mesh radios carry little, slowly: they get updates less often. */
        val slow: Boolean
        fun start(context: Context, onPacket: (ByteArray) -> Unit)
        fun send(packet: ByteArray)
        fun stop()
        /** "ready", or why not ("no hotspot or Wi-Fi network"). */
        val state: StateFlow<String>
    }

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val _peers = MutableStateFlow<Map<Int, Peer>>(emptyMap())
    val peers: StateFlow<Map<Int, Peer>> = _peers.asStateFlow()
    private val _estimate = MutableStateFlow<LocationEstimate?>(null)
    /** Where the target probably is, from everyone's readings together. */
    val estimate: StateFlow<LocationEstimate?> = _estimate.asStateFlow()

    /** This phone's latest position, kept up to date by the scanner. */
    @Volatile var myLat: Double? = null
    @Volatile var myLon: Double? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var key: SecretKeySpec? = null
    private var sid: ByteArray = ByteArray(4)
    private val links = mutableListOf<Link>()
    val activeLinks: List<Link> get() = synchronized(links) { links.toList() }

    private val remote = ArrayDeque<Reading>()
    private val local = ArrayDeque<Reading>()
    private var lastSentReading = 0L
    private var lastSlowSend = 0L
    private val random = SecureRandom()

    // --- Joining by QR code ---

    /** What a search's QR code holds: opens Siren's join screen with the code filled in. */
    fun joinLink(code: String) = "siren://join/$code"

    /** A code from a scanned QR code, waiting for the join screen. */
    val pendingJoin = MutableStateFlow<String?>(null)

    /** Picks up a scanned join link (siren://join/CODE) from the intent that opened Siren. */
    fun handleLink(intent: android.content.Intent?) {
        codeFromLink(intent?.dataString)?.let { pendingJoin.value = it }
    }

    /** The search code in a join link, or null if it isn't one. */
    fun codeFromLink(link: String?): String? {
        val m = Regex("^siren://join/([A-Za-z0-9-]+)$").find(link?.trim() ?: return null) ?: return null
        return normalize(m.groupValues[1]).takeIf { it.length == CODE_LENGTH }
    }

    // --- Codes and keys ---

    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" // no 0/O, 1/I
    const val CODE_LENGTH = 10

    fun newCode(): String = String(CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** "ABCD-EFGH-JK" for reading aloud; any case, spaces or dashes are accepted back. */
    fun pretty(code: String) = code.chunked(4).joinToString("-")
    fun normalize(input: String) = input.uppercase().filter { it in ALPHABET }

    internal fun keysFor(code: String): Pair<ByteArray, SecretKeySpec> {
        val h = MessageDigest.getInstance("SHA-256").digest("siren-search|$code".toByteArray())
        return h.copyOfRange(0, 4) to SecretKeySpec(h.copyOfRange(16, 32), "AES")
    }

    // --- Messages ---

    internal object Type { const val HELLO = 1; const val POSITION = 2; const val READING = 3; const val BYE = 4 }

    internal sealed class Message(val from: Int) {
        class Hello(from: Int, val name: String, val target: Target?) : Message(from)
        class Position(from: Int, val lat: Double, val lon: Double) : Message(from)
        class Read(from: Int, val lat: Double, val lon: Double, val rssi: Int, val time: Long) : Message(from)
        class Bye(from: Int) : Message(from)
    }

    /** Compact binary, so a reading fits a mesh text message (about 60 bytes sealed). */
    internal fun encode(m: Message): ByteArray {
        val b = ByteBuffer.allocate(200)
        fun str(s: String, max: Int) { val bytes = s.toByteArray().let { if (it.size > max) it.copyOf(max) else it }; b.put(bytes.size.toByte()); b.put(bytes) }
        fun deg(v: Double) = b.putInt(Math.round(v * 1e6).toInt())
        b.put(1) // format version
        when (m) {
            is Message.Hello -> {
                b.put(Type.HELLO.toByte()); b.putShort(m.from.toShort()); str(m.name, 24)
                val t = m.target
                b.put((t?.source?.ordinal ?: -1).toByte()); str(t?.id ?: "", 40); str(t?.label ?: "", 40)
            }
            is Message.Position -> { b.put(Type.POSITION.toByte()); b.putShort(m.from.toShort()); deg(m.lat); deg(m.lon) }
            is Message.Read -> {
                b.put(Type.READING.toByte()); b.putShort(m.from.toShort()); deg(m.lat); deg(m.lon)
                b.put(m.rssi.coerceIn(-127, 0).toByte()); b.putInt((m.time / 1000).toInt())
            }
            is Message.Bye -> { b.put(Type.BYE.toByte()); b.putShort(m.from.toShort()) }
        }
        return b.array().copyOf(b.position())
    }

    internal fun decode(bytes: ByteArray): Message? = runCatching {
        val b = ByteBuffer.wrap(bytes)
        if (b.get().toInt() != 1) return null
        fun str(): String { val n = b.get().toInt() and 0xFF; val a = ByteArray(n); b.get(a); return String(a) }
        fun deg() = b.getInt() / 1e6
        val type = b.get().toInt()
        val from = b.getShort().toInt() and 0xFFFF
        when (type) {
            Type.HELLO -> {
                val name = str()
                val src = b.get().toInt()
                val id = str(); val label = str()
                Message.Hello(from, name, Source.entries.getOrNull(src)?.takeIf { id.isNotEmpty() }?.let { Target(it, id, label) })
            }
            Type.POSITION -> Message.Position(from, deg(), deg())
            Type.READING -> Message.Read(from, deg(), deg(), b.get().toInt(), (b.getInt().toLong() and 0xFFFFFFFFL) * 1000)
            Type.BYE -> Message.Bye(from)
            else -> null
        }
    }.getOrNull()

    /** Session id (4 bytes, so others' packets are skipped cheaply) + nonce + AES-GCM ciphertext. */
    internal fun seal(plain: ByteArray, sid: ByteArray, key: SecretKeySpec): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(sid) }
        return sid + nonce + c.doFinal(plain)
    }

    internal fun open(packet: ByteArray, sid: ByteArray, key: SecretKeySpec): ByteArray? {
        if (packet.size < 4 + 12 + 16 || !packet.copyOfRange(0, 4).contentEquals(sid)) return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packet.copyOfRange(4, 16))); updateAAD(sid)
            }
            c.doFinal(packet.copyOfRange(16, packet.size))
        }.getOrNull()
    }

    // --- Running a search ---

    /** Starts searching for [target]; returns the code to give the others. */
    fun start(context: Context, target: Target): String {
        val code = newCode()
        begin(context, code, target)
        return code
    }

    /** Joins a search someone else started; false if the code doesn't look right. */
    fun join(context: Context, input: String): Boolean {
        val code = normalize(input)
        if (code.length != CODE_LENGTH) return false
        begin(context, code, null)
        return true
    }

    private fun begin(context: Context, code: String, target: Target?) {
        stop()
        val (s, k) = keysFor(code)
        sid = s; key = k
        val prefs = Prefs(context)
        _session.value = Session(code, 1 + random.nextInt(0xFFFE), prefs.searchName, target)
        _peers.value = emptyMap()
        synchronized(remote) { remote.clear(); local.clear() }
        _estimate.value = null
        val app = context.applicationContext
        synchronized(links) {
            links.clear()
            links += LocalNetworkLink()
            links.forEach { link -> link.start(app) { onPacket(it, link) } }
        }
        // Mesh radios: work out which kind each is (may need a quick Bluetooth look), then connect.
        val radios = prefs.meshRadios
        if (radios.isNotEmpty()) scope.launch {
            for (address in radios) {
                val link = when (MeshRadios.detect(app, address)) {
                    MeshRadios.Kind.MESHTASTIC -> MeshtasticLink(address, code)
                    else -> MeshCoreLink(address, code) // MeshCore, or unknown: MeshCore reports if it isn't one
                }
                if (_session.value?.code != code) return@launch // stopped meanwhile
                synchronized(links) { links += link }
                link.start(app) { onPacket(it, link) }
            }
        }
        loop = scope.launch {
            var tick = 0
            while (isActive) {
                val s0 = _session.value ?: break
                val now = System.currentTimeMillis()
                val fast = activeLinks.filter { !it.slow }
                val slow = activeLinks.filter { it.slow }
                val slowDue = now - lastSlowSend > slowEveryMs(_peers.value.size + 1)
                // Hello every 10 s (and on every slow send), so late joiners learn the target and names.
                if (tick % 5 == 0) send(Message.Hello(s0.myId, s0.myName, s0.target), fast)
                val lat = myLat; val lon = myLon
                if (lat != null && lon != null) send(Message.Position(s0.myId, lat, lon), fast)
                val newest = synchronized(remote) { local.lastOrNull() }
                if (newest != null && newest.time > lastSentReading) {
                    send(Message.Read(s0.myId, newest.lat, newest.lon, newest.rssi.toInt(), newest.time), fast)
                }
                if (slowDue && slow.isNotEmpty()) {
                    lastSlowSend = now
                    send(Message.Hello(s0.myId, s0.myName, s0.target), slow)
                    if (newest != null && newest.time > lastSentReading) send(Message.Read(s0.myId, newest.lat, newest.lon, newest.rssi.toInt(), newest.time), slow)
                    else if (lat != null && lon != null) send(Message.Position(s0.myId, lat, lon), slow)
                }
                if (newest != null) lastSentReading = maxOf(lastSentReading, newest.time)
                // Forget searchers not heard from in a while.
                _peers.value = _peers.value.filterValues { now - it.lastHeard < PEER_TIMEOUT_MS }
                tick++
                delay(FAST_EVERY_MS)
            }
        }
        AppLog.i("Search together: ${if (target != null) "started" else "joined"} (${pretty(code).take(4)}…)")
    }

    fun stop() {
        val s = _session.value
        if (s != null) send(Message.Bye(s.myId), activeLinks)
        loop?.cancel(); loop = null
        synchronized(links) { links.forEach { runCatching { it.stop() } }; links.clear() }
        _session.value = null
        _peers.value = emptyMap()
        _estimate.value = null
    }

    private fun send(m: Message, to: List<Link>) {
        val k = key ?: return
        if (to.isEmpty()) return
        val packet = seal(encode(m), sid, k)
        to.forEach { runCatching { it.send(packet) } }
    }

    /** Packets already handled, by their nonce: the same message can arrive over two links. */
    private val seen = java.util.LinkedHashSet<String>()

    private fun onPacket(packet: ByteArray, from: Link) {
        val via = from.name
        val k = key ?: return
        if (packet.size > 16) synchronized(seen) {
            if (!seen.add(packet.copyOfRange(4, 16).joinToString("") { "%02x".format(it) })) return
            while (seen.size > 2000) seen.remove(seen.first())
        }
        val s = _session.value ?: return
        val m = open(packet, sid, k)?.let(::decode) ?: return
        if (m.from == s.myId) return
        // Bridge: a phone with a radio passes what it hears to its other links, so a mixed team
        // (MeshCore, Meshtastic, people on the hotspot) all hear each other. Fast-link traffic
        // isn't pushed onto the radios - that would flood the mesh - only radio traffic is passed on.
        if (from.slow) activeLinks.filter { it !== from }.forEach { runCatching { it.send(packet) } }
        val now = System.currentTimeMillis()
        val old = _peers.value[m.from]
        when (m) {
            is Message.Hello -> {
                _peers.value = _peers.value + (m.from to Peer(m.from, m.name, old?.lat, old?.lon, now, via))
                // A joiner learns what everyone is looking for.
                if (s.target == null && m.target != null) _session.value = s.copy(target = m.target)
            }
            is Message.Position -> _peers.value = _peers.value + (m.from to Peer(m.from, old?.name ?: "Searcher", m.lat, m.lon, now, via))
            is Message.Read -> {
                _peers.value = _peers.value + (m.from to Peer(m.from, old?.name ?: "Searcher", m.lat, m.lon, now, via))
                synchronized(remote) {
                    remote.addLast(Reading(m.lat, m.lon, m.rssi.toDouble(), m.time))
                    while (remote.size > MAX_READINGS) remote.removeFirst()
                }
                recompute()
            }
            is Message.Bye -> _peers.value = _peers.value - m.from
        }
    }

    /** Called with each of this phone's readings of any device; keeps the target's. */
    fun onLocalReading(key: String, lat: Double, lon: Double, rssi: Int, time: Long) {
        if (_session.value?.target?.key != key) return
        synchronized(remote) {
            local.addLast(Reading(lat, lon, rssi.toDouble(), time))
            while (local.size > MAX_READINGS) local.removeFirst()
        }
        recompute()
    }

    private fun recompute() {
        val all = synchronized(remote) { (local + remote).sortedBy { it.time } }
        _estimate.value = LocationEstimator.estimate(all)
    }

    /**
     * How often each phone sends over a mesh radio: 20 s for a small team, stretching as it grows
     * so the channel carries about one Siren message every 7 seconds whatever the team size.
     */
    internal fun slowEveryMs(teamSize: Int) = SLOW_EVERY_MS * maxOf(1, (teamSize + 2) / 3)

    private const val FAST_EVERY_MS = 2_000L
    private const val SLOW_EVERY_MS = 20_000L
    private const val PEER_TIMEOUT_MS = 3 * 60_000L
    private const val MAX_READINGS = 400
}
