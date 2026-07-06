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
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeWidth = 3f
        color = inkColor.toArgb()
    }
    drawIntoCanvas { canvas ->
        paths.forEachIndexed { index, fullPath ->
            val local = glyphLocal(progress, index, total)
            if (local <= 0f) return@forEachIndexed
            val pm = PathMeasure(fullPath, false)
            val dst = android.graphics.Path()
            pm.getSegment(0f, pm.length * local, dst, true)
            while (pm.nextContour()) {
                val extra = android.graphics.Path()
                pm.getSegment(0f, pm.length * local, extra, true)
                dst.addPath(extra)
            }
            canvas.nativeCanvas.drawPath(dst, paint)
        }
    }
}
