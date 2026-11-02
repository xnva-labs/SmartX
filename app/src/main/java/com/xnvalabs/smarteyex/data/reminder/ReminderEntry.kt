package com.xnvalabs.smarteyex.data.reminder

/** A single scheduled reminder/alarm. [hour]/[minute] are in 24h local time. */
data class ReminderEntry(
    val id: Int,
    val title: String,
    val hour: Int,
    val minute: Int,
)
