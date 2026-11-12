package app.sirenscout

/**
 * Decides whether a detection is an ordinary everyday device (home router, phone, headphones…)
 * from Siren's guesses, so All nearby can leave out the noise. Anything matching a signature or
 * watch rule, and anything Siren can't identify at all, is never treated as everyday.
 */
object Everyday {
    /** Device kinds from [DeviceGuesser] that are ordinary consumer gear. */
    private val KINDS = listOf(
        "home router", "public hotspot", "phone", "iphone", "ipad", "mac", "watch", "airpods", "beats",
        "earbuds", "headphones", "speaker", "airplay", "tv", "streaming device", "media player", "display",
        "printer", "computer", "laptop", "windows pc", "keyboard", "mouse", "remote control", "fast pair",
        "swift pair", "fitness", "heart-rate", "running sensor", "cycling sensor", "bike sensor",
        "bike power meter", "weight scale", "thermometer", "glucose meter", "blood-pressure", "eyeglasses",
        "security camera", "wi-fi direct", "beacon", "ibeacon", "tag", "keyring", "airtag / find my",
        "smarttag tracker", "tracker", "phone hotspot", "apple hotspot", "car stereo", "hands-free",
        "audio device", "wearable", "health device",
    )

    /** Makers whose Wi-Fi gear in the wild is overwhelmingly home routers, TVs and gadgets. */
    private val CONSUMER_MAKERS = listOf(
        "commscope", "arris", "arcadyan", "sagemcom", "technicolor", "vantiva", "humax", "netgear", "tp-link",
        "linksys", "asustek", "eero", "google", "askey", "calix", "zyxel", "actiontec", "huawei", "zte",
        "sercomm", "wnc", "d-link", "xiaomi", "amazon", "roku", "samsung", "apple", "lg electronics", "sony",
        "hisense", "tcl", "vizio", "espressif", "murata", "intel", "belkin", "tenda", "mercusys", "nokia",
        "bose", "jbl", "harman", "skullcandy", "anker", "fitbit", "garmin", "sennheiser", "jabra", "oura",
    )

    fun isEveryday(d: Detection): Boolean {
        if (d.signature != null) return false
        AiGuesser.lookup(d)?.let { ai ->
            if (ai.isOfficial) return false
            if (ai.isConsumer && ai.confidence != "low") return true
        }
        val guess = d.guess?.lowercase() ?: return false
        val (maker, kind) = guess.split(" · ", limit = 2).let { if (it.size == 2) it[0] to it[1] else null to it[0] }
        // "Ford car hotspot" etc. stay visible: a vehicle hotspot could be a fleet or police car.
        if ("car hotspot" in guess) return false
        if (KINDS.any { kind.contains(it) }) return true
        // A bare maker name (no kind) - e.g. a router that only gave away its manufacturer.
        val name = maker ?: kind
        return CONSUMER_MAKERS.any { name.startsWith(it) }
    }
}
