package app.sirenscout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Round map buttons with drawn icons, like other map apps: a compass rose for orientation,
 * corner brackets for full screen, a magnifier for search… (drawn here: no icon library needed).
 */
@Composable
fun MapIconButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    icon: DrawScope.(Color) -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg.copy(alpha = 0.92f),
        shadowElevation = 3.dp,
        modifier = modifier.size(44.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Canvas(Modifier.size(24.dp)) { icon(fg) } }
    }
}

object MapIcons {
    private fun DrawScope.stroke(w: Float = size.minDimension * 0.09f) = Stroke(w, cap = StrokeCap.Round)

    /** Compass rose: red north needle, turned by the map's [bearingDeg]. */
    fun compass(bearingDeg: Float): DrawScope.(Color) -> Unit = { fg ->
        val c = center
        val r = size.minDimension / 2
        drawCircle(fg.copy(alpha = 0.35f), r * 0.95f, c, style = stroke(r * 0.1f))
        rotate(-bearingDeg, c) {
            val north = Path().apply { moveTo(c.x, c.y - r * 0.85f); lineTo(c.x - r * 0.3f, c.y); lineTo(c.x + r * 0.3f, c.y); close() }
            val south = Path().apply { moveTo(c.x, c.y + r * 0.85f); lineTo(c.x - r * 0.3f, c.y); lineTo(c.x + r * 0.3f, c.y); close() }
            drawPath(north, Color(0xFFE53935))
            drawPath(south, fg.copy(alpha = 0.7f))
        }
    }

    /** Heading up: a navigation arrow pointing up. */
    val heading: DrawScope.(Color) -> Unit = { fg ->
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w * 0.5f, h * 0.06f); lineTo(w * 0.88f, h * 0.92f); lineTo(w * 0.5f, h * 0.72f); lineTo(w * 0.12f, h * 0.92f); close()
        }
        drawPath(p, fg)
    }

    /** Full screen: four corner brackets pointing out (or in, to leave it). */
    fun fullScreen(exit: Boolean): DrawScope.(Color) -> Unit = { fg ->
        val s = size.minDimension
        val a = s * 0.12f; val b = s * 0.88f; val l = s * 0.3f
        val st = stroke()
        val corners = listOf(Offset(a, a) to Offset(1f, 1f), Offset(b, a) to Offset(-1f, 1f), Offset(a, b) to Offset(1f, -1f), Offset(b, b) to Offset(-1f, -1f))
        for ((p, d) in corners) {
            // Leaving full screen: the corner sits inward, arms pointing out.
            val o = if (exit) Offset(p.x + d.x * l, p.y + d.y * l) else p
            val dx = if (exit) -d.x else d.x; val dy = if (exit) -d.y else d.y
            drawLine(fg, o, Offset(o.x + dx * l, o.y), st.width, StrokeCap.Round)
            drawLine(fg, o, Offset(o.x, o.y + dy * l), st.width, StrokeCap.Round)
        }
    }

    val search: DrawScope.(Color) -> Unit = { fg ->
        val s = size.minDimension
        drawCircle(fg, s * 0.3f, Offset(s * 0.42f, s * 0.42f), style = stroke())
        drawLine(fg, Offset(s * 0.63f, s * 0.63f), Offset(s * 0.9f, s * 0.9f), s * 0.12f, StrokeCap.Round)
    }

    /** Layers: two stacked diamonds. */
    val layers: DrawScope.(Color) -> Unit = { fg ->
        val w = size.width; val h = size.height
        fun diamond(y: Float) = Path().apply { moveTo(w * 0.5f, y); lineTo(w * 0.92f, y + h * 0.22f); lineTo(w * 0.5f, y + h * 0.44f); lineTo(w * 0.08f, y + h * 0.22f); close() }
        drawPath(diamond(h * 0.38f), fg.copy(alpha = 0.55f))
        drawPath(diamond(h * 0.1f), fg)
    }

    /** Report camera: a camera body with a lens. */
    val camera: DrawScope.(Color) -> Unit = { fg ->
        val w = size.width; val h = size.height
        drawRoundRect(fg, Offset(w * 0.06f, h * 0.28f), Size(w * 0.88f, h * 0.58f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f))
        drawRect(fg, Offset(w * 0.32f, h * 0.16f), Size(w * 0.36f, h * 0.14f))
        drawCircle(Color.Black.copy(alpha = 0.55f), w * 0.17f, Offset(w * 0.5f, h * 0.57f))
        drawCircle(fg, w * 0.09f, Offset(w * 0.5f, h * 0.57f))
    }

    /** Join a search: two people. */
    val people: DrawScope.(Color) -> Unit = { fg ->
        val w = size.width; val h = size.height
        drawCircle(fg.copy(alpha = 0.6f), w * 0.13f, Offset(w * 0.68f, h * 0.3f))
        drawArc(fg.copy(alpha = 0.6f), 180f, 180f, true, Offset(w * 0.44f, h * 0.48f), Size(w * 0.48f, h * 0.5f))
        drawCircle(fg, w * 0.15f, Offset(w * 0.36f, h * 0.32f))
        drawArc(fg, 180f, 180f, true, Offset(w * 0.08f, h * 0.52f), Size(w * 0.56f, h * 0.56f))
    }

    /** Locate me: a crosshair around a dot (filled while following). */
    fun locate(following: Boolean): DrawScope.(Color) -> Unit = { fg ->
        val s = size.minDimension; val c = center
        drawCircle(fg, s * 0.3f, c, style = stroke())
        if (following) drawCircle(fg, s * 0.15f, c)
        for ((dx, dy) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
            drawLine(fg, Offset(c.x + dx * s * 0.3f, c.y + dy * s * 0.3f), Offset(c.x + dx * s * 0.48f, c.y + dy * s * 0.48f), s * 0.09f, StrokeCap.Round)
        }
    }

    /** Show all: a frame with corner marks around a few dots. */
    val showAll: DrawScope.(Color) -> Unit = { fg ->
        val s = size.minDimension
        for ((x, y) in listOf(0.35f to 0.38f, 0.62f to 0.32f, 0.5f to 0.65f)) drawCircle(fg, s * 0.08f, Offset(s * x, s * y))
        val l = s * 0.22f; val a = s * 0.08f; val b = s * 0.92f; val w = s * 0.08f
        for ((p, d) in listOf(Offset(a, a) to (1f to 1f), Offset(b, a) to (-1f to 1f), Offset(a, b) to (1f to -1f), Offset(b, b) to (-1f to -1f))) {
            drawLine(fg, p, Offset(p.x + d.first * l, p.y), w, StrokeCap.Round)
            drawLine(fg, p, Offset(p.x, p.y + d.second * l), w, StrokeCap.Round)
        }
    }
}
