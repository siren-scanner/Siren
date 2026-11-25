package app.sirenscout

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Search together over the local network: one phone shares its hotspot and the others join it
 * (or everyone is on the same Wi-Fi). Messages are broadcast to every phone on the network,
 * already encrypted by [SearchTogether]. Short range, no internet needed, updates every 2 s.
 */
class LocalNetworkLink : SearchTogether.Link {
    override val name = "hotspot / Wi-Fi"
    override val slow = false
    private val _state = MutableStateFlow("starting")
    override val state: StateFlow<String> = _state

    @Volatile private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var thread: Thread? = null

    override fun start(context: Context, onPacket: (ByteArray) -> Unit) {
        // Some phones drop broadcast packets to save power unless an app asks for them.
        lock = runCatching {
            context.getSystemService(WifiManager::class.java)?.createMulticastLock("siren-search")?.apply {
                setReferenceCounted(false); acquire()
            }
        }.getOrNull()
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(PORT))
        }
        socket = s
        thread = Thread({
            val buf = ByteArray(1500)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { s.receive(p) }.onFailure { if (s.isClosed) return@Thread } .onSuccess {
                    onPacket(p.data.copyOfRange(p.offset, p.offset + p.length))
                }
            }
        }, "siren-search-rx").apply { isDaemon = true; start() }
        _state.value = if (broadcastAddresses().isEmpty()) "no hotspot or Wi-Fi network" else "ready"
    }

    override fun send(packet: ByteArray) {
        val s = socket ?: return
        val targets = broadcastAddresses()
        _state.value = if (targets.isEmpty()) "no hotspot or Wi-Fi network" else "ready"
        for (addr in targets) runCatching { s.send(DatagramPacket(packet, packet.size, addr, PORT)) }
    }

    override fun stop() {
        socket?.close(); socket = null
        runCatching { lock?.release() }; lock = null
        thread = null
    }

    /**
     * The broadcast address of every Wi-Fi-like network the phone is on - including its own
     * hotspot, which a plain 255.255.255.255 would miss (that goes out over mobile data).
     */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("dummy") }
            .flatMap { i -> i.interfaceAddresses.mapNotNull { it.broadcast } }
            .distinct()
    }.getOrDefault(emptyList())

    companion object {
        const val PORT = 47123
    }
}
