package com.game.translator

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class HyMtClient {

    companion object {
        const val STREAM_TYPE_FORM_B = "form_b"
        const val STREAM_TYPE_FORM_A = "form_a"
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun getClient(timeoutSeconds: Int): OkHttpClient {
        val sec = timeoutSeconds.coerceIn(5, 600).toLong()
        return baseClient.newBuilder()
            .readTimeout(sec, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // OpenAI 格式请求实体
    private data class ChatMessage(
        val role: String,
        val content: String
    )

    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Float,
        @SerializedName("top_p") val topP: Float,
        @SerializedName("frequency_penalty") val frequencyPenalty: Float,
        @SerializedName("repetition_penalty") val repetitionPenalty: Float,
        @SerializedName("max_tokens") val maxTokens: Int,
        val stream: Boolean? = null
    )

    // OpenAI 格式普通响应实体
    private data class ChatChoice(
        val index: Int,
        val message: ChatMessage
    )

    private data class ChatCompletionResponse(
        val id: String?,
        val choices: List<ChatChoice>?,
        val error: ChatError?
    )

    private data class ChatError(
        val message: String?,
        val type: String?,
        val code: Any?
    )

    // OpenAI 格式流式 SSE 响应实体
    private data class ChatDelta(
        val role: String?,
        val content: String?
    )

    private data class ChatStreamChoice(
        val index: Int,
        val delta: ChatDelta?,
        @SerializedName("finish_reason") val finishReason: String?
    )

    private data class ChatStreamChunk(
        val id: String?,
        val choices: List<ChatStreamChoice>?,
        val error: ChatError?
    )

    data class TranslationConfig(
        val endpointUrl: String,
        val apiKey: String? = null,
        val modelName: String,
        val temperature: Float,
        val topP: Float,
        val frequencyPenalty: Float,
        val maxTokens: Int,
        val systemPrompt: String,
        val timeoutSeconds: Int = 60,
        val streamMode: Boolean = true,
        val streamType: String = STREAM_TYPE_FORM_B
    )

    private fun buildRequestUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun buildUserPrompt(clusters: List<ClusteredText>, systemPrompt: String): String {
        return buildString {
            if (systemPrompt.isNotBlank()) {
                append(systemPrompt.trim())
                if (!systemPrompt.contains("# 数据输入")) {
                    append("\n\n# 数据输入\n")
                } else {
                    append("\n")
                }
            }
            for (item in clusters) {
                append("[${item.id}] ${item.originalText}\n")
            }
        }.trim()
    }

    /**
     * 发送结构化单次全量批量翻译请求（非流式模式）
     */
    suspend fun translate(
        clusters: List<ClusteredText>,
        config: TranslationConfig
    ): Result<List<ClusteredText>> = withContext(Dispatchers.IO) {
        if (clusters.isEmpty()) {
            return@withContext Result.success(clusters)
        }

        val fullUserPrompt = buildUserPrompt(clusters, config.systemPrompt)

        val requestPayload = ChatCompletionRequest(
            model = config.modelName,
            messages = listOf(
                ChatMessage(role = "user", content = fullUserPrompt)
            ),
            temperature = config.temperature,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            repetitionPenalty = config.frequencyPenalty,
            maxTokens = config.maxTokens,
            stream = false
        )

        val jsonBody = gson.toJson(requestPayload)
        val finalUrl = buildRequestUrl(config.endpointUrl)

        val requestBuilder = Request.Builder()
            .url(finalUrl)
            .post(jsonBody.toRequestBody(jsonMediaType))

        if (!config.apiKey.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        val httpRequest = requestBuilder.build()
        val client = getClient(config.timeoutSeconds)

        try {
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: ${response.message} - $responseBody")
                    )
                }

                val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
                if (chatResponse?.choices.isNullOrEmpty()) {
                    val errorMsg = chatResponse?.error?.message ?: "大模型未返回有效选择项"
                    return@withContext Result.failure(IOException(errorMsg))
                }

                var responseContent = chatResponse.choices!![0].message.content.trim()

                if (responseContent.startsWith("```")) {
                    responseContent = responseContent
                        .replaceFirst(Regex("^```[a-zA-Z0-9_-]*\\R"), "")
                        .replace(Regex("\\R```$"), "")
                        .trim()
                }

                val pattern = Pattern.compile("[\\[【［](\\d+)[\\]】］][:：]?\\s*([\\s\\S]*?)(?=[\\[【［]\\d+[\\]】］]|$)")
                val matcher = pattern.matcher(responseContent)

                val translationMap = mutableMapOf<Int, String>()
                while (matcher.find()) {
                    val id = matcher.group(1)?.toIntOrNull()
                    val text = matcher.group(2)?.trim()
                    if (id != null && !text.isNullOrBlank()) {
                        translationMap[id] = text
                    }
                }

                if (translationMap.isEmpty() && clusters.isNotEmpty()) {
                    clusters[0].translatedText = responseContent
                } else {
                    for (cluster in clusters) {
                        cluster.translatedText = translationMap[cluster.id] ?: cluster.originalText
                    }
                }

                Result.success(clusters)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 流式翻译入口：支持形态 B（逐句独立流式）与形态 A（单请求合并流式）
     */
    suspend fun translateStream(
        clusters: List<ClusteredText>,
        config: TranslationConfig,
        onProgress: (clusterId: Int, partialText: String, isFinished: Boolean) -> Unit
    ): Result<List<ClusteredText>> = withContext(Dispatchers.IO) {
        if (clusters.isEmpty()) {
            return@withContext Result.success(clusters)
        }

        // 若未开启流式，则直接走普通全量请求
        if (!config.streamMode) {
            val res = translate(clusters, config)
            res.onSuccess {
                for (item in it) {
                    onProgress(item.id, item.translatedText ?: item.originalText, true)
                }
            }
            return@withContext res
        }

        // 根据用户选定的形态路由
        if (config.streamType == STREAM_TYPE_FORM_B) {
            translateStreamFormB(clusters, config, onProgress)
        } else {
            translateStreamFormA(clusters, config, onProgress)
        }
    }

    /**
     * 形态 B：逐句独立分批流式翻译（对齐混元官方单句 Prompt，1.8B 小模型极稳，无任何 [id] 标号干扰）
     */
    private suspend fun translateStreamFormB(
        clusters: List<ClusteredText>,
        config: TranslationConfig,
        onProgress: (clusterId: Int, partialText: String, isFinished: Boolean) -> Unit
    ): Result<List<ClusteredText>> = withContext(Dispatchers.IO) {
        val client = getClient(config.timeoutSeconds)
        val finalUrl = buildRequestUrl(config.endpointUrl)

        for (item in clusters) {
            val original = item.originalText.trim()
            if (original.isEmpty()) continue

            // 混元官方标准单句 Prompt，适用于 1.8B 等各类开源翻译模型
            val singlePrompt = "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n$original"

            val requestPayload = ChatCompletionRequest(
                model = config.modelName,
                messages = listOf(
                    ChatMessage(role = "user", content = singlePrompt)
                ),
                temperature = config.temperature,
                topP = config.topP,
                frequencyPenalty = config.frequencyPenalty,
                repetitionPenalty = config.frequencyPenalty,
                maxTokens = config.maxTokens,
                stream = true
            )

            val jsonBody = gson.toJson(requestPayload)
            val requestBuilder = Request.Builder()
                .url(finalUrl)
                .post(jsonBody.toRequestBody(jsonMediaType))

            if (!config.apiKey.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
            }

            val httpRequest = requestBuilder.build()
            val textBuffer = StringBuilder()

            try {
                val response = client.newCall(httpRequest).execute()
                if (!response.isSuccessful) {
                    response.close()
                    item.translatedText = item.originalText
                    onProgress(item.id, item.originalText, true)
                    continue
                }

                val body = response.body
                if (body != null) {
                    val source = body.source()
                    body.use {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:")) continue
                            if (trimmed == "data: [DONE]" || trimmed == "data:[DONE]") break
                            if (trimmed.startsWith("data:")) {
                                val dataJson = trimmed.removePrefix("data:").trim()
                                if (dataJson.isEmpty()) continue
                                val chunk = try {
                                    gson.fromJson(dataJson, ChatStreamChunk::class.java)
                                } catch (e: Exception) {
                                    null
                                }
                                val deltaContent = chunk?.choices?.firstOrNull()?.delta?.content
                                if (!deltaContent.isNullOrEmpty()) {
                                    textBuffer.append(deltaContent)
                                    var current = textBuffer.toString().trim()
                                    if (current.startsWith("```")) {
                                        current = current.replaceFirst(Regex("^```[a-zA-Z0-9_-]*\\R"), "")
                                    }
                                    if (current.isNotBlank()) {
                                        onProgress(item.id, current, false)
                                    }
                                }
                            }
                        }
                    }
                }

                var finalClean = textBuffer.toString().trim()
                if (finalClean.startsWith("```")) {
                    finalClean = finalClean
                        .replaceFirst(Regex("^```[a-zA-Z0-9_-]*\\R"), "")
                        .replace(Regex("\\R```$"), "")
                        .trim()
                }

                val textToDisplay = if (finalClean.isNotBlank()) finalClean else item.originalText
                item.translatedText = textToDisplay
                onProgress(item.id, textToDisplay, true)

            } catch (e: Exception) {
                // 单句发生异常，降级显示原文并标记完成，不阻断后续气泡翻译
                item.translatedText = item.originalText
                onProgress(item.id, item.originalText, true)
            }
        }

        Result.success(clusters)
    }

    /**
     * 形态 A：单请求合并流式翻译（带 [1][2] 编号，单次网络请求）
     */
    private suspend fun translateStreamFormA(
        clusters: List<ClusteredText>,
        config: TranslationConfig,
        onProgress: (clusterId: Int, partialText: String, isFinished: Boolean) -> Unit
    ): Result<List<ClusteredText>> = withContext(Dispatchers.IO) {
        val fullUserPrompt = buildUserPrompt(clusters, config.systemPrompt)

        val requestPayload = ChatCompletionRequest(
            model = config.modelName,
            messages = listOf(
                ChatMessage(role = "user", content = fullUserPrompt)
            ),
            temperature = config.temperature,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            repetitionPenalty = config.frequencyPenalty,
            maxTokens = config.maxTokens,
            stream = true
        )

        val jsonBody = gson.toJson(requestPayload)
        val finalUrl = buildRequestUrl(config.endpointUrl)

        val requestBuilder = Request.Builder()
            .url(finalUrl)
            .post(jsonBody.toRequestBody(jsonMediaType))

        if (!config.apiKey.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        val httpRequest = requestBuilder.build()
        val client = getClient(config.timeoutSeconds)

        try {
            val response = client.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                response.close()
                // 服务端可能不支持 stream，尝试自动降级到非流式
                val fallbackRes = translate(clusters, config)
                fallbackRes.onSuccess {
                    for (item in it) {
                        onProgress(item.id, item.translatedText ?: item.originalText, true)
                    }
                }
                return@withContext fallbackRes
            }

            val body = response.body
            if (body == null) {
                return@withContext Result.failure(IOException("响应体为空"))
            }

            val source = body.source()
            val streamBuffer = StringBuilder()
            val idRegex = Regex("""[\\[【［](\d+)[\\]】］][:：]?""")
            val reportedFinishedIds = mutableSetOf<Int>()

            body.use {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:")) continue
                    if (trimmed == "data: [DONE]" || trimmed == "data:[DONE]") break
                    if (trimmed.startsWith("data:")) {
                        val dataJson = trimmed.removePrefix("data:").trim()
                        if (dataJson.isEmpty()) continue
                        val chunk = try {
                            gson.fromJson(dataJson, ChatStreamChunk::class.java)
                        } catch (e: Exception) {
                            null
                        }

                        if (chunk?.error != null) {
                            throw IOException(chunk.error.message ?: "流式传输异常")
                        }

                        val deltaContent = chunk?.choices?.firstOrNull()?.delta?.content
                        if (!deltaContent.isNullOrEmpty()) {
                            streamBuffer.append(deltaContent)

                            var cleanText = streamBuffer.toString()
                            if (cleanText.startsWith("```")) {
                                cleanText = cleanText.replaceFirst(Regex("^```[a-zA-Z0-9_-]*\\R"), "")
                            }

                            val matches = idRegex.findAll(cleanText).toList()
                            if (matches.isNotEmpty()) {
                                for (i in matches.indices) {
                                    val id = matches[i].groupValues[1].toIntOrNull() ?: continue
                                    val start = matches[i].range.last + 1
                                    val end = if (i + 1 < matches.size) matches[i + 1].range.first else cleanText.length
                                    val text = cleanText.substring(start, end).trim()
                                    val isFinished = (i + 1 < matches.size)

                                    if (isFinished) {
                                        reportedFinishedIds.add(id)
                                    }
                                    if (text.isNotBlank()) {
                                        onProgress(id, text, isFinished)
                                    }
                                }
                            } else if (clusters.isNotEmpty()) {
                                val rawText = cleanText.trim()
                                if (rawText.isNotBlank()) {
                                    onProgress(clusters[0].id, rawText, false)
                                }
                            }
                        }
                    }
                }
            }

            // 流式全部结束：提取最终结果并写入各 cluster
            var finalClean = streamBuffer.toString().trim()
            if (finalClean.startsWith("```")) {
                finalClean = finalClean
                    .replaceFirst(Regex("^```[a-zA-Z0-9_-]*\\R"), "")
                    .replace(Regex("\\R```$"), "")
                    .trim()
            }

            val finalMatches = idRegex.findAll(finalClean).toList()
            val translationMap = mutableMapOf<Int, String>()
            for (i in finalMatches.indices) {
                val id = finalMatches[i].groupValues[1].toIntOrNull() ?: continue
                val start = finalMatches[i].range.last + 1
                val end = if (i + 1 < finalMatches.size) finalMatches[i + 1].range.first else finalClean.length
                val text = finalClean.substring(start, end).trim()
                if (text.isNotBlank()) {
                    translationMap[id] = text
                }
            }

            if (translationMap.isEmpty() && clusters.isNotEmpty()) {
                clusters[0].translatedText = finalClean
                onProgress(clusters[0].id, finalClean, true)
            } else {
                for (cluster in clusters) {
                    val text = translationMap[cluster.id] ?: cluster.originalText
                    cluster.translatedText = text
                    onProgress(cluster.id, text, true)
                }
            }

            Result.success(clusters)
        } catch (e: Exception) {
            try {
                val fallbackRes = translate(clusters, config)
                fallbackRes.onSuccess {
                    for (item in it) {
                        onProgress(item.id, item.translatedText ?: item.originalText, true)
                    }
                }
                fallbackRes
            } catch (fallbackEx: Exception) {
                Result.failure(e)
            }
        }
    }
}
