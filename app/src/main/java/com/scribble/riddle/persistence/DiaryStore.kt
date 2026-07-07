package com.scribble.riddle.persistence

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredPoint(val x: Float, val y: Float, val t: Long, val p: Float)

@Serializable
data class StoredStroke(val pts: List<StoredPoint>)

@Serializable
data class StoredTurn(
    val userStrokes: List<StoredStroke>,
    val userText: String,
    val assistantText: String,
    val assistantOriginY: Float,
)

@Serializable
data class StoredDiary(val turns: List<StoredTurn> = emptyList())

object DiaryStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(ctx: Context): File = File(ctx.filesDir, "diary.json")

    fun load(ctx: Context): StoredDiary = try {
        val f = file(ctx)
        if (!f.exists()) StoredDiary() else json.decodeFromString(StoredDiary.serializer(), f.readText())
    } catch (e: Exception) {
        StoredDiary()
    }

    fun save(ctx: Context, diary: StoredDiary) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, "diary.json.tmp")
            tmp.writeText(json.encodeToString(StoredDiary.serializer(), diary))
            // atomic on the same filesystem — never a half-written file even if killed mid-save
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
        }
    }
}
