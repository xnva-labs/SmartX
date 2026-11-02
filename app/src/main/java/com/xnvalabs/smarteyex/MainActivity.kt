package com.xnvalabs.smarteyex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.xnvalabs.smarteyex.data.auth.AuthRepository
import com.xnvalabs.smarteyex.data.call.CallRepository
import com.xnvalabs.smarteyex.data.education.EducationRepository
import com.xnvalabs.smarteyex.data.emergency.EmergencyRepository
import com.xnvalabs.smarteyex.data.glasses.GlassesRepository
import com.xnvalabs.smarteyex.data.session.SessionRepository
import com.xnvalabs.smarteyex.data.enterprise.EnterpriseRepository
import com.xnvalabs.smarteyex.data.memory.MemoryRepository
import com.xnvalabs.smarteyex.data.privacy.PrivacyRepository
import com.xnvalabs.smarteyex.data.reminder.ReminderRepository
import com.xnvalabs.smarteyex.ui.screens.account.AccountScreen
import com.xnvalabs.smarteyex.ui.screens.account.WelcomeScreen
import com.xnvalabs.smarteyex.ui.screens.activation.ActivationScreen
import com.xnvalabs.smarteyex.ui.screens.auth.PinLockScreen
import com.xnvalabs.smarteyex.ui.screens.auth.PinMode
import com.xnvalabs.smarteyex.ui.screens.call.CallScreen
import com.xnvalabs.smarteyex.ui.screens.device.DeviceScreen
import com.xnvalabs.smarteyex.ui.screens.emergency.EmergencyScreen
import com.xnvalabs.smarteyex.ui.screens.enterprise.EnterpriseScreen
import com.xnvalabs.smarteyex.ui.screens.library.LibraryScreen
import com.xnvalabs.smarteyex.ui.screens.listener.NotificationListenerScreen
import com.xnvalabs.smarteyex.ui.screens.loading.LoadingScreen
import com.xnvalabs.smarteyex.ui.screens.media.MediaScreen
import com.xnvalabs.smarteyex.ui.screens.memory.MemoryScreen
import com.xnvalabs.smarteyex.ui.screens.org.OrgGate
import com.xnvalabs.smarteyex.ui.screens.org.OrgScreen
import com.xnvalabs.smarteyex.ui.screens.navigation.NavigationScreen
import com.xnvalabs.smarteyex.ui.screens.privacy.PrivacySettingsScreen
import com.xnvalabs.smarteyex.ui.screens.profile.ProfileScreen
import com.xnvalabs.smarteyex.ui.screens.progress.ProgressScreen
import com.xnvalabs.smarteyex.ui.screens.reminder.ReminderScreen
import com.xnvalabs.smarteyex.ui.screens.system.SystemScreen
import com.xnvalabs.smarteyex.ui.screens.translation.TranslationScreen
import com.xnvalabs.smarteyex.ui.screens.vision.VisionScreen
import com.xnvalabs.smarteyex.ui.screens.xnai.XNAICoreScreen
import com.xnvalabs.smarteyex.ui.theme.SmartEyeXTheme

/**
 * MainActivity — single entry point for the native app.
 *
 * Navigation is a plain `when` over a local enum, not Navigation Compose.
 * With only a handful of screens built so far and no back-stack
 * requirements yet (every screen so far returns straight to System),
 * pulling in the Navigation Compose dependency would be premature — this
 * can be upgraded later once enough screens exist that a real back stack
 * / deep links actually matter.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tahap 1 (Privacy) must init before Tahap 2 (Memory), since
        // MemoryRepository checks PrivacyRepository on every write.
        PrivacyRepository.init(applicationContext)
        MemoryRepository.init(applicationContext)
        ReminderRepository.init(applicationContext)
        AuthRepository.init(applicationContext)
        EmergencyRepository.init(applicationContext)
        CallRepository.init(applicationContext)
        EducationRepository.init(applicationContext)
        EnterpriseRepository.init(applicationContext)
        // Server session (tokens are Keystore-encrypted) and the glasses link. Cloud consent set in Privacy Control is
        // mirrored to the server, which enforces it independently.
        SessionRepository.init(applicationContext)
        GlassesRepository.init(applicationContext)
        PrivacyRepository.onCloudChanged = { enabled ->
            lifecycleScope.launch { runCatching { SessionRepository.pushCloudConsent(enabled) } }
        }
        // MediaRepository / TranslationRepository / DeviceStatusRepository
        // have no init() — all stateless or session/pull-scoped, nothing
        // to load at startup.
        setContent {
            SmartEyeXTheme {
                SmartEyeXApp()
            }
        }
    }
}

/** Screens that exist so far. Extend this as each new screen is built. */
private enum class Screen {
    Loading, PinUnlock, Activation, System, Profile, XnaiCore, Listener,
    PrivacySettings, PinSetup, Memory, Vision, Reminder, Media, Translation,
    Device, Emergency, Navigation, Call, Library, Progress, Enterprise,
    Welcome, Account, LocalBoard,
}

