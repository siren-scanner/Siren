package app.sirenscout

/**
 * Decodes drone Remote ID broadcasts (ASTM F3411 / FAA Part 89, the format OpenDroneID uses),
 * heard over Bluetooth (service data 0xFFFA) or in Wi-Fi beacons (vendor element FA:0B:BC, type 0x0D).
 * A drone sends its ID, position and operator position in separate 25-byte messages, so each
 * one updates a [Drone] that collects them.
 */
object RemoteId {
    const val BLE_SERVICE_UUID = "0000fffa-0000-1000-8000-00805f9b34fb"
    private const val APP_CODE = 0x0D
    private const val MESSAGE_SIZE = 25

    data class Drone(
        /** Serial number or registration, as broadcast. */
        val id: String? = null,
        val idType: String? = null,
        val aircraftType: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        /** Metres above the take-off point or the ground when known, else above sea level. */
        val heightM: Double? = null,
        val speedMs: Double? = null,
        val headingDeg: Double? = null,
        val operatorLat: Double? = null,
        val operatorLon: Double? = null,
        val description: String? = null,
        val operatorId: String? = null,
    )

    /** BLE service data: app code, counter, then one message (BT4) or a message pack (BT5). */
    fun fromBle(serviceData: ByteArray, into: Drone = Drone()): Drone? {
        if (serviceData.size < 2 + MESSAGE_SIZE || u8(serviceData, 0) != APP_CODE) return null
        return message(serviceData, 2, into)
    }

    /** Wi-Fi beacon vendor element contents: OUI FA:0B:BC, type 0x0D, counter, message pack. */
    fun fromWifiElement(element: ByteArray, into: Drone = Drone()): Drone? {
        if (element.size < 5 + 3 + MESSAGE_SIZE) return null
        if (u8(element, 0) != 0xFA || u8(element, 1) != 0x0B || u8(element, 2) != 0xBC || u8(element, 3) != APP_CODE) return null
        return message(element, 5, into)
    }

    /** Applies one message (or every message in a pack) starting at [at]; null if it isn't Remote ID. */
    fun message(b: ByteArray, at: Int, into: Drone): Drone? {
        if (at >= b.size) return null
        return when (u8(b, at) shr 4) {
            0xF -> pack(b, at, into)
            else -> if (at + MESSAGE_SIZE <= b.size) single(b, at, into) else null
        }
    }

    private fun pack(b: ByteArray, at: Int, into: Drone): Drone? {
        if (at + 3 > b.size || u8(b, at + 1) != MESSAGE_SIZE) return null
        val count = u8(b, at + 2)
        var d = into
        for (i in 0 until count) {
            val start = at + 3 + i * MESSAGE_SIZE
            if (start + MESSAGE_SIZE > b.size) break
            d = single(b, start, d) ?: d
        }
        return d
    }

    private fun single(b: ByteArray, at: Int, d: Drone): Drone? = when (u8(b, at) shr 4) {
        0 -> {
            val idType = u8(b, at + 1) shr 4
            val uaType = u8(b, at + 1) and 0x0F
            val id = text(b, at + 2, 20)
            // A drone may send a serial number and a registration; keep the serial number.
            if (id == null || (d.idType == ID_TYPES[1] && idType != 1)) d
            else d.copy(id = id, idType = ID_TYPES.getOrNull(idType), aircraftType = UA_TYPES.getOrNull(uaType))
        }
        1 -> {
            val flags = u8(b, at + 1)
            val direction = u8(b, at + 2) + if (flags and 0x02 != 0) 180 else 0
            val rawSpeed = u8(b, at + 3)
            val speed = if (flags and 0x01 == 0) rawSpeed * 0.25 else rawSpeed * 0.75 + 255 * 0.25
            val lat = s32(b, at + 5) * 1e-7
            val lon = s32(b, at + 9) * 1e-7
            val geodetic = altitude(b, at + 15)
            val height = altitude(b, at + 17)
            val valid = (lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0
            d.copy(
                lat = if (valid) lat else d.lat,
                lon = if (valid) lon else d.lon,
                heightM = height ?: geodetic ?: d.heightM,
                speedMs = if (rawSpeed == 255) d.speedMs else speed,
                headingDeg = if (direction > 360) d.headingDeg else direction.toDouble(),
            )
        }
        3 -> d.copy(description = text(b, at + 2, 23) ?: d.description)
        4 -> {
            val lat = s32(b, at + 2) * 1e-7
            val lon = s32(b, at + 6) * 1e-7
            val valid = (lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0
            if (valid) d.copy(operatorLat = lat, operatorLon = lon) else d
        }
        5 -> d.copy(operatorId = text(b, at + 2, 20) ?: d.operatorId)
        2 -> d // authentication: nothing to show
        else -> null
    }

    /** Altitude field: 0.5 m steps from -1000 m; 0 means unknown. */
    private fun altitude(b: ByteArray, at: Int): Double? {
        val raw = u8(b, at) or (u8(b, at + 1) shl 8)
        return if (raw == 0) null else raw * 0.5 - 1000
    }

    private fun text(b: ByteArray, at: Int, len: Int): String? {
        val bytes = b.copyOfRange(at, minOf(at + len, b.size)).takeWhile { it != 0.toByte() }
        return bytes.toByteArray().decodeToString().filter { it in ' '..'~' }.trim().takeIf { it.isNotEmpty() }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun s32(b: ByteArray, i: Int) = (u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24))

    private val ID_TYPES = listOf(null, "Serial number", "Registration", "UTM ID", "Session ID")
    private val UA_TYPES = listOf(
        null, "Aeroplane", "Multirotor / helicopter", "Gyroplane", "Hybrid lift (VTOL)", "Ornithopter", "Glider",
        "Kite", "Free balloon", "Captive balloon", "Airship", "Parachute", "Rocket", "Tethered aircraft",
        "Ground obstacle", "Other",
    )
}
