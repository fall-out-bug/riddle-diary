package com.scribble.riddle.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LlmProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun chat(messages: List<Pair<String, String>>): Flow<String> = flow {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", 0.7)
            put("max_tokens", 512)
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { (role, content) ->
                        add(buildJsonObject {
                            put("role", role)
                            put("content", content)
                        })
                    }
                },
            )
        }.toString()

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("User-Agent", "riddle-diary/0.2 (android)")
            .header("HTTP-Referer", "https://github.com/fall-out-bug/riddle-diary")
            .header("X-Title", "Riddle Diary")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        Log.d(TAG, "→ POST $url model=$model msgs=${messages.size}")

        val outcome: Boolean? = withTimeoutOrNull(45_000) {
            val response = client.newCall(req).execute()
            Log.d(TAG, "← HTTP ${response.code}")
            if (!response.isSuccessful) {
                Log.d(TAG, "body: " + (response.body?.string()?.take(300) ?: "null"))
                response.close()
                return@withTimeoutOrNull false
            }
            val responseBody = response.body ?: run {
                Log.d(TAG, "null body")
                return@withTimeoutOrNull false
            }
            val reader = responseBody.byteStream().bufferedReader()
            var line = reader.readLine()
            while (line != null) {
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") { Log.d(TAG, "✓ DONE"); break }
                    if (data.isNotEmpty() && data != "{}") {
                        val content = parseDelta(data)
                        if (content.isNotEmpty()) emit(content)
                    }
                }
                line = reader.readLine()
            }
            reader.close()
            response.close()
            true
        }
        Log.d(TAG, "outcome=$outcome")
        if (outcome == null) Log.d(TAG, "timeout")
    }.flowOn(Dispatchers.IO)

    private fun parseDelta(data: String): String =
        runCatching {
            val obj = Json.parseToJsonElement(data).jsonObject
            val choices = obj["choices"]?.jsonArray ?: return ""
            val first = choices.firstOrNull()?.jsonObject ?: return ""
            val delta = first["delta"]?.jsonObject ?: return ""
            delta["content"]?.jsonPrimitive?.content ?: ""
        }.getOrDefault("")

    companion object { private const val TAG = "RiddleLLM" }
}
