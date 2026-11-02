package com.xnvalabs.smarteyex.ui.screens.translation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.translation.TranslationRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight
import kotlinx.coroutines.launch

private val LANGUAGES = listOf("Indonesian", "English", "Japanese", "Mandarin", "Spanish")

/**
 * Translation screen — feature #9. Text-only for now: type source text,
 * pick a target language, get a translation back from the same backend
 * XNAI chat and Computer Vision talk to (see
 * [TranslationRepository]'s doc comment). Real-time SPEECH translation
 * (the feature spec's "orang ngomong Inggris, SmartEyeX bilang artinya")
 * needs speech-to-text first, which isn't built yet — this is the
 * text-in/text-out foundation that a voice layer would sit on top of.
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun TranslationScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sourceText by remember { mutableStateOf("") }
    var targetLanguage by remember { mutableStateOf(LANGUAGES[1]) }
    var showLangPicker by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var isTranslating by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Translation", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp)
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                if (sourceText.isEmpty()) {
                    Text("Ketik teks yang mau diterjemahkan...", fontSize = 14.sp, color = TextMutedLight)
                }
                BasicTextField(
                    value = sourceText,
                    onValueChange = { sourceText = it },
                    textStyle = TextStyle(color = TextPrimaryLight, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(LightSurface, RoundedCornerShape(10.dp))
                        .clickable { showLangPicker = !showLangPicker }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text("Ke: ", fontSize = 13.sp, color = TextMutedLight)
                    Text(targetLanguage, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                }
                if (showLangPicker) {
                    Column(
                        modifier = Modifier
                            .padding(top = 44.dp)
                            .background(LightSurface, RoundedCornerShape(10.dp))
                            .padding(4.dp),
                    ) {
                        LANGUAGES.forEach { lang ->
                            Text(
                                lang,
                                fontSize = 13.sp,
                                color = if (lang == targetLanguage) AccentOrange else TextPrimaryLight,
                                modifier = Modifier
                                    .clickable {
                                        targetLanguage = lang
                                        showLangPicker = false
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .clickable(enabled = !isTranslating && sourceText.isNotBlank()) {
                        isTranslating = true
                        resultText = null
                        scope.launch {
                            val result = TranslationRepository.translate(sourceText.trim(), targetLanguage)
                            isTranslating = false
                            resultText = result.fold(
                                onSuccess = { it },
                                onFailure = { e -> e.message ?: "Gagal menerjemahkan." },
                            )
                        }
                    }
                    .padding(16.dp),
            ) {
                Text(
                    if (isTranslating) "Menerjemahkan..." else "Terjemahkan",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentOrange,
                )
            }

            resultText?.let { text ->
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LightSurface, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Text("HASIL", fontSize = 10.sp, color = TextMutedLight, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text, fontSize = 15.sp, color = TextPrimaryLight)
                }
            }
        }
    }
}
