package app.sirenscout

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.ParcelUuid

/**
 * Best-effort guess at what a device is, from the manufacturer registries bundled in assets
 * (IEEE OUIs, Bluetooth SIG company IDs and member UUIDs) plus well-known advertisement formats.
 * Results are guesses and are always shown with an "(UNK)" mark.
 */
object DeviceGuesser {
    @Volatile private var ouis: Map<Int, String> = emptyMap()
    @Volatile private var companies: Map<Int, String> = emptyMap()
    @Volatile private var memberUuids: Map<Int, String> = emptyMap()
    @Volatile private var loaded = false

    /** Reads the registries from assets. Slow-ish (~40k lines), so call off the main thread. */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        fun read(file: String) = HashMap<Int, String>().apply {
            context.assets.open(file).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) put(line.substring(0, tab).toInt(16), line.substring(tab + 1))
                }
            }
        }
        ouis = read("oui.txt")
        companies = read("bt_companies.txt")
        memberUuids = read("bt_member_uuids.txt")
        loaded = true
    }

    fun ble(r: ScanResult): String? {
        val record = r.scanRecord
        var vendor: String? = null
        var kind: String? = null

        record?.manufacturerSpecificData?.takeIf { it.size() > 0 }?.let { data ->
            val id = data.keyAt(0)
            kind = manufacturerKind(id, data.valueAt(0))
            // iBeacons carry Apple's company ID whoever made them, so it says nothing about the maker.
            if (kind != "iBeacon") vendor = companies[id]
        }
        val uuids = (record?.serviceUuids.orEmpty() + record?.serviceData?.keys.orEmpty()).mapNotNull(::uuid16)
        for (u in uuids) {
            if (kind == null) kind = SERVICE_KINDS[u]
            if (vendor == null) vendor = memberUuids[u]
        }
        if (kind == null) kind = record?.bytes?.let(::appearanceKind)
        // Phones and watches use random addresses; their first bytes say nothing about the maker.
        if (vendor == null && Build.VERSION.SDK_INT >= 35 && r.device.addressType == BluetoothDevice.ADDRESS_TYPE_PUBLIC) {
            vendor = ouiVendor(r.device.address)
        }
        return join(vendor, kind)
    }

    /**
     * The stable parts of a BLE advertisement, e.g. "maker 0x004C (Apple) type 0x10; services FE2C;
     * appearance watch". Leaves out addresses and changing bytes, so it works as a cache key.
     */
    fun summary(r: ScanResult): String? {
        val record = r.scanRecord ?: return null
        val parts = mutableListOf<String>()
        record.manufacturerSpecificData?.let { data ->
            for (i in 0 until data.size()) {
                val id = data.keyAt(i)
                val first = data.valueAt(i).firstOrNull()?.toInt()?.and(0xFF)
                val type = if (id == 0x004C || id == 0x0006) first?.let { " type 0x%02X".format(it) }.orEmpty() else ""
                parts += "maker 0x%04X%s%s".format(id, companies[id]?.let { " ($it)" }.orEmpty(), type)
            }
        }
        val uuids = (record.serviceUuids.orEmpty() + record.serviceData?.keys.orEmpty())
            .map { uuid16(it)?.let { u -> "%04X".format(u) } ?: it.uuid.toString() }.distinct().sorted()
        if (uuids.isNotEmpty()) parts += "services " + uuids.joinToString(",")
        appearanceKind(record.bytes ?: ByteArray(0))?.let { parts += "appearance $it" }
        return parts.joinToString("; ").takeIf { it.isNotEmpty() }
    }

    /** Classic Bluetooth addresses are always the manufacturer-assigned (public) ones. */
    fun classic(address: String, btClass: BluetoothClass?): String? =
        join(ouiVendor(address), btClass?.let(::classKind))

    fun wifi(bssid: String, ssid: String?): String? {
        val first = bssid.take(2).toIntOrNull(16) ?: return null
        // Locally administered BSSIDs are made up by the access point, so skip the vendor lookup.
        val vendor = if (first and 0x02 == 0) ouiVendor(bssid) else null
        val kind = ssid?.let { s -> SSID_KINDS.firstOrNull { it.first.containsMatchIn(s) }?.second }
        return join(vendor, kind)
    }

    /** Registered owner of a MAC address's first three bytes, if known. */
    fun companyName(id: Int): String? = companies[id]

    fun ouiVendor(address: String): String? =
        address.replace(":", "").take(6).toIntOrNull(16)?.let { ouis[it] }

    private fun join(vendor: String?, kind: String?) = when {
        vendor == null -> kind
        kind == null -> vendor
        kind.contains(vendor.substringBefore(' '), ignoreCase = true) -> kind
        else -> "$vendor · $kind"
    }

    fun uuid16(uuid: ParcelUuid): Int? {
        val u = uuid.uuid
        // 16-bit UUIDs are shorthand for 0000xxxx-0000-1000-8000-00805F9B34FB.
        if (u.leastSignificantBits != BASE_UUID_LSB || (u.mostSignificantBits and 0xFFFFFFFFL) != 0x1000L) return null
        val top = u.mostSignificantBits ushr 32
        return if (top <= 0xFFFF) top.toInt() else null
    }

    private fun manufacturerKind(company: Int, data: ByteArray): String? {
        val type = data.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        if (type == 0x02 && data.size > 1 && data[1].toInt() == 0x15) return "iBeacon"
        return when (company) {
            0x004C -> when (type) { // Apple Continuity message types
                0x07 -> "AirPods / Beats"
                0x09 -> "AirPlay speaker or TV"
                0x0C -> "iPhone / iPad / Mac"
                0x0F -> "iPhone / iPad"
                0x10 -> "iPhone / iPad / Mac / Watch"
                0x12 -> "AirTag / Find My"
                else -> null
            }
            0x0006 -> when (type) { // Microsoft
                0x01 -> "Windows PC"
                0x03 -> "Swift Pair accessory"
                else -> null
            }
            else -> null
        }
    }

    /** Reads the GAP Appearance field (AD type 0x19) from a raw advertisement. */
    private fun appearanceKind(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size - 1) {
            val len = bytes[i].toInt() and 0xFF
            if (len == 0 || i + len >= bytes.size) break
            if (bytes[i + 1].toInt() and 0xFF == 0x19 && len >= 3) {
                val value = (bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)
                return APPEARANCE_KINDS[value shr 6]
            }
            i += len + 1
        }
        return null
    }

    private fun classKind(c: BluetoothClass): String? = when (c.deviceClass) {
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "car stereo"
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "hands-free / car kit"
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "headphones"
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> "speaker"
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER -> "TV"
        BluetoothClass.Device.PHONE_SMART, BluetoothClass.Device.PHONE_CELLULAR -> "phone"
        BluetoothClass.Device.COMPUTER_LAPTOP -> "laptop"
        BluetoothClass.Device.COMPUTER_DESKTOP -> "desktop computer"
        BluetoothClass.Device.WEARABLE_WRIST_WATCH -> "watch"
        else -> when (c.majorDeviceClass) {
            BluetoothClass.Device.Major.COMPUTER -> "computer"
            BluetoothClass.Device.Major.PHONE -> "phone"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio device"
            BluetoothClass.Device.Major.PERIPHERAL -> "keyboard / mouse / controller"
            BluetoothClass.Device.Major.IMAGING -> "printer / camera"
            BluetoothClass.Device.Major.WEARABLE -> "wearable"
            BluetoothClass.Device.Major.HEALTH -> "health device"
            BluetoothClass.Device.Major.NETWORKING -> "network device"
            BluetoothClass.Device.Major.TOY -> "toy"
            else -> null
        }
    }

    private const val BASE_UUID_LSB = -0x7fffff7fa064cb05L // 8000-00805F9B34FB

    private val SERVICE_KINDS = mapOf(
        0x180D to "heart-rate sensor",
        0x1812 to "keyboard / mouse / remote",
        0x1816 to "bike sensor",
        0x1818 to "bike power meter",
        0x1814 to "running sensor",
        0x1826 to "fitness machine",
        0x181D to "weight scale",
        0x1810 to "blood-pressure monitor",
        0x1809 to "thermometer",
        0x1808 to "glucose meter",
        0xFEAA to "beacon",
        0xFE2C to "Fast Pair accessory",
        0xFD6F to "phone",
        0xFEED to "tracker",
        0xFEEC to "tracker",
        0xFD84 to "tracker",
        0xFD5A to "SmartTag tracker",
    )

    /** GAP Appearance categories (value >> 6). */
    private val APPEARANCE_KINDS = mapOf(
        1 to "phone",
        2 to "computer",
        3 to "watch",
        5 to "display",
        6 to "remote control",
        7 to "eyeglasses",
        8 to "tag",
        9 to "keyring",
        10 to "media player",
        12 to "thermometer",
        13 to "heart-rate sensor",
        15 to "keyboard / mouse",
        16 to "glucose meter",
        17 to "running sensor",
        18 to "cycling sensor",
        0x21 to "speaker",
        0x25 to "earbuds / headphones",
    )

    private val SSID_KINDS = listOf(
        // Cradlepoint's default names give the model: these are branch, home and IoT routers, not vehicles.
        "^(E\\d{2,4}|AER\\d{3,4}|CBA\\d{3}|L950|W\\d{4}|S7\\d\\d|MBR\\d{3,4}|CR\\d{4})-[0-9a-f]{3}$" to "business router",
        "^DIRECT-.*(HP|Brother|Canon|EPSON|Xerox)" to "printer",
        "^(HP-Print|EPSON|Brother|Canon_ij)" to "printer",
        "^DIRECT-.*(\\[TV]|Samsung|BRAVIA|LG|Roku|TCL|Hisense)" to "TV",
        "^DIRECT-" to "Wi-Fi Direct device",
        "iPhone|iPad" to "Apple hotspot",
        "^(AndroidAP|Galaxy|Pixel|moto|OnePlus)" to "phone hotspot",
        "^(MyFord|SYNC|FordPass)" to "Ford car hotspot",
        "^(Tesla|Chevrolet|Chevy|GMC|Buick|Cadillac|Toyota|Honda|Hyundai|Kia|Nissan|Jeep|Dodge|Chrysler|Subaru|Mazda|VW|Audi|BMW|Mercedes)\\b" to "car hotspot",
        "^(xfinitywifi|XFINITY|CableWiFi|optimumwifi|attwifi|Spectrum ?WiFi)$" to "public hotspot",
        "^(Roku|Chromecast|FireTV|AFTV)" to "streaming device",
        "^(Ring|Wyze|Blink|Arlo)\\b" to "security camera",
        "^(NETGEAR|Linksys|TP-Link|ARRIS|ATT|Verizon_|MySpectrumWiFi|SpectrumSetup|ORBI|eero|Google ?Wifi|CenturyLink|Frontier|HOME-|TMOBILE|T-Mobile|Starlink|NOKIA|Xfinity)" to "home router",
    ).map { (pattern, kind) -> Regex(pattern, RegexOption.IGNORE_CASE) to kind }
}
