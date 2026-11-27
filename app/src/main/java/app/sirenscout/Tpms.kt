package app.sirenscout

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tire-pressure sensors (TPMS): every car's wheels broadcast a fixed sensor ID on 315 MHz (in
 * the US) about once a minute while driving. The same car's sensors heard with you place after
 * place mean that car is following you - including unmarked cars with no police gear on board.
 *
 * There are dozens of sensor makes with their own formats, so rather than decoding each one this
 * is a protocol-agnostic decoder: find short bursts, read them as frequency-shift (FSK) and as
 * on-off (OOK) keying, recover the bit rate from the shortest pulses, undo Manchester coding (used
 * by nearly all of them), and take the start of the message - where the sensor ID sits - as its
 * fingerprint. Noise is ruled out later by requiring the same message start twice (TireWatch).
 * After RF Sentinel's tire-sensor feature; signal handling after rtl_433's approach.
 */
object Tpms {
    /** Tuned this far below 315 MHz so the dongle's DC spike stays clear of the sensors. */
    const val TUNE_HZ = 314_750_000L
    const val CARRIER_HZ = 315_000_000L

    data class Frame(val bits: String, val modulation: Char, val levelDb: Double = 0.0) {
        /** The sensor's fingerprint: the first [ID_BITS] bits of the message, as hex. */
        val id: String get() = bits.take(ID_BITS).chunked(4).joinToString("") { Integer.toHexString(it.toInt(2)) }
    }

    const val ID_BITS = 32
    /** Shortest message worth keeping, after the preamble. */
    private const val MIN_PAYLOAD_BITS = 48

    /**
     * Frames in [iq] (unsigned 8-bit I/Q at [sampleRate], tuned to [tunedHz]): mixed down so 315 MHz
     * sits at 0, low-passed and decimated by [decimation].
     */
    fun decode(
        iq: ByteArray, sampleRate: Int = RtlTcpMonitor.SAMPLE_RATE, tunedHz: Long = TUNE_HZ, decimation: Int = 4,
        /** Bytes at the start that the previous call already covered: bursts ending in them are skipped. */
        alreadyCoveredBytes: Int = 0,
    ): List<Frame> {
        val offset = (CARRIER_HZ - tunedHz).toDouble() / sampleRate
        val n = iq.size / 2 / decimation
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        // A rotating phasor instead of cos/sin per sample (renormalised now and then).
        val stepC = cos(-2 * PI * offset); val stepS = sin(-2 * PI * offset)
        var c = 1.0; var s = 0.0
        var k = 0
        for (j in 0 until n) {
            var sr = 0.0; var si = 0.0
            repeat(decimation) {
                val x = (iq[2 * k].toInt() and 0xFF) - 127.5
                val y = (iq[2 * k + 1].toInt() and 0xFF) - 127.5
                sr += x * c - y * s
                si += x * s + y * c
                val nc = c * stepC - s * stepS
                s = c * stepS + s * stepC
                c = nc
                k++
            }
            if (j and 1023 == 0) { val norm = sqrt(c * c + s * s); c /= norm; s /= norm }
            re[j] = sr / decimation; im[j] = si / decimation
        }
        return decodeBaseband(re, im, alreadyCoveredBytes / 2 / decimation)
    }

    /** Frames in baseband samples (315 MHz at 0 Hz). */
    fun decodeBaseband(re: DoubleArray, im: DoubleArray, skipEndingBefore: Int = 0): List<Frame> {
        val n = re.size
        if (n < 64) return emptyList()
        val mag = DoubleArray(n) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        // The background level from the quieter samples, so a long message can't pass for noise.
        val floor = mag.copyOf().also { it.sort() }[n / 5].coerceAtLeast(0.3)
        val frames = ArrayList<Frame>()
        // Bursts: long enough stretches clearly above the noise (small gaps bridged, for OOK).
        var i = 0
        while (i < n) {
            if (mag[i] < floor * BURST_FACTOR) { i++; continue }
            val start = i
            var lastHigh = i
            while (i < n && i - lastHigh < MAX_GAP) { if (mag[i] >= floor * BURST_FACTOR) lastHigh = i; i++ }
            val end = lastHigh + 1
            // A burst that ended within the part the previous call covered was reported then.
            if (end - start >= MIN_BURST && end > skipEndingBefore) frames += burst(re, im, mag, floor, start, end)
        }
        return frames
    }

    /** About 4.7x the typical noise level (the 20th percentile of noise is ~0.57x its median). */
    private const val BURST_FACTOR = 7.0
    private const val MAX_GAP = 40
    private const val MIN_BURST = 200

