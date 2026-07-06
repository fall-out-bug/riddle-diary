package com.scribble.riddle.ui

import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

internal fun glyphLocal(progress: Float, index: Int, total: Int): Float {
    val perGlyph = 1f / total
    return ((progress - index * perGlyph) / perGlyph).coerceIn(0f, 1f)
}

fun DrawScope.renderAnimated(
    paths: List<android.graphics.Path>,
    progress: Float,
    inkColor: Color,
) {
    if (paths.isEmpty()) return
    val total = paths.size
    val argb = inkColor.toArgb()
    val fillPaint = android.graphics.Paint().apply {
        isAntiAlias = true; color = argb; style = android.graphics.Paint.Style.FILL
    }
    val blotPaint = android.graphics.Paint().apply {
        isAntiAlias = true; color = argb; style = android.graphics.Paint.Style.FILL
    }
    val bounds = RectF()
    val posOut = FloatArray(2)
    val tanOut = FloatArray(2)

    drawIntoCanvas { canvas ->
        paths.forEachIndexed { index, path ->
            val local = glyphLocal(progress, index, total)
            if (local <= 0f) return@forEachIndexed
            path.computeBounds(bounds, true)
            val w = bounds.width().coerceAtLeast(1f)
            val revealRight = bounds.left + w * local

            val nc = canvas.nativeCanvas
            nc.save()
            nc.clipRect(bounds.left - 2f, bounds.top - 2f, revealRight + 2f, bounds.bottom + 2f)
            nc.drawPath(path, fillPaint)
            nc.restore()

            if (local < 1f) {
                val pm = PathMeasure(path, false)
                if (pm.getPosTan((revealRight - bounds.left).coerceIn(0f, pm.length), posOut, tanOut)) {
                    val tipR = 5.0f + if (local > 0.8f) 1.5f else 0f
                    nc.drawCircle(posOut[0], posOut[1], tipR, blotPaint)
                }
            }
            if (local > 0f) {
                val pm2 = PathMeasure(path, false)
                if (pm2.getPosTan(0f, posOut, tanOut)) {
                    nc.drawCircle(posOut[0], posOut[1], 3.2f, blotPaint)
                }
            }
        }
    }
}
