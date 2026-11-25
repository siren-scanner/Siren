package app.sirenscout

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid

/** Telling mesh radios (MeshCore, Meshtastic) apart from the earbuds, watches and cars a phone is paired with. */
object MeshRadios {
    private val MESHCORE_SERVICE by lazy { ParcelUuid.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E") }
    private val MESHTASTIC_SERVICE by lazy { ParcelUuid.fromString("6BA1B218-15A8-461F-9FA8-5DCAE273EAFD") }
    private val NAMES = Regex(
        "meshcore|meshtastic|t-?echo|t-?beam|t-?deck|t1000|rak\\s?\\d|wisblock|heltec|wio|xiao|seeed|sensecap|lilygo|" +
            "station ?g|nano ?g|tracker ?t|thinknode|muzi|ikoka",
        RegexOption.IGNORE_CASE,
    )

    fun looksLikeRadio(name: String?): Boolean = name != null && NAMES.containsMatchIn(name)

    enum class Kind { MESHCORE, MESHTASTIC, UNKNOWN }

    /** Which kind of radio, from its default name ("MeshCore-…", "Meshtastic_…") or advertised service. */
    fun kind(name: String?): Kind = when {
        name == null -> Kind.UNKNOWN
        name.startsWith("MeshCore", ignoreCase = true) -> Kind.MESHCORE
        name.startsWith("Meshtastic", ignoreCase = true) -> Kind.MESHTASTIC
        else -> Kind.UNKNOWN
    }

    /**
     * Which kind of radio is at [address]: from its name or cached services, else by connecting
     * and looking at the services it offers (up to ~10 s). Call off the main thread.
     */
    @SuppressLint("MissingPermission")
    fun detect(context: android.content.Context, address: String): Kind {
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return Kind.UNKNOWN
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return Kind.UNKNOWN
        kind(device).takeIf { it != Kind.UNKNOWN }?.let { return it }
        val result = java.util.concurrent.LinkedBlockingQueue<Kind>()
        val gatt = device.connectGatt(context, false, object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(g: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) result.offer(Kind.UNKNOWN)
            }
            override fun onServicesDiscovered(g: android.bluetooth.BluetoothGatt, status: Int) {
                result.offer(
                    when {
                        g.getService(MESHCORE_SERVICE.uuid) != null -> Kind.MESHCORE
                        g.getService(MESHTASTIC_SERVICE.uuid) != null -> Kind.MESHTASTIC
                        else -> Kind.UNKNOWN
                    }
                )
            }
        }, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        val k = result.poll(10, java.util.concurrent.TimeUnit.SECONDS) ?: Kind.UNKNOWN
        runCatching { gatt.disconnect(); gatt.close() }
        Thread.sleep(500) // let the radio see the disconnect before the real link connects
        return k
    }

    @SuppressLint("MissingPermission")
    fun kind(d: BluetoothDevice): Kind {
        kind(runCatching { d.name }.getOrNull()).takeIf { it != Kind.UNKNOWN }?.let { return it }
        val uuids = runCatching { d.uuids?.toList() }.getOrNull().orEmpty()
        return when {
            MESHCORE_SERVICE in uuids -> Kind.MESHCORE
            MESHTASTIC_SERVICE in uuids -> Kind.MESHTASTIC
            else -> Kind.UNKNOWN
        }
    }

    @SuppressLint("MissingPermission")
    fun looksLikeRadio(d: BluetoothDevice): Boolean =
        looksLikeRadio(runCatching { d.name }.getOrNull()) ||
            runCatching { d.uuids?.any { it == MESHCORE_SERVICE || it == MESHTASTIC_SERVICE } }.getOrNull() == true
}
