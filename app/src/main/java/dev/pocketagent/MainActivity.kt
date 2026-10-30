package dev.pocketagent

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dev.pocketagent.ui.ChatScreen
import dev.pocketagent.ui.ChatViewModel
import dev.pocketagent.ui.theme.PocketAgentTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            PocketAgentTheme {
                ChatScreen(viewModel)
            }
        }
    }
}
