package app.sirenscout

enum class Source(val label: String) {
    BLE("BLE"),
    BT_CLASSIC("Bluetooth"),
    WIFI("Wi-Fi"),
    RF("RF"),
}

enum class Category(val label: String) {
    POLICE("Police"),
    FIRE_EMS("Fire / EMS"),
    FLEET("First-responder fleet gear"),
    RADIO("Radio transmission"),
    SURVEILLANCE("Plate reader / surveillance"),
    TRACKER("Location tracker"),
}

enum class Confidence { HIGH, MEDIUM, LOW }

data class Signature(
    val id: String,
    val label: String,
    val category: Category,
    val confidence: Confidence,
    val sources: Set<Source>,
    /** MAC address prefixes ("AA:BB:CC"). Only useful for devices that advertise a public address. */
    val ouis: List<String> = emptyList(),
    /** Matched against the BLE / Bluetooth device name or the Wi-Fi SSID. */
    val names: List<Regex> = emptyList(),
    /** Bluetooth SIG company IDs from BLE manufacturer data. */
    val manufacturerIds: Set<Int> = emptySet(),
    /** Advertised BLE service UUIDs, lowercase 128-bit form. */
    val serviceUuids: Set<String> = emptySet(),
    val note: String = "",
)

private fun names(vararg patterns: String) = patterns.map { Regex(it, RegexOption.IGNORE_CASE) }

private fun uuid16(vararg shorts: Int) = shorts.map { "%08x-0000-1000-8000-00805f9b34fb".format(it) }.toSet()

private val BT = setOf(Source.BLE, Source.BT_CLASSIC)
private val ALL_RADIOS = setOf(Source.BLE, Source.BT_CLASSIC, Source.WIFI)

/**
 * Heuristic signatures for equipment commonly installed in or carried from emergency vehicles.
 * None of these are exclusive to emergency services - routers in particular also show up on
 * buses, utility trucks and in businesses - hence the confidence levels.
 */
object Signatures {
    val RF_CARRIER = Signature(
        id = "rf-carrier",
        label = "Transmission on watched frequency",
        category = Category.RADIO,
        confidence = Confidence.MEDIUM,
        sources = setOf(Source.RF),
    )

    val BUILTIN = listOf(
        Signature(
            id = "axon",
            label = "Axon body camera / Signal / Fleet",
            category = Category.POLICE,
            confidence = Confidence.HIGH,
            sources = ALL_RADIOS,
            ouis = listOf("00:25:DF"),
            names = names("^axon", "signal sidearm", "signal vehicle"),
            note = "Axon Signal units broadcast when the light bar or siren is switched on.",
        ),
        Signature(
            id = "motorola-video",
            label = "Motorola / WatchGuard camera",
            category = Category.POLICE,
            confidence = Confidence.MEDIUM,
            sources = ALL_RADIOS,
            names = names("^V300", "^VISTA", "^Si500", "^M500", "^4RE", "watchguard"),
        ),
        Signature(
            id = "motorola-radio",
            label = "Motorola public-safety radio",
            category = Category.POLICE,
            confidence = Confidence.MEDIUM,
            sources = BT,
            names = names("^APX", "MOTOTRBO"),
        ),
        Signature(
            id = "digital-ally",
            label = "Digital Ally camera",
            category = Category.POLICE,
            confidence = Confidence.MEDIUM,
            sources = ALL_RADIOS,
            names = names("digital ?ally", "firstvu"),
        ),
        Signature(
            id = "warning-controls",
            label = "Light bar / siren controller",
            category = Category.FLEET,
            confidence = Confidence.MEDIUM,
            sources = BT,
            names = names("^whelen", "wecan", "cencom", "federal signal", "pathfinder", "^code ?3"),
        ),
        Signature(
            id = "ems-equipment",
            label = "EMS medical equipment",
            category = Category.FIRE_EMS,
            confidence = Confidence.MEDIUM,
            sources = BT,
            names = names("^lifepak", "^zoll", "^stryker"),
        ),
        // Flock and Raven identifiers come from community research (the flock-you projects),
        // not from the vendors. Their chip makers' MAC prefixes are deliberately left out:
        // Liteon and Silicon Labs radios are in countless unrelated devices.
        Signature(
            id = "flock",
            label = "Flock Safety camera / battery pack",
            category = Category.SURVEILLANCE,
            confidence = Confidence.MEDIUM,
            sources = ALL_RADIOS,
            names = names("flock", "penguin", "pigvision", "fs ext battery", "flck"),
            manufacturerIds = setOf(0x09C8), // XUNTONG radio module seen in Flock units
            serviceUuids = setOf("e8ccbb38-9532-46a8-9fe5-1814df172e6f"), // Flock accessory service
            note = "From community research. Most Flock cameras only use cellular and won't be seen.",
        ),
        Signature(
            id = "raven",
            label = "Raven gunshot detector",
            category = Category.SURVEILLANCE,
            confidence = Confidence.MEDIUM,
            sources = setOf(Source.BLE),
            serviceUuids = uuid16(0x3100, 0x3200, 0x3300, 0x3400, 0x3500),
            note = "From community research. Often installed alongside Flock cameras.",
        ),
        Signature(
            id = "inseego",
            label = "Inseego vehicle router",
            category = Category.FLEET,
            confidence = Confidence.LOW,
            sources = setOf(Source.WIFI),
            // Verified against the IEEE registry: all five are allocated to Inseego Wireless.
            ouis = listOf("00:15:FF", "18:EE:86", "28:80:A2", "78:0C:71", "E0:86:14"),
            note = "Inseego (Skyus, MiFi) routers are common in police and fire vehicles, but also sold as personal hotspots.",
        ),
        Signature(
            id = "cradlepoint",
            label = "Cradlepoint vehicle router",
            category = Category.FLEET,
            confidence = Confidence.LOW,
            sources = setOf(Source.WIFI),
            ouis = listOf("00:30:44"),
            names = names("^IBR[0-9]{3,4}", "^R1900", "^cradlepoint"),
            note = "Common in police, fire and EMS vehicles, but also buses and businesses.",
        ),
        Signature(
            id = "sierra-airlink",
            label = "Sierra Wireless AirLink vehicle router",
            category = Category.FLEET,
            confidence = Confidence.LOW,
            sources = setOf(Source.WIFI),
            ouis = listOf("00:A0:D5"),
            names = names("^MG90", "^MP70", "^airlink"),
        ),
        Signature(
            id = "peplink",
            label = "Peplink / Pepwave vehicle router",
            category = Category.FLEET,
            confidence = Confidence.LOW,
            sources = setOf(Source.WIFI),
            ouis = listOf("00:1A:DD"),
            names = names("^MAX[- ]?(BR1|transit|HD2|HD4)", "^pepwave", "^peplink"),
        ),
        Signature(
            id = "rugged-laptop",
            label = "In-car rugged computer",
            category = Category.FLEET,
            confidence = Confidence.LOW,
            sources = ALL_RADIOS,
            names = names("^getac", "toughbook", "arbitrator"),
        ),
    )

