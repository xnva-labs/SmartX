package com.xnvalabs.smarteyex.data.xnai

import com.xnvalabs.smarteyex.data.memory.MemoryRepository
import com.xnvalabs.smarteyex.data.memory.MemoryType
import com.xnvalabs.smarteyex.data.net.ApiClient
import com.xnvalabs.smarteyex.data.net.CloudGate
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * XNAI chat. The model, prompting and (for Enterprise accounts) retrieval over the organization's
 * knowledge base all live on the SmartEyeX server (POST /v1/xnai/chat); this client sends the user's
 * message, a short context string, the conversation history and the think mode, and shows the reply.
 *
 * Nothing leaves the phone unless [CloudGate] passes: the Cloud Processing toggle must be ON and the
 * user must be signed in to a server. The server enforces the same consent independently.
 */
object XnaiRepository {
    suspend fun sendMessage(
        userText: String,
        history: List<XnaiMessage>,
        thinkMode: String,
    ): Result<String> {
        CloudGate.check()?.let { return Result.failure(IllegalStateException(it)) }
        return try {
            val body = JSONObject().apply {
                put("message", userText)
                put("thinkMode", thinkMode)
                put("context", buildContext())
                put(
                    "history",
                    JSONArray().apply {
                        history.forEach { msg -> put(JSONObject().put("role", msg.role).put("text", msg.text)) }
                    },
                )
            }
            val res = ApiClient.call("POST", "/v1/xnai/chat", body)
            var reply = res.getString("reply")
            val cites = res.optJSONArray("citations")
            if (cites != null && cites.length() > 0) {
                val titles = (0 until cites.length()).map { cites.getJSONObject(it).optString("title") }.filter { it.isNotBlank() }.distinct()
                if (titles.isNotEmpty()) reply += "\n\nSumber: " + titles.joinToString(", ")
            }
            Result.success(reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(ApiClient.describe(e)))
        }
    }

    /**
     * Context string sent with every message: profile fields plus preferences (see MemoryRepository),
     * concatenated into one plain-text block the server's prompt treats as data about the user.
     */
    private fun buildContext(): String {
        val entries = MemoryRepository.entries.value
        val profile = entries.filter { it.type == MemoryType.PROFILE }
        val preferences = entries.filter { it.type == MemoryType.PREFERENCE }

        val parts = mutableListOf<String>()
        if (profile.isNotEmpty()) {
            parts += "Profile: " + profile.joinToString("; ") { "${it.title}: ${it.content}" }
        }
        if (preferences.isNotEmpty()) {
            parts += "Preferences: " + preferences.joinToString("; ") { it.content }
        }
        return parts.joinToString(" | ")
    }
}
