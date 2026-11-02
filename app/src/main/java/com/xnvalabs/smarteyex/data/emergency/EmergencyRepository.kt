package com.xnvalabs.smarteyex.data.emergency

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/** A single saved emergency contact. */
data class EmergencyContact(val name: String, val phone: String)

/**
 * Feature #30 (Emergency Assistance) — stores one emergency contact,
 * same SharedPreferences pattern as every other repository here.
 *
 * Deliberately does NOT place a call or send an SMS itself.
 * EmergencyScreen launches Intent.ACTION_DIAL (opens the phone dialer
 * pre-filled with the saved number) rather than ACTION_CALL — ACTION_DIAL
 * needs no CALL_PHONE permission, and more importantly it always leaves
 * the user tapping the actual call button themselves. That tap IS the
 * "confirmation logic" the feature spec calls for ("harus dibuat dengan
 * confirmation logic, supaya tidak menelepon polisi hanya gara-gara
 * pengguna bersin") — simpler and more reliable than trying to build a
 * custom confirm-dialog around a real auto-dial.
 */
object EmergencyRepository {
    private const val PREFS_NAME = "smarteyex_emergency"
    private const val KEY_NAME = "contact_name"
    private const val KEY_PHONE = "contact_phone"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    var contact = mutableStateOf<EmergencyContact?>(null)
        private set

    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null)
        val phone = prefs.getString(KEY_PHONE, null)
        contact.value = if (name != null && phone != null) EmergencyContact(name, phone) else null
        initialized = true
    }

    fun save(name: String, phone: String) {
        prefs.edit().putString(KEY_NAME, name).putString(KEY_PHONE, phone).apply()
        contact.value = EmergencyContact(name, phone)
    }

    fun clear() {
        prefs.edit().remove(KEY_NAME).remove(KEY_PHONE).apply()
        contact.value = null
    }
}
