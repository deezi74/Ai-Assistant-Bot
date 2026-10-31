package dev.pocketagent.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.agent.ChatItem
import dev.pocketagent.agent.ItemRole
import dev.pocketagent.agent.TerminalKind
import dev.pocketagent.agent.TerminalLine
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.llm.LocalModelState
import dev.pocketagent.ui.theme.Mono
import dev.pocketagent.ui.theme.Palette
import java.io.File
import kotlin.math.roundToInt

private val starterPrompts = listOf(
    "Plan my day around three priorities",
    "What's happening in tech today?",
    "Roast my to-do list",
    "Explain something I should know by now"
)

@Composable
fun ChatTab(viewModel: AppViewModel, showTerminal: Boolean, onHideTerminal: () -> Unit) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val local by viewModel.localState.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val pending by viewModel.pendingImage.collectAsStateWithLifecycle()
    val avatarVersion by viewModel.avatarVersion.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    var taskMode by rememberSaveable { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val speaker = remember { Speaker(context, viewModel.cloudVoice) }
    val speakingNow by speaker.speaking.collectAsStateWithLifecycle()
    var speakingId by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraUri?.let { if (ok) viewModel.attachImage(it) }
    }
    // The app declares CAMERA (for live camera in voice mode), so Android requires it to be
    // granted before another app's camera can be opened too.
    fun openCamera() {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        val file = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        cameraUri = uri
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            viewModel.toast("No camera app found")
        } catch (e: SecurityException) {
            viewModel.toast("Camera permission is needed")
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else viewModel.toast("Camera permission is needed to take a photo")
    }
    val accent = if (settings.mode == AgentMode.WIT) Palette.Accent else Palette.Research

    LaunchedEffect(draft) {
        draft?.let {
            input = it
            viewModel.consumeDraft()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.attachImage(uri)
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) input = (input.trimEnd() + " " + heard).trim()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()
        Column(Modifier.fillMaxSize()) {
            if (chat.items.isEmpty()) {
                EmptyChat(
                    name = settings.assistantName,
                    status = modelLabel(local, chat.providerLabel),
                    onPick = { input = it },
                    modifier = Modifier.weight(1f),
                    setup = {
                        val needsModel = (local is LocalModelState.Idle || local is LocalModelState.Error) &&
                            !viewModel.hasToken(settings.remoteKind)
                        if (needsModel) {
                            Text(
                                "Get ${settings.assistantName} a brain: download the model that fits your phone.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.TextMuted
                            )
                            Spacer(Modifier.height(8.dp))
                            dev.pocketagent.ui.settings.RecommendedModelCard(viewModel, viewModel.bestModel, highlight = true)
                            Text(
                                "More models",
                                color = Palette.Accent,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clickable { viewModel.push("models") }
                                    .padding(10.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    },
                    avatar = {
                        ActivityAvatar(
                            settings.avatarKind, settings.avatarValue, settings.assistantName,
                            viewModel.avatarPhoto(settings), 96.dp, activity = chat.activity, photoVersion = avatarVersion
                        )
                    }
                )
            } else {
                MessageList(
                    chat.items, accent, Modifier.weight(1f),
                    speakingId = if (speakingNow) speakingId else null,
                    onSpeak = { item ->
                        if (speakingNow && speakingId == item.id) speaker.stop() else {
                            speakingId = item.id
                            speaker.speak(item.text)
                        }
                    },
                    onCopy = { text ->
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("reply", text))
                        viewModel.toast("Copied")
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActivityAvatar(
                            settings.avatarKind, settings.avatarValue, settings.assistantName,
                            viewModel.avatarPhoto(settings), 30.dp, activity = chat.activity ?: "thinking", photoVersion = avatarVersion
                        )
                        Text(
                            (activityCaption(chat.activity) ?: "thinking") + "…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.TextMuted
                        )
                    }
                }
            }
            pending?.let { file -> PendingAttachment(file, onRemove = viewModel::clearPendingImage) }
            if (taskMode) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Bolt, null, tint = accent, modifier = Modifier.size(18.dp))
                    Text(
                        " Agent task: it plans, works through the steps on its own and saves the result as a file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextMuted,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { taskMode = false }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, "Cancel task mode", tint = Palette.TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Composer(
                value = input,
                onValueChange = { input = it },
                running = chat.running,
                accent = accent,
                placeholder = if (taskMode) "Describe the task" else "Message ${settings.assistantName}",
                canSend = input.isNotBlank() || pending != null,
                onAttach = { attachMenu = true },
                attachMenu = {
                    androidx.compose.material3.DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Photo") },
                            leadingIcon = { Icon(Icons.Filled.Image, null) },
                            onClick = {
                                attachMenu = false
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Camera") },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                            onClick = {
                                attachMenu = false
                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) openCamera() else cameraPermission.launch(android.Manifest.permission.CAMERA)
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Agent task") },
                            leadingIcon = { Icon(Icons.Filled.Bolt, null) },
                            onClick = {
                                attachMenu = false
                                taskMode = true
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Imagine an image") },
                            leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                            onClick = {
                                attachMenu = false
                                input = if (pending != null) "Edit this photo: " else "Imagine "
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Make a video") },
                            leadingIcon = { Icon(Icons.Filled.Movie, null) },
                            onClick = {
                                attachMenu = false
                                input = if (pending != null) "Animate this photo into a video: " else "Make a video of "
                            }
                        )
                    }
                },
                onVoice = { voiceOpen = true },
                onMic = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to ${settings.assistantName}")
                    try {
                        speech.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        viewModel.toast("No speech recognizer is installed on this phone")
                    }
                },
                onSend = {
                    if (taskMode) viewModel.startTask(input) else viewModel.send(input)
                    input = ""
                    taskMode = false
                },
                onStop = viewModel::stop
            )
        }

        if (voiceOpen) VoiceMode(viewModel) { voiceOpen = false }

        AnimatedVisibility(visible = !voiceOpen && showTerminal && settings.showTerminal && (chat.terminal.isNotEmpty() || chat.running)) {
            AgentTerminal(
                lines = chat.terminal,
                running = chat.running,
                step = chat.step,
                maxWidthPx = maxWidthPx,
                maxHeightPx = maxHeightPx,
                onClear = viewModel::clearTerminal,
                onClose = onHideTerminal
            )
        }
    }
}

