package com.xnvalabs.smarteyex.data.net

import com.xnvalabs.smarteyex.data.privacy.PrivacyRepository
import com.xnvalabs.smarteyex.data.session.SessionRepository

/** Single check every cloud-calling repository runs before any data leaves the phone. */
object CloudGate {
    fun check(): String? {
        if (!PrivacyRepository.settings.value.cloudProcessingEnabled) {
            return "Cloud Processing OFF — aktifkan di Privacy Control."
        }
        if (SessionRepository.info == null) {
            return "Belum masuk ke server SmartEyeX. Masuk lewat Privacy Control › Akun & Server untuk memakai XNAI."
        }
        return null
    }
}
