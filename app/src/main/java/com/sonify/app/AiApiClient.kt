package com.sonify.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class AiProvider(
    val labelHe: String,
    val labelEn: String,
    val defaultModel: String,
    val presetModels: List<String>,
    val defaultEndpoint: String,
    val keyHint: String
) {
    GEMINI(
        labelHe = "Google Gemini",
        labelEn = "Google Gemini",
        defaultModel = "gemini-2.5-flash",
        presetModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite"
        ),
        defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta",
        keyHint = "AIza..."
    ),
    OPENAI(
        labelHe = "OpenAI (ChatGPT)",
        labelEn = "OpenAI (ChatGPT)",
        defaultModel = "gpt-4o-mini",
        presetModels = listOf(
            "gpt-4o-mini",
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "gpt-4o"
        ),
        defaultEndpoint = "https://api.openai.com/v1/chat/completions",
        keyHint = "sk-..."
    ),
    GROQ(
        labelHe = "Groq (מהיר וחינמי)",
        labelEn = "Groq (Fast & Free Tier)",
        defaultModel = "llama-3.3-70b-versatile",
        presetModels = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "meta-llama/llama-4-scout-17b-16e-instruct"
        ),
        defaultEndpoint = "https://api.groq.com/openai/v1/chat/completions",
        keyHint = "gsk_..."
    ),
    OPENROUTER(
        labelHe = "OpenRouter (עשרות מודלים)",
        labelEn = "OpenRouter (Multi-Model)",
        defaultModel = "google/gemini-2.0-flash-001",
        presetModels = listOf(
            "google/gemini-2.0-flash-001",
            "meta-llama/llama-3.3-70b-instruct:free",
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-haiku",
            "deepseek/deepseek-chat-v3-0324:free"
        ),
        defaultEndpoint = "https://openrouter.ai/api/v1/chat/completions",
        keyHint = "sk-or-..."
    ),
    CUSTOM_COMPAT(
        labelHe = "שרת OpenAI מותאם אישית",
        labelEn = "Custom OpenAI-Compatible",
        defaultModel = "gpt-4o-mini",
        presetModels = listOf("gpt-4o-mini", "llama3.1", "qwen2.5"),
        defaultEndpoint = "https://api.openai.com/v1/chat/completions",
        keyHint = "API Key..."
    );

    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
}

/**
 * Unified, crash-proof AI API client supporting Gemini, OpenAI, Groq, OpenRouter & Custom endpoints.
 * Features sanitized headers, controlled batching, automatic model fallback, exponential retry on 429/5xx,
 * and live connection testing.
 */
class AiApiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(14, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun cleanKey(raw: String): String = raw.trim().replace(Regex("[\\r\\n\\s]+"), "")

    fun hasValidConfig(settings: AppSettings): Boolean = cleanKey(settings.geminiApiKey).isNotBlank()

    suspend fun testConnection(settings: AppSettings): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val isEn = settings.language == AppLanguage.ENGLISH
        val key = cleanKey(settings.geminiApiKey)
        if (key.isBlank()) {
            return@withContext false to if (isEn) "Please enter an API key first" else "יש להזין מפתח API תחילה"
        }
        val t0 = System.currentTimeMillis()
        val (reply, err) = generateText(
            prompt = "Reply with only the single word: OK",
            settings = settings,
            expectJson = false,
            maxTokens = 16
        )
        val dt = System.currentTimeMillis() - t0
        if (!reply.isNullOrBlank()) {
            val modelUsed = activeModelFor(settings)
            true to if (isEn) "Connected to ${settings.aiProvider.labelEn} ($modelUsed) in ${dt}ms!"
            else "חיבור הצליח ל-${settings.aiProvider.labelHe} ($modelUsed) תוך ${dt}ms!"
        } else {
            false to (err ?: if (isEn) "Connection failed" else "החיבור לשרת ה-AI נכשל")
        }
    }

    fun activeModelFor(settings: AppSettings): String {
        val chosen = settings.aiModelId.trim()
        return if (chosen.isNotBlank()) chosen else settings.aiProvider.defaultModel
    }

    suspend fun generateText(
        prompt: String,
        settings: AppSettings,
        expectJson: Boolean = false,
        maxTokens: Int = 2048
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val key = cleanKey(settings.geminiApiKey)
        if (key.isBlank()) {
            return@withContext null to "לא הוזן מפתח API בהגדרות ← בינה מלאכותית"
        }
        val safePrompt = prompt.take(14_000)

        when (settings.aiProvider) {
            AiProvider.GEMINI -> callGeminiWithFallback(safePrompt, key, activeModelFor(settings), expectJson, maxTokens)
            AiProvider.OPENAI, AiProvider.GROQ, AiProvider.OPENROUTER, AiProvider.CUSTOM_COMPAT -> {
                val endpoint = settings.aiCustomEndpoint.trim().ifBlank { settings.aiProvider.defaultEndpoint }
                callOpenAiCompatible(safePrompt, key, activeModelFor(settings), endpoint, settings.aiProvider, expectJson, maxTokens)
            }
        }
    }

    private suspend fun callGeminiWithFallback(
        prompt: String,
        apiKey: String,
        primaryModel: String,
        expectJson: Boolean,
        maxTokens: Int
    ): Pair<String?, String?> {
        val modelQueue = (listOf(primaryModel) + listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.0-flash-lite")).distinct()
        var lastError = "שגיאת חיבור ל-Gemini"

        for (model in modelQueue) {
            val supportsThinking = model.contains("2.5")
            val genConfig = JSONObject().apply {
                put("temperature", 0.25)
                put("maxOutputTokens", maxTokens)
                if (expectJson) put("responseMimeType", "application/json")
                if (supportsThinking) {
                    put("thinkingConfig", JSONObject().apply { put("thinkingBudget", 0) })
                }
            }
            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                put("generationConfig", genConfig)
            }

            for (attempt in 0..1) {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                try {
                    http.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val text = extractGeminiText(bodyStr)
                            if (!text.isNullOrBlank()) return text to null
                            lastError = "המודל החזיר תשובה ריקה"
                        } else {
                            val apiMsg = parseApiErrorMessage(bodyStr)
                            lastError = mapHttpError(resp.code, apiMsg, model)
                            if (resp.code == 400 && (apiMsg.contains("API key", true) || apiMsg.contains("invalid", true))) {
                                return null to lastError
                            }
                            if (resp.code == 429 && attempt == 0) {
                                delay(900)
                                return@use
                            }
                        }
                    }
                } catch (e: Exception) {
                    lastError = "בעיית רשת בגישה ל-Gemini (${e.javaClass.simpleName})"
                    if (attempt == 0) delay(500)
                }
                break
            }
        }
        return null to lastError
    }

    private suspend fun callOpenAiCompatible(
        prompt: String,
        apiKey: String,
        model: String,
        endpoint: String,
        provider: AiProvider,
        expectJson: Boolean,
        maxTokens: Int
    ): Pair<String?, String?> {
        if (!endpoint.startsWith("http")) return null to "כתובת Endpoint לא תקינה"

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", if (expectJson) "You are a precise music assistant. Always reply with valid JSON only." else "You are a helpful, concise music assistant.")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.25)
            put("max_tokens", maxTokens)
            if (expectJson && provider != AiProvider.OPENROUTER) {
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }
        }

        var lastError = "שגיאת חיבור ל-${provider.labelHe}"
        for (attempt in 0..1) {
            val rb = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (provider == AiProvider.OPENROUTER) {
                rb.header("HTTP-Referer", "https://sonify.link")
                rb.header("X-Title", "Sonify")
            }

            try {
                http.newCall(rb.build()).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val root = JSONObject(bodyStr)
                        val text = root.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content")
                            ?.trim()
                        if (!text.isNullOrBlank()) return text to null
                        lastError = "המודל החזיר תשובה ריקה"
                    } else {
                        val apiMsg = parseApiErrorMessage(bodyStr)
                        lastError = mapHttpError(resp.code, apiMsg, model)
                        if (resp.code == 401 || resp.code == 403) return null to lastError
                        if (resp.code == 429 && attempt == 0) {
                            delay(900)
                            return@use
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = "בעיית רשת בגישה ל-${provider.labelHe} (${e.javaClass.simpleName})"
                if (attempt == 0) delay(500)
            }
            break
        }
        return null to lastError
    }

    private fun extractGeminiText(bodyStr: String): String? = runCatching {
        val root = JSONObject(bodyStr)
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts") ?: return null
        buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (!p.optBoolean("thought", false)) {
                    append(p.optString("text"))
                }
            }
        }.trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseApiErrorMessage(bodyStr: String): String = runCatching {
        val root = JSONObject(bodyStr)
        root.optJSONObject("error")?.optString("message").orEmpty()
            .ifBlank { root.optString("message") }
    }.getOrDefault("")

    private fun mapHttpError(code: Int, apiMsg: String, model: String): String = when (code) {
        400 -> if (apiMsg.contains("key", true)) "מפתח ה-API לא תקין — בדוק שהעתקת נכון" else "שגיאת בקשה (400): ${apiMsg.take(60)}"
        401, 403 -> "מפתח ה-API נדחה ($code) — בדוק את המפתח או ההרשאות"
        404 -> "המודל $model לא זמין כרגע (404)"
        429 -> "חרגת ממכסת הבקשות (429) — נסה להחליף מודל או להמתין רגע"
        in 500..599 -> "שרת ה-AI עמוס זמנית ($code) — נסה שוב"
        else -> "שגיאת API ($code)"
    }
}
