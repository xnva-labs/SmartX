package com.xnvalabs.smarteyex.data.memory

/**
 * What kind of thing a [MemoryEntry] represents. PREFERENCE was added
 * for feature #31 (Personalization) — "Gue lebih suka jawaban singkat"
 * style entries that shape how XNAI responds, not what it remembers
 * about the user's identity (that's PROFILE) or a one-off note (NOTE).
 */
enum class MemoryType { PROFILE, NOTE, PERSON, TASK, PREFERENCE }

/**
 * A single remembered thing — a profile field, a quick note, a person, a
 * task, or a preference. [id] is stable per entry so it can be targeted
 * for deletion; [title] doubles as the upsert key for PROFILE entries
 * (see MemoryRepository.upsertProfileField).
 */
data class MemoryEntry(
    val id: String,
    val type: MemoryType,
    val title: String,
    val content: String,
    val timestamp: Long,
)
