package app.sirenscout

/**
 * A reality check on the on-device AI's answers. The small model often ignores its instructions:
 * a review of 3,684 answers found most of its police, surveillance, fleet and tracker labels were
 * wrong - joke Wi-Fi names, home routers, home cameras, delivery trucks, propane-tank monitors.
 * Here Siren corrects what it can tell from the maker and name, and only lets an official
 * category stand when the maker actually makes that kind of gear.
 */
object AiCheck {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Known things the AI gets wrong, by maker/advertisement and name: (maker pattern, name pattern, label, category). */
    private val KNOWN = listOf(
        // Commercial trucking: electronic logging devices and fleet trackers, not first responders.
        Fix(r("Samsara|KeepTruckin|Motive|Geotab|Ezurio|Omnitracs|PeopleNet|Verizon Connect"), null, "Commercial truck tracker (ELD)", "infrastructure"),
        Fix(null, r("^(eld\\b|.*\\beld\\b|keeptruckin|motive_|samsara)"), "Commercial truck tracker (ELD)", "infrastructure"),
        // Passenger cars' built-in Wi-Fi hotspots ("HOTSPOTA1B2").
        Fix(r("Flextronics|AUMOVIO|Harman|LG Innotek|Continental"), r("^hotspot"), "Car's built-in Wi-Fi hotspot", "consumer"),
        // Home security cameras and alarms.
        Fix(r("\\bRing\\b|Arlo|Blink|Vivint|Nest|Wyze|Eufy|SimpliSafe|ADT|Canary|0x1664"), null, "Home security camera or alarm", "consumer"),
        Fix(null, r("^(ring[- ]|ring setup|blink-|arlo_|ntgr_vmb|nest cam|wyze|eufy)"), "Home security camera or alarm", "consumer"),
        // Things mistaken for police gear.
        Fix(r("Otodata"), null, "Tank level monitor (propane, fuel)", "infrastructure"),
        Fix(r("services FE04"), null, "Door access reader", "infrastructure"),
        Fix(r("Zebra"), null, "Store or warehouse scanner", "infrastructure"),
        Fix(r("AirTies"), null, "Home Wi-Fi mesh router", "consumer"),
        Fix(r("Garmin"), null, "Garmin watch or sensor", "consumer"),
        // Apple "type 0x02" is an iBeacon, usually in a shop or venue - not an AirTag.
        Fix(r("Apple\\) type 0x02"), null, "iBeacon (shop or venue beacon)", "infrastructure"),
        Fix(r("services FEAA"), null, "Eddystone beacon", "infrastructure"),
    )

    private data class Fix(val maker: Regex?, val name: Regex?, val label: String, val category: String)

    /** Makers whose gear really is in police, fire / EMS or fleet vehicles, or is roadside surveillance. */
    private val PUBLIC_SAFETY_MAKERS = r(
        "Axon|TASER|Motorola Solutions|WatchGuard|L3Harris|Harris|Kenwood|Getac|Panasonic|Toughbook|Sierra Wireless|AirLink|" +
            "Cradlepoint|Peplink|Pepwave|Inseego|Teltonika|Whelen|Federal Signal|Code 3|Digital Ally|Zoll|Stryker|Physio|" +
            "Flock|Raven|Vigilant|Genetec|ShotSpotter|SoundThinking|Utility Inc|Coban|Kustom|Stalker|Decatur",
    )
    private val CONSUMER_ROUTER_MAKERS = r(
        "Commscope|Arris|Calix|Vantiva|Technicolor|Humax|TP-Link|Netgear|eero|Askey|Arcadyan|Sagemcom|Nokia|Actiontec|" +
            "Linksys|ASUS|D-Link|Belkin|Google|Ubee|Hitron|Sercomm|Zyxel|home router",
    )

    /** The answer after the reality check. [maker] is the maker/type text or BLE advertisement Siren saw. */
    fun check(source: Source, name: String?, maker: String?, ai: AiGuess): AiGuess {
        val m = maker.orEmpty()
        val n = name.orEmpty()
        KNOWN.firstOrNull { f ->
            (f.maker == null || f.maker.containsMatchIn(m)) && (f.name == null || f.name.containsMatchIn(n)) &&
                (f.maker != null || f.name != null)
        }?.let { f -> return ai.copy(label = f.label, category = f.category, why = "Corrected by Siren: ${ai.why}".take(140)) }
        if (!ai.isOfficial && ai.category != "tracker") return ai
        // Trackers: Siren's own tracker detection is reliable; the AI's guesses here were generic beacons.
        if (ai.category == "tracker") return ai.copy(category = "consumer", label = "Bluetooth beacon or tag", confidence = "low")
        if (PUBLIC_SAFETY_MAKERS.containsMatchIn(m) || PUBLIC_SAFETY_MAKERS.containsMatchIn(n)) return ai
        // Official-sounding guess with no public-safety maker behind it: almost always a joke name or a home router.
        return when {
            source == Source.WIFI && (CONSUMER_ROUTER_MAKERS.containsMatchIn(m) || m.isBlank() || m.contains("Ubiquiti") || m.contains("Cisco")) ->
                ai.copy(label = if (CONSUMER_ROUTER_MAKERS.containsMatchIn(m)) "Home Wi-Fi network" else "Wi-Fi network", category = "consumer", confidence = "low",
                    why = "Name or maker isn't public-safety gear; official-sounding names are usually jokes")
            else -> ai.copy(category = "unknown", confidence = "low", why = "Maker isn't known for public-safety gear: ${ai.why}".take(140))
        }
    }

    /** What Siren saw that tells the maker: registries for Wi-Fi and classic Bluetooth, the advertisement for BLE. */
    fun makerOf(d: Detection): String? = if (d.source == Source.BLE) listOfNotNull(d.adSummary, d.guess).joinToString("; ") else d.guess
}
