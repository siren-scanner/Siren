package app.sirenscout

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * A range of frequencies that radios (not towers) transmit on. Watching the radios' side means a
 * strong signal comes from a transmitter near you, not a tower miles away.
 */
data class RadioBand(
    val id: String,
    val label: String,
    val loHz: Long,
    val hiHz: Long,
    val signature: Signature,
)

object Bands {
    private fun signature(id: String, label: String, confidence: Confidence, note: String) = Signature(
        id = id,
        label = label,
        category = Category.RADIO,
        confidence = confidence,
        sources = setOf(Source.RF),
        note = note,
    )

    /** 700 MHz narrowband: radio transmit side (towers are 30 MHz lower). Public safety only. */
    val PS_700 = RadioBand(
        "band-700", "700 MHz public safety", 799_000_000, 805_000_000,
        signature(
            "rf-band-700", "Public-safety radio transmitting nearby", Confidence.MEDIUM,
            "799-805 MHz is reserved for public safety nationwide (police, fire, EMS, statewide P25 systems).",
        ),
    )

    /** 800 MHz NPSPAC interoperability channels, radio transmit side. */
    val NPSPAC = RadioBand(
        "band-npspac", "800 MHz public safety (NPSPAC)", 821_000_000, 824_000_000,
        signature(
            "rf-band-npspac", "Public-safety radio transmitting nearby", Confidence.MEDIUM,
            "821-824 MHz is set aside for public-safety interoperability channels.",
        ),
    )

    /** The rest of the 800 MHz radio transmit side, shared with utilities and businesses. */
    val SHARED_800 = RadioBand(
        "band-800", "800 MHz shared", 806_000_000, 821_000_000,
        signature(
            "rf-band-800", "Radio transmitting nearby (800 MHz)", Confidence.LOW,
            "806-821 MHz is used by many county and city systems, but also by utilities and businesses.",
        ),
    )

    val ALL = listOf(PS_700, NPSPAC, SHARED_800)

    fun signatureById(id: String) = ALL.firstOrNull { it.signature.id == id }?.signature
}

/**
 * Turns raw RTL-SDR IQ samples into the power in each radio channel of a band, using an FFT.
 * Pure Kotlin with no Android dependencies, so it can be tested with synthetic signals.
 */
class BandAnalyzer(private val sampleRate: Int, private val fftSize: Int = 1024) {
    init {
        require(fftSize and (fftSize - 1) == 0) { "FFT size must be a power of two" }
    }

    /** Only the middle of the tuned span is used; the dongle's filters roll off at the edges. */
    val usableSpanHz = (sampleRate * 0.78).toLong()

    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    private val twCos = DoubleArray(fftSize / 2) { cos(-2 * PI * it / fftSize) }
    private val twSin = DoubleArray(fftSize / 2) { sin(-2 * PI * it / fftSize) }
    private val bitReversed = IntArray(fftSize) { i ->
        var r = 0
        var x = i
        var bits = Integer.numberOfTrailingZeros(fftSize)
        while (bits-- > 0) {
            r = (r shl 1) or (x and 1)
            x = x shr 1
        }
        r
    }

    /** Tuning centers that cover [band] with the usable middle of each step. */
    fun steps(band: RadioBand): List<Long> {
        val steps = mutableListOf<Long>()
        var lo = band.loHz
        while (lo < band.hiHz) {
            steps += lo + usableSpanHz / 2
            lo += usableSpanHz
        }
        return steps
    }

    /**
     * Power (dBFS) per [channelHz]-wide channel inside [band] and within the usable span of a
     * capture tuned to [centerHz]. Keys are channel center frequencies.
     */
    fun channelPowers(iq: ByteArray, centerHz: Long, band: RadioBand, channelHz: Long = CHANNEL_HZ): Map<Long, Double> {
        val bins = averagedSpectrum(iq)
        val binHz = sampleRate.toDouble() / fftSize
        val sums = HashMap<Long, Double>()
        for (k in 0 until fftSize) {
            val offset = (if (k < fftSize / 2) k else k - fftSize) * binHz
            // Skip the DC spike every RTL-SDR has at its tuned center, and the rolled-off edges.
            if (kotlin.math.abs(offset) < DC_GUARD_HZ || kotlin.math.abs(offset) > usableSpanHz / 2.0) continue
            val freq = centerHz + offset
            if (freq < band.loHz || freq >= band.hiHz) continue
            val channel = band.loHz + ((freq - band.loHz) / channelHz).toLong() * channelHz + channelHz / 2
            sums[channel] = (sums[channel] ?: 0.0) + bins[k]
        }
        return sums.mapValues { 10 * log10(it.value + 1e-12) }
    }

    /** Averages windowed FFT power over every full block in [iq] (8-bit unsigned I/Q pairs). */
    fun averagedSpectrum(iq: ByteArray): DoubleArray {
        val acc = DoubleArray(fftSize)
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        var blocks = 0
        var i = 0
        while (i + 2 * fftSize <= iq.size) {
            for (n in 0 until fftSize) {
                re[n] = ((iq[i].toInt() and 0xFF) - 127.5) / 127.5 * window[n]
                im[n] = ((iq[i + 1].toInt() and 0xFF) - 127.5) / 127.5 * window[n]
                i += 2
            }
            fft(re, im)
            for (n in 0 until fftSize) acc[n] += re[n] * re[n] + im[n] * im[n]
            blocks++
        }
        if (blocks > 0) for (n in 0 until fftSize) acc[n] /= blocks.toDouble() * fftSize * fftSize
        return acc
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until fftSize) {
            val j = bitReversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= fftSize) {
            val half = size / 2
            val step = fftSize / size
            var start = 0
            while (start < fftSize) {
                for (k in 0 until half) {
                    val c = twCos[k * step]
                    val s = twSin[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }

    companion object {
        /** Narrowband public-safety channels are 12.5 kHz wide. */
        const val CHANNEL_HZ = 12_500L
        const val DC_GUARD_HZ = 8_000.0
    }
}

/**
 * Learns each channel's normal level and reports the strongest channel well above it. A floor
 * that drops quickly and rises slowly ignores transmitters that are always on.
 */
class ChannelFloors(private val thresholdDb: Float, private val warmupSweeps: Int = 3) {
    private val floors = HashMap<Long, Double>()
    private val seen = HashMap<Long, Int>()

    data class Hit(val channelHz: Long, val levelDb: Double, val aboveDb: Double)

    /** Updates floors from one step's [powers] and returns the strongest qualifying channel, if any. */
    fun update(powers: Map<Long, Double>): Hit? {
        var best: Hit? = null
        for ((channel, level) in powers) {
            val floor = floors[channel]
            val count = (seen[channel] ?: 0) + 1
            seen[channel] = count
            floors[channel] = when {
                floor == null -> level
                level < floor -> floor + (level - floor) * 0.5
                else -> floor + (level - floor) * 0.02
            }
            if (floor == null || count <= warmupSweeps) continue
            val above = level - floor
            if (above >= thresholdDb && (best == null || above > best.aboveDb)) best = Hit(channel, level, above)
        }
        return best
    }

    val learning get() = seen.values.maxOrNull()?.let { it <= warmupSweeps } ?: true
}
