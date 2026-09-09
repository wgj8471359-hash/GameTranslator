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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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
        @SerializedName("max_tokens") val maxTokens: Int
    )

    // OpenAI 格式响应实体
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

    data class TranslationConfig(
        val endpointUrl: String,
        val apiKey: String? = null,
        val modelName: String,
        val temperature: Float,
        val topP: Float,
        val frequencyPenalty: Float,
        val maxTokens: Int,
        val systemPrompt: String
    )

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

        // 拼接格式化的带编号待翻译原文
        val userPromptBuilder = StringBuilder()
        for (item in clusters) {
            userPromptBuilder.append("[${item.id}] ${item.originalText}\n")
        }

        val requestPayload = ChatCompletionRequest(
            model = config.modelName,
            messages = listOf(
                ChatMessage(role = "system", content = config.systemPrompt),
                ChatMessage(role = "user", content = userPromptBuilder.toString().trim())
            ),
            temperature = config.temperature,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            maxTokens = config.maxTokens
        )

        val jsonBody = gson.toJson(requestPayload)

        // 规范化 URL，兼容用户填写的 /v1 或直接填写的 BaseURL
        val baseUrl = config.endpointUrl.trimEnd('/')
        val finalUrl = when {
            baseUrl.endsWith("/chat/completions") -> baseUrl
            baseUrl.endsWith("/v1") -> "$baseUrl/chat/completions"
            else -> "$baseUrl/v1/chat/completions"
        }

        val requestBuilder = Request.Builder()
            .url(finalUrl)
            .post(jsonBody.toRequestBody(jsonMediaType))

        // 若配置了 API Key，则注入 Bearer 鉴权头
        if (!config.apiKey.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        val httpRequest = requestBuilder.build()

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

                // 预处理：剔除可能的 Markdown 代码块标记（如 ```markdown ... ``` 或 ``` ... ```）
                if (responseContent.startsWith("```")) {
                    responseContent = responseContent
                        .replaceFirst(Regex("^```[a-zA-Z0-9_-]*\\R"), "")
                        .replace(Regex("\\R```$"), "")
                        .trim()
                }

                // 使用增强型正则表达式抽取 [id] 翻译文本
                // 兼容半角 [1]、全角 【1】 或 ［1］，并容许冒号分隔符如 [1]:
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

                // 容错降级处理：若模型未带编号（例如单条回复情况），直接将全部输出赋予第一项
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
}
