package com.scribble.riddle.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.scribble.riddle.ui.InkStroke
import kotlinx.coroutines.tasks.await

object InkRecognizer {

    private val model: DigitalInkRecognitionModel = run {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("ru")
            ?: error("ML Kit: Russian model identifier not available")
        DigitalInkRecognitionModel.builder(id).build()
    }

    private val recognizer = DigitalInkRecognition.getClient(
        DigitalInkRecognizerOptions.builder(model).build()
    )

    suspend fun isModelDownloaded(): Boolean =
        runCatching { RemoteModelManager.getInstance().isModelDownloaded(model).await() }.getOrDefault(false)

    suspend fun ensureModel(): Boolean {
        if (isModelDownloaded()) return true
        return runCatching {
            RemoteModelManager.getInstance()
                .download(model, DownloadConditions.Builder().build()).await()
            true
        }.getOrDefault(false)
    }

    suspend fun recognize(strokes: List<InkStroke>): String {
        if (strokes.isEmpty()) return ""
        val inkBuilder = Ink.builder()
        strokes.forEach { s ->
            val sb = Ink.Stroke.builder()
            s.points.forEach { p -> sb.addPoint(Ink.Point.create(p.x, p.y, p.tMs)) }
            inkBuilder.addStroke(sb.build())
        }
        val result = runCatching { recognizer.recognize(inkBuilder.build()).await() }.getOrNull()
            ?: return ""
        return result.candidates.firstOrNull()?.text ?: ""
    }
}
