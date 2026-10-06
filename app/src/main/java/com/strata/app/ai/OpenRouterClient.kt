package com.strata.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ToolCall(val id: String, val name: String, val arguments: String)

data class AssistantReply(
    val content: String?,
    val toolCalls: List<ToolCall>,
    /** The tool_calls array exactly as returned, to replay in history. */
    val rawToolCalls: JsonArray?,
)

data class ModelInfo(val id: String, val name: String, val promptPricePerMillion: Double?, val contextLength: Int?)

data class KeyInfo(val label: String?, val usage: Double?, val limit: Double?)

class OpenRouterException(message: String) : Exception(message)

/** Minimal OpenAI-compatible client for OpenRouter's chat completions with tool calling. */
class OpenRouterClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(
        apiKey: String,
        model: String,
        messages: JsonArray,
        tools: JsonArray,
        privateProvidersOnly: Boolean,
    ): AssistantReply = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("tools", tools)
            put("temperature", 0.2)
            if (privateProvidersOnly) put("provider", buildJsonObject { put("data_collection", "deny") })
        }
        val root = post("chat/completions", apiKey, body)
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw OpenRouterException("The model returned no message.")
        val rawCalls = (message["tool_calls"] as? JsonArray)?.takeIf { it.isNotEmpty() }
        val calls = rawCalls.orEmpty().map { element ->
            val call = element.jsonObject
            val function = call["function"]!!.jsonObject
            ToolCall(
                id = call["id"]!!.jsonPrimitive.content,
                name = function["name"]!!.jsonPrimitive.content,
                // Most providers send a JSON string; a few send the object itself.
                arguments = when (val args = function["arguments"]) {
                    is JsonPrimitive -> args.contentOrNull ?: "{}"
                    null -> "{}"
                    else -> args.toString()
                },
            )
        }
        AssistantReply(
            content = (message["content"] as? JsonPrimitive)?.contentOrNull,
            toolCalls = calls,
            rawToolCalls = rawCalls,
        )
    }

    suspend fun models(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE/models?supported_parameters=tools").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw OpenRouterException("Could not load models (${response.code}).")
            val data = json.parseToJsonElement(response.body.string()).jsonObject["data"]!!.jsonArray
            data.map { it.jsonObject }
                .filterNot { it["id"]!!.jsonPrimitive.content.endsWith(":batch") }
                .map { m ->
                    ModelInfo(
                        id = m["id"]!!.jsonPrimitive.content,
                        name = m["name"]?.jsonPrimitive?.contentOrNull ?: m["id"]!!.jsonPrimitive.content,
                        promptPricePerMillion = m["pricing"]?.jsonObject?.get("prompt")?.jsonPrimitive?.contentOrNull
                            ?.toDoubleOrNull()?.times(1_000_000),
                        contextLength = m["context_length"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                    )
                }
        }
    }

    suspend fun checkKey(apiKey: String): KeyInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE/key").header("Authorization", "Bearer $apiKey").build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw OpenRouterException(errorMessage(text) ?: "Key rejected (${response.code}).")
            val data = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            KeyInfo(
                label = data?.get("label")?.jsonPrimitive?.contentOrNull,
                usage = data?.get("usage")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                limit = data?.get("limit")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            )
        }
    }

    private fun post(path: String, apiKey: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url("$BASE/$path")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Strata")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw OpenRouterException(errorMessage(text) ?: "OpenRouter returned ${response.code}.")
            }
            val root = json.parseToJsonElement(text).jsonObject
            root["error"]?.let { throw OpenRouterException(errorMessage(text) ?: it.toString()) }
            return root
        }
    }

    private fun errorMessage(text: String): String? = runCatching {
        json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private companion object {
        const val BASE = "https://openrouter.ai/api/v1"
    }
}
