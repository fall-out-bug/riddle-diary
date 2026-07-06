package com.scribble.riddle.ui

class InkPathFactory(
    private val paint: android.graphics.Paint,
    private val maxWidth: Float = 1500f,
    private val lineHeight: Float = 80f,
) {
    fun pathsFor(text: String, originX: Float, originY: Float): List<android.graphics.Path> {
        val result = mutableListOf<android.graphics.Path>()
        var x = originX
        var y = originY

        fun widthOf(s: String): Float {
            if (s.isEmpty()) return 0f
            val w = FloatArray(s.length)
            paint.getTextWidths(s.toCharArray(), 0, s.length, w)
            return w.sum()
        }

        fun drawAt(s: String, startX: Float, yy: Float): Float {
            if (s.isEmpty()) return startX
            val w = FloatArray(s.length)
            paint.getTextWidths(s.toCharArray(), 0, s.length, w)
            var cx = startX
            for (i in s.indices) {
                val p = android.graphics.Path()
                paint.getTextPath(s[i].toString(), 0, 1, cx, yy, p)
                result.add(p)
                cx += w[i]
            }
            return cx
        }

        for (rawLine in text.split("\n")) {
            val words = rawLine.split(" ").filter { it.isNotEmpty() }
            for ((idx, word) in words.withIndex()) {
                val token = if (idx == 0) word else " $word"
                val tw = widthOf(token)
                if (idx > 0 && (x - originX) + tw > maxWidth) {
                    x = originX
                    y += lineHeight
                    x = drawAt(word, x, y)
                } else {
                    val atStart = (x == originX)
                    x = drawAt(if (atStart) word else token, x, y)
                }
            }
            x = originX
            y += lineHeight
        }
        return result
    }
}
