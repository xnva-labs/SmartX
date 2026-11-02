package com.xnvalabs.smarteyex.data.glasses

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Turns text into 16 kHz mono PCM using the phone's own text-to-speech engine, so it can be streamed to the glasses'
 * bone-conduction speaker. Synthesis happens fully on the phone; no text is sent anywhere for this step.
 */
object PhoneTts {
    suspend fun synthesize(context: Context, text: String): ShortArray? = suspendCancellableCoroutine { cont ->
        val file = File(context.cacheDir, "tts_" + System.nanoTime() + ".wav")
        var tts: TextToSpeech? = null
        fun finish(result: ShortArray?) {
            if (cont.isActive) cont.resume(result)
            tts?.shutdown()
            file.delete()
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                finish(null)
            } else {
                val lang = engine.setLanguage(Locale("id", "ID"))
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) engine.setLanguage(Locale.getDefault())
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        finish(runCatching { GlassesProtocol.wavToPcm16k(file.readBytes()) }.getOrNull())
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        finish(null)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        finish(null)
                    }
                })
                if (engine.synthesizeToFile(text.take(1200), null, file, "xnai") != TextToSpeech.SUCCESS) finish(null)
            }
        }
        cont.invokeOnCancellation {
            tts?.shutdown()
            file.delete()
        }
    }
}
