package com.scribble.riddle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.scribble.riddle.ui.DrawSurface
import com.scribble.riddle.ui.InkPathFactory
import com.scribble.riddle.ui.StrokeStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    TracerScreen()
                }
            }
        }
    }
}

private const val PER_GLYPH_MS = 350L
private const val ANIMATION_TICK_MS = 120L

@Composable
private fun TracerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by StrokeStore.responseProgress
    var animJob by remember { mutableStateOf<Job?>(null) }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Text(
            text = "Riddle Diary — tracer",
            color = Color.Black,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().background(Color(0xFFDDDDDD)).padding(12.dp),
        )
        Box(Modifier.weight(1f)) {
            DrawSurface(
                modifier = Modifier.fillMaxSize(),
                response = StrokeStore.responsePaths,
                responseProgress = progress,
                responseInk = Color(0xFF5A4A2F),
            )
        }
        Button(
            onClick = {
                val typeface = ResourcesCompat.getFont(context, R.font.caveat)!!
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 64f
                    this.typeface = typeface
                }
                val factory = InkPathFactory(paint)
                StrokeStore.responsePaths.clear()
                StrokeStore.responsePaths.addAll(
                    factory.pathsFor("Я дневник. Пиши мне.", originX = 200f, originY = 900f)
                )
                val n = StrokeStore.responsePaths.size.coerceAtLeast(1)
                val totalMs = n * PER_GLYPH_MS
                animJob?.cancel()
                StrokeStore.responseProgress.value = 0f
                animJob = scope.launch {
                    val start = android.os.SystemClock.elapsedRealtime()
                    while (true) {
                        val t = (android.os.SystemClock.elapsedRealtime() - start).toFloat() / totalMs
                        StrokeStore.responseProgress.value = t.coerceIn(0f, 1f)
                        if (t >= 1f) break
                        delay(ANIMATION_TICK_MS)
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Black,
                contentColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(8.dp),
        ) { Text("Отправить", fontSize = 20.sp) }
    }
}