    private fun burst(re: DoubleArray, im: DoubleArray, mag: DoubleArray, floor: Double, start: Int, end: Int): List<Frame> {
        val len = end - start
        // FSK: instantaneous frequency (phase step), compared with its middle value.
        val freq = DoubleArray(len)
        for (j in 1 until len) {
            val a = start + j - 1; val b = start + j
            freq[j] = atan2(im[b] * re[a] - re[b] * im[a], re[b] * re[a] + im[b] * im[a])
        }
        freq[0] = freq.getOrElse(1) { 0.0 }
        val mid = freq.copyOf().also { it.sort() }.let { (it[it.size / 4] + it[it.size * 3 / 4]) / 2 }
        val fsk = BooleanArray(len) { freq[it] > mid }
        // OOK: on above halfway between the noise and the burst's strong level.
        val strong = mag.copyOfRange(start, end).also { it.sort() }[len * 9 / 10]
        val ookLevel = (strong + floor) / 2
        val ook = BooleanArray(len) { mag[start + it] > ookLevel }
        val levelDb = 20 * kotlin.math.log10(strong / 127.5 + 1e-9)
        return listOfNotNull(manchester(fsk)?.let { Frame(it, 'F', levelDb) }, manchester(ook)?.let { Frame(it, 'O', levelDb) })
    }

    /**
     * The bit-rate is set by the shortest pulses (half a Manchester bit); every pulse is then one
     * or two of those. Returns the longest valid message after its preamble, or null.
     */
    internal fun manchester(level: BooleanArray): String? {
        // Runs of equal level, ignoring one-sample glitches.
        val runs = ArrayList<Pair<Boolean, Int>>()
        var cur = level[0]; var count = 0
        for (b in level) {
            if (b == cur) count++ else { runs += cur to count; cur = b; count = 1 }
        }
        runs += cur to count
        val lengths = runs.map { it.second }.filter { it >= 3 }.sorted()
        if (lengths.size < 20) return null
        val half = lengths[lengths.size / 5].toDouble().coerceAtLeast(3.0) // a short pulse: the 20th percentile
        val halves = StringBuilder()
        for ((b, r) in runs) {
            val count2 = (r / half).roundToInt()
            if (count2 == 0) continue
            if (count2 > 2) { halves.append('|') } // too long for Manchester: a break
            repeat(count2.coerceAtMost(2)) { halves.append(if (b) '1' else '0') }
        }
        var best: String? = null
        for (segment in halves.split('|')) {
            for (align in 0..1) {
                val bits = StringBuilder()
                var j = align
                while (j + 1 < segment.length) {
                    val pair = segment.substring(j, j + 2)
                    when (pair) {
                        "10" -> bits.append('1')
                        "01" -> bits.append('0')
                        else -> { // a coding error: keep the longest clean stretch
                            if (bits.length > (best?.length ?: 0)) best = bits.toString()
                            bits.setLength(0)
                        }
                    }
                    j += 2
                }
                if (bits.length > (best?.length ?: 0)) best = bits.toString()
            }
        }
        return best?.let(::afterPreamble)?.takeIf { it.length >= MIN_PAYLOAD_BITS }?.let(::normalize)
    }

    /** Drops the preamble: the leading run of repeated 0s, 1s or 01 pairs. */
    private fun afterPreamble(bits: String): String {
        var i = 0
        while (i + 1 < bits.length && bits[i] == bits[i + 1]) i++
        if (i >= 6) return bits.substring(i + 1)
        i = 0
        while (i + 2 < bits.length && bits[i] == bits[i + 2] && bits[i] != bits[i + 1]) i++
        return if (i >= 6) bits.substring(i + 2) else bits
    }

    /** The same message can come out inverted (polarity depends on the make): pick one form. */
    private fun normalize(bits: String) = if (bits[0] == '1') bits else bits.map { if (it == '1') '0' else '1' }.joinToString("")

    /** For tests and the simulator: a message as Manchester half-bits after a preamble. */
    fun manchesterEncode(payload: String, preambleBits: Int = 16): String =
        ("0".repeat(preambleBits) + "1" + payload).map { if (it == '1') "10" else "01" }.joinToString("")

    /** Baseband FSK samples for [halfBits] at [samplesPerHalf], with a carrier [offsetRad] per sample. */
    fun fskBaseband(halfBits: String, samplesPerHalf: Int, deviationRad: Double, offsetRad: Double, amplitude: Double): Pair<DoubleArray, DoubleArray> {
        val n = halfBits.length * samplesPerHalf
        val re = DoubleArray(n); val im = DoubleArray(n)
        var ph = 0.0
        for (j in 0 until n) {
            val bit = halfBits[j / samplesPerHalf] == '1'
            ph += offsetRad + if (bit) deviationRad else -deviationRad
            re[j] = amplitude * cos(ph); im[j] = amplitude * sin(ph)
        }
        return re to im
    }

