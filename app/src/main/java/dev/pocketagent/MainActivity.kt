package dev.pocketagent

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.pocketagent.ui.AppShell
import dev.pocketagent.ui.AppViewModel
import dev.pocketagent.ui.theme.Palette
import dev.pocketagent.ui.theme.PocketAgentTheme
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class MainActivity : FragmentActivity() {
    private val viewModel: AppViewModel by viewModels()

    private var unlocked by mutableStateOf(false)
    private var lastBackgroundAt = 0L

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = viewModel.settings.value
        Palette.apply(settings.accent, settings.background, settings.textColor)
        unlocked = !settings.appLock || savedInstanceState?.getBoolean(KEY_UNLOCKED) == true

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.settings.collect {
                    Palette.apply(it.accent, it.background, it.textColor)
                    applySystemBars()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) viewModel.handleIntent(intent)

        setContent {
            val s by viewModel.settings.collectAsStateWithLifecycle()
            PocketAgentTheme(fontScale = s.fontScale) {
                if (unlocked || !s.appLock) {
                    AppShell(viewModel, onRestartNeeded = ::restartApp)
                } else {
                    LockedScreen()
                }
            }
        }
    }

    private var barsLight: Boolean? = null

    /** Dark status-bar icons on the light background, light icons otherwise. */
    private fun applySystemBars() {
        val light = Palette.IsLight
        if (barsLight == light) return
        barsLight = light
        val style = if (light) {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val s = viewModel.settings.value
        if (s.appLock && lastBackgroundAt > 0 && System.currentTimeMillis() - lastBackgroundAt > RELOCK_AFTER_MS) unlocked = false
        if (s.appLock && !unlocked) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            lastBackgroundAt = System.currentTimeMillis()
            viewModel.onAppBackground()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, unlocked)
    }

    @androidx.compose.runtime.Composable
    private fun LockedScreen() {
        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.Background),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🔒", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(getString(R.string.unlock_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))
            Button(onClick = ::authenticate) { Text("Unlock") }
        }
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Lock was turned on but the phone no longer has a screen lock: don't strand the user.
            unlocked = true
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.unlock_title))
                .setSubtitle(getString(R.string.unlock_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    /** Called after a backup restore replaced the database. */
    private fun restartApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) startActivity(launch)
        finishAffinity()
        exitProcess(0)
    }

    companion object {
        private const val KEY_UNLOCKED = "unlocked"
        private const val RELOCK_AFTER_MS = 60_000L
    }
}
