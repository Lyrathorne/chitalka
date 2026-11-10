package ru.chitalka.app.ui

import android.graphics.*
import android.graphics.drawable.Drawable

class ReadingBubble(private val fill: Int, private val line: Int, private val density: Float) : Drawable() {
    var pointsDown = true
        set(value) { field = value; invalidateSelf() }
    var pointX = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val tip = 7 * density
        val rect = RectF(bounds.left + density, bounds.top + tip, bounds.right - density, bounds.bottom - tip)
        val shape = Path().apply { addRoundRect(rect, 10 * density, 10 * density, Path.Direction.CW) }
        val x = pointX.coerceIn(rect.left + 20 * density, rect.right - 20 * density)
        val triangle = Path().apply {
            val edge = if (pointsDown) rect.bottom else rect.top
            moveTo(x - tip, edge); lineTo(x, if (pointsDown) bounds.bottom.toFloat() else bounds.top.toFloat()); lineTo(x + tip, edge); close()
        }
        shape.op(triangle, Path.Op.UNION)
        paint.color = fill; paint.style = Paint.Style.FILL; canvas.drawPath(shape, paint)
        paint.color = line; paint.style = Paint.Style.STROKE; paint.strokeWidth = density; canvas.drawPath(shape, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