@Composable
private fun SmartEyeXApp() {
    var currentScreen by remember { mutableStateOf(Screen.Loading) }

    when (currentScreen) {
        Screen.Loading -> LoadingScreen(
            onFinished = {
                // Feature #26: if a PIN is set, it gates entry every
                // process start (AuthRepository.isUnlocked resets to
                // false on fresh launch) — otherwise skip straight to
                // Activation, same as before this feature existed.
                currentScreen = if (AuthRepository.isUnlocked.value) Screen.Activation else Screen.PinUnlock
            },
        )

        Screen.PinUnlock -> PinLockScreen(
            mode = PinMode.UNLOCK,
            onDone = { currentScreen = Screen.Activation },
        )

        Screen.Activation -> ActivationScreen(
            // First run: choose Personal or Enterprise (and sign in, or continue without an account).
            onActivate = { currentScreen = if (SessionRepository.welcomeSeen) Screen.System else Screen.Welcome },
        )

        Screen.Welcome -> WelcomeScreen(
            onDone = { currentScreen = Screen.System },
        )

        Screen.Account -> AccountScreen(
            onBack = { currentScreen = Screen.PrivacySettings },
            onOpenWelcome = { currentScreen = Screen.Welcome },
        )

        Screen.System -> SystemScreen(
            onNodeSelected = { nodeId ->
                when (nodeId) {
                    "profile" -> currentScreen = Screen.Profile
                    "xnai" -> currentScreen = Screen.XnaiCore
                    "listener" -> currentScreen = Screen.Listener
                    "memory" -> currentScreen = Screen.Memory
                    "vision" -> currentScreen = Screen.Vision
                    "schedule" -> currentScreen = Screen.Reminder
                    "media" -> currentScreen = Screen.Media
                    "translation" -> currentScreen = Screen.Translation
                    "device" -> currentScreen = Screen.Device
                    "emergency" -> currentScreen = Screen.Emergency
                    "navigation" -> currentScreen = Screen.Navigation
                    "call" -> currentScreen = Screen.Call
                    "library" -> currentScreen = Screen.Library
                    "progress" -> currentScreen = Screen.Progress
                    "enterprise" -> currentScreen = Screen.Enterprise
                }
                // Each new destination gets an added branch here as
                // it's built — same incremental pattern as every screen
                // so far.
            },
            onOpenSettings = {
                currentScreen = Screen.PrivacySettings
            },
        )

        Screen.Profile -> ProfileScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.XnaiCore -> XNAICoreScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Listener -> NotificationListenerScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.PrivacySettings -> PrivacySettingsScreen(
            onBack = { currentScreen = Screen.System },
            onSetPin = { currentScreen = Screen.PinSetup },
            onOpenAccount = { currentScreen = Screen.Account },
        )

        Screen.PinSetup -> PinLockScreen(
            mode = PinMode.SETUP,
            onDone = { currentScreen = Screen.PrivacySettings },
            onCancel = { currentScreen = Screen.PrivacySettings },
        )

        Screen.Memory -> MemoryScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Vision -> VisionScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Reminder -> ReminderScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Media -> MediaScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Translation -> TranslationScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Device -> DeviceScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Emergency -> EmergencyScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Navigation -> NavigationScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Call -> CallScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Library -> LibraryScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Progress -> ProgressScreen(
            onBack = { currentScreen = Screen.System },
        )

        Screen.Enterprise -> {
            val session = SessionRepository.info
            if (session?.realm == SessionRepository.Realm.ENTERPRISE) {
                OrgScreen(
                    onBack = { currentScreen = Screen.System },
                    onOpenLocalBoard = { currentScreen = Screen.LocalBoard },
                )
            } else {
                OrgGate(
                    onBack = { currentScreen = Screen.System },
                    onSignIn = { currentScreen = if (session == null) Screen.Welcome else Screen.Account },
                    onOpenLocalBoard = { currentScreen = Screen.LocalBoard },
                )
            }
        }

        // The original single-device task board is kept as-is (works offline, no account).
        Screen.LocalBoard -> EnterpriseScreen(
            onBack = { currentScreen = Screen.Enterprise },
        )
    }
}
