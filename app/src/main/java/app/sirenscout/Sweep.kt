package app.sirenscout

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Chest sweep: your body blocks Bluetooth and Wi-Fi coming from behind you, so with the phone held
 * upright against your chest, a device sounds loudest when you face it. Turning slowly in a full
 * circle and noting the signal at each compass heading points to it. (After RF Sentinel's Locate
 * chest sweep.)
 */
class SweepModel(private val bins: Int = 36) {
    private val sum = DoubleArray(bins)
    private val count = IntArray(bins)

    data class Result(val bearingDeg: Float, val contrastDb: Double)

    private fun bin(headingDeg: Float) = (((headingDeg % 360f) + 360f) % 360f / (360f / bins)).toInt().coerceIn(0, bins - 1)

    fun add(headingDeg: Float, rssi: Int) {
        val b = bin(headingDeg)
        sum[b] += rssi; count[b]++
    }

    /** How much of the circle has readings, 0..1. */
    fun coverage() = count.count { it > 0 }.toFloat() / bins

    /** Average signal per direction, or null where nothing was heard yet. */
    fun levels(): List<Double?> = (0 until bins).map { if (count[it] > 0) sum[it] / count[it] else null }

    /**
     * The direction the signal is strongest from, once most of the circle is covered and the
     * difference is big enough to mean something; smoothed over neighbouring directions, since a
     * single reading jumps by several dB.
     */
    fun result(minCoverage: Float = 0.75f, minContrastDb: Double = 4.0): Result? {
        if (coverage() < minCoverage) return null
        val lv = levels()
        val smooth = (0 until bins).map { i ->
            val near = (-2..2).mapNotNull { k -> lv[(i + k + bins) % bins] }
            if (near.isEmpty()) null else near.average()
        }
        val known = smooth.withIndex().filter { it.value != null }
        val best = known.maxByOrNull { it.value!! } ?: return null
        val worst = known.minOf { it.value!! }
        val contrast = best.value!! - worst
        if (contrast < minContrastDb) return null
        return Result((best.index + 0.5f) * 360f / bins, contrast)
    }

    fun reset() { sum.fill(0.0); count.fill(0) }
}

/** The phone's heading with it held upright (portrait, screen towards you): where its back faces. */
private class Heading(context: Context, private val onHeading: (Float) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val available get() = sensor != null
    private val r = FloatArray(9)
    private val upright = FloatArray(9)
    private val o = FloatArray(3)

    fun start() { sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, upright)
        SensorManager.getOrientation(upright, o)
        onHeading(((Math.toDegrees(o[0].toDouble()).toFloat() % 360f) + 360f) % 360f)
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}

@Composable
fun ChestSweepDialog(d: Detection, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val model = remember { SweepModel() }
    var heading by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf<SweepModel.Result?>(null) }
    val sensor = remember { Heading(context) { heading = it } }
    DisposableEffect(Unit) {
        sensor.start()
        onDispose { sensor.stop() }
    }
    // New signal readings go into the direction the phone faced when they arrived.
    LaunchedEffect(d.key) {
        var lastTime = System.currentTimeMillis()
        while (true) {
            delay(100)
            val fresh = Detections.signal(d.key).filter { it.time > lastTime }
            if (fresh.isNotEmpty()) {
                lastTime = fresh.maxOf { it.time }
                if (locked == null) {
                    fresh.forEach { model.add(heading, it.rssi) }
                    model.result()?.let { if (model.coverage() >= 0.9f) locked = it }
                }
            }
            tick++
        }
    }
    val accent = d.signature?.category.color()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find its direction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!sensor.available) {
                    Text("This phone has no compass sensor, so the sweep can't work here.")
                    return@Column
                }
                tick // redraw as readings arrive
                val result = locked ?: model.result()
                when {
                    locked != null -> Text(
                        "Strongest toward ${compass(locked!!.bearingDeg)} (${locked!!.bearingDeg.roundToInt()}°) - the arrow points there as you turn. " +
                            "Walk that way, then sweep again closer in.",
                        fontWeight = FontWeight.Bold,
                    )
                    else -> Text(
                        "Hold the phone upright against your chest, screen facing you, and turn slowly in a full circle - about 20 seconds. " +
                            "Your body blocks the signal from behind, so it's loudest when you face the device.",
                    )
                }
                if (d.source == Source.WIFI) Text(
                    "Wi-Fi is only heard every few seconds: turn very slowly, or do two circles.",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB300),
                )
                SweepRing(model, heading, locked?.bearingDeg, accent, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(
                    "${(model.coverage() * 100).roundToInt()}% of the circle covered" +
                        (result?.let { " · ${it.contrastDb.roundToInt()} dB stronger one way" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { model.reset(); locked = null }) { Text("Sweep again") } },
    )
}

/**
 * The circle as you see it: north-up segments shaded by how strong the signal was that way,
 * a tick for where you face now, and once locked, an arrow turning with you toward the device.
 */
@Composable
private fun SweepRing(model: SweepModel, heading: Float, lockedDeg: Float?, accent: Color, modifier: Modifier) {
    val levels = model.levels()
    val known = levels.filterNotNull()
    val lo = known.minOrNull() ?: -100.0
    val hi = known.maxOrNull() ?: -40.0
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val outer = size.minDimension / 2 * 0.95f
        val width = outer * 0.28f
        val seg = 360f / levels.size
        // The ring turns with you, so "up" on screen is where you face.
        rotate(-heading, c) {
            levels.forEachIndexed { i, v ->
                val color = if (v == null) Color(0x33FFFFFF)
                else accent.copy(alpha = (0.2f + 0.8f * ((v - lo) / (hi - lo).coerceAtLeast(1.0))).toFloat().coerceIn(0.2f, 1f))
                drawArc(
                    color, startAngle = i * seg - 90f, sweepAngle = seg - 1f, useCenter = false,
                    topLeft = Offset(c.x - outer + width / 2, c.y - outer + width / 2),
                    size = androidx.compose.ui.geometry.Size((outer - width / 2) * 2, (outer - width / 2) * 2),
                    style = Stroke(width),
                )
            }
            // North marker.
            drawCircle(Color.White, radius = width * 0.18f, center = Offset(c.x, c.y - outer + width / 2))
        }
        // Where you face: the top of the screen.
        drawLine(Color.White, Offset(c.x, c.y - outer - 4f), Offset(c.x, c.y - outer + width + 4f), strokeWidth = 6f)
        if (lockedDeg != null) {
            rotate(lockedDeg - heading, c) {
                val len = outer - width - 12f
                val path = Path().apply {
                    moveTo(c.x, c.y - len)
                    lineTo(c.x - len * 0.25f, c.y - len * 0.45f)
                    lineTo(c.x - len * 0.09f, c.y - len * 0.45f)
                    lineTo(c.x - len * 0.09f, c.y + len * 0.5f)
                    lineTo(c.x + len * 0.09f, c.y + len * 0.5f)
                    lineTo(c.x + len * 0.09f, c.y - len * 0.45f)
                    lineTo(c.x + len * 0.25f, c.y - len * 0.45f)
                    close()
                }
                drawPath(path, accent)
            }
        }
    }
}
