package com.xnvalabs.smarteyex.data.education

import com.xnvalabs.smarteyex.data.xnai.XnaiRepository

/**
 * Asks XNAI to break a topic into study items, then adds them to
 * [EducationRepository]. Goes through [XnaiRepository.sendMessage], so
 * the Cloud Processing toggle and the "endpoint belum diisi" checks
 * apply automatically — no separate enforcement here.
 *
 * The backend replies in free text, so the prompt asks for one item per
 * line and [parseItems] tolerates bullets/numbering the model adds
 * anyway. Nothing is added if the reply has no usable lines.
 */
object StudyPlanner {
    private const val MAX_ITEMS = 12

    suspend fun generate(topic: String): Result<Int> {
        val prompt = "Pecah topik belajar \"$topic\" menjadi $MAX_ITEMS sub-materi atau langkah belajar " +
            "yang berurutan, dari dasar ke lanjutan. Balas HANYA daftar, satu item per baris, " +
            "tanpa penomoran, tanpa penjelasan tambahan."
        return XnaiRepository.sendMessage(prompt, emptyList(), "HIGH").mapCatching { reply ->
            val items = parseItems(reply)
            if (items.isEmpty()) error("XNAI gak ngasih daftar yang bisa dipakai, coba lagi.")
            items.forEach { EducationRepository.add(it, topic) }
            items.size
        }
    }

    private fun parseItems(reply: String): List<String> =
        reply.lines()
            .map { it.trim().trimStart('-', '*', '•', '·').replace(Regex("^\\d+[.)]\\s*"), "").trim() }
            .filter { it.length in 3..120 }
            .take(MAX_ITEMS)
}
