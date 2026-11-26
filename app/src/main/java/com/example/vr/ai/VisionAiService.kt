package com.example.vr.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

data class VisionChatTurn(
    val role: String,
    val text: String
)

sealed class VisionAiFailure(message: String) : Exception(message) {
    class NoInternet : VisionAiFailure(
        "No internet connection. Connect to Wi-Fi or mobile data and try again."
    )
    class MissingApiKey : VisionAiFailure(
        "FreeLLMAPI key is not configured. Add FREELLMAPI_API_KEY in the AI Studio Secrets panel."
    )
    class Timeout : VisionAiFailure(
        "The vision request timed out. Please check your connection and try again."
    )
    class ApiError(detail: String) : VisionAiFailure(detail)
}

interface VisionAiRepository {
    suspend fun askAboutImage(
        imageJpegBase64: String,
        history: List<VisionChatTurn>,
        question: String
    ): Result<String>
}

class FreeLlmVisionService(private val context: Context) : VisionAiRepository {

    @Volatile
    private var cachedVisionModels: List<String> = emptyList()

    override suspend fun askAboutImage(
        imageJpegBase64: String,
        history: List<VisionChatTurn>,
        question: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!hasActiveInternet()) {
            return@withContext Result.failure(VisionAiFailure.NoInternet())
        }

        val apiKey = BuildConfig.FREELLMAPI_API_KEY.trim()
        if (apiKey.isEmpty() || apiKey == PLACEHOLDER_KEY) {
            return@withContext Result.failure(VisionAiFailure.MissingApiKey())
        }

        try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                val models = resolveCandidateVisionModels(apiKey)
                var lastError: VisionAiFailure? = null

                for ((index, modelId) in models.withIndex()) {
                    val response = executeChatCompletion(
                        apiKey = apiKey,
                        modelId = modelId,
                        imageJpegBase64 = imageJpegBase64,
                        history = history,
                        question = question
                    )
                    if (response.isSuccess) {
                        return@withTimeout response
                    }
                    val err = response.exceptionOrNull()
                    if (err is HttpStatusException &&
                        (err.statusCode == 400 || err.statusCode == 404 || err.statusCode == 422) &&
                        index < models.lastIndex
                    ) {
                        Log.w(TAG, "Model $modelId rejected (${err.statusCode}), trying next candidate")
                        lastError = VisionAiFailure.ApiError(err.message ?: "Model error ${err.statusCode}")
                        continue
                    }
                    return@withTimeout Result.failure(
                        (err as? VisionAiFailure)
                            ?: VisionAiFailure.ApiError(err?.message ?: "Vision API request failed.")
                    )
                }

