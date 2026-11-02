package com.xnvalabs.smarteyex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBorder
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

/** Small shared building blocks for the account and organization screens (same light style as the rest of the app). */

@Composable
fun FormHeader(title: String, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (onBack != null) {
            Text("‹", fontSize = 28.sp, color = TextPrimaryLight, modifier = Modifier.clickable { onBack() }.padding(end = 14.dp))
        }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
    }
}

@Composable
fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    minLines: Int = 1,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = TextMutedLight, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(LightSurface, RoundedCornerShape(12.dp))
                .border(1.dp, LightBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = TextPrimaryLight, fontSize = 14.sp),
                cursorBrush = SolidColor(AccentOrange),
                singleLine = minLines == 1,
                minLines = minLines,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (enabled) AccentOrange else LightBorder, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LightSurface)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, LightBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
    }
}

@Composable
fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(LightSurface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun ErrorNote(text: String?) {
    if (text != null) Text(text, fontSize = 12.sp, color = AccentOrange)
}

@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, color = TextMutedLight, fontWeight = FontWeight.Bold)
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) AccentOrange else LightSurface, RoundedCornerShape(50))
            .border(1.dp, if (selected) AccentOrange else LightBorder, RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selected) LightSurface else TextPrimaryLight)
    }
}
