package dev.pocketagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Spa
import dev.pocketagent.ui.settings.LevelBadge
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.data.ConversationEntity
import dev.pocketagent.ui.settings.SettingsRouter
import dev.pocketagent.ui.theme.Palette
import kotlinx.coroutines.launch

private fun tabIcon(tab: Tab): ImageVector = when (tab) {
    Tab.CHAT -> Icons.AutoMirrored.Filled.Chat
    Tab.FEED -> Icons.Filled.DynamicFeed
    Tab.IDEAS -> Icons.Filled.Lightbulb
    Tab.GOALS -> Icons.Filled.Flag
    Tab.LIBRARY -> Icons.Filled.CollectionsBookmark
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppShell(viewModel: AppViewModel, onRestartNeeded: () -> Unit) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showTerminal by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    BackHandler(enabled = stack.isNotEmpty() || drawer.isOpen || tab != Tab.CHAT) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            stack.isNotEmpty() -> viewModel.pop()
            else -> viewModel.selectTab(Tab.CHAT)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
    ) {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = stack.isEmpty(),
            drawerContent = {
                DrawerContent(viewModel, close = { scope.launch { drawer.close() } })
            }
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Palette.Background)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Header(
                    viewModel = viewModel,
                    tab = tab,
                    showTerminal = showTerminal,
                    onToggleTerminal = { showTerminal = !showTerminal },
                    onMenu = { scope.launch { drawer.open() } }
                )
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.CHAT -> ChatTab(viewModel, showTerminal, onHideTerminal = { showTerminal = false })
                        Tab.FEED -> FeedTab(viewModel)
                        Tab.IDEAS -> IdeasTab(viewModel)
                        Tab.GOALS -> GoalsTab(viewModel)
                        Tab.LIBRARY -> LibraryTab(viewModel)
                    }
                }
                AnimatedVisibility(visible = !WindowInsets.isImeVisible) {
                    BottomBar(tab, onSelect = viewModel::selectTab)
                }
            }
        }

        stack.lastOrNull()?.let { route ->
            SettingsRouter(viewModel, route, onRestartNeeded)
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 80.dp)
        )
    }
}

