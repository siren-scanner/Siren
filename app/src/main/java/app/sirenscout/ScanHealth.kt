package app.sirenscout

/** Live counters showing whether each scanner is actually receiving anything. */
object ScanHealth {
    private const val WINDOW_MS = 60_000L

    @Volatile var startedAt: Long? = null
        private set
    @Volatile var lastWifiResults = 0L
        private set
    @Volatile var wifiRefused = 0
        private set
    @Volatile var bleFailures = 0
        private set
    @Volatile var lastBleError: String? = null
        private set
    private val bleSeen = ArrayDeque<Long>()
    private val classicSeen = ArrayDeque<Long>()

    @Synchronized
    fun started(now: Long = System.currentTimeMillis()) {
        startedAt = now
        lastWifiResults = 0
        wifiRefused = 0
        bleFailures = 0
        lastBleError = null
        bleSeen.clear()
        classicSeen.clear()
    }

    fun stopped() {
        startedAt = null
    }

    @Synchronized fun ble(now: Long = System.currentTimeMillis()) = add(bleSeen, now)

    @Synchronized fun classic(now: Long = System.currentTimeMillis()) = add(classicSeen, now)

    fun wifiResults(now: Long = System.currentTimeMillis()) {
        lastWifiResults = now
    }

    fun wifiRefused() {
        wifiRefused++
    }

    fun bleFailed(error: String) {
        bleFailures++
        lastBleError = error
    }

    private fun add(q: ArrayDeque<Long>, now: Long) {
        q.addLast(now)
        while (q.isNotEmpty() && now - q.first() > WINDOW_MS) q.removeFirst()
    }

    @Synchronized
    fun blePerMinute(now: Long) = bleSeen.count { now - it <= WINDOW_MS }

    @Synchronized
    fun classicPerMinute(now: Long) = classicSeen.count { now - it <= WINDOW_MS }

    /** One line for the activity log; null when not scanning. */
    fun summary(now: Long): String? {
        val start = startedAt ?: return null
        val wifiAge = if (lastWifiResults == 0L) "none yet" else "${(now - lastWifiResults) / 1000}s ago"
        return "running ${(now - start) / 60_000} min, BLE ${blePerMinute(now)}/min, " +
            "classic ${classicPerMinute(now)}/min, Wi-Fi results $wifiAge, " +
            "Wi-Fi scans refused $wifiRefused, BLE failures $bleFailures${lastBleError?.let { " ($it)" } ?: ""}"
    }
}
