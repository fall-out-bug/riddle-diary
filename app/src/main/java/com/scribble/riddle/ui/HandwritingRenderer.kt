package com.scribble.riddle.ui

import android.graphics.PathMeasure
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
    val wetPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = argb
        alpha = 55
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeWidth = 9f
    }
    val crispPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = argb
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeWidth = 3f
    }
    val blotPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = argb
        alpha = 210
        style = android.graphics.Paint.Style.FILL
    }
    val posOut = FloatArray(2)
    val tanOut = FloatArray(2)

    drawIntoCanvas { canvas ->
        paths.forEachIndexed { index, fullPath ->
            val local = glyphLocal(progress, index, total)
            if (local <= 0f) return@forEachIndexed
            val pm = PathMeasure(fullPath, false)
            val fullLen = pm.length
            val segLen = fullLen * local
            val dst = android.graphics.Path()
            pm.getSegment(0f, segLen, dst, true)
            while (pm.nextContour()) {
                val extra = android.graphics.Path()
                pm.getSegment(0f, pm.length * local, extra, true)
                dst.addPath(extra)
            }
            // wet underlay (ink soaking into paper)
            canvas.nativeCanvas.drawPath(dst, wetPaint)
            // crisp ink line
            canvas.nativeCanvas.drawPath(dst, crispPaint)
            // leading-tip blot (wet nib head moving forward)
            if (local < 1f && pm.getPosTan(segLen.coerceAtMost(fullLen), posOut, tanOut)) {
                val r = 3.5f + (if (local > 0.85f) 1.5f else 0f)
                canvas.nativeCanvas.drawCircle(posOut[0], posOut[1], r, blotPaint)
            }
            // pen-down puddle at glyph start (ink pooling)
            if (local > 0f) {
                val pm2 = PathMeasure(fullPath, false)
                if (pm2.getPosTan(0f, posOut, tanOut)) {
                    canvas.nativeCanvas.drawCircle(posOut[0], posOut[1], 2.8f, blotPaint)
                }
            }
        }
    }
}
