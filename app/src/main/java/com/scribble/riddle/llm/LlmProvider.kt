package com.scribble.riddle.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun chat(messages: List<Pair<String, String>>): Flow<String> = flow {
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
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(req).execute()
        if (!response.isSuccessful) {
            response.close()
            error("LLM HTTP ${response.code}")
        }
        val responseBody = response.body ?: error("LLM empty body")
        val reader = responseBody.byteStream().bufferedReader()
        var line = reader.readLine()
        while (line != null) {
            if (line.startsWith("data:")) {
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isNotEmpty() && data != "{}") {
                    val content = parseDelta(data)
                    if (content.isNotEmpty()) emit(content)
                }
            }
            line = reader.readLine()
        }
        reader.close()
        response.close()
    }.flowOn(Dispatchers.IO)

    private fun parseDelta(data: String): String =
        runCatching {
            val obj = Json.parseToJsonElement(data).jsonObject
            val choices = obj["choices"]?.jsonArray ?: return ""
            val first = choices.firstOrNull()?.jsonObject ?: return ""
            val delta = first["delta"]?.jsonObject ?: return ""
            delta["content"]?.jsonPrimitive?.content ?: ""
        }.getOrDefault("")
}
