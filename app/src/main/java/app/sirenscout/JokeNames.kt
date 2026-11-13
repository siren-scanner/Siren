package app.sirenscout

/**
 * Prank Wi-Fi names on home routers - "FBI Surveillance Van", "NSA Listening Post", "Police Van 3".
 * Anyone can name their network anything, so a law-enforcement name on a consumer router's
 * address is a joke, not an agency. Real covert gear doesn't announce itself.
 */
object JokeNames {
    const val LABEL = "Joke network name"

    private val AGENCY = Regex(
        """\b(fbi|nsa|cia|dea|atf|dhs|swat|interpol|secret\s*service|homeland|undercover|surveil+ance|stakeout|wire\s*tap|""" +
            """police|cops?|sheriff|patrol|swat|federal\s*agent|govt?|government|spy|spies|bugged|listening\s*post)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "Joke network name · Arcadyan home router" when [ssid] sounds like an agency and [guess]
     * (from [DeviceGuesser.wifi]) says the access point is ordinary consumer gear; otherwise null.
     */
    fun label(ssid: String?, guess: String?): String? {
        if (ssid == null || guess == null || !AGENCY.containsMatchIn(ssid)) return null
        if (!Everyday.isConsumerMaker(guess)) return null
        val maker = guess.substringBefore(" · ")
        return "$LABEL · $maker home router"
    }
}