fun modelLabel(local: LocalModelState, provider: String): String = when {
    provider.isNotBlank() -> provider
    local is LocalModelState.Ready -> "On-device · ${local.fileName}"
    local is LocalModelState.Loading -> "Loading ${local.fileName}…"
    local is LocalModelState.Error -> "Model error: ${local.message}"
    else -> "No model yet · Settings ▸ Models"
}

@Composable
private fun EmptyChat(
    name: String,
    status: String,
    onPick: (String) -> Unit,
    modifier: Modifier,
    setup: @Composable () -> Unit,
    avatar: @Composable () -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        avatar()
        Spacer(Modifier.height(16.dp))
        Text("Hey, I'm $name.", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
        Spacer(Modifier.height(24.dp))
        setup()
        starterPrompts.forEach { p ->
            Text(
                text = p,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.Surface)
                    .border(1.dp, Palette.Outline, RoundedCornerShape(20.dp))
                    .clickable { onPick(p) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun MessageList(
    items: List<ChatItem>,
    accent: Color,
    modifier: Modifier,
    speakingId: Long?,
    onSpeak: (ChatItem) -> Unit,
    onCopy: (String) -> Unit,
    working: @Composable () -> Unit
) {
    val listState = rememberLazyListState()
    val lastLength = items.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(items.size, lastLength) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { it.id }) { item ->
            when (item.role) {
                ItemRole.USER -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    item.imagePath?.let { path -> ChatImage(File(path)) }
                    Bubble(item.text, Palette.SurfaceHigh, Alignment.CenterEnd, Palette.Text)
                }
                ItemRole.ASSISTANT -> if (item.streaming && item.text.isEmpty()) working() else Column(Modifier.fillMaxWidth()) {
                    item.imagePath?.let { path -> GeneratedImage(File(path)) }
                    Bubble(
                        text = item.text + if (item.streaming) "▍" else "",
                        background = Color.Transparent,
                        alignment = Alignment.CenterStart,
                        color = if (item.isError) Palette.Error else Palette.Text,
                        border = null
                    )
                    if (!item.streaming && !item.isError && item.text.isNotBlank()) {
                        Row {
                            IconButton(onClick = { onSpeak(item) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    if (speakingId == item.id) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                    if (speakingId == item.id) "Stop reading" else "Read aloud",
                                    tint = Palette.TextMuted, modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = { onCopy(item.text) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.ContentCopy, "Copy", tint = Palette.TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                ItemRole.TOOL -> Column(Modifier.fillMaxWidth()) {
                    item.imagePath?.let { path -> GeneratedImage(File(path)) }
                    ToolChip(item, accent)
                }
            }
        }
    }
}

/** A picture the assistant made, shown large; tap to open it full screen in the gallery app. */
@Composable
private fun GeneratedImage(file: File) {
    if (file.name.endsWith(".mp4", ignoreCase = true)) {
        GeneratedVideo(file)
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val image = rememberFileImage(file, maxDim = 1080)
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "Generated image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable {
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }
                }
        )
    }
}

/** An AI video in the chat: plays inline (tap to pause or play), with buttons to open full screen or share. */
@Composable
private fun GeneratedVideo(file: File) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!file.isFile) return
    fun uri() = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    var view by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.widget.VideoView?>(null) }
    androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 4.dp).widthIn(max = 360.dp)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoPath(file.absolutePath)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        val w = mp.videoWidth.coerceAtLeast(1)
                        val h = mp.videoHeight.coerceAtLeast(1)
                        layoutParams = layoutParams?.apply { height = (width * h / w).coerceAtMost((ctx.resources.displayMetrics.heightPixels * 0.6f).toInt()) }
                        requestLayout()
                        seekTo(1)
                    }
                    setOnClickListener { if (isPlaying) pause() else start() }
                    view = this
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp)
                .clip(RoundedCornerShape(18.dp))
        )
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { view?.let { if (it.isPlaying) it.pause() else it.start() } }) { Text("Play / pause") }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }) { Text("Full screen") }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Share video"
                        )
                    )
                }
            }) { Text("Share") }
        }
    }
    androidx.compose.runtime.DisposableEffect(file) { onDispose { view?.stopPlayback() } }
}

