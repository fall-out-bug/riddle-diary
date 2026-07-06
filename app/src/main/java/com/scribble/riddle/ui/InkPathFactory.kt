package com.scribble.riddle.ui

class InkPathFactory(private val paint: android.graphics.Paint) {
    fun pathsFor(text: String, originX: Float, originY: Float): List<android.graphics.Path> {
        val result = mutableListOf<android.graphics.Path>()
        val widths = FloatArray(1)
        var x = originX
        text.forEach { ch ->
            val p = android.graphics.Path()
            paint.getTextPath(ch.toString(), 0, 1, x, originY, p)
            result.add(p)
            paint.getTextWidths(charArrayOf(ch), 0, 1, widths)
            x += widths[0]
        }
        return result
    }
}
