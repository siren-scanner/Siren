package app.sirenscout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlinx.coroutines.flow.MutableStateFlow

/** The "you are here" marker on the map, in a colour the user picks so it stands out. */
object LocationMarker {
    /** Name to ARGB. No yellows or beiges - those are what the map itself is made of. */
    val COLORS = listOf(
        "Blue" to 0xFF1E88E5.toInt(),
        "Red" to 0xFFE53935.toInt(),
        "Green" to 0xFF2E7D32.toInt(),
        "Purple" to 0xFF8E24AA.toInt(),
        "Pink" to 0xFFD81B60.toInt(),
        "Orange" to 0xFFEF6C00.toInt(),
        "Cyan" to 0xFF00ACC1.toInt(),
        "Black" to 0xFF212121.toInt(),
    )

    /** Current colour; the map redraws its marker when this changes. */
    val color = MutableStateFlow(COLORS.first().second)

    fun init(context: Context) {
        color.value = Prefs(context).locationColor
    }

    fun set(context: Context, argb: Int) {
        Prefs(context).locationColor = argb
        color.value = argb
    }

    /** A dot with a white ring and soft shadow, for when you're standing still. */
    fun dot(context: Context, argb: Int): Bitmap {
        val d = context.resources.displayMetrics.density
        val size = (36 * d).toInt()
        val c = size / 2f
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE
            paint.setShadowLayer(3 * d, 0f, 1 * d, 0x66000000)
            canvas.drawCircle(c, c, 11 * d, paint)
            paint.clearShadowLayer()
            paint.color = argb
            canvas.drawCircle(c, c, 8 * d, paint)
        }
    }

    /** A navigation arrow pointing up; the map rotates it to your direction of travel. */
    fun arrow(context: Context, argb: Int): Bitmap {
        val d = context.resources.displayMetrics.density
        val size = (44 * d).toInt()
        val c = size / 2f
        val path = Path().apply {
            moveTo(c, c - 15 * d)
            lineTo(c + 11 * d, c + 13 * d)
            lineTo(c, c + 7 * d)
            lineTo(c - 11 * d, c + 13 * d)
            close()
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.FILL
            paint.color = argb
            paint.setShadowLayer(3 * d, 0f, 1 * d, 0x66000000)
            canvas.drawPath(path, paint)
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * d
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.WHITE
            canvas.drawPath(path, paint)
        }
    }
}
