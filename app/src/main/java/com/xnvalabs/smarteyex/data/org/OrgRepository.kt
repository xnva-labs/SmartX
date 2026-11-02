package com.xnvalabs.smarteyex.data.org

import com.xnvalabs.smarteyex.data.net.ApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enterprise features backed by the SmartEyeX server (docs/openapi.yaml, /v1/enterprise/*). Every call is authorized
 * server-side by role (RBAC) and scoped to the signed-in organization; this class only shapes requests and responses.
 */
object OrgRepository {
    class Task(val id: String, val title: String, val description: String, val status: String, val priority: String, val assigneeId: String?, val dueDate: String?, val overdue: Boolean)
    class Person(val id: String, val name: String, val email: String, val role: String, val title: String, val status: String)
    class Announcement(val id: String, val title: String, val body: String, val pinned: Boolean)
    class Doc(val id: String, val title: String, val visibility: String)
    class Answer(val text: String, val sources: List<String>)
    class Notice(val title: String, val body: String, val read: Boolean)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.opt(k: String): String? = if (isNull(k)) null else optString(k)

    suspend fun tasks(mine: Boolean): List<Task> =
        ApiClient.call("GET", "/v1/enterprise/tasks", query = if (mine) mapOf("mine" to "1") else emptyMap()).getJSONArray("items").objects().map {
            Task(it.getString("id"), it.getString("title"), it.optString("description"), it.getString("status"), it.getString("priority"), it.opt("assignee_id"), it.opt("due_date"), it.optBoolean("overdue"))
        }

    suspend fun createTask(title: String, description: String, assigneeId: String?, priority: String, due: String?) {
        val body = JSONObject().put("title", title).put("priority", priority)
        if (description.isNotBlank()) body.put("description", description)
        if (assigneeId != null) body.put("assignee_id", assigneeId)
        if (!due.isNullOrBlank()) body.put("due_date", due)
        ApiClient.call("POST", "/v1/enterprise/tasks", body)
    }

    suspend fun setTaskStatus(id: String, status: String) {
        ApiClient.call("POST", "/v1/enterprise/tasks/$id/status", JSONObject().put("status", status))
    }

    suspend fun people(): List<Person> =
        ApiClient.call("GET", "/v1/enterprise/employees").getJSONArray("items").objects().map {
            Person(it.getString("id"), it.optString("display_name"), it.optString("email"), it.optString("role"), it.optString("title"), it.optString("status"))
        }

    /** Returns the one-time invitation code to hand to the new employee. */
    suspend fun invite(email: String, name: String, role: String): String =
        ApiClient.call("POST", "/v1/enterprise/employees", JSONObject().put("email", email.trim()).put("display_name", name.trim()).put("role", role)).getString("invite_token")

    suspend fun setPersonStatus(id: String, status: String) {
        ApiClient.call("PUT", "/v1/enterprise/employees/$id", JSONObject().put("status", status))
    }

    suspend fun announcements(): List<Announcement> =
        ApiClient.call("GET", "/v1/enterprise/announcements").getJSONArray("items").objects().map {
            Announcement(it.getString("id"), it.getString("title"), it.optString("body"), it.optBoolean("pinned"))
        }

    suspend fun postAnnouncement(title: String, body: String, pinned: Boolean) {
        ApiClient.call("POST", "/v1/enterprise/announcements", JSONObject().put("title", title).put("body", body).put("pinned", pinned))
    }

    suspend fun documents(): List<Doc> =
        ApiClient.call("GET", "/v1/enterprise/kb/documents").getJSONArray("items").objects().map { Doc(it.getString("id"), it.getString("title"), it.optString("visibility")) }

    suspend fun addDocument(title: String, text: String, visibility: String) {
        ApiClient.call("POST", "/v1/enterprise/kb/documents", JSONObject().put("title", title).put("text", text).put("visibility", visibility))
    }

    suspend fun ask(question: String): Answer {
        val j = ApiClient.call("POST", "/v1/enterprise/kb/ask", JSONObject().put("question", question))
        val cites = j.optJSONArray("citations")?.objects()?.map { it.optString("title") }?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
        return Answer(j.getString("answer"), cites)
    }

    suspend fun overview(days: Int = 30): List<Pair<String, String>> {
        val j = ApiClient.call("GET", "/v1/enterprise/analytics/overview", query = mapOf("days" to days.toString()))
        val out = ArrayList<Pair<String, String>>()
        out.add("Total tugas" to j.optInt("total_tasks").toString())
        out.add("Tugas terbuka" to j.optInt("open_now").toString())
        out.add("Terlambat" to j.optInt("overdue_now").toString())
        out.add("Selesai ($days hari)" to j.optInt("completed_in_range").toString())
        out.add("Tingkat penyelesaian" to percent(j.optDouble("completion_rate", 0.0)))
        if (!j.isNull("on_time_rate")) out.add("Selesai tepat waktu" to percent(j.optDouble("on_time_rate")))
        if (!j.isNull("avg_cycle_days")) out.add("Rata-rata durasi (hari)" to j.optDouble("avg_cycle_days").toString())
        j.optJSONObject("ai_usage")?.let { out.add("Permintaan AI ($days hari)" to it.optInt("requests").toString()) }
        j.optJSONObject("devices")?.let { out.add("Kacamata terdaftar" to it.optInt("registered").toString()); out.add("Aktif 7 hari" to it.optInt("seen_last_7d").toString()) }
        return out
    }

    suspend fun transparency(): List<String> {
        val j = ApiClient.call("GET", "/v1/enterprise/transparency")
        val lines = ArrayList<String>()
        lines.add("YANG DIKUMPULKAN")
        j.optJSONArray("collected")?.let { a -> for (i in 0 until a.length()) lines.add("• " + a.getString(i)) }
        lines.add("YANG TIDAK DIKUMPULKAN")
        j.optJSONArray("not_collected")?.let { a -> for (i in 0 until a.length()) lines.add("• " + a.getString(i)) }
        return lines
    }

    suspend fun audit(): List<String> {
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID"))
        return ApiClient.call("GET", "/v1/enterprise/audit", query = mapOf("limit" to "40")).getJSONArray("items").objects().map {
            fmt.format(Date(it.getLong("ts"))) + "  " + (it.opt("actor_name") ?: "sistem") + "  " + it.getString("action")
        }
    }

    suspend fun verifyAudit(): String {
        val j = ApiClient.call("GET", "/v1/enterprise/audit/verify")
        return if (j.getBoolean("ok")) "Rantai audit utuh (${j.getInt("entries")} entri terverifikasi)." else "PERINGATAN: rantai audit rusak di entri #${j.optInt("broken_at_seq")}."
    }

    suspend fun notifications(): List<Notice> =
        ApiClient.call("GET", "/v1/notifications").getJSONArray("items").objects().map { Notice(it.getString("title"), it.optString("body"), !it.isNull("read_at")) }

    suspend fun markAllRead() {
        ApiClient.call("POST", "/v1/notifications/read-all", JSONObject())
    }

    private fun percent(v: Double): String = "${Math.round(v * 100)}%"
}
