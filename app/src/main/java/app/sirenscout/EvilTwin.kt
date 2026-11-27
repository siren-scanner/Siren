package app.sirenscout

/**
 * Fake copies of Wi-Fi networks ("evil twins"): a network using a real network's name so phones
 * connect to it and its owner can watch or tamper with the traffic. Siren flags:
 *  - an open copy of a network that is otherwise password-protected (the strongest sign), except
 *    for factory-default names, which neighbours share by accident;
 *  - a copy of the network this phone is connected to from a made-up address (phone hotspot or
 *    gadget) that isn't one of that network's own access points;
 *  - Wi-Fi attack gear by its maker (Hak5's WiFi Pineapple).
 * A different maker alone isn't flagged: routers with an extender of another brand, mesh systems
 * and big public networks legitimately broadcast one name from many kinds of hardware.
 */
object EvilTwin {
    val SIGNATURE = Signature(
        id = "evil-twin",
        label = "Possible fake Wi-Fi (evil twin)",
        category = Category.SURVEILLANCE,
        confidence = Confidence.MEDIUM,
        sources = setOf(Source.WIFI),
        note = "A network copying another network's name, so phones join it instead. Don't connect to it; if you " +
            "already did, forget it in Wi-Fi settings.",
    )

    /** One access point from a scan. [secure] means it asks for a password (WPA/WEP/SAE/EAP). */
    data class Ap(val bssid: String, val ssid: String?, val secure: Boolean)

    /** Hak5 (WiFi Pineapple). */
    private val ATTACK_OUIS = mapOf("00:13:37" to "Hak5 (WiFi Pineapple)")

    /** From a scan result's capabilities, e.g. "[WPA2-PSK-CCMP][ESS]". Enhanced Open (OWE) counts as open. */
    fun secure(capabilities: String?): Boolean =
        capabilities != null && !capabilities.contains("OWE") && Regex("WPA|RSN|WEP|SAE|EAP").containsMatchIn(capabilities)

    /** Names routers ship with, which unrelated neighbours share by accident ("linksys", "NETGEAR42", "TP-Link_3F2A"). */
    private val DEFAULT_NAME = Regex(
        "^(linksys\\d*|netgear\\d*|netgear[-_ ].*|default|dlink.*|d-link.*|tp-?link.*|belkin.*|asus.*|tenda.*|" +
            "home|wireless|wifi|setup.*|xfinitywifi|attwifi|spectrumwifi.*|optimumwifi|cablewifi|guest)$",
        RegexOption.IGNORE_CASE,
    )

    /** The maker part of an address, with the made-up bit cleared: a router's own extra networks keep it. */
    private fun maker(bssid: String): String {
        val b = bssid.lowercase().split(':')
        if (b.size != 6) return bssid.lowercase()
        val first = ((b[0].toIntOrNull(16) ?: 0) and 0x02.inv()).toString(16).padStart(2, '0')
        return "$first:${b[1]}:${b[2]}"
    }

    /** Locally administered (made-up) addresses: phone hotspots, extra networks on a router, attack gadgets. */
    private fun randomized(bssid: String) = (bssid.take(2).toIntOrNull(16) ?: 0) and 0x02 != 0

    /**
     * The same physical access point broadcasts several networks from near-identical addresses
     * (different last byte, sometimes the "made-up" bit set): compare the middle four bytes.
     */
    private fun hardware(bssid: String) = bssid.lowercase().split(':').let { if (it.size == 6) it.subList(1, 5).joinToString(":") else bssid }

    /** Suspicious access points in one scan, by address, with why. [connected] is (SSID, BSSID) of this phone's network. */
    fun find(aps: List<Ap>, connected: Pair<String, String>?, @Suppress("UNUSED_PARAMETER") vendor: (String) -> String? = { null }): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (ap in aps) ATTACK_OUIS[ap.bssid.take(8).uppercase()]?.let { out[ap.bssid] = "Address registered to $it, Wi-Fi attack hardware" }
        for ((ssid, group) in aps.filter { it.ssid != null }.groupBy { it.ssid!! }) {
            // 1. Open copies of a password-protected network.
            val connectedHere = connected?.first == ssid
            if (group.any { it.secure } && (connectedHere || !DEFAULT_NAME.matches(ssid.trim()))) {
                group.filter { !it.secure }.forEach {
                    out.putIfAbsent(it.bssid, "Open (no password) copy of “$ssid”, which is otherwise password-protected")
                }
            }
            // 2. Copies of the network we're on, from other hardware.
            val (cSsid, cBssid) = connected ?: continue
            if (ssid != cSsid) continue
            val mine = group.firstOrNull { it.bssid.equals(cBssid, ignoreCase = true) } ?: continue
            if (randomized(mine.bssid)) continue
            // The network's own hardware: mesh nodes and extra bands keep their maker's prefix.
            val makers = group.filterNot { randomized(it.bssid) }.mapTo(HashSet()) { maker(it.bssid) }
            for (other in group) {
                if (other === mine || hardware(other.bssid) == hardware(mine.bssid)) continue
                if (randomized(other.bssid) && maker(other.bssid) !in makers) {
                    out.putIfAbsent(other.bssid, "Copy of “$ssid”, the network you're connected to, from a made-up address (phone hotspot or gadget)")
                }
            }
        }
        return out
    }
}
