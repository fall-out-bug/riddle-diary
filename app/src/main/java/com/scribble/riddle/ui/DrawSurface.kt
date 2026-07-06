package com.scribble.riddle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput

internal data class InkStroke(val points: List<Offset>)

@Composable
fun DrawSurface(
    modifier: Modifier = Modifier,
    response: List<android.graphics.Path> = emptyList(),
    responseProgress: Float = 0f,
    responseInk: Color = Color(0xFF5A4A2F),
) {
    val strokes = remember { androidx.compose.runtime.mutableStateListOf<InkStroke>() }
    var current by remember { mutableStateOf<MutableList<Offset>?>(null) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    event.changes.forEach { change ->
                        if (change.type != PointerType.Stylus) return@forEach
                        when {
                            change.changedToDown() -> current = mutableListOf(change.position)
                            change.pressed -> current?.add(change.position)
                            change.changedToUp() -> {
                                current?.add(change.position)
                                current?.let { strokes.add(InkStroke(it.toList())) }
                                current = null
                            }
                        }
                        change.consume()
                    }
                }
            }
        }
    ) {
        val userInk = Color(0xFF1A1A2E)
        if (response.isNotEmpty()) {
            renderAnimated(response, responseProgress, responseInk)
        }
        val all = strokes + (current?.let { listOf(InkStroke(it)) } ?: emptyList())
        all.forEach { stroke ->
            if (stroke.points.size >= 2) {
                val p = Path()
                p.moveTo(stroke.points[0].x, stroke.points[0].y)
                for (i in 1 until stroke.points.size) p.lineTo(stroke.points[i].x, stroke.points[i].y)
                drawPath(
                    p,
                    color = userInk,
                    style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
