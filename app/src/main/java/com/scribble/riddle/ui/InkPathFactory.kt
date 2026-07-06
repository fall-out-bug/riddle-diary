package com.scribble.riddle.ui

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint

class InkPathFactory(
    private val paint: Paint,
    private val maxWidth: Float = 1500f,
    private val lineHeight: Float = 80f,
) {
    fun pathsFor(text: String, originX: Float, originY: Float): List<Path> {
        val result = mutableListOf<Path>()
        val rnd = java.util.Random(text.hashCode().toLong())
        var x = originX
        var y = originY

        fun widthOf(s: String): Float {
            if (s.isEmpty()) return 0f
            val w = FloatArray(s.length)
            paint.getTextWidths(s.toCharArray(), 0, s.length, w)
            return w.sum()
        }

        fun emit(ch: Char, atX: Float, atY: Float): Float {
            val s = ch.toString()
            val w = widthOf(s)
            val p = Path()
            paint.getTextPath(s, 0, 1, atX, atY, p)
            val rot = (rnd.nextFloat() - 0.5f) * 5f
            val yoff = (rnd.nextFloat() - 0.5f) * 12f
            val xscale = 0.95f + rnd.nextFloat() * 0.12f
            val cx = atX + w / 2f
            val cy = atY - paint.textSize / 2f
            val m = Matrix()
            m.postScale(xscale, 1f, cx, cy)
            m.postRotate(rot, cx, cy)
            m.postTranslate(0f, yoff)
            p.transform(m)
            result.add(p)
            return w * xscale
        }

        for (rawLine in text.split("\n")) {
            val words = rawLine.split(" ").filter { it.isNotEmpty() }
            var firstOnLine = true
            for (word in words) {
                val token = if (firstOnLine) word else " $word"
                val tw = widthOf(token)
                if (!firstOnLine && (x - originX) + tw > maxWidth) {
                    x = originX
                    y += lineHeight
                    for (ch in word) x += emit(ch, x, y)
                    firstOnLine = false
                } else {
                    for (ch in token) x += emit(ch, x, y)
                    firstOnLine = false
                }
            }
            x = originX
            y += lineHeight
        }
        return result
    }
}
