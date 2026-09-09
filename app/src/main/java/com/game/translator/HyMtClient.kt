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
        val streamMode: Boolean = true
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
     * 发送结构化单次批量翻译请求
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
     * 发送 SSE 流式翻译请求，并在逐句或增量生成时通过 [onProgress] 回调通知上屏展示。
     * 若服务端不支持流式或发生异常，将优雅降级到单次全量请求。
     *
     * @param onProgress (clusterId, text, isFinished) -> Unit
     */
    suspend fun translateStream(
        clusters: List<ClusteredText>,
        config: TranslationConfig,
        onProgress: (clusterId: Int, partialText: String, isFinished: Boolean) -> Unit
    ): Result<List<ClusteredText>> = withContext(Dispatchers.IO) {
        if (clusters.isEmpty()) {
            return@withContext Result.success(clusters)
        }

        // 若用户未开启流式，则直接执行普通单次请求
        if (!config.streamMode) {
            val res = translate(clusters, config)
            res.onSuccess {
                for (item in it) {
                    onProgress(item.id, item.translatedText ?: item.originalText, true)
                }
            }
            return@withContext res
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
                val errorBody = response.body?.string() ?: ""
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
                        } catch (_: Exception) {
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
                                // 容错：未检测到 [id] 时，作为第一项单句流式输出
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
            // 如果流式在开始阶段失败且尚未上报任何完成项，尝试普通请求降级
            try {
                val fallbackRes = translate(clusters, config)
                fallbackRes.onSuccess {
                    for (item in it) {
                        onProgress(item.id, item.translatedText ?: item.originalText, true)
                    }
                }
                fallbackRes
            } catch (_: Exception) {
                Result.failure(e)
            }
        }
    }
}
