package com.scribble.riddle.ui

import androidx.compose.runtime.mutableStateOf

object StrokeStore {
    val responsePaths: MutableList<android.graphics.Path> = androidx.compose.runtime.mutableStateListOf()
    val responseProgress = mutableStateOf(0f)
}
