package dev.pocketagent.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val local by viewModel.localState.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showTerminal by rememberSaveable { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    val accent = if (settings.mode == AgentMode.WIT) Palette.Accent else Palette.Research

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    Scaffold(
        containerColor = Palette.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Background),
                title = {
                    Column {
                        Text("PocketAgent", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = modelLabel(local, chat.providerLabel),
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.TextMuted,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showTerminal = !showTerminal }) {
                        Icon(Icons.Filled.Terminal, "Toggle agent terminal", tint = if (showTerminal) accent else Palette.TextMuted)
                    }
                    IconButton(onClick = viewModel::newChat) { Icon(Icons.Filled.AddComment, "New chat") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                }
            )
        }
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            val maxWidthPx = constraints.maxWidth.toFloat()
            val maxHeightPx = constraints.maxHeight.toFloat()

            Column(Modifier.fillMaxSize()) {
                ModeToggle(settings.mode, onSelect = viewModel::setMode)
                MessageList(chat.items, accent, Modifier.weight(1f))
                InputBar(
                    value = input,
                    onValueChange = { input = it },
                    running = chat.running,
                    accent = accent,
                    placeholder = if (settings.mode == AgentMode.WIT) "Ask anything. Expect opinions." else "What should I research?",
                    onSend = {
                        viewModel.send(input)
                        input = ""
                    },
                    onStop = viewModel::stop
                )
            }

            AnimatedVisibility(visible = showTerminal && (chat.terminal.isNotEmpty() || chat.running)) {
                AgentTerminal(
                    lines = chat.terminal,
                    running = chat.running,
                    step = chat.step,
                    maxWidthPx = maxWidthPx,
                    maxHeightPx = maxHeightPx,
                    onClear = viewModel::clearTerminal,
                    onClose = { showTerminal = false }
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(viewModel = viewModel, onDismiss = { showSettings = false })
    }
}

private fun modelLabel(local: LocalModelState, provider: String): String = when {
    provider.isNotBlank() -> provider
    local is LocalModelState.Ready -> "Local · ${local.fileName} · ${local.contextSize} ctx"
    local is LocalModelState.Loading -> "Loading ${local.fileName}…"
    local is LocalModelState.Error -> "Model error: ${local.message}"
    else -> "No model loaded · open Settings"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeToggle(mode: AgentMode, onSelect: (AgentMode) -> Unit) {
    val modes = AgentMode.entries
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        modes.forEachIndexed { index, m ->
            val color = if (m == AgentMode.WIT) Palette.Accent else Palette.Research
            SegmentedButton(
                selected = m == mode,
                onClick = { onSelect(m) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = if (m == AgentMode.WIT) Palette.AccentSoft else Palette.ResearchSoft,
                    activeContentColor = color,
                    activeBorderColor = color,
                    inactiveContainerColor = Palette.Surface,
                    inactiveContentColor = Palette.TextMuted,
                    inactiveBorderColor = Palette.Outline
                ),
                label = { Text(if (m == AgentMode.WIT) "Wit mode" else "Research mode") }
            )
        }
    }
}

@Composable
private fun MessageList(items: List<ChatItem>, accent: Color, modifier: Modifier) {
    val listState = rememberLazyListState()
    val lastLength = items.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(items.size, lastLength) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }
    if (items.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("⬡", fontSize = 44.sp, color = accent)
                Spacer(Modifier.size(8.dp))
                Text("Local brain. Live web. Zero filter on the jokes.", color = Palette.TextMuted)
            }
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { it.id }) { item ->
            when (item.role) {
                ItemRole.USER -> Bubble(item.text, Palette.SurfaceHigh, Alignment.CenterEnd, Palette.Text)
                ItemRole.ASSISTANT -> Bubble(
                    text = if (item.streaming && item.text.isEmpty()) "▍" else item.text + if (item.streaming) "▍" else "",
                    background = Palette.Surface,
                    alignment = Alignment.CenterStart,
                    color = if (item.isError) Palette.Error else Palette.Text,
                    border = accent.copy(alpha = 0.35f)
                )
                ItemRole.TOOL -> ToolChip(item)
            }
        }
    }
}

@Composable
private fun Bubble(text: String, background: Color, alignment: Alignment, color: Color, border: Color? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        val shape = RoundedCornerShape(16.dp)
        SelectionContainer {
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(shape)
                    .background(background)
                    .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun ToolChip(item: ChatItem) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Background)
            .border(1.dp, Palette.Outline, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${if (item.isError) "✗" else "⚙"} ${item.toolName ?: "tool"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (item.isError) Palette.Error else Palette.TerminalCyan,
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
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    accent: Color,
    placeholder: String,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp, max = 160.dp),
            placeholder = { Text(placeholder, color = Palette.TextMuted) },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = Palette.Outline,
                focusedContainerColor = Palette.Surface,
                unfocusedContainerColor = Palette.Surface,
                cursorColor = accent
            )
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = if (running) onStop else onSend,
            enabled = running || value.isNotBlank(),
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (running) Palette.Error else accent,
                contentColor = Color.Black
            )
        ) {
            Icon(if (running) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send, if (running) "Stop" else "Send")
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
    var offsetY by remember { mutableFloatStateOf(with(density) { 64.dp.toPx() }) }
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