@Composable
private fun ChatImage(file: File) {
    val image = rememberFileImage(file, maxDim = 720)
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "Attached image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .size(180.dp)
                .clip(RoundedCornerShape(16.dp))
        )
    }
}

@Composable
private fun Bubble(text: String, background: Color, alignment: Alignment, color: Color, border: Color? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        val shape = RoundedCornerShape(20.dp)
        SelectionContainer {
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .widthIn(max = if (background == Color.Transparent) 600.dp else 320.dp)
                    .clip(shape)
                    .background(background)
                    .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
                    .padding(horizontal = if (background == Color.Transparent) 4.dp else 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun ToolChip(item: ChatItem, accent: Color) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    item.toolName == "growth" -> "🌱 growing"
                    item.isError -> "✗ ${item.toolName ?: "tool"}"
                    else -> "⚙ ${item.toolName ?: "tool"}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (item.isError) Palette.Error else accent,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = Palette.TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = if (expanded) item.text else item.text.lineSequence().take(2).joinToString(" ").take(140),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextMuted
        )
    }
}

@Composable
private fun PendingAttachment(file: File, onRemove: () -> Unit) {
    val image = rememberFileImage(file, maxDim = 256)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "Image to send",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.SurfaceHigh)
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Palette.SurfaceHigh)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, "Remove image", modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    accent: Color,
    placeholder: String,
    canSend: Boolean,
    onAttach: () -> Unit,
    attachMenu: @Composable () -> Unit,
    onVoice: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, RoundedCornerShape(28.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box {
            IconButton(onClick = onAttach) { Icon(Icons.Filled.Add, "Attach or imagine", tint = Palette.TextMuted) }
            attachMenu()
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp, max = 160.dp),
            placeholder = { Text(placeholder, color = Palette.TextMuted) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = accent
            )
        )
        if (!running && !canSend) {
            IconButton(onClick = onMic) { Icon(Icons.Filled.Mic, "Dictate", tint = Palette.TextMuted) }
            FilledIconButton(
                onClick = onVoice,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Color.Black)
            ) { Icon(Icons.Filled.GraphicEq, "Voice mode") }
        } else {
            FilledIconButton(
                onClick = if (running) onStop else onSend,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (running) Palette.Error else accent,
                    contentColor = Color.Black
                )
            ) {
                Icon(if (running) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send, if (running) "Stop" else "Send")
            }
        }
    }
}

/** Draggable, collapsible overlay that streams the agent's thoughts, tool calls and observations. */
@Composable
private fun AgentTerminal(
    lines: List<TerminalLine>,
    running: Boolean,
    step: Int,
    maxWidthPx: Float,
    maxHeightPx: Float,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    val density = LocalDensity.current
    val panelWidth = 300.dp
    val panelWidthPx = with(density) { panelWidth.toPx() }
    var offsetX by remember { mutableFloatStateOf((maxWidthPx - panelWidthPx - with(density) { 12.dp.toPx() }).coerceAtLeast(0f)) }
    var offsetY by remember { mutableFloatStateOf(with(density) { 8.dp.toPx() }) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val lastLen = lines.lastOrNull()?.text?.length ?: 0

    LaunchedEffect(lines.size, lastLen, collapsed) {
        if (!collapsed && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Column(
        Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(panelWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.TerminalBg)
            .border(1.dp, Palette.TerminalGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F1A12))
                .pointerInput(maxWidthPx, maxHeightPx) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offsetX = (offsetX + drag.x).coerceIn(0f, (maxWidthPx - panelWidthPx).coerceAtLeast(0f))
                        offsetY = (offsetY + drag.y).coerceIn(0f, (maxHeightPx - 120f).coerceAtLeast(0f))
                    }
                }
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (running) Palette.TerminalGreen else Palette.TextMuted)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (running) "agent · step $step" else "agent · idle",
                fontFamily = Mono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.TerminalGreen,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.DeleteSweep, "Clear", tint = Palette.TextMuted, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { collapsed = !collapsed }, modifier = Modifier.size(32.dp)) {
                Icon(if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, "Collapse", tint = Palette.TextMuted, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, "Hide", tint = Palette.TextMuted, modifier = Modifier.size(16.dp))
            }
        }
        if (!collapsed) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(lines, key = { it.id }) { line ->
                    Text(
                        text = line.text,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = when (line.kind) {
                            TerminalKind.SYSTEM -> Palette.TextMuted
                            TerminalKind.THOUGHT -> Palette.TerminalGreen
                            TerminalKind.ACTION -> Palette.TerminalAmber
                            TerminalKind.OBSERVATION -> Palette.TerminalCyan
                            TerminalKind.ERROR -> Palette.Error
                        }
                    )
                }
            }
        }
    }
}
