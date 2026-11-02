package com.xnvalabs.smarteyex.data.enterprise

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TaskStatus(val label: String) {
    TODO("Todo"),
    DOING("Dikerjakan"),
    DONE("Selesai");

    fun next(): TaskStatus = entries[(ordinal + 1) % entries.size]
}

/** One work task on the Enterprise board. [assignee] is free text (may be blank). */
data class WorkTask(
    val id: String,
    val title: String,
    val assignee: String,
    val status: TaskStatus,
)

/**
 * Enterprise Mode — a small local task board for work use: tasks with an
 * optional assignee and a Todo → Dikerjakan → Selesai status. Same
 * SharedPreferences + hand-rolled JSON pattern as the other repositories.
 *
 * Deliberately single-device: there's no team sync, accounts, or shared
 * dashboard — that would need a real backend and multi-user auth, which
 * this app doesn't have. The screen's "dashboard" is a summary of this
 * device's own board.
 */
object EnterpriseRepository {
    private const val PREFS_NAME = "smarteyex_enterprise"
    private const val KEY_TASKS = "tasks"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    var tasks = mutableStateOf(emptyList<WorkTask>())
        private set

    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        tasks.value = load()
        initialized = true
    }

    fun add(title: String, assignee: String) =
        persist(tasks.value + WorkTask(UUID.randomUUID().toString(), title, assignee, TaskStatus.TODO))

    fun advance(id: String) =
        persist(tasks.value.map { if (it.id == id) it.copy(status = it.status.next()) else it })

    fun remove(id: String) = persist(tasks.value.filterNot { it.id == id })

    private fun persist(next: List<WorkTask>) {
        tasks.value = next
        val array = JSONArray()
        next.forEach { t ->
            array.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("assignee", t.assignee)
                    put("status", t.status.name)
                },
            )
        }
        prefs.edit().putString(KEY_TASKS, array.toString()).apply()
    }

    private fun load(): List<WorkTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                WorkTask(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    assignee = obj.getString("assignee"),
                    status = TaskStatus.valueOf(obj.getString("status")),
                )
            }
        }.getOrDefault(emptyList())
    }
}
