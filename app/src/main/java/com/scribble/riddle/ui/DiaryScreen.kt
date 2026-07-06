package com.scribble.riddle.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scribble.riddle.llm.Config
import com.scribble.riddle.llm.LlmProvider
import com.scribble.riddle.llm.Secrets
import com.scribble.riddle.ocr.InkRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAUSE_MS = 1400L
private const val RESPONSE_TICK_MS = 90L
private const val RESPONSE_PER_GLYPH_MS = 90L
private const val RESPONSE_ORIGIN_X = 160f
private const val RESPONSE_GAP = 220f
private const val BOTTOM_PAD = 1600f

private fun androidx.compose.ui.graphics.drawscope.DrawScope.renderUserStroke(pts: List<StrokePoint>, color: Color, width: Float) {
    if (pts.size < 2) return
    val p = Path()
    p.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 2) {
        p.lineTo(pts[1].x, pts[1].y)
    } else {
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2f
            val my = (pts[i].y + pts[i + 1].y) / 2f
            p.quadraticTo(pts[i].x, pts[i].y, mx, my)
        }
        p.lineTo(pts[pts.size - 1].x, pts[pts.size - 1].y)
    }
    drawPath(p, color = color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
fun DiaryScreen(
    responsePaint: android.graphics.Paint,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val pending = remember { mutableStateListOf<InkStroke>() }
    val allUser = remember { mutableStateListOf<InkStroke>() }
    val responses = remember { mutableStateListOf<ResponseGroup>() }
    val history = remember { mutableListOf<Pair<String, String>>() }

    var lastActivityAt by remember { mutableStateOf(0L) }
    var processing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var contentHeightPx by remember { mutableStateOf(3000f) }
    val scroll = rememberScrollState()
    var curStroke by remember { mutableStateOf<MutableList<StrokePoint>?>(null) }
    var frame by remember { mutableStateOf(0) }

    fun recomputeHeight() {
        var maxY = 0f
        allUser.forEach { s -> s.points.forEach { if (it.y > maxY) maxY = it.y } }
        if (maxY == 0f) maxY = 1000f
        contentHeightPx = maxY + BOTTOM_PAD
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            if (processing || pending.isEmpty() || lastActivityAt == 0L ||
                SystemClock.elapsedRealtime() - lastActivityAt <= PAUSE_MS
            ) continue
            processing = true
            val turnStrokes = pending.toList()
            pending.clear()
            if (!InkRecognizer.isModelDownloaded()) {
                status = "Скачиваю модель…"
                InkRecognizer.ensureModel()
            }
            status = "Распознаю…"
            val text = runCatching { InkRecognizer.recognize(turnStrokes) }.getOrDefault("").trim()
            if (text.isBlank()) {
                status = ""
                processing = false
                continue
            }
            status = "Думаю…"
            val maxY = (allUser.flatMap { it.points }.maxOfOrNull { it.y } ?: 0f)
            val originY = maxY + RESPONSE_GAP
            history.add("user" to text)
            val msgs = listOf("system" to Config.SYSTEM_PROMPT) + history.toList()
            val sb = StringBuilder()
            val provider = LlmProvider(Config.BASE_URL, Secrets.OPENROUTER_API_KEY, Config.MODEL)
            val ok = runCatching {
                provider.chat(msgs).collect { chunk ->
                    sb.append(chunk)
                    status = "Пишет…"
                }
                true
            }.getOrDefault(false)
            if (!ok || sb.isBlank()) {
                status = "Ошибка LLM"
                if (history.isNotEmpty()) history.removeAt(history.lastIndex)
                processing = false
                continue
            }
            val full = sb.toString().trim()
            history.add("assistant" to full)
            val paths = InkPathFactory(responsePaint).pathsFor(full, RESPONSE_ORIGIN_X, originY)
            val rg = ResponseGroup(paths, 0f)
            responses.add(rg)
            recomputeHeight()
            scope.launch {
                val n = paths.size.coerceAtLeast(1)
                val total = n * RESPONSE_PER_GLYPH_MS
                val start = SystemClock.elapsedRealtime()
                while (true) {
                    val t = (SystemClock.elapsedRealtime() - start).toFloat() / total
                    rg.progress = t.coerceIn(0f, 1f)
                    if (t >= 1f) break
                    delay(RESPONSE_TICK_MS)
                }
                rg.progress = 1f
                status = ""
            }
            processing = false
        }
    }

    Box(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { contentHeightPx.toDp() })
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                event.changes.forEach { change ->
                                    if (change.type != PointerType.Stylus) return@forEach
                                    val now = SystemClock.elapsedRealtime()
                                    val pos = change.position
                                    when {
                                        change.changedToDown() -> {
                                            curStroke = mutableListOf(StrokePoint(pos.x, pos.y, now, change.pressure))
                                            lastActivityAt = now
                                        }
                                        change.pressed -> {
                                            curStroke?.add(StrokePoint(pos.x, pos.y, now, change.pressure))
                                            lastActivityAt = now
                                        }
                                        change.changedToUp() -> {
                                            curStroke?.add(StrokePoint(pos.x, pos.y, now, change.pressure))
                                            val pts = curStroke
                                            if (pts != null && pts.size >= 2) {
                                                val stroke = InkStroke(pts.toList())
                                                allUser.add(stroke)
                                                if (!processing) pending.add(stroke)
                                                recomputeHeight()
                                            }
                                            curStroke = null
                                            lastActivityAt = now
                                        }
                                    }
                                    frame++
                                change.consume()
                                }
                            }
                        }
                    },
            ) {
                responses.forEach { rg ->
                    renderAnimated(rg.paths, rg.progress, Color(0xFF5A4A2F))
                }
                val userInk = Color(0xFF1A1A2E)
                val f = frame
                allUser.forEach { renderUserStroke(it.points, userInk, 4.5f) }
                curStroke?.let { renderUserStroke(it, userInk, 4.5f) }
            }
        }

        if (status.isNotEmpty()) {
            Text(
                text = status,
                fontSize = 16.sp,
                color = Color(0xFF333333),
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            )
        }
    }
}
