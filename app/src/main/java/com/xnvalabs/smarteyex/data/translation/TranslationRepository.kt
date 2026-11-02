package com.xnvalabs.smarteyex.data.translation

import com.xnvalabs.smarteyex.data.net.ApiClient
import com.xnvalabs.smarteyex.data.net.CloudGate
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Feature #9 (Translation): POST /v1/xnai/translate on the SmartEyeX server, same backend as XNAI chat and
 * vision. Gated by [CloudGate] (Cloud Processing ON and signed in); translation has no camera/mic component.
 */
object TranslationRepository {
    suspend fun translate(text: String, targetLanguage: String): Result<String> {
        CloudGate.check()?.let { return Result.failure(IllegalStateException(it)) }
        return try {
            val body = JSONObject().put("text", text).put("targetLanguage", targetLanguage)
            Result.success(ApiClient.call("POST", "/v1/xnai/translate", body).getString("translated"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(ApiClient.describe(e)))
        }
    }
}
