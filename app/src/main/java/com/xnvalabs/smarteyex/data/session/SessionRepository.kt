package com.xnvalabs.smarteyex.data.session

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnvalabs.smarteyex.BuildConfig
import com.xnvalabs.smarteyex.data.net.ApiClient
import com.xnvalabs.smarteyex.data.security.SecureStore
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Who is signed in to which SmartEyeX server. Two separate realms exist on the server (Personal and
 * Enterprise); this app holds at most one session at a time. Tokens are stored encrypted with a
 * Keystore key ([SecureStore]); the server address is plain configuration.
 *
 * The app also works without any account (local-first Personal): everything that needs the server
 * (XNAI chat, vision, translation, Enterprise) simply reports "belum masuk" until the user signs in.
 */
object SessionRepository {
    enum class Realm { PERSONAL, ENTERPRISE }

    data class Info(
        val realm: Realm,
        val id: String,
        val email: String,
        val name: String,
        val orgId: String? = null,
        val orgSlug: String? = null,
        val orgName: String? = null,
        val role: String? = null,
        val permissions: Set<String> = emptySet(),
    ) {
        fun can(permission: String): Boolean = realm == Realm.ENTERPRISE && permission in permissions
    }

    private const val PREFS = "smarteyex_session"
    private const val KEY_SERVER = "server_url"
    private const val KEY_BLOB = "blob"
    private const val KEY_WELCOME = "welcome_seen"

    private lateinit var prefs: SharedPreferences
    private var initialized = false
    private var access: String? = null
    private var refresh: String? = null