                Result.failure(lastError ?: VisionAiFailure.ApiError("No supported vision model responded."))
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Vision request timed out", e)
            Result.failure(VisionAiFailure.Timeout())
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Socket timeout calling FreeLLMAPI", e)
            Result.failure(VisionAiFailure.Timeout())
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Host resolution failed", e)
            Result.failure(VisionAiFailure.NoInternet())
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error calling FreeLLMAPI", e)
            Result.failure(
                VisionAiFailure.ApiError(
                    e.message?.take(180) ?: "Failed to contact vision service."
                )
            )
        }
    }

    private fun resolveCandidateVisionModels(apiKey: String): List<String> {
        if (cachedVisionModels.isNotEmpty()) {
            return cachedVisionModels
        }
        val discovered = fetchModelsList(apiKey)
        val ranked = if (discovered.isNotEmpty()) {
            val preferred = discovered.filter { id ->
                val lower = id.lowercase()
                VISION_KEYWORDS.any { lower.contains(it) }
            }
            val others = discovered.filterNot { it in preferred }
            (preferred + others).distinct().take(4)
        } else {
            DEFAULT_FALLBACK_MODELS
        }
        cachedVisionModels = ranked
        Log.d(TAG, "Resolved candidate models: $ranked")
        return ranked
    }

    private fun fetchModelsList(apiKey: String): List<String> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$FREELLMAPI_BASE_URL/models")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return emptyList()
            }
            val body = readStream(conn)
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                    if (id.isNotEmpty()) add(id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch /models list, using fallback candidates", e)
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    private fun executeChatCompletion(
        apiKey: String,
        modelId: String,
        imageJpegBase64: String,
        history: List<VisionChatTurn>,
        question: String
    ): Result<String> {
        val messages = JSONArray()

        messages.put(
            JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "You are VisionBox Spatial Assistant helping a user wearing a Cardboard VR passthrough headset. " +
                        "Answer concisely in 1 to 3 clear sentences suitable for spoken Text-to-Speech. " +
                        "Base your answer strictly on what is visible in the captured camera frame."
                )
            }
        )

        if (history.isEmpty()) {
            messages.put(buildUserVisionMessage(question, imageJpegBase64))
        } else {
            var attachedImage = false
            for (turn in history) {
                if (turn.role == "user" && !attachedImage) {
                    messages.put(buildUserVisionMessage(turn.text, imageJpegBase64))
                    attachedImage = true
                } else {
                    messages.put(
                        JSONObject().apply {
                            put("role", turn.role)
                            put("content", turn.text)
                        }
                    )
                }
            }
            messages.put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", question)
                }
            )
        }

        val payload = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("max_tokens", 320)
            put("temperature", 0.4)
        }

        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$FREELLMAPI_BASE_URL/chat/completions")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 40_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            val responseText = readStream(conn)
            if (code !in 200..299) {
                val parsedErr = extractErrorMessage(responseText, code)
                return Result.failure(HttpStatusException(code, parsedErr))
            }

            val answer = extractAssistantText(responseText)
            if (answer.isBlank()) {
                Result.failure(VisionAiFailure.ApiError("The AI model returned an empty response."))
            } else {
                Result.success(answer.trim())
            }
        } catch (e: SocketTimeoutException) {
            Result.failure(VisionAiFailure.Timeout())
        } catch (e: UnknownHostException) {
            Result.failure(VisionAiFailure.NoInternet())
        } finally {
            conn?.disconnect()
        }
    }

    private fun buildUserVisionMessage(prompt: String, imageJpegBase64: String): JSONObject {
        val parts = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                }
            )
            put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$imageJpegBase64")
                        }
                    )
                }
            )
        }
        return JSONObject().apply {
            put("role", "user")
            put("content", parts)
        }
    }

    private fun extractAssistantText(rawJson: String): String {
        val root = JSONObject(rawJson)
        val choices = root.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        val contentObj = message.opt("content")
        return when (contentObj) {
            is String -> contentObj
            is JSONArray -> buildString {
                for (i in 0 until contentObj.length()) {
                    val part = contentObj.optJSONObject(i)
                    val text = part?.optString("text").orEmpty()
                    if (text.isNotEmpty()) {
                        if (isNotEmpty()) append(' ')
                        append(text)
                    }
                }
            }
            else -> message.optString("content", "")
        }
    }

    private fun extractErrorMessage(rawJson: String, code: Int): String {
        return try {
            val root = JSONObject(rawJson)
            val errObj = root.optJSONObject("error")
            val msg = errObj?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
                ?: "Vision API returned HTTP $code."
            msg.take(160)
        } catch (_: Exception) {
            when (code) {
                401, 403 -> "Authentication failed ($code). Check FREELLMAPI_API_KEY in Secrets."
                429 -> "Rate limit reached (429). Please wait a moment and try again."
                else -> "Vision API error (HTTP $code)."
            }
        }
    }

    private fun readStream(conn: HttpURLConnection): String {
        val stream = try {
            if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        } catch (_: Exception) {
            conn.errorStream
        } ?: return ""

        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    private fun hasActiveInternet(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private class HttpStatusException(val statusCode: Int, message: String) : Exception(message)

    companion object {
        private const val TAG = "FreeLlmVisionService"
        const val FREELLMAPI_BASE_URL = "https://freellmapi-production-7b4b294a.up.railway.app/v1"
        private const val PLACEHOLDER_KEY = "MY_FREELLMAPI_API_KEY"
        private const val REQUEST_TIMEOUT_MS = 45_000L

        private val VISION_KEYWORDS = listOf("gemini", "gpt-4o", "vision", "-vl", "pixtral", "flash", "claude-3")
        private val DEFAULT_FALLBACK_MODELS = listOf("gpt-4o-mini", "gpt-4o", "gemini-2.0-flash")
    }
}
