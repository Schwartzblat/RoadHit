package com.roadhit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.roadhit.game.GameSettings
import com.roadhit.game.Lang
import com.roadhit.game.Phase
import com.roadhit.game.Prefs
import com.roadhit.session.AppStores
import com.roadhit.session.GameService
import com.roadhit.session.appStores
import com.roadhit.session.defaultLang
import com.roadhit.session.updatePrefsLater
import com.roadhit.session.updateSettingsLater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone app: the game screen and the settings screen (a two-screen state, no navigation
 * library). The game itself runs in [GameService]; this only mirrors [GameService.view], starts
 * and stops the game, and edits the shared preferences ([appStores]).
 */
class MainActivity : ComponentActivity() {

    private enum class Screen { GAME, SETTINGS }

    /** The shared stores once opened; null while opening or after a failed open (then the defaults show). */
    private val stores = MutableStateFlow<AppStores?>(null)

    /** Main thread only: an open of the stores is under way (Compose state: the settings screen shows it). */
    private var storesOpening by mutableStateOf(false)

    /** Set when Start was tapped and the microphone permission was then refused. */
    private val micDenied = MutableStateFlow(false)

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true || hasMic()) {
            startGame()
        } else {
            Log.i(TAG, "ui: microphone permission refused")
            micDenied.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fresh = savedInstanceState == null
        micDenied.value = savedInstanceState?.getBoolean(KEY_MIC_DENIED) ?: false
        val fallback = Prefs(lang = defaultLang(this))

        lifecycleScope.launch {
            val s = openStores() ?: return@launch // no prefs, so no auto-start
            // Auto-start only on a fresh open, never on recreation (rotation, process restore).
            if (fresh) withResumed {
                if (s.prefs.value.autoStartOnOpen && hasMic() && GameService.view.value.phase == Phase.IDLE) {
                    Log.i(TAG, "ui: auto-start on open")
                    startGame()
                }
            }
        }
        lifecycleScope.launch {
            GameService.view.collect { v ->
                if (isGameActive(v.phase)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        setContent {
            val loaded by stores.collectAsState()
            val prefs = loaded?.prefs?.collectAsState()?.value ?: fallback
            val view by GameService.view.collectAsState()
            val denied by micDenied.collectAsState()
            var screen by rememberSaveable { mutableStateOf(Screen.GAME) }
            var confirmingStop by rememberSaveable { mutableStateOf(false) }
            val strings = uiStringsFor(prefs.lang)
            val model = screenModel(view, prefs.lang)

            // A game that ends by itself closes a pending stop confirmation.
            LaunchedEffect(model is GameScreenModel.InGame) { if (model !is GameScreenModel.InGame) confirmingStop = false }

            RoadHitTheme(prefs.lang) {
                val current = loaded
                if (screen == Screen.SETTINGS && current != null) {
                    val nextGame by current.settings.collectAsState()
                    SettingsScreen(
                        prefs = prefs,
                        game = nextGame ?: GameSettings(),
                        strings = strings,
                        onChange = current::updatePrefsLater,
                        onGameChange = current::updateSettingsLater,
                        onBack = { screen = Screen.GAME },
                    )
                } else if (screen == Screen.SETTINGS) {
                    // Still opening, or the open failed (a retry runs on each visit and replaces this on success).
                    SettingsUnavailable(strings, failed = !storesOpening, onBack = { screen = Screen.GAME })
                } else {
                    GameScreen(
                        model = model,
                        strings = strings,
                        micDenied = denied,
                        confirmingStop = confirmingStop,
                        onStart = ::onStartTapped,
                        onStop = {
                            Log.i(TAG, "ui: stop confirmed")
                            GameService.stop(this)
                        },
                        onConfirmStop = { confirmingStop = it },
                        onSettings = {
                            screen = Screen.SETTINGS
                            if (stores.value == null) lifecycleScope.launch { openStores() }
                        },
                        onOpenAppSettings = ::openAppSettings,
                        onDismissMicDenied = { micDenied.value = false },
                    )
                }
            }
        }
    }

    /**
     * Opens the shared stores into [stores]; null if that fails (logged), so the screens keep
     * working on the default prefs. Main thread; a call while an open is under way returns null.
     */
    private suspend fun openStores(): AppStores? {
        stores.value?.let { return it }
        if (storesOpening) return null
        storesOpening = true
        return try {
            appStores().also { stores.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ui: the stores could not be opened", e)
            null
        } finally {
            storesOpening = false
        }
    }

    override fun onStart() {
        super.onStart()
        PhoneUi.started.incrementAndGet()
    }

    override fun onStop() {
        PhoneUi.started.decrementAndGet()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_MIC_DENIED, micDenied.value)
    }

    private fun hasMic() = granted(Manifest.permission.RECORD_AUDIO)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Asks for what is missing (microphone; notifications, which do not block the game), then starts. */
    private fun onStartTapped() {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).filterNot(::granted)
        if (missing.isEmpty()) startGame() else permissionRequest.launch(missing.toTypedArray())
    }

    private fun startGame() {
        micDenied.value = false
        Log.i(TAG, "ui: start")
        GameService.start(this)
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    private companion object {
        const val TAG = "RoadHit"
        const val KEY_MIC_DENIED = "micDenied"
    }
}

/** Whether the phone's game screen is on screen; the car's disconnect then leaves the game running on the phone. */
object PhoneUi {
    /** Started [MainActivity] instances (a recreation briefly overlaps two). */
    internal val started = AtomicInteger(0)

    val visible: Boolean get() = started.get() > 0
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B4FD8),
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001257),
    secondaryContainer = Color(0xFFD5D5DE),
    onSecondaryContainer = Color(0xFF111111),
    tertiaryContainer = Color(0xFFFFE9B0),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color.White,
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEFEFF3),
    onSurfaceVariant = Color(0xFF2B2B33),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB6FF),
    onPrimary = Color(0xFF00105C),
    primaryContainer = Color(0xFF2A47A8),
    onPrimaryContainer = Color.White,
    secondaryContainer = Color(0xFF3A3A44),
    onSecondaryContainer = Color.White,
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFF3D6),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E24),
    onSurfaceVariant = Color(0xFFE2E2EA),
)

/** High-contrast colours (following the system's dark mode) and the app language's direction. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RoadHitTheme(lang: Lang, content: @Composable () -> Unit) {
    val direction = if (lang == Lang.HE) LayoutDirection.Rtl else LayoutDirection.Ltr
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            // Test tags double as resource ids, so `uiautomator dump` finds the controls.
            Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, content = content)
        }
    }
}