    var info by mutableStateOf<Info?>(null)
        private set
    var serverUrl by mutableStateOf("")
        private set
    var welcomeSeen by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        serverUrl = prefs.getString(KEY_SERVER, null) ?: BuildConfig.API_BASE_URL
        welcomeSeen = prefs.getBoolean(KEY_WELCOME, false)
        prefs.getString(KEY_BLOB, null)?.let { blob ->
            val json = SecureStore.decrypt(blob, "session")
            if (json != null) {
                runCatching {
                    val o = JSONObject(json)
                    access = o.getString("access")
                    refresh = o.getString("refresh")
                    info = infoFromJson(o.getJSONObject("info"))
                }.onFailure { clear() }
            } else {
                prefs.edit().remove(KEY_BLOB).apply()
            }
        }
        initialized = true
    }

    fun baseUrl(): String? = serverUrl.ifBlank { null }
    fun accessToken(): String? = access
    fun markWelcomeSeen() {
        welcomeSeen = true
        prefs.edit().putBoolean(KEY_WELCOME, true).apply()
    }

    /** Returns a normalized URL, or null if it is not acceptable (HTTPS only; plain HTTP just for local debug builds). */
    fun normalizeServerUrl(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        val uri = runCatching { URI(t) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val local = host == "localhost" || host == "10.0.2.2" || host == "127.0.0.1"
        if (scheme == "https") return t
        if (scheme == "http" && local && BuildConfig.DEBUG) return t
        return null
    }

    /** False if the address is invalid or a session is active (tokens belong to one server). */
    fun setServerUrl(raw: String): Boolean {
        if (info != null) return false
        val url = normalizeServerUrl(raw) ?: return false
        serverUrl = url
        prefs.edit().putString(KEY_SERVER, url).apply()
        return true
    }

    // ------------------------------------------------------------------ sign in / out

    suspend fun registerPersonal(email: String, password: String, name: String): Result<Unit> =
        authCall("/v1/auth/personal/register", JSONObject().put("email", email.trim()).put("password", password).put("display_name", name.trim()).put("device_label", Build.MODEL))

    suspend fun loginPersonal(email: String, password: String): Result<Unit> =
        authCall("/v1/auth/personal/login", JSONObject().put("email", email.trim()).put("password", password).put("device_label", Build.MODEL))

    suspend fun loginEnterprise(org: String, email: String, password: String): Result<Unit> =
        authCall("/v1/auth/enterprise/login", JSONObject().put("org", org.trim().lowercase()).put("email", email.trim()).put("password", password).put("device_label", Build.MODEL))

    suspend fun acceptInvite(token: String, password: String, name: String): Result<Unit> =
        authCall("/v1/auth/enterprise/invites/accept", JSONObject().put("token", token.trim()).put("password", password).put("display_name", name.trim()).put("device_label", Build.MODEL))

    suspend fun logout() {
        runCatching { ApiClient.call("POST", "/v1/auth/logout") }
        clear()
    }

    suspend fun deletePersonalAccount(password: String): Result<Unit> = try {
        ApiClient.call("DELETE", "/v1/personal/account", JSONObject().put("password", password))
        clear()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Called by [ApiClient] on 401. True if new tokens were obtained; false (and signed out) if the session is gone. */
    suspend fun refreshTokens(): Boolean {
        val rt = refresh ?: return false
        return try {
            val j = ApiClient.call("POST", "/v1/auth/refresh", JSONObject().put("refresh_token", rt), auth = false)
            access = j.getString("access_token")
            refresh = j.getString("refresh_token")
            persist()
            true
        } catch (e: ApiClient.ApiException) {
            if (e.status == 401) {
                clear()
                false
            } else {
                throw e
            }
        }
    }

    /** Loads role and permissions from the server (they can change while signed in). */
    suspend fun refreshIdentity() {
        val j = ApiClient.call("GET", "/v1/auth/me")
        val cur = info ?: return
        val perms = j.optJSONArray("permissions")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        val org = j.optJSONObject("org")
        info = cur.copy(
            name = j.optString("display_name", cur.name),
            orgName = org?.optString("name") ?: cur.orgName,
            role = j.optString("role").ifEmpty { cur.role },
            permissions = perms,
        )
        persist()
    }

    /** Mirrors the local Cloud Processing toggle to the server, which enforces it independently. */
    suspend fun pushCloudConsent(enabled: Boolean) {
        if (info?.realm != Realm.PERSONAL) return
        ApiClient.call("PUT", "/v1/personal/privacy", JSONObject().put("cloud_processing", enabled))
    }

    // ------------------------------------------------------------------ internals

    private suspend fun authCall(path: String, body: JSONObject): Result<Unit> = try {
        val j = ApiClient.call("POST", path, body, auth = false)
        access = j.getString("access_token")
        refresh = j.getString("refresh_token")
        info = if (j.has("employee")) {
            val e = j.getJSONObject("employee")
            val o = j.getJSONObject("org")
            Info(Realm.ENTERPRISE, e.getString("id"), e.getString("email"), e.optString("display_name"), o.getString("id"), o.getString("slug"), o.getString("name"), e.optString("role"))
        } else {
            val u = j.getJSONObject("user")
            Info(Realm.PERSONAL, u.getString("id"), u.getString("email"), u.optString("display_name"))
        }
        persist()
        runCatching { refreshIdentity() }
        runCatching { pushCloudConsent(com.xnvalabs.smarteyex.data.privacy.PrivacyRepository.settings.value.cloudProcessingEnabled) }
        markWelcomeSeen()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun clear() {
        access = null
        refresh = null
        info = null
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun persist() {
        val cur = info
        val a = access
        val r = refresh
        if (cur == null || a == null || r == null) {
            prefs.edit().remove(KEY_BLOB).apply()
            return
        }
        val o = JSONObject().put("access", a).put("refresh", r).put("info", infoToJson(cur))
        prefs.edit().putString(KEY_BLOB, SecureStore.encrypt(o.toString(), "session")).apply()
    }

    private fun infoToJson(i: Info): JSONObject = JSONObject()
        .put("realm", i.realm.name).put("id", i.id).put("email", i.email).put("name", i.name)
        .put("orgId", i.orgId ?: JSONObject.NULL).put("orgSlug", i.orgSlug ?: JSONObject.NULL).put("orgName", i.orgName ?: JSONObject.NULL)
        .put("role", i.role ?: JSONObject.NULL).put("permissions", JSONArray(i.permissions.toList()))

    private fun infoFromJson(o: JSONObject): Info {
        fun opt(k: String): String? = if (o.isNull(k)) null else o.getString(k)
        val perms = o.optJSONArray("permissions")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        return Info(Realm.valueOf(o.getString("realm")), o.getString("id"), o.getString("email"), o.optString("name"), opt("orgId"), opt("orgSlug"), opt("orgName"), opt("role"), perms)
    }
}