    fun byId(id: String): Signature? =
        if (id == RF_CARRIER.id) RF_CARRIER
        else if (id.startsWith("rf-band-")) Bands.signatureById(id)
        else (BUILTIN + Trackers.ALL).firstOrNull { it.id == id } ?: WatchRules.byId(id)

    fun match(
        source: Source,
        address: String?,
        name: String?,
        manufacturerIds: Collection<Int> = emptyList(),
        serviceUuids: Collection<String> = emptyList(),
    ): Signature? = matchWithReason(source, address, name, manufacturerIds, serviceUuids)?.first

    /** The first built-in signature that matches, with a plain-language reason it matched. */
    fun matchWithReason(
        source: Source,
        address: String?,
        name: String?,
        manufacturerIds: Collection<Int> = emptyList(),
        serviceUuids: Collection<String> = emptyList(),
    ): Pair<Signature, String>? {
        for (sig in BUILTIN) {
            if (source !in sig.sources) continue
            sig.ouis.firstOrNull { ouiMatches(source, address, it) }?.let { oui ->
                val owner = DeviceGuesser.ouiVendor(oui)?.let { ", registered to $it" } ?: ""
                return sig to "Address starts with $oui$owner"
            }
            if (name != null) sig.names.firstOrNull { it.containsMatchIn(name) }?.let { re ->
                return sig to "Name \u201c$name\u201d matches \u201c${re.pattern.trim('^', '$').replace("\\b", "")}\u201d"
            }
            manufacturerIds.firstOrNull { it in sig.manufacturerIds }?.let { id ->
                val owner = DeviceGuesser.companyName(id)?.let { " ($it)" } ?: ""
                return sig to "Bluetooth manufacturer ID 0x%04X%s".format(id, owner)
            }
            serviceUuids.firstOrNull { it in sig.serviceUuids }?.let { uuid ->
                return sig to "Advertises Bluetooth service $uuid"
            }
        }
        return null
    }

    private fun ouiMatches(source: Source, address: String?, oui: String): Boolean {
        if (address == null || address.length < 8) return false
        if (address.startsWith(oui, ignoreCase = true)) return true
        // Access points often derive extra BSSIDs by setting the locally-administered bit.
        if (source != Source.WIFI) return false
        val first = address.substring(0, 2).toIntOrNull(16) ?: return false
        val normalized = "%02X".format(first and 0x02.inv()) + address.substring(2, 8)
        return normalized.equals(oui, ignoreCase = true)
    }
}
