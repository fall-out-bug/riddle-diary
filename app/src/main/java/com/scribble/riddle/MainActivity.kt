package com.scribble.riddle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.scribble.riddle.ocr.InkRecognizer
import com.scribble.riddle.ui.DiaryScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    val context = LocalContext.current
                    val paint = remember {
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            textSize = 56f
                            typeface = ResourcesCompat.getFont(context, R.font.caveat)
                        }
                    }
                    LaunchedEffect(Unit) { InkRecognizer.ensureModel() }
                    DiaryScreen(responsePaint = paint)
                }
            }
        }
    }
}
