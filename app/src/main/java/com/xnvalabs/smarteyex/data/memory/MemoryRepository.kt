package com.xnvalabs.smarteyex.data.memory

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.xnvalabs.smarteyex.data.privacy.PrivacyRepository
import com.xnvalabs.smarteyex.data.security.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Tahap 2 (Personal Memory) — local storage for everything the user lets
 * SmartEyeX remember: profile fields, quick notes, people, tasks.
 *
 * Uses SharedPreferences + a hand-rolled JSON array (org.json ships with
 * the Android SDK) instead of Room, same "no dependency before it's
 * earned" rule as PrivacyRepository — a flat list with a handful of entry
 * types doesn't need a relational database yet. Revisit this if
 * querying/filtering gets complex enough that Room actually pays for
 * itself.
 *
 * Every write here checks [PrivacyRepository]'s memory toggle first and
 * silently no-ops when it's off — screens don't need their own privacy
 * checks, they just call these functions. Deletes are always allowed
 * (the user can clean up regardless of the toggle).
 */
object MemoryRepository {
    private const val PREFS_NAME = "smarteyex_memory"
    private const val KEY_ENTRIES = "entries"

    private const val AAD = "memory-entries"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    /** Reactive snapshot — Compose screens read this directly and recompose on change. */
    var entries = mutableStateOf(emptyList<MemoryEntry>())
        private set

    /** Call once, from MainActivity.onCreate, before any screen reads [entries]. */
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        entries.value = load()
        initialized = true
    }

    /** Add a standalone entry (NOTE, PERSON, TASK) — always appended, never merged. */
    fun addEntry(type: MemoryType, title: String, content: String) {
        if (!memoryAllowed()) return
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            type = type,
            title = title,
            content = content,
            timestamp = System.currentTimeMillis(),
        )
        persist(entries.value + entry)
    }

    /**
     * Profile fields (Name, Interest, Habit, ...) are singletons by label —
     * re-saving the same field updates it in place instead of piling up a
     * new entry every time the user edits a character. Blank content
     * removes the field entirely rather than storing an empty string.
     */
    fun upsertProfileField(label: String, content: String) {
        if (!memoryAllowed()) return
        val without = entries.value.filterNot { it.type == MemoryType.PROFILE && it.title == label }
        if (content.isBlank()) {
            persist(without)
            return
        }
        val existingId = entries.value
            .firstOrNull { it.type == MemoryType.PROFILE && it.title == label }?.id
        val entry = MemoryEntry(
            id = existingId ?: UUID.randomUUID().toString(),
            type = MemoryType.PROFILE,
            title = label,
            content = content,
            timestamp = System.currentTimeMillis(),
        )
        persist(without + entry)
    }

    /** Read-only helper so ProfileScreen can prefill a field, even when memory is off. */
    fun getProfileValue(label: String): String? =
        entries.value.firstOrNull { it.type == MemoryType.PROFILE && it.title == label }?.content

    fun deleteEntry(id: String) {
        persist(entries.value.filterNot { it.id == id })
    }

    fun deleteByType(type: MemoryType) {
        persist(entries.value.filterNot { it.type == type })
    }

    /** Wipes everything — this is the [PrivacyRepository.clearAllData] hook target. */
    fun deleteAll() {
        persist(emptyList())
    }

    private fun memoryAllowed(): Boolean {
        check(initialized) { "MemoryRepository.init(context) must be called before writing entries" }
        return PrivacyRepository.settings.value.memoryEnabled
    }

    private fun persist(next: List<MemoryEntry>) {
        entries.value = next
        val array = JSONArray()
        next.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("type", entry.type.name)
                    put("title", entry.title)
                    put("content", entry.content)
                    put("timestamp", entry.timestamp)
                },
            )
        }
        prefs.edit().putString(KEY_ENTRIES, SecureStore.encrypt(array.toString(), AAD)).apply()
    }

    private fun load(): List<MemoryEntry> {
        val stored = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        // Entries are encrypted at rest (Android Keystore). A plaintext value is legacy data: read it, then re-save encrypted.
        val legacy = !stored.startsWith(SecureStore.PREFIX)
        val raw = if (legacy) stored else (SecureStore.decrypt(stored, AAD) ?: return emptyList())
        if (legacy) prefs.edit().putString(KEY_ENTRIES, SecureStore.encrypt(raw, AAD)).apply()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                MemoryEntry(
                    id = obj.getString("id"),
                    type = MemoryType.valueOf(obj.getString("type")),
                    title = obj.getString("title"),
                    content = obj.getString("content"),
                    timestamp = obj.getLong("timestamp"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
