package com.xnvalabs.smarteyex.data.vision

import android.util.Base64
import com.xnvalabs.smarteyex.data.net.ApiClient
import com.xnvalabs.smarteyex.data.net.CloudGate
import com.xnvalabs.smarteyex.data.privacy.PrivacyRepository
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Computer vision / OCR (features #7 and #8). The phone (or the glasses) captures a JPEG; the SmartEyeX
 * server strips its metadata, downsizes it, and asks the model (POST /v1/xnai/vision). Images are not stored
 * on the server.
 *
 * Gated by TWO privacy toggles: cameraEnabled (capturing at all) and cloudProcessingEnabled (sending the
 * frame off-device, via [CloudGate]). A frame is never analyzed if either is off.
 *
 * [mode]: "describe" (what is in view), "read" (OCR), or "inspect" (Enterprise only; needs [checklist]).
 */
object VisionRepository {
    suspend fun analyzeFrame(
        jpegBytes: ByteArray,
        mode: String = "describe",
        checklist: List<String> = emptyList(),
    ): Result<String> {
        if (!PrivacyRepository.settings.value.cameraEnabled) {
            return Result.failure(IllegalStateException("Camera OFF — aktifkan di Privacy Control."))
        }
        CloudGate.check()?.let { return Result.failure(IllegalStateException(it)) }
        return try {
            val body = JSONObject()
                .put("imageBase64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                .put("mode", mode)
            if (checklist.isNotEmpty()) body.put("checklist", JSONArray(checklist))
            Result.success(ApiClient.call("POST", "/v1/xnai/vision", body).getString("description"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(ApiClient.describe(e)))
        }
    }
}
