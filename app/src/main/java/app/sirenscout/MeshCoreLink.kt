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
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Search together over a MeshCore companion radio (miles, no phone signal needed). Siren talks to
 * the radio directly over Bluetooth with MeshCore's open companion protocol, so nothing else is
 * needed - but a radio talks to one app at a time, so MeshCore's own app is disconnected while a
 * search runs. Siren adds a private channel for the search (its secret made from the search code,
 * so every searcher's radio is on it), sends binary channel datagrams (which chat apps don't show
 * as messages), and removes the channel again when the search stops.
 * Protocol: github.com/meshcore-dev/MeshCore docs/companion_protocol.md (firmware 1.18).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is granted when scanning starts
class MeshCoreLink(private val address: String, private val code: String) : SearchTogether.Link {
    override val name = "MeshCore"
    override val slow = true
    private val _state = MutableStateFlow("connecting")
    override val state: StateFlow<String> = _state

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var onPacket: (ByteArray) -> Unit = {}
    /** Frames waiting to be written; Android allows one GATT write at a time. */
    private val outbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var writing = false
    @Volatile private var channelIdx = -1
    @Volatile private var maxChannels = 8
    @Volatile private var stopped = false
    private val replies = LinkedBlockingQueue<ByteArray>()

    override fun start(context: Context, onPacket: (ByteArray) -> Unit) {
        this.onPacket = onPacket
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) { _state.value = "radio not found - pick it in Settings > Search together"; return }
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, true, callback)
    }

    override fun send(packet: ByteArray) {
        val idx = channelIdx
        if (idx < 0 || packet.size > MAX_DATA) return
        // CMD_SEND_CHANNEL_DATA: channel, flood (no path), data type, payload.
        enqueue(byteArrayOf(CMD_SEND_CHANNEL_DATA, idx.toByte(), FLOOD, DATA_TYPE_LO, DATA_TYPE_HI) + packet)
    }

    override fun stop() {
        stopped = true
        val idx = channelIdx
        if (idx >= 0) {
            // Leave the radio as we found it: clear the search channel.
            runCatching { writeNow(byteArrayOf(CMD_SET_CHANNEL, idx.toByte()) + ByteArray(32) + ByteArray(16)) }
            Thread.sleep(300)
        }
        channelIdx = -1
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (stopped) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> { _state.value = "connected, setting up"; g.requestMtu(185) }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    channelIdx = -1
                    _state.value = if (status == 0) "disconnected - reconnecting" else
                        "can't connect (in use by another app? close MeshCore's connection) - retrying"
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            if (svc == null) { _state.value = "not a MeshCore companion radio"; return }
            rx = svc.getCharacteristic(RX_CHAR)
            val tx = svc.getCharacteristic(TX_CHAR)
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD)
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // Notifications on: introduce ourselves and set up the channel, off the Bluetooth thread.
            Thread({ setUp() }, "meshcore-setup").apply { isDaemon = true; start() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writing = false
            pump()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") onFrame(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = onFrame(value)
    }

    private fun setUp() {
        replies.clear()
        // Device query (protocol version 3+), then app start.
        val info = request(byteArrayOf(CMD_DEVICE_QUERY, 3), RESP_DEVICE_INFO)
        if (info != null && info.size >= 4) maxChannels = info[3].toInt() and 0xFF
        request(byteArrayOf(CMD_APP_START, 3, 0, 0, 0, 0, 0, 0) + "Siren".toByteArray(), RESP_SELF_INFO)
        // Find a free channel slot (or ours from before), from the top down to stay out of the way.
        var slot = -1
        for (i in (maxChannels - 1) downTo 1) {
            val r = request(byteArrayOf(CMD_GET_CHANNEL, i.toByte()), RESP_CHANNEL_INFO) ?: continue
            val chName = String(r.copyOfRange(2, minOf(r.size, 34))).substringBefore('\u0000')
            if (chName.isEmpty() || chName == CHANNEL_NAME) { slot = i; break }
        }
        if (slot < 0) { _state.value = "no free channel slot on the radio - remove a channel in MeshCore and retry"; return }
        val nameBytes = CHANNEL_NAME.toByteArray().copyOf(32)
        val ok = request(byteArrayOf(CMD_SET_CHANNEL, slot.toByte()) + nameBytes + channelSecret(code), RESP_OK)
        if (ok == null) { _state.value = "couldn't set up the search channel"; return }
        channelIdx = slot
        _state.value = "ready (radio channel ${slot})"
        AppLog.i("Search together: MeshCore radio ready on channel $slot")
        syncMessages()
    }

    /** Sends [frame] and waits up to 5 s for a reply whose first byte is [expect] (or an error). */
    private fun request(frame: ByteArray, expect: Byte): ByteArray? {
        enqueue(frame)
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            val r = replies.poll(until - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: return null
            if (r[0] == expect) return r
            if (r[0] == RESP_ERR) return null
        }
        return null
    }

    private fun onFrame(f: ByteArray) {
        if (f.isEmpty()) return
        when (f[0]) {
            PUSH_MSG_WAITING -> Thread({ syncMessages() }, "meshcore-sync").apply { isDaemon = true; start() }
            RESP_CHANNEL_DATA_RECV -> {
                // [27, snr, r1, r2, channel, path_len, type lo, type hi, len, data...]
                if (f.size >= 9 && (f[4].toInt() and 0xFF) == channelIdx && f[6] == DATA_TYPE_LO && f[7] == DATA_TYPE_HI) {
                    val n = f[8].toInt() and 0xFF
                    if (f.size >= 9 + n) onPacket(f.copyOfRange(9, 9 + n))
                }
                syncing.offer(f)
            }
            RESP_NO_MORE, RESP_CHANNEL_MSG_V3, RESP_CONTACT_MSG_V3, RESP_CHANNEL_MSG, RESP_CONTACT_MSG -> syncing.offer(f)
            else -> replies.offer(f)
        }
    }

    private val syncing = LinkedBlockingQueue<ByteArray>()

    /** Drains the radio's queue of received messages (only our channel's datagrams are used). */
    @Synchronized
    private fun syncMessages() {
        repeat(30) {
            syncing.clear()
            enqueue(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            val r = syncing.poll(5, TimeUnit.SECONDS) ?: return
            if (r[0] == RESP_NO_MORE) return
        }
    }

    private fun enqueue(frame: ByteArray) { outbox.put(frame); pump() }

    @Synchronized
    private fun pump() {
        if (writing) return
        val f = outbox.poll() ?: return
        if (!writeNow(f)) outbox.clear() else writing = true
    }

    private fun writeNow(f: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = rx ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, f, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else @Suppress("DEPRECATION") run {
            c.value = f; c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(c)
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX_CHAR: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_CHAR: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val CHANNEL_NAME = "Siren search"
        /** Radio-side limit for a channel datagram. */
        const val MAX_DATA = 165

        const val CMD_APP_START: Byte = 1
        const val CMD_SYNC_NEXT_MESSAGE: Byte = 10
        const val CMD_DEVICE_QUERY: Byte = 22
        const val CMD_GET_CHANNEL: Byte = 31
        const val CMD_SET_CHANNEL: Byte = 32
        const val CMD_SEND_CHANNEL_DATA: Byte = 62
        const val RESP_OK: Byte = 0
        const val RESP_ERR: Byte = 1
        const val RESP_SELF_INFO: Byte = 5
        const val RESP_CONTACT_MSG: Byte = 7
        const val RESP_CHANNEL_MSG: Byte = 8
        const val RESP_NO_MORE: Byte = 10
        const val RESP_DEVICE_INFO: Byte = 13
        const val RESP_CONTACT_MSG_V3: Byte = 16
        const val RESP_CHANNEL_MSG_V3: Byte = 17
        const val RESP_CHANNEL_INFO: Byte = 18
        const val RESP_CHANNEL_DATA_RECV: Byte = 27
        const val PUSH_MSG_WAITING: Byte = 0x83.toByte()
        const val FLOOD: Byte = 0xFF.toByte()
        /** DATA_TYPE_DEV (0xFFFF), MeshCore's namespace for app development, little-endian. */
        const val DATA_TYPE_LO: Byte = 0xFF.toByte()
        const val DATA_TYPE_HI: Byte = 0xFF.toByte()

        /** The radio channel's secret: from the search code, so every searcher's radio shares it. */
        fun channelSecret(code: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("siren-meshcore|$code".toByteArray()).copyOf(16)
    }
}
