package com.xnvalabs.smarteyex.ui.screens.vision

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xnvalabs.smarteyex.data.privacy.PrivacyRepository
import com.xnvalabs.smarteyex.data.vision.CaptureLedController
import com.xnvalabs.smarteyex.data.vision.VisionRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight
import kotlinx.coroutines.launch

/**
 * Camera / Computer Vision screen — Tahap 4. Shows a live CameraX
 * preview, lets the user snap a frame, and sends it to
 * [VisionRepository] for analysis (feature #7 Computer Vision, #8
 * Reading Assistant/OCR — same "model lives server-side" backend as
 * Tahap 3's XNAI chat).
 *
 * REQUIRES a Gradle dependency this file cannot add itself: CameraX
 * (camera-core, camera-camera2, camera-lifecycle, camera-view). Add
 * those to the app module's build.gradle before this compiles.
 *
 * The on-screen dot next to "REC" mirrors [CaptureLedController] —
 * feature #23 Camera Privacy Indicator's software form; a future
 * hardware build reads the same flag to drive a physical LED. It is lit
 * for exactly as long as the camera preview is actually bound: bound in
 * the AndroidView factory below, unbound in this composable's
 * DisposableEffect, never left running after the user navigates away.
 *
 * Camera access is gated on TWO things before the preview ever starts:
 * the Privacy Control camera toggle (checked here — if off, this screen
 * shows a message and never touches CameraX) and the runtime CAMERA
 * permission (requested here if not yet granted).
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun VisionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val cameraEnabled = PrivacyRepository.settings.value.cameraEnabled
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }

    LaunchedEffect(cameraEnabled) {
        if (cameraEnabled && !hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            CaptureLedController.setActive(false)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Computer Vision", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                Spacer(modifier = Modifier.weight(1f))
                if (CaptureLedController.isActive.value) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(AccentOrange, CircleShape))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("REC", fontSize = 11.sp, color = AccentOrange)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                !cameraEnabled -> {
                    Text(
                        "Camera sedang OFF — nyalain di Privacy Control buat pakai Computer Vision.",
                        fontSize = 13.sp,
                        color = AccentOrange,
                    )
                }
                !hasPermission -> {
                    Text(
                        "Nunggu izin kamera dari sistem Android...",
                        fontSize = 13.sp,
                        color = TextMutedLight,
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .background(LightSurface, RoundedCornerShape(16.dp)),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val provider = cameraProviderFuture.get()
                                    cameraProvider = provider
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    val capture = ImageCapture.Builder().build()
                                    imageCapture = capture
                                    runCatching {
                                        provider.unbindAll()
                                        provider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            capture,
                                        )
                                        CaptureLedController.setActive(true)
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LightSurface, RoundedCornerShape(14.dp))
                            .clickable(enabled = !isAnalyzing) {
                                val capture = imageCapture ?: return@clickable
                                isAnalyzing = true
                                resultText = null
                                capture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val jpeg = imageProxyToJpegBytes(image)
                                            image.close()
                                            scope.launch {
                                                val result = VisionRepository.analyzeFrame(jpeg)
                                                isAnalyzing = false
                                                resultText = result.fold(
                                                    onSuccess = { it },
                                                    onFailure = { e -> e.message ?: "Gagal menganalisis gambar." },
                                                )
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            isAnalyzing = false
                                            resultText = "Gagal mengambil gambar: ${exception.message}"
                                        }
                                    },
                                )
                            }
                            .padding(16.dp),
                    ) {
                        Text(
                            text = if (isAnalyzing) "Menganalisis..." else "📷 Ambil & Analisis",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentOrange,
                        )
                    }

                    resultText?.let { text ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LightSurface, RoundedCornerShape(14.dp))
                                .padding(16.dp),
                        ) {
                            Text(text, fontSize = 14.sp, color = TextPrimaryLight)
                        }
                    }
                }
            }
        }
    }
}

/** Converts a captured [ImageProxy] (JPEG format from ImageCapture) into raw JPEG bytes. */
private fun imageProxyToJpegBytes(image: ImageProxy): ByteArray {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}
