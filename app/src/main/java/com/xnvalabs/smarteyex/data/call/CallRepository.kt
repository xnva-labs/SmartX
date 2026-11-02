package com.xnvalabs.smarteyex.data.call

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/** A saved quick-dial contact. */
data class QuickContact(val name: String, val phone: String)

/**
 * Call Assistant — a small quick-dial list (name + number), same
 * SharedPreferences + hand-rolled JSON pattern as ReminderRepository.
 *
 * Like EmergencyRepository, this never places a call itself:
 * CallScreen opens the dialer via ACTION_DIAL, so the user's own tap on
 * the call button is the confirmation, and no CALL_PHONE or
 * READ_CONTACTS permission is needed. Looking up the phone's real
 * contacts by name ("telpon Budi") would need READ_CONTACTS — a
 * deliberate later decision, not bundled in here.
 */
object CallRepository {
    private const val PREFS_NAME = "smarteyex_call"
    private const val KEY_CONTACTS = "contacts"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    var contacts = mutableStateOf(emptyList<QuickContact>())
        private set

    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        contacts.value = load()
        initialized = true
    }

    fun add(name: String, phone: String) = persist(contacts.value + QuickContact(name, phone))

    fun remove(contact: QuickContact) = persist(contacts.value - contact)

    private fun persist(next: List<QuickContact>) {
        contacts.value = next
        val array = JSONArray()
        next.forEach { c ->
            array.put(JSONObject().apply { put("name", c.name); put("phone", c.phone) })
        }
        prefs.edit().putString(KEY_CONTACTS, array.toString()).apply()
    }

    private fun load(): List<QuickContact> {
        val raw = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                QuickContact(obj.getString("name"), obj.getString("phone"))
            }
        }.getOrDefault(emptyList())
    }
}
