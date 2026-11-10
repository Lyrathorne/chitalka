package ru.chitalka.app.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/** Wrap whole controls between rows; never split a button's label to make a row fit. */
class ActionFlow(context: Context) : ViewGroup(context) {
    private val positions = mutableMapOf<View, Rect>()
    private val gap = (8 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = 0; var y = 0; var rowHeight = 0
        positions.clear()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > available) { x = 0; y += rowHeight + gap; rowHeight = 0 }
            positions[child] = Rect(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(paddingTop + y + rowHeight + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        positions.forEach { (child, r) -> child.layout(r.left, r.top, r.right, r.bottom) }
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
