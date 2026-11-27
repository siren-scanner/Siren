package app.sirenscout

/**
 * Lots of the same kind of gear in one spot is a business, not a police car: a store's handheld
 * printers, a warehouse's scanners, a hospital's monitors. Those don't count as matches or alert.
 * (Vehicle routers have their own, stricter check: VehicleRouters.)
 */
object Crowds {
    /** This many of one kind, heard recently within [RADIUS_M] of each other, is a business. */
    const val MIN = 5
    private const val WINDOW_MS = 10 * 60_000L
    private const val RADIUS_M = 300.0

    /**
     * Only gear that businesses use in bulk: fleet gear, EMS, camera glasses / skimmers, and
     * low-confidence police matches (Motorola radios, breath testers...). Never confident police or
     * surveillance gear: five body cams in one spot is an incident or a precinct - exactly when you
     * want the alert.
     */
    private val BUSINESS_PRONE = setOf(Category.FLEET, Category.FIRE_EMS, Category.PRIVACY)

    fun appliesTo(sig: Signature) =
        sig.category in BUSINESS_PRONE || (sig.category == Category.POLICE && sig.confidence == Confidence.LOW)

    fun crowded(sig: Signature?, detections: Collection<Detection>, now: Long): Boolean {
        if (sig == null || !appliesTo(sig)) return false
        val same = detections.filter { it.signature?.id == sig.id && now - it.lastSeen < WINDOW_MS && it.lat != null && it.lon != null }
        if (same.size < MIN) return false
        val lat = same.map { it.lat!! }.average()
        val lon = same.map { it.lon!! }.average()
        return same.count { Odometer.distanceM(lat, lon, it.lat!!, it.lon!!) <= RADIUS_M } >= MIN
    }

    /**
     * What belongs under Matches: not part of a crowd, and trackers only once they've been with you
     * (otherwise every AirTag in every shopper's bag would be listed).
     */
    fun isMatch(d: Detection, all: Collection<Detection>, now: Long): Boolean {
        val sig = d.signature ?: return false
        if (sig.category == Category.TRACKER) return d.withYou != null
        return !crowded(sig, all, now)
    }
}