    fun ookBaseband(halfBits: String, samplesPerHalf: Int, offsetRad: Double, amplitude: Double): Pair<DoubleArray, DoubleArray> {
        val n = halfBits.length * samplesPerHalf
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (j in 0 until n) {
            val on = halfBits[j / samplesPerHalf] == '1'
            val ph = offsetRad * j
            re[j] = if (on) amplitude * cos(ph) else 0.0; im[j] = if (on) amplitude * sin(ph) else 0.0
        }
        return re to im
    }

}

/**
 * Which tire sensors are around, which are your own car's, and whether another car's sensors keep
 * turning up with you. A sensor counts once its fingerprint is heard twice within a few minutes
 * (noise never repeats). Your own car is learned: sensors heard in the first minutes of two
 * separate scanning sessions. Sensors first heard together belong to one car, so one car makes one
 * alert. Pure logic with times and places passed in, for testing.
 */
class TireWatch(
    /** Sensor IDs known to be your own car's, with how many sessions they started with you. */
    private val starts: MutableMap<String, Int>,
    private val sessionStart: Long,
    private val followMs: Long = 10 * 60_000L,
    private val followM: Double = 5_000.0,
) {
    data class Sensor(
        val id: String,
        val firstSeen: Long,
        var lastSeen: Long,
        var hits: Int,
        val startOdometerM: Double,
        var lastOdometerM: Double,
        val cells: MutableSet<Long> = HashSet(),
        var confirmed: Boolean = false,
        var alerted: Boolean = false,
    )

    /** A car whose sensors stayed with you. */
    data class Follow(val sensors: List<Sensor>, val durationMs: Long, val distanceM: Double)

    val sensors = LinkedHashMap<String, Sensor>()
    private val inStartSet = HashSet<String>()

    fun isOwn(id: String) = (starts[id] ?: 0) >= OWN_AFTER_SESSIONS

    /**
     * Records hearing [id]. Returns the sensor when it was just confirmed (to list it), and a
     * [Follow] when its car has now stayed with you long enough.
     */
    fun heard(id: String, now: Long, lat: Double?, lon: Double?, odometerM: Double): Pair<Sensor?, Follow?> {
        val s = sensors.getOrPut(id) { Sensor(id, now, now, 0, odometerM, odometerM) }
        if (now - s.lastSeen > FORGET_MS) { sensors.remove(id); return heard(id, now, lat, lon, odometerM) }
        s.hits++; s.lastSeen = now; s.lastOdometerM = odometerM
        if (lat != null && lon != null) s.cells += cell(lat, lon)
        var newlyConfirmed: Sensor? = null
        if (!s.confirmed && s.hits >= 2) {
            s.confirmed = true
            newlyConfirmed = s
            // Heard in this session's first minutes: maybe your own car (counted once per session).
            if (now - sessionStart < START_WINDOW_MS && inStartSet.add(id)) starts[id] = (starts[id] ?: 0) + 1
        }
        return newlyConfirmed to follow(s, now)
    }

    private fun follow(s: Sensor, now: Long): Follow? {
        if (!s.confirmed || s.alerted || isOwn(s.id)) return null
        val duration = now - s.firstSeen
        val distance = s.lastOdometerM - s.startOdometerM
        if (duration < followMs || distance < followM || s.cells.size < MIN_PLACES || s.hits < MIN_HITS) return null
        // The rest of its car: sensors first heard within a couple of minutes of it, still with you.
        val car = sensors.values.filter {
            it.confirmed && !isOwn(it.id) && abs(it.firstSeen - s.firstSeen) < SAME_CAR_MS && now - it.lastSeen < SAME_CAR_MS
        }.ifEmpty { listOf(s) }
        if (car.any { it.alerted }) { s.alerted = true; return null }
        car.forEach { it.alerted = true }
        return Follow(car, duration, distance)
    }

    /** Roughly 1 km grid cells, for counting separate places. */
    private fun cell(lat: Double, lon: Double): Long = ((lat * 100).roundToInt().toLong() shl 32) or ((lon * 100).roundToInt().toLong() and 0xFFFFFFFFL)

    companion object {
        const val FORGET_MS = 20 * 60_000L
        const val START_WINDOW_MS = 4 * 60_000L
        const val OWN_AFTER_SESSIONS = 2
        const val SAME_CAR_MS = 3 * 60_000L
        const val MIN_PLACES = 3
        const val MIN_HITS = 4
    }
}
