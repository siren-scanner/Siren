package app.sirenscout

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Search together over a Meshtastic radio (miles, no phone signal needed). Siren talks to the
 * radio directly over Bluetooth with Meshtastic's open phone protocol (protobufs over GATT), so
 * the Meshtastic app is disconnected while a search runs. Siren adds a private secondary channel
 * for the search (its key made from the search code, so every searcher's radio is on it), sends
 * PRIVATE_APP datagrams (which chat apps don't show), and disables the channel again on stop.
 * Protocol: github.com/meshtastic/protobufs (mesh.proto, channel.proto, admin.proto).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is granted when scanning starts
class MeshtasticLink(private val address: String, private val code: String) : SearchTogether.Link {
    override val name = "Meshtastic"
    override val slow = true
    private val _state = MutableStateFlow("connecting")
    override val state: StateFlow<String> = _state

    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var onPacket: (ByteArray) -> Unit = {}
    @Volatile private var channelIdx = -1
    @Volatile private var myNode = 0L
    @Volatile private var stopped = false
    private val random = SecureRandom()

    /** One Bluetooth operation at a time, each waiting for its callback. */
    private sealed class Op { class Write(val data: ByteArray) : Op(); object ReadAll : Op(); class Run(val block: () -> Unit) : Op() }
    private val ops = LinkedBlockingQueue<Op>()
    private val done = Semaphore(0)
    @Volatile private var lastRead: ByteArray? = null
    private var worker: Thread? = null

    override fun start(context: Context, onPacket: (ByteArray) -> Unit) {
        this.onPacket = onPacket
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) { _state.value = "radio not found - pick it in Settings > Search together"; return }
        worker = Thread({ work() }, "meshtastic-ops").apply { isDaemon = true; start() }
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, true, callback)
    }

    override fun send(packet: ByteArray) {
        val idx = channelIdx
        if (idx < 0 || packet.size > MAX_PAYLOAD) return
        val data = ProtoWriter().uint(DATA_PORTNUM, PRIVATE_APP).bytes(DATA_PAYLOAD, packet)
        val mesh = ProtoWriter().fixed32(PKT_TO, BROADCAST).uint(PKT_CHANNEL, idx.toLong())
            .message(PKT_DECODED, data).fixed32(PKT_ID, random.nextInt())
        ops.put(Op.Write(ProtoWriter().message(TO_PACKET, mesh).toByteArray()))
    }

    override fun stop() {
        stopped = true
        val idx = channelIdx
        if (idx > 0) {
            // Leave the radio as we found it: disable the search channel.
            ops.put(Op.Write(adminSetChannel(idx, null)))
            ops.put(Op.Run { })
            Thread.sleep(800)
        }
        channelIdx = -1
        worker?.interrupt(); worker = null
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (stopped) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> { _state.value = "connected, setting up"; g.requestMtu(512) }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    channelIdx = -1
                    _state.value = if (status == 0) "disconnected - reconnecting" else
                        "can't connect (in use by the Meshtastic app? disconnect it there) - retrying"
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            if (svc == null) { _state.value = "not a Meshtastic radio"; return }
            toRadio = svc.getCharacteristic(TO_RADIO)
            fromRadio = svc.getCharacteristic(FROM_RADIO)
            val fromNum = svc.getCharacteristic(FROM_NUM)
            g.setCharacteristicNotification(fromNum, true)
            val cccd = fromNum.getDescriptor(CCCD)
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ops.clear()
            ops.put(Op.Run { setUp() })
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { done.release() }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") run { lastRead = if (status == 0) c.value else null }
            done.release()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            lastRead = if (status == 0) value else null
            done.release()
        }

        // FromNum changed: the radio has something for us.
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { ops.put(Op.ReadAll) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { ops.put(Op.ReadAll) }
    }

    private fun work() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                when (val op = ops.take()) {
                    is Op.Write -> write(op.data)
                    is Op.ReadAll -> readAll()
                    is Op.Run -> op.block()
                }
            }
        } catch (e: InterruptedException) { /* stopped */ }
    }

    private fun write(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = toRadio ?: return false
        done.drainPermits()
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        else @Suppress("DEPRECATION") run { c.value = data; c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(c) }
        return ok && done.tryAcquire(5, TimeUnit.SECONDS)
    }

    /** Reads FromRadio until it's empty, handling each message. */
    private fun readAll(maxMessages: Int = 300, onEach: (ByteArray) -> Boolean = { false }) {
        repeat(maxMessages) {
            val g = gatt ?: return
            val c = fromRadio ?: return
            done.drainPermits(); lastRead = null
            if (!g.readCharacteristic(c) || !done.tryAcquire(5, TimeUnit.SECONDS)) return
            val v = lastRead ?: return
            if (v.isEmpty()) return
            handle(v)
            if (onEach(v)) return
        }
    }

    private fun setUp() {
        // Ask for the radio's setup: our node number and its channels.
        val configId = random.nextInt(Int.MAX_VALUE).toLong()
        if (!write(ProtoWriter().uint(TO_WANT_CONFIG, configId).toByteArray())) { _state.value = "couldn't talk to the radio"; return }
        val channels = HashMap<Int, Pair<Int, String>>() // index -> role, name
        var complete = false
        repeat(3) {
            readAll { frame ->
                val f = ProtoReader(frame)
                f.first(FROM_MY_INFO)?.bytes?.let { myNode = ProtoReader(it).first(1)?.long ?: myNode }
                f.first(FROM_CHANNEL)?.bytes?.let { ch ->
                    val r = ProtoReader(ch)
                    val idx = (r.first(1)?.long ?: 0).toInt()
                    val role = (r.first(3)?.long ?: 0).toInt()
                    val name = r.first(2)?.bytes?.let { s -> ProtoReader(s).first(3)?.bytes?.let(::String) }.orEmpty()
                    channels[idx] = role to name
                }
                if (f.first(FROM_CONFIG_COMPLETE)?.long == configId) { complete = true; true } else false
            }
            if (complete) return@repeat
        }
        if (myNode == 0L) { _state.value = "radio didn't send its setup"; return }
        // Our channel from before, or the highest unused slot (0 is the radio's primary channel).
        val slot = channels.entries.firstOrNull { it.value.second == CHANNEL_NAME }?.key
            ?: (7 downTo 1).firstOrNull { (channels[it]?.first ?: 0) == ROLE_DISABLED }
        if (slot == null) { _state.value = "no free channel slot on the radio - remove a channel in Meshtastic and retry"; return }
        if (!write(adminSetChannel(slot, psk(code)))) { _state.value = "couldn't set up the search channel"; return }
        channelIdx = slot
        _state.value = "ready (radio channel $slot)"
        AppLog.i("Search together: Meshtastic radio ready on channel $slot")
    }

    /** An AdminMessage to our own radio: [psk] null disables the slot. */
    private fun adminSetChannel(slot: Int, psk: ByteArray?): ByteArray {
        val settings = ProtoWriter().apply { if (psk != null) { bytes(2, psk); string(3, CHANNEL_NAME) } }
        val channel = ProtoWriter().uint(1, slot.toLong()).message(2, settings).uint(3, if (psk != null) ROLE_SECONDARY else ROLE_DISABLED.toLong())
        val admin = ProtoWriter().message(ADMIN_SET_CHANNEL, channel)
        val data = ProtoWriter().uint(DATA_PORTNUM, ADMIN_APP).bytes(DATA_PAYLOAD, admin.toByteArray()).bool(3, true)
        val mesh = ProtoWriter().fixed32(PKT_TO, myNode.toInt()).message(PKT_DECODED, data).fixed32(PKT_ID, random.nextInt())
        return ProtoWriter().message(TO_PACKET, mesh).toByteArray()
    }

    private fun handle(frame: ByteArray) {
        val pkt = ProtoReader(frame).first(FROM_PACKET)?.bytes ?: return
        val p = ProtoReader(pkt)
        val channel = (p.first(PKT_CHANNEL)?.long ?: 0).toInt()
        val decoded = p.first(PKT_DECODED)?.bytes ?: return
        val d = ProtoReader(decoded)
        if (d.first(DATA_PORTNUM)?.long == PRIVATE_APP && channel == channelIdx) {
            d.first(DATA_PAYLOAD)?.bytes?.let(onPacket)
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Meshtastic channel names are at most 11 characters. */
        const val CHANNEL_NAME = "SirenSearch"
        const val MAX_PAYLOAD = 200

        // Field numbers from meshtastic/protobufs.
        const val TO_PACKET = 1; const val TO_WANT_CONFIG = 3
        const val FROM_PACKET = 2; const val FROM_MY_INFO = 3; const val FROM_CONFIG_COMPLETE = 7; const val FROM_CHANNEL = 10
        const val PKT_TO = 2; const val PKT_CHANNEL = 3; const val PKT_DECODED = 4; const val PKT_ID = 6
        const val DATA_PORTNUM = 1; const val DATA_PAYLOAD = 2
        const val ADMIN_SET_CHANNEL = 33
        const val PRIVATE_APP = 256L; const val ADMIN_APP = 6L
        const val ROLE_DISABLED = 0; const val ROLE_SECONDARY = 2L
        const val BROADCAST = -1 // 0xFFFFFFFF

        /** The channel key: from the search code, so every searcher's radio shares it (AES-128). */
        fun psk(code: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("siren-meshtastic|$code".toByteArray()).copyOf(16)
    }
}
