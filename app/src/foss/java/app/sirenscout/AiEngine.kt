package app.sirenscout

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The open-source edition has no on-device AI: Google's model is only reachable through its
 * closed-source ML Kit library. AI guesses shared from another phone still show.
 */
object AiEngine {
    const val AVAILABLE_IN_BUILD = false

    suspend fun status(context: Context): AiStatus = AiStatus.Unsupported

    fun download(context: Context): Flow<String> = flowOf("Not available in this edition")

    suspend fun generate(context: Context, prompt: String): String =
        throw AiError(AiError.Kind.UNAVAILABLE, "not available in this edition")
}
