package app.sirenscout

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Watches a list of frequencies through an rtl_tcp server (the "RTL2832U driver" Android app
 * with a USB RTL-SDR dongle, or rtl_tcp running on another machine) and reports when a channel
 * rises above its learned noise floor - i.e. someone is transmitting nearby.
 *
 * It only measures carrier power; it does not demodulate or decode anything.
 */
class RtlTcpMonitor(
    private val host: String,
    private val port: Int,
    private val freqsHz: List<Long>,
    private val gainTenthsDb: Int,
    private val thresholdDb: Float,
    private val onStatus: (String) -> Unit,
    private val onCarrier: (freqHz: Long, levelDb: Double, aboveFloorDb: Double) -> Unit,
    /** Radio bands to sweep for nearby transmitters, between rounds of [freqsHz]. */
    private val bands: List<RadioBand> = emptyList(),
    private val bandThresholdDb: Float = 15f,
    private val onBandHit: (RadioBand, ChannelFloors.Hit) -> Unit = { _, _ -> },
) {
    private val analyzer = BandAnalyzer(SAMPLE_RATE)
    /** Kept across reconnects, so a dropped connection doesn't restart learning. */
    private val bandFloors = bands.associate { it.id to ChannelFloors(bandThresholdDb) }

    suspend fun run() = withContext(Dispatchers.IO) {
        if (freqsHz.isEmpty() && bands.isEmpty()) {
            onStatus("no frequencies or bands configured")
            return@withContext
        }
        val floors = DoubleArray(freqsHz.size) { Double.NaN }
        var sweeps = 0
        while (isActive) {
            try {
                Socket().use { socket ->
                    onStatus("connecting to $host:$port…")
                    socket.connect(InetSocketAddress(host, port), 3000)
                    socket.soTimeout = 5000
                    sweeps = session(socket, floors, sweeps)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                onStatus("${e.message ?: "connection failed"} - retrying")
                delay(3000)
            }
        }
    }

    private suspend fun session(socket: Socket, floors: DoubleArray, startSweeps: Int): Int {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        val header = ByteArray(12)
        input.readFully(header)
        if (String(header, 0, 4, Charsets.US_ASCII) != "RTL0") throw IOException("not an rtl_tcp server")

        fun command(cmd: Int, value: Int) {
            out.writeByte(cmd)
            out.writeInt(value)
            out.flush()
        }
        command(CMD_SAMPLE_RATE, SAMPLE_RATE)
        command(CMD_GAIN_MODE, 1) // manual gain, so levels are comparable over time
        command(CMD_GAIN, gainTenthsDb)
        command(CMD_AGC, 0)

        val settle = ByteArray(SETTLE_BYTES)
        val measure = ByteArray(MEASURE_BYTES)
        var sweeps = startSweeps
        while (currentCoroutineContext().isActive) {
            var busiest = ""
            var busiestAbove = Double.NEGATIVE_INFINITY
            for (i in freqsHz.indices) {
                // Tune below the target so the dongle's DC spike stays out of the channel.
                command(CMD_FREQ, (freqsHz[i] - IF_OFFSET_HZ).toInt())
                input.readFully(settle) // samples still in flight from the previous frequency
                input.readFully(measure)
                val level = channelPowerDb(measure)

                val floor = floors[i]
                floors[i] = when {
                    floor.isNaN() -> level
                    level < floor -> floor + (level - floor) * 0.5 // drop quickly to the quietest level
                    else -> floor + (level - floor) * 0.02 // creep up slowly, so always-on carriers fade out
                }
                val above = level - floor
                if (sweeps >= WARMUP_SWEEPS && above >= thresholdDb) onCarrier(freqsHz[i], level, above)
                if (above > busiestAbove) {
                    busiestAbove = above
                    busiest = "%.4f".format(Locale.US, freqsHz[i] / 1e6)
                }
            }
            // Band watch: step across each band, looking for a channel that suddenly lights up.
            for (band in bands) {
                val floors = bandFloors.getValue(band.id)
                for (center in analyzer.steps(band)) {
                    command(CMD_FREQ, center.toInt())
                    input.readFully(settle)
                    input.readFully(measure)
                    floors.update(analyzer.channelPowers(measure, center, band))?.let { onBandHit(band, it) }
                }
            }
            sweeps++
            val what = listOfNotNull(
                freqsHz.size.takeIf { it > 0 }?.let { "$it freqs" },
                bands.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label },
            ).joinToString(" + ")
            val learning = sweeps < WARMUP_SWEEPS || bandFloors.values.any { it.learning }
            onStatus(
                when {
                    learning -> "learning noise floor ($what)…"
                    freqsHz.isEmpty() -> "watching $what"
                    else -> "watching $what · busiest freq $busiest MHz %+.1f dB".format(Locale.US, busiestAbove)
                }
            )
        }
        return sweeps
    }

    /**
     * Mixes the target (IF_OFFSET_HZ above the tuned frequency) down to DC, low-passes it with a
     * boxcar average (~16 kHz wide, about one narrowband FM channel) and returns its power in dBFS.
     */
    private fun channelPowerDb(buf: ByteArray): Double {
        var acc = 0.0
        var blocks = 0
        var n = 0
        var i = 0
        while (i + 2 * DECIMATION <= buf.size) {
            var re = 0.0
            var im = 0.0
            repeat(DECIMATION) {
                val x = (buf[i].toInt() and 0xFF) - 127.5
                val y = (buf[i + 1].toInt() and 0xFF) - 127.5
                val c = LO_COS[n and LO_MASK]
                val s = LO_SIN[n and LO_MASK]
                re += x * c + y * s
                im += y * c - x * s
                i += 2
                n++
            }
            re /= DECIMATION
            im /= DECIMATION
            acc += re * re + im * im
            blocks++
        }
        return 10 * log10(acc / blocks / (127.5 * 127.5) + 1e-12)
    }

    companion object {
        private const val CMD_FREQ = 0x01
        private const val CMD_SAMPLE_RATE = 0x02
        private const val CMD_GAIN_MODE = 0x03
        private const val CMD_GAIN = 0x04
        private const val CMD_AGC = 0x08

        const val SAMPLE_RATE = 1_024_000
        private const val IF_OFFSET_HZ = 250_000L
        private const val DECIMATION = 64
        private const val SETTLE_BYTES = 196_608 // ~96 ms of IQ data
        private const val MEASURE_BYTES = 131_072 // ~64 ms of IQ data
        private const val WARMUP_SWEEPS = 5

        // IF_OFFSET_HZ / SAMPLE_RATE = 125/512, so the local oscillator repeats every 512 samples.
        private const val LO_LEN = 512
        private const val LO_MASK = LO_LEN - 1
        private val LO_COS = DoubleArray(LO_LEN) { cos(2 * PI * IF_OFFSET_HZ * it / SAMPLE_RATE) }
        private val LO_SIN = DoubleArray(LO_LEN) { sin(2 * PI * IF_OFFSET_HZ * it / SAMPLE_RATE) }
    }
}
