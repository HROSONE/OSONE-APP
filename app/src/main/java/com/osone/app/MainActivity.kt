package com.osone.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val viewModel: OsoneViewModel by viewModels()
    private val live by lazy { LiveSession.get(application) }
    private val writing by lazy { WritingWorkspace.get(application) }
    private val updater by lazy { AppUpdater(this) }
    private var speech: TextToSpeech? = null
    private var showLive by mutableStateOf(false)
    private var showWriting by mutableStateOf(false)
    private var permissionError by mutableStateOf(false)
    private var darkMode by mutableStateOf(false)
    private var bubblePermission by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var overlayRequested = false
    private val diagnostics by lazy { AppDiagnostics.get(applicationContext) }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openLive() else { permissionError = true; diagnostics.record("Permissão", "Acesso ao microfone negado.") }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && live.active != null) LiveSessionService.command(this, LiveSessionService.CAMERA_START)
        else if (!granted) diagnostics.record("Permissão", "Acesso à câmera negado.")
    }
    private val screenPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null)
            LiveSessionService.command(this, LiveSessionService.SCREEN_START) {
                putExtra(LiveSessionService.SCREEN_RESULT, result.resultCode)
                putExtra(LiveSessionService.SCREEN_DATA, result.data)
            }
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(uri)
    }
    private val pickUpdateApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { updater.chooseApk(uri) }
    }

    private fun openLive() {
        permissionError = false
        showLive = true
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (live.active == null) LiveSessionService.command(this, LiveSessionService.START)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics.installCrashHandler()
        showLive = live.active != null
        bubblePermission = Settings.canDrawOverlays(this)
        accessibilityEnabled = OsoneAccessibilityService.active != null
        volumeControlStream = AudioManager.STREAM_MUSIC
        val preferences = getSharedPreferences("osone_config", 0)
        // Sem escolha salva, segue o tema do sistema.
        darkMode = if (preferences.contains("dark_mode")) preferences.getBoolean("dark_mode", false)
            else (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applySystemBars()
        speech = TextToSpeech(this, this)
        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var readAloud by remember { mutableStateOf(false) }
            var showDiagnostics by remember { mutableStateOf(false) }
            OstieTheme(darkMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (showLive) LiveScreen(live, diagnostics, bubblePermission, accessibilityEnabled,
                        onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onWriting = { showWriting = true; showLive = false },
                        onOverlay = {
                            if (Settings.canDrawOverlays(this)) LiveSessionService.command(this, LiveSessionService.OVERLAY_ON)
                            else {
                                overlayRequested = true
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")))
                            }
                        },
                        onShareScreen = {
                            val manager = getSystemService(MediaProjectionManager::class.java)
                            screenPermission.launch(manager.createScreenCaptureIntent())
                        },
                        onCameraToggle = {
                            if (live.cameraSharing) LiveSessionService.command(this, LiveSessionService.CAMERA_STOP)
                            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                                LiveSessionService.command(this, LiveSessionService.CAMERA_START)
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        },
                        onCameraSwitch = { LiveSessionService.command(this, LiveSessionService.CAMERA_SWITCH) },
                        onStopScreen = { LiveSessionService.command(this, LiveSessionService.SCREEN_STOP) },
                        onEnd = { LiveSessionService.command(this, LiveSessionService.STOP); showLive = false;
                            volumeControlStream = AudioManager.STREAM_MUSIC },
                        onDiagnostics = { showDiagnostics = true }, onBack = { showLive = false;
                            volumeControlStream = AudioManager.STREAM_MUSIC })
                    else if (showSettings) SettingsScreen(viewModel, live, updater, darkMode,
                        onDarkMode = { enabled ->
                            darkMode = enabled
                            applySystemBars()
                            getSharedPreferences("osone_config", 0).edit().putBoolean("dark_mode", enabled).apply()
                        }, onBack = { showSettings = false },
                        onPickUpdate = { pickUpdateApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                        diagnostics = diagnostics, onDiagnostics = { showDiagnostics = true })
                    else if (showWriting) WritingScreen(writing, live, diagnostics,
                        onDiagnostics = { showDiagnostics = true },
                        onBack = { showWriting = false },
                        onLive = {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                openLive()
                            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        })
                    else ChatScreen(viewModel, onAttach = { pickFile.launch(arrayOf("*/*")) }, onMic = {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                openLive()
                            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }, permissionError = permissionError, onSettings = { showSettings = true },
                        onWriting = { showWriting = true }, diagnostics = diagnostics, liveActive = live.active != null,
                        onDiagnostics = { showDiagnostics = true }, readAloud = readAloud,
                        onReadAloud = { readAloud = !readAloud }, onAnswer = { answer ->
                            if (readAloud) speech?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "osone_resposta")
                        })
                    if (showDiagnostics) DiagnosticsDialog(diagnostics, onClose = { showDiagnostics = false })
                }
            }
        }
    }

    /** Ícones da barra de status acompanham o modo escolhido no app, não só o do sistema. */
    private fun applySystemBars() {
        val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) speech?.language = Locale("pt", "BR")
    }
    override fun onResume() {
        super.onResume()
        bubblePermission = Settings.canDrawOverlays(this)
        accessibilityEnabled = OsoneAccessibilityService.active != null
        updater.resumeAfterPermission()
        if (overlayRequested && bubblePermission && live.active != null)
            LiveSessionService.command(this, LiveSessionService.OVERLAY_ON)
        overlayRequested = false
    }
    override fun onDestroy() { speech?.stop(); speech?.shutdown(); super.onDestroy() }
}