@Composable
private fun Header(
    viewModel: AppViewModel,
    tab: Tab,
    showTerminal: Boolean,
    onToggleTerminal: () -> Unit,
    onMenu: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val avatarVersion by viewModel.avatarVersion.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.Filled.Menu, "Open menu") }

        Row(
            Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(24.dp))
                .clickable { viewModel.push("avatar") }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActivityAvatar(
                settings.avatarKind, settings.avatarValue, settings.assistantName,
                viewModel.avatarPhoto(settings), 34.dp, activity = chat.activity, photoVersion = avatarVersion
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(settings.assistantName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val sub = when {
                    tab != Tab.CHAT -> tab.label
                    chat.running -> (activityCaption(chat.activity) ?: "thinking") + "…"
                    chat.activity != null -> activityCaption(chat.activity) ?: settings.mode.label
                    chat.isMain -> settings.mode.label
                    else -> chat.title
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1)
            }
        }

        Box(Modifier.align(Alignment.CenterEnd)) {
            if (tab == Tab.CHAT) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Chat options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Palette.SurfaceHigh) {
                    AgentMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("${m.label} mode" + if (settings.mode == m) "  ✓" else "") },
                            leadingIcon = { Icon(if (m == AgentMode.WIT) Icons.Filled.AutoAwesome else Icons.Filled.Search, null) },
                            onClick = { viewModel.setMode(m); menu = false }
                        )
                    }
                    HorizontalDivider(color = Palette.Outline)
                    DropdownMenuItem(
                        text = { Text(if (showTerminal) "Hide agent terminal" else "Show agent terminal") },
                        leadingIcon = { Icon(Icons.Filled.Terminal, null) },
                        onClick = { onToggleTerminal(); menu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("New side chat") },
                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = { viewModel.newSideChat(); menu = false }
                    )
                    if (!chat.isMain) {
                        DropdownMenuItem(
                            text = { Text("Rename chat") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { renaming = true; menu = false }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Clear messages") },
                        leadingIcon = { Icon(Icons.Outlined.CleaningServices, null) },
                        onClick = { confirmClear = true; menu = false }
                    )
                    if (!chat.isMain) {
                        DropdownMenuItem(
                            text = { Text("Delete chat", color = Palette.Error) },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = Palette.Error) },
                            onClick = { confirmDelete = true; menu = false }
                        )
                    }
                }
            } else {
                IconButton(onClick = { viewModel.push("settings") }) { Icon(Icons.Filled.Settings, "Settings") }
            }
        }
    }

    if (renaming) TextInputDialog("Rename chat", chat.title, "Title", onConfirm = { viewModel.renameCurrent(it) }, onDismiss = { renaming = false })
    if (confirmClear) ConfirmDialog("Clear this chat?", "All messages here are removed. Memory and Library are kept.", "Clear", onConfirm = { viewModel.clearCurrent() }, onDismiss = { confirmClear = false })
    if (confirmDelete) ConfirmDialog("Delete \"${chat.title}\"?", "This side chat and its messages are removed.", onConfirm = {
        chat.conversationId?.let { viewModel.deleteConversation(it) }
    }, onDismiss = { confirmDelete = false })
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(32.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.Outline, RoundedCornerShape(32.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Tab.entries.forEach { t ->
                val active = t == selected
                Column(
                    Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (active) Palette.AccentSoft else Color.Transparent)
                        .clickable { onSelect(t) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(tabIcon(t), t.label, tint = if (active) Palette.Accent else Palette.TextMuted, modifier = Modifier.size(22.dp))
                    Text(
                        t.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) Palette.Accent else Palette.TextMuted,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerContent(viewModel: AppViewModel, close: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val side by viewModel.sideChats.collectAsStateWithLifecycle()
    val archived by viewModel.archivedChats.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val avatarVersion by viewModel.avatarVersion.collectAsStateWithLifecycle()
    val growth by viewModel.growth.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<ConversationEntity?>(null) }
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleting by remember { mutableStateOf<ConversationEntity?>(null) }

    ModalDrawerSheet(drawerContainerColor = Palette.Background, modifier = Modifier.width(310.dp)) {
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp, horizontal = 4.dp)
                        .clickable { close(); viewModel.push("avatar") },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistantAvatar(settings.avatarKind, settings.avatarValue, settings.assistantName, viewModel.avatarPhoto(settings), 44.dp, photoVersion = avatarVersion)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(settings.assistantName, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(8.dp))
                            LevelBadge(growth)
                        }
                        Text("Tap to change name or character", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                    }
                }
            }
            item {
                TextField(
                    value = query,
                    onValueChange = { query = it; viewModel.search(it) },
                    placeholder = { Text("Search chats", color = Palette.TextMuted) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = Palette.TextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Palette.Surface,
                        unfocusedContainerColor = Palette.Surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Palette.Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            val found = results
            if (query.isNotBlank() && found != null) {
                item { SectionTitle("Results") }
                if (found.isEmpty()) item { Hint("No chats match \"$query\"") }
                items(found, key = { "s${it.id}" }) { c ->
                    DrawerItem(Icons.AutoMirrored.Outlined.Chat, if (c.isMain) "Main chat" else c.title, selected = false) {
                        close()
                        viewModel.openConversation(c.id)
                    }
                }
            } else {
                item { SectionTitle("Tabs") }
                items(Tab.entries.toList(), key = { "t${it.name}" }) { t ->
                    DrawerItem(tabIcon(t), t.label, selected = tab == t && t != Tab.CHAT) {
                        close()
                        viewModel.selectTab(t)
                    }
                }
                item { SectionTitle("Workspace") }
                item {
                    Column {
                        DrawerItem(Icons.Filled.Computer, "Computer", selected = false) { close(); viewModel.push("computer") }
                        DrawerItem(Icons.Filled.Code, "Code", selected = false) { close(); viewModel.push("code") }
                        DrawerItem(Icons.Filled.Spa, "Growth · level ${growth.level}", selected = false) { close(); viewModel.push("growth") }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    DrawerItem(Icons.Filled.Home, "Main chat", selected = tab == Tab.CHAT && chat.isMain) {
                        close()
                        viewModel.openMain()
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Side chats", Modifier.weight(1f))
                        IconButton(onClick = { close(); viewModel.newSideChat() }) { Icon(Icons.Filled.Add, "New side chat", tint = Palette.TextMuted) }
                    }
                }
                if (side.isEmpty()) item { Hint("Side chats keep a topic separate from your main chat.") }
                items(side, key = { "c${it.id}" }) { c ->
                    ConversationRow(
                        c,
                        selected = tab == Tab.CHAT && chat.conversationId == c.id,
                        onOpen = { close(); viewModel.openConversation(c.id) },
                        onLongPress = { actionsFor = c }
                    )
                }
                if (archived.isNotEmpty()) {
                    item {
                        DrawerItem(Icons.Filled.Archive, if (showArchived) "Hide archived (${archived.size})" else "Archived (${archived.size})", selected = false) {
                            showArchived = !showArchived
                        }
                    }
                    if (showArchived) {
                        items(archived, key = { "a${it.id}" }) { c ->
                            ConversationRow(c, selected = false, onOpen = { close(); viewModel.openConversation(c.id) }, onLongPress = { actionsFor = c })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.Outline)
        Row(Modifier.padding(12.dp)) {
            DrawerItem(Icons.Filled.Settings, "Settings", selected = false) {
                close()
                viewModel.push("settings")
            }
        }
    }

    actionsFor?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { actionsFor = null },
            containerColor = Palette.Surface,
            title = { Text(c.title, maxLines = 2) },
            text = {
                Column {
                    DrawerItem(Icons.Filled.Edit, "Rename", selected = false) { renaming = c; actionsFor = null }
                    DrawerItem(if (c.archived) Icons.Filled.Unarchive else Icons.Filled.Archive, if (c.archived) "Unarchive" else "Archive", selected = false) {
                        viewModel.setArchived(c.id, !c.archived)
                        actionsFor = null
                    }
                    DrawerItem(Icons.Filled.DeleteOutline, "Delete", selected = false, tint = Palette.Error) { deleting = c; actionsFor = null }
                }
            },
            confirmButton = {},
            dismissButton = { androidx.compose.material3.TextButton(onClick = { actionsFor = null }) { Text("Close") } }
        )
    }
    renaming?.let { c ->
        TextInputDialog("Rename chat", c.title, "Title", onConfirm = { viewModel.renameConversation(c.id, it) }, onDismiss = { renaming = null })
    }
    deleting?.let { c ->
        ConfirmDialog("Delete \"${c.title}\"?", "This side chat and its messages are removed.", onConfirm = { viewModel.deleteConversation(c.id) }, onDismiss = { deleting = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(c: ConversationEntity, selected: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.Surface else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (c.mode == AgentMode.RESEARCH.name) Palette.Research else Palette.Accent)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(relativeTime(c.updatedAt), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, selected: Boolean, tint: Color = Palette.Text, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.Surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) Palette.Accent else tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
