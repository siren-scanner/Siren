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
    PRIVACY("Camera glasses / card skimmer"),
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
    /** Names that rule a match out, even when the address matches (a maker's non-vehicle models). */
    val notNames: List<Regex> = emptyList(),
    /** ASCII text hidden in the raw BLE broadcast (e.g. "BWCDEVICE" in body cameras' adverts). */
    val payloadTokens: List<String> = emptyList(),
    /**
     * Match only when a [names] pattern AND one of the IDs (address prefix, company ID, service)
     * agree: for makers whose gear is everywhere, where only a telling name narrows it down.
     */
    val needNameAndId: Boolean = false,
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
            // D8:1F:65 is unregistered but field-attributed to Axon; AB2-/AB3-/AB4- and AXON- are
            // Axon Body camera and device Wi-Fi networks (from Axon's admin docs, via SignalSweep).
            ouis = listOf("00:25:DF", "D8:1F:65"),
            names = names("^axon", "signal sidearm", "signal vehicle", "^AB[234]-", "^AXON-"),
            manufacturerIds = setOf(0x034D), // TASER International
            serviceUuids = uuid16(0xFC81, 0xFE6B, 0xFE6C), // Axon, TASER
            payloadTokens = listOf("BWCDEVICE"),
            note = "Axon Signal units broadcast when the light bar or siren is switched on.",
        ),
        Signature(
            id = "motorola-video",
            label = "Motorola / WatchGuard camera",
            category = Category.POLICE,
            confidence = Confidence.MEDIUM,
            sources = ALL_RADIOS,
            ouis = listOf("00:1D:96"), // WatchGuard Video
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
            ouis = listOf("00:23:BD"),
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
            ouis = listOf("B4:1E:52"), // registered to Flock Safety
            names = names("flock", "penguin", "pigvision", "fs ext battery", "flck", "^FS-[0-9A-F]{4,}$"),
            manufacturerIds = setOf(0x09C8), // XUNTONG radio module seen in Flock units
            // Flock accessory and battery services.
            serviceUuids = setOf("e8ccbb38-9532-46a8-9fe5-1814df172e6f", "20c944c1-add2-42d7-a638-967ee9a26ff6"),
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
            ouis = listOf("00:30:44", "00:E0:1C"),
            names = names("^IBR[0-9]{3,4}", "^R(500|920|980|1900|2100)\\b", "^cradlepoint"),
            note = "Common in police, fire and EMS vehicles, but also buses and businesses.",
            // Default names of Cradlepoint's branch, home and IoT models: shops, ATMs, signs and kiosks.
            notNames = names("^(E\\d{2,4}|AER\\d{3,4}|AP\\d{2}|CBA\\d{3}|L950|W\\d{4}|S7\\d\\d|MBR\\d{3,4}|CR\\d{4}|ARC\\b)"),
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
        // The signatures below were added from RF Sentinel's signature list (GPL-3.0,
        // github.com/CIS-C0/RFSentinel, docs/SIGNATURES.md), which cites its sources: the Bluetooth
        // SIG and IEEE registries and community field research (ACAB, SignalSweep, Wardrive Go).
        Signature(
            id = "body-cam-makers",
            label = "Body / in-car camera",
            category = Category.POLICE,
            confidence = Confidence.MEDIUM,
            sources = ALL_RADIOS,
            // Zepcam, Utility Inc., PRO-VISION, i-PRO, Kustom Signals (28-bit block), Safety Vision.
            ouis = listOf("48:46:8D", "00:09:BC", "00:16:ED", "9C:83:BF", "D4:2D:C5", "58:E8:76:C", "00:22:AF", "E4:1E:0A:B"),
            names = names("bodyworn remote"),
            note = "Camera makers that mostly sell to police; PRO-VISION and Safety Vision also equip school buses and transit.",
        ),
        Signature(
            id = "motorola-solutions",
            label = "Motorola Solutions device (often a two-way radio)",
            category = Category.POLICE,
            confidence = Confidence.LOW,
            sources = setOf(Source.BLE),
            manufacturerIds = setOf(0x04EC),
            serviceUuids = uuid16(0xFD8E, 0xFE04),
            note = "Mostly two-way radios: police and fire, but also security guards, schools and businesses.",
        ),
        Signature(
            id = "fleet-printer",
            label = "Zebra mobile printer (e-ticket printer)",
            category = Category.FLEET,
            confidence = Confidence.LOW,
            sources = setOf(Source.BLE),
            serviceUuids = uuid16(0xFE79, 0xFD66),
            // Only a printer never renamed from its factory serial: shops rename theirs (e.g. "WMP04.1766").
            names = listOf(Regex("^[A-Z0-9]{2}[A-Z]{3}[0-9]{9}$")),
            needNameAndId = true,
            note = "Police use these for e-tickets; the same printers are common in warehouses and shops.",
        ),
        Signature(
            id = "ptt-button",
            label = "Push-to-talk radio button",
            category = Category.FLEET,
            confidence = Confidence.LOW,
            sources = setOf(Source.BLE),
            serviceUuids = setOf("2320ae58-8394-4652-95f7-0a872ac0958f"), // Coda / Blu-PTT
            names = names("^ASB[0-9A-F]{6}$", "^PTT-(Z|AZ|Z01)\\b"),
            note = "Buttons for phone radio apps (e.g. ESChat), used by police and EMS but also guards and transit crews.",
        ),
        Signature(
            id = "breath-tester",
            label = "Dräger device (roadside breath / drug tester)",
            category = Category.POLICE,
            confidence = Confidence.LOW,
            sources = setOf(Source.BLE),
            manufacturerIds = setOf(0x04BC),
            serviceUuids = uuid16(0xFCDA),
            note = "Dräger also makes hospital and gas-detection gear.",
        ),
        Signature(
            id = "ems-devices",
            label = "Ambulance monitor / defibrillator",
            category = Category.FIRE_EMS,
            confidence = Confidence.LOW,
            sources = setOf(Source.BLE, Source.WIFI),
            ouis = listOf("40:BD:9E", "00:0D:D1"), // Physio-Control, Stryker (LIFEPAK monitors send over Wi-Fi)
            manufacturerIds = setOf(0x0A5F), // Stryker
            serviceUuids = uuid16(0xFE22, 0xFE23, 0xFC6E), // Zoll, Stryker
            note = "Also in hospitals, and worn by some patients (Zoll LifeVest).",
        ),
        Signature(
            id = "gunshot-sensor",
            label = "ShotSpotter gunshot sensor",
            category = Category.SURVEILLANCE,
            confidence = Confidence.MEDIUM,
            sources = ALL_RADIOS,
            ouis = listOf("D4:11:D6"), // ShotSpotter / SoundThinking
        ),
        Signature(
            id = "phone-forensics",
            label = "Phone forensics (Cellebrite / GrayKey)",
            category = Category.POLICE,
            confidence = Confidence.MEDIUM,
            sources = ALL_RADIOS,
            ouis = listOf("00:16:00", "70:B3:D5:71:F"),
            note = "Equipment for unlocking and copying phones.",
        ),
        Signature(
            id = "camera-glasses",
            label = "Camera glasses (Ray-Ban / Oakley Meta, Spectacles, Vuzix)",
            category = Category.PRIVACY,
            confidence = Confidence.MEDIUM,
            sources = setOf(Source.BLE),
            manufacturerIds = setOf(0x0D53, 0x03C2, 0x060C), // Luxottica, Snap, Vuzix
            serviceUuids = uuid16(0xFE45) + "7905fff0-b5ce-4e99-a40f-4b1e122d00d0", // Snap; HeyCyan-based glasses
            note = "Glasses that can quietly take photos and video.",
        ),
        Signature(
            id = "meta-device",
            label = "Meta device (camera glasses or a Quest headset)",
            category = Category.PRIVACY,
            confidence = Confidence.LOW,
            sources = setOf(Source.BLE),
            manufacturerIds = setOf(0x01AB),
            serviceUuids = uuid16(0xFEB7, 0xFEB8),
        ),
        Signature(
            id = "card-skimmer",
            label = "Possible card skimmer (Bluetooth serial module)",
            category = Category.PRIVACY,
            confidence = Confidence.MEDIUM,
            sources = BT,
            names = names("^HC-0[356]$", "^RN42", "^BT04-A$"),
            note = "Cheap Bluetooth modules hidden in gas pumps and ATMs to send stolen card data. Hobby electronics use them too, " +
                "so it matters most right at a pump or ATM.",
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
        else (BUILTIN + Trackers.ALL + EvilTwin.SIGNATURE).firstOrNull { it.id == id } ?: WatchRules.byId(id)

    fun match(
        source: Source,
        address: String?,
        name: String?,
        manufacturerIds: Collection<Int> = emptyList(),
        serviceUuids: Collection<String> = emptyList(),
        payload: ByteArray? = null,
    ): Signature? = matchWithReason(source, address, name, manufacturerIds, serviceUuids, payload)?.first

    /** The first built-in signature that matches, with a plain-language reason it matched. */
    fun matchWithReason(
        source: Source,
        address: String?,
        name: String?,
        manufacturerIds: Collection<Int> = emptyList(),
        serviceUuids: Collection<String> = emptyList(),
        payload: ByteArray? = null,
    ): Pair<Signature, String>? {
        val text = payload?.let { String(it, Charsets.ISO_8859_1) }
        for (sig in BUILTIN) {
            if (source !in sig.sources) continue
            if (name != null && sig.notNames.any { it.containsMatchIn(name) }) continue
            if (sig.needNameAndId) {
                val nameHit = name != null && sig.names.any { it.containsMatchIn(name) }
                val idHit = sig.ouis.any { ouiMatches(source, address, it) } || manufacturerIds.any { it in sig.manufacturerIds } ||
                    serviceUuids.any { it in sig.serviceUuids }
                if (nameHit && idHit) return sig to "Name \u201c$name\u201d is a factory serial number, with ${sig.label.substringBefore(" (")}'s Bluetooth ID"
                continue
            }
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
            if (text != null) sig.payloadTokens.firstOrNull { tokenIn(text, it) }?.let { token ->
                return sig to "Its Bluetooth broadcast contains “$token”"
            }
        }
        return null
    }

    /** [token] in the broadcast, in either byte order (some devices store it reversed). */
    internal fun tokenIn(text: String, token: String) = text.contains(token) || text.contains(token.reversed())

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
