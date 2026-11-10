package androidx.text.vertical

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance

/** Per-run native caption outline, retaining the platform's glyph shaping. */
public class OutlineSpan(public val color: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(paint: TextPaint) = Unit
}

internal inline fun Canvas.withTextOutline(
    text: CharSequence, start: Int, end: Int, paint: Paint, draw: () -> Unit,
) {
    val outline = (text as? Spanned)?.getSpans(start, end, OutlineSpan::class.java)?.lastOrNull()
    if (outline != null && paint.style == Paint.Style.FILL) {
        val color = paint.color
        val width = paint.strokeWidth
        try {
            paint.style = Paint.Style.STROKE
            paint.color = outline.color
            paint.strokeWidth = paint.textSize / 16f
            draw()
        } finally {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.strokeWidth = width
        }
    }
    draw()
}

internal fun Canvas.drawTextWithOutline(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
    withTextOutline(text, start, end, paint) { drawText(text, start, end, x, y, paint) }
}
