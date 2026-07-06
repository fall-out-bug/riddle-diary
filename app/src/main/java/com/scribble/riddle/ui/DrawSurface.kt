package com.scribble.riddle.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class StrokePoint(val x: Float, val y: Float, val tMs: Long)
data class InkStroke(val points: List<StrokePoint>)

class ResponseGroup(val paths: List<android.graphics.Path>, progress: Float) {
    var progress by mutableStateOf(progress)
}
