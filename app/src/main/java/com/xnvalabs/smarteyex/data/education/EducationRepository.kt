package com.xnvalabs.smarteyex.data.education

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One study item in the personal library — a topic/material under a [subject]. */
data class StudyItem(
    val id: String,
    val title: String,
    val subject: String,
    val done: Boolean,
    val completedAt: Long?,
)

/**
 * Education Mode — the data behind the Perpustakaan (library) and
 * Progress screens. The user builds their own study list (title +
 * subject) and checks items off; Progress is derived from that list, so
 * there's one source of truth and nothing to keep in sync.
 *
 * Same SharedPreferences + hand-rolled JSON pattern as Reminder/Call
 * repositories. Everything is local and on-device — no privacy toggle
 * or backend involved. A pre-built curriculum or XNAI-generated study
 * plans would be a later layer on top of this.
 */
object EducationRepository {
    private const val PREFS_NAME = "smarteyex_education"
    private const val KEY_ITEMS = "items"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    var items = mutableStateOf(emptyList<StudyItem>())
        private set

    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        items.value = load()
        initialized = true
    }

    fun add(title: String, subject: String) {
        val item = StudyItem(UUID.randomUUID().toString(), title, subject.ifBlank { "Umum" }, false, null)
        persist(items.value + item)
    }

    fun toggleDone(id: String) {
        persist(
            items.value.map {
                if (it.id == id) {
                    val nowDone = !it.done
                    it.copy(done = nowDone, completedAt = if (nowDone) System.currentTimeMillis() else null)
                } else {
                    it
                }
            },
        )
    }

    fun remove(id: String) = persist(items.value.filterNot { it.id == id })

    private fun persist(next: List<StudyItem>) {
        items.value = next
        val array = JSONArray()
        next.forEach { i ->
            array.put(
                JSONObject().apply {
                    put("id", i.id)
                    put("title", i.title)
                    put("subject", i.subject)
                    put("done", i.done)
                    put("completedAt", i.completedAt ?: JSONObject.NULL)
                },
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun load(): List<StudyItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                StudyItem(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    subject = obj.getString("subject"),
                    done = obj.getBoolean("done"),
                    completedAt = if (obj.isNull("completedAt")) null else obj.getLong("completedAt"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
