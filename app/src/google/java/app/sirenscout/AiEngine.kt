package app.sirenscout

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Gemini Nano through ML Kit's GenAI Prompt API (runs on the phone, only while Siren is on screen). */
object AiEngine {
    const val AVAILABLE_IN_BUILD = true

    private val model: GenerativeModel by lazy { Generation.getClient() }

    suspend fun status(context: Context): AiStatus = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> AiStatus.Ready
            FeatureStatus.DOWNLOADABLE -> AiStatus.NeedsDownload
            FeatureStatus.DOWNLOADING -> AiStatus.Downloading
            else -> AiStatus.Unsupported
        }
    } catch (e: GenAiException) {
        AiStatus.Error(describe(e))
    }

    /** Progress messages until the model is ready. */
    fun download(context: Context): Flow<String> = model.download()
        .map { s ->
            when (s) {
                is DownloadStatus.DownloadStarted -> "Starting download…"
                is DownloadStatus.DownloadProgress -> "Downloaded %.0f MB…".format(s.totalBytesDownloaded / 1_048_576.0)
                DownloadStatus.DownloadCompleted -> "Download complete"
                is DownloadStatus.DownloadFailed -> "Download failed: ${s.e.message}"
                else -> s.toString()
            }
        }
        .catch { emit("Download failed: ${it.message}") }

    suspend fun generate(context: Context, prompt: String): String {
        try {
            val response = model.generateContent(
                generateContentRequest(TextPart(prompt)) {
                    temperature = 0f // the same device should get the same answer
                    topK = 1
                    maxOutputTokens = 400
                }
            )
            return response.candidates.firstOrNull()?.text.orEmpty()
        } catch (e: GenAiException) {
            throw AiError(
                when (e.errorCode) {
                    GenAiException.ErrorCode.BUSY -> AiError.Kind.BUSY
                    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> AiError.Kind.QUOTA
                    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> AiError.Kind.BACKGROUND
                    GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED,
                    GenAiException.ErrorCode.AICORE_INCOMPATIBLE, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> AiError.Kind.UNAVAILABLE
                    else -> AiError.Kind.OTHER
                },
                describe(e),
            )
        }
    }

    private fun describe(e: GenAiException) = "${e.message ?: "error"} (code ${e.errorCode})"
}
