package dev.pocketagent.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.agent.ItemRole
import dev.pocketagent.ui.theme.Palette

private enum class Phase { LISTENING, THINKING, SPEAKING, PAUSED }

/**
 * Hands-free conversation: listens, sends what it heard, reads the reply
 * aloud, then listens again. Tap the avatar to interrupt; the mic button
 * pauses and resumes.
 */
@Composable
fun VoiceMode(viewModel: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val avatarVersion by viewModel.avatarVersion.collectAsStateWithLifecycle()
    val speaker = remember { Speaker(context) }
    val listener = remember { Listener(context) }
    val heard by listener.state.collectAsStateWithLifecycle()
    var phase by remember { mutableStateOf(Phase.PAUSED) }
    var sentAt by remember { mutableIntStateOf(-1) }
    var quietTries by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var lastReply by remember { mutableStateOf("") }

    fun listen() {
        speaker.stop()
        note = null
        phase = Phase.LISTENING
        listener.start()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else note = "${settings.assistantName} needs the microphone for voice mode."
    }

    fun begin() {
        if (!listener.available()) {
            note = "No speech recognizer on this phone. Install or enable Google speech services."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) { begin() }
    DisposableEffect(Unit) {
        onDispose {
            listener.stop()
            speaker.shutdown()
        }
    }
    BackHandler { onClose() }

    LaunchedEffect(heard) {
        when (val h = heard) {
            is Listener.State.Heard -> {
                quietTries = 0
                listener.stop()
                phase = Phase.THINKING
                sentAt = chat.items.size
                viewModel.send(h.text)
            }
            is Listener.State.Failed -> if (phase == Phase.LISTENING) {
                if (h.silent && quietTries < 2) {
                    quietTries++
                    listen()
                } else {
                    quietTries = 0
                    phase = Phase.PAUSED
                    note = h.message + " Tap the mic to talk."
                }
            }
            else -> Unit
        }
    }

    // When the reply finishes, read it out and listen again.
    LaunchedEffect(chat.running, chat.items.size) {
        if (phase != Phase.THINKING || chat.running || sentAt < 0) return@LaunchedEffect
        val reply = chat.items.drop(sentAt).lastOrNull { it.role == ItemRole.ASSISTANT && !it.streaming }?.text
        if (reply.isNullOrBlank()) return@LaunchedEffect
        lastReply = reply
        sentAt = -1
        phase = Phase.SPEAKING
        speaker.speak(reply) { if (phase == Phase.SPEAKING) listen() }
    }

    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, if (phase == Phase.LISTENING || phase == Phase.SPEAKING) 1.08f else 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledIconButton(
                onClick = onClose,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.Text)
            ) { Icon(Icons.Filled.Close, "Close voice mode") }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .scale(pulse.value)
                .clip(CircleShape)
                .clickable { if (phase == Phase.SPEAKING || phase == Phase.PAUSED) listen() },
            contentAlignment = Alignment.Center
        ) {
            ActivityAvatar(
                settings.avatarKind, settings.avatarValue, settings.assistantName,
                viewModel.avatarPhoto(settings), 180.dp,
                activity = when (phase) {
                    Phase.THINKING -> chat.activity ?: "thinking"
                    else -> chat.activity
                },
                photoVersion = avatarVersion
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            when (phase) {
                Phase.LISTENING -> "Listening…"
                Phase.THINKING -> "Thinking…"
                Phase.SPEAKING -> "Speaking · tap to interrupt"
                Phase.PAUSED -> "Paused"
            },
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextMuted
        )
        Spacer(Modifier.height(12.dp))
        val caption = when {
            note != null -> note!!
            phase == Phase.LISTENING -> (heard as? Listener.State.Listening)?.partial.orEmpty()
            phase == Phase.SPEAKING -> lastReply
            else -> ""
        }
        Text(
            Speaker.forSpeech(caption).take(400),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = Palette.Text,
            maxLines = 8
        )
        Spacer(Modifier.weight(1f))
        FilledIconButton(
            onClick = {
                if (phase == Phase.LISTENING) {
                    listener.stop()
                    phase = Phase.PAUSED
                } else {
                    begin()
                }
            },
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (phase == Phase.LISTENING) Palette.Accent else Palette.SurfaceHigh,
                contentColor = if (phase == Phase.LISTENING) Color.Black else Palette.Text
            )
        ) { Icon(if (phase == Phase.LISTENING) Icons.Filled.Mic else Icons.Filled.MicOff, "Talk") }
        Spacer(Modifier.height(8.dp))
        Text("Hands-free: it listens again after each answer", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
    }
}
