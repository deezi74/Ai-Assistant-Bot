package dev.pocketagent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.ContentScale
import dev.pocketagent.ui.CharacterState
import dev.pocketagent.ui.rememberFileImage
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.data.Skill
import dev.pocketagent.ui.AppTextField
import dev.pocketagent.ui.AppViewModel
import dev.pocketagent.ui.AssistantAvatar
import dev.pocketagent.ui.BuiltinAvatars
import dev.pocketagent.ui.ConfirmDialog
import dev.pocketagent.ui.Hint
import dev.pocketagent.ui.SectionTitle
import dev.pocketagent.ui.SubScreen
import dev.pocketagent.ui.SurfaceCard
import dev.pocketagent.ui.formatBytes
import dev.pocketagent.ui.relativeTime
import dev.pocketagent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val version by viewModel.avatarVersion.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(settings.assistantName) }
    var userName by remember { mutableStateOf(settings.userName) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setAvatarPhoto(uri)
    }

    SubScreen("Avatar & name", onBack) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            AssistantAvatar(
                settings.avatarKind, settings.avatarValue, name.ifBlank { settings.assistantName },
                viewModel.avatarPhoto(settings), 120.dp, photoVersion = version, ring = Palette.Accent
            )
        }
        Text(
            name.ifBlank { "Your assistant" },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        SectionTitle("Name")
        AppTextField(name, { name = it.take(24) }, "Assistant name")
        AppTextField(userName, { userName = it.take(40) }, "What should it call you? (optional)")
        Button(
            onClick = {
                viewModel.updateSettings { it.copy(assistantName = name.trim().ifEmpty { "Nova" }, userName = userName.trim()) }
                viewModel.toast("Saved. ${name.trim().ifEmpty { "Nova" }} will answer to that name from the next message.")
            },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
        ) { Text("Save name") }

        SectionTitle("Create a character with AI")
        AiCharacterSection(viewModel, name.ifBlank { settings.assistantName })

        SectionTitle("Avatar")
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Use a photo")
        }
        Hint("Or pick one of these:")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BuiltinAvatars.forEach { a ->
                val selected = settings.avatarKind == "builtin" && settings.avatarValue == a.id
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { viewModel.setBuiltinAvatar(a.id) }) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .border(2.dp, if (selected) Palette.Accent else Color.Transparent, CircleShape)
                            .padding(3.dp)
                    ) {
                        AssistantAvatar("builtin", a.id, name.ifBlank { settings.assistantName }, null, 56.dp)
                    }
                    Text(a.label, style = MaterialTheme.typography.bodySmall, color = if (selected) Palette.Accent else Palette.TextMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun AiCharacterSection(viewModel: AppViewModel, assistantName: String) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.character.collectAsStateWithLifecycle()
    val gallery by viewModel.characterGallery.collectAsStateWithLifecycle()
    var description by rememberSaveable { mutableStateOf(settings.characterDescription) }
    var styleId by rememberSaveable { mutableStateOf(viewModel.characterStyles.first().id) }
    val sources = remember { viewModel.imageSources() }
    var sourceId by rememberSaveable { mutableStateOf(settings.imageSource.takeIf { id -> sources.any { it.id == id } } ?: sources.first().id) }

    Hint("Describe anything and $assistantName becomes it. You can also just say it in chat, like \"turn yourself into a robot owl\".")
    AppTextField(description, { description = it.take(300) }, "e.g. a cyberpunk fox with neon goggles", singleLine = false, minLines = 2)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("a friendly robot owl", "a space cat astronaut", "a wise old wizard", "a tiny dragon with glasses").forEach { idea ->
            ChoiceChip(idea, selected = false) { description = idea }
        }
    }
    Text("Style", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        viewModel.characterStyles.forEach { st -> ChoiceChip(st.label, styleId == st.id) { styleId = st.id } }
    }
    if (sources.size > 1) {
        Text("Drawn by", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { src -> ChoiceChip(src.label, sourceId == src.id) { sourceId = src.id } }
        }
        Hint(sources.first { it.id == sourceId }.note)
    } else {
        Hint("Drawn free by Pollinations, no account needed. Add an OpenAI or OpenRouter key in Manage providers for more choices.")
    }

    val drawing = state is CharacterState.Drawing
    Button(
        onClick = { viewModel.generateCharacter(description.trim(), styleId, sourceId) },
        enabled = description.isNotBlank() && !drawing,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
    ) {
        Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(if (drawing) "Drawing…" else "Generate character")
    }

    when (val st = state) {
        is CharacterState.Drawing -> SurfaceCard {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent)
            Hint("Drawing ${st.description}. This usually takes 10 to 40 seconds.")
        }
        is CharacterState.Failed -> SurfaceCard {
            Text(st.message, color = Palette.Error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { viewModel.dismissCharacter() }) { Text("OK") }
        }
        is CharacterState.Preview -> SurfaceCard {
            val image = rememberFileImage(st.file, maxDim = 768)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (image != null) {
                    Image(
                        image, contentDescription = st.description, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(220.dp).clip(RoundedCornerShape(24.dp))
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.useCharacter(st.file, st.description) },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
                ) { Text("Use this") }
                OutlinedButton(onClick = { viewModel.generateCharacter(st.description, styleId, sourceId) }) { Text("Try again") }
                TextButton(onClick = { viewModel.dismissCharacter() }) { Text("Close") }
            }
        }
        CharacterState.Idle -> Unit
    }

    if (gallery.isNotEmpty()) {
        Text("Your characters", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            gallery.forEach { file ->
                var confirm by remember { mutableStateOf(false) }
                val thumb = rememberFileImage(file, maxDim = 192)
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Palette.SurfaceHigh)
                        .combinedClickable(onClick = { viewModel.useCharacter(file) }, onLongClick = { confirm = true })
                ) {
                    if (thumb != null) Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
                }
                if (confirm) {
                    ConfirmDialog("Delete this character?", viewModel.workspace.characterDescription(file).ifBlank { "It is removed from the gallery." }, onConfirm = { viewModel.deleteCharacter(file) }, onDismiss = { confirm = false })
                }
            }
        }
        Hint("Tap a character to switch back to it. Long-press to delete.")
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) Color.Black else Palette.Text,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Palette.Accent else Palette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
fun SoulScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    var soul by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { soul = withContext(Dispatchers.IO) { viewModel.readSoul() } }
    SubScreen("Soul", onBack) {
        Hint("The personality prompt behind every answer. Written in markdown; applied from the next message. Name and memory are added automatically.")
        OutlinedTextField(
            value = soul,
            onValueChange = { soul = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp),
            textStyle = MaterialTheme.typography.labelSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.saveSoul(soul) }) { Text("Save") }
            OutlinedButton(onClick = { soul = viewModel.resetSoul() }) { Text("Reset to default") }
        }
    }
}

@Composable
fun MemoryScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var memory by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { memory = withContext(Dispatchers.IO) { viewModel.readMemory() } }
    SubScreen("Memory", onBack) {
        Hint("${settings.assistantName} adds facts here when you say \"remember\" or share something lasting. Everything below is included in each conversation.")
        OutlinedTextField(
            value = memory,
            onValueChange = { memory = it },
            placeholder = { Text("- I'm vegetarian\n- My sister's name is Sam") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.saveMemory(memory) }) { Text("Save") }
            OutlinedButton(onClick = { confirm = true }) { Text("Forget everything") }
        }
    }
    if (confirm) {
        ConfirmDialog("Forget everything?", "memory.md is emptied. Chats are kept.", "Forget", onConfirm = {
            viewModel.clearMemory()
            memory = ""
        }, onDismiss = { confirm = false })
    }
}

@Composable
fun SystemFilesScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val files by produceState(emptyList<java.io.File>()) { value = withContext(Dispatchers.IO) { viewModel.systemFiles() } }
    var open by remember { mutableStateOf<java.io.File?>(null) }
    SubScreen("System files", onBack) {
        Hint("Plain text files that shape the assistant. Stored privately in the app.")
        files.forEach { f ->
            SurfaceCard(onClick = { open = f }) {
                Text(f.name, style = MaterialTheme.typography.titleMedium)
                Hint(
                    if (f.isFile) "${formatBytes(f.length())} · edited ${relativeTime(f.lastModified())}" else "Not created yet"
                )
            }
        }
    }
    open?.let { f ->
        var text by remember(f) { mutableStateOf("") }
        LaunchedEffect(f) { text = withContext(Dispatchers.IO) { if (f.isFile) f.readText() else "" } }
        AlertDialog(
            onDismissRequest = { open = null },
            containerColor = Palette.Surface,
            title = { Text(f.name) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp),
                    textStyle = MaterialTheme.typography.labelSmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    f.parentFile?.mkdirs()
                    f.writeText(text)
                    viewModel.refreshSkills()
                    viewModel.toast("${f.name} saved")
                    open = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { open = null }) { Text("Close") } }
        )
    }
}

@Composable
fun SkillsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Skill?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshSkills() }

    SubScreen("Skills", onBack, actions = {
        IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "New skill") }
    }) {
        Hint("Skills are short instructions the assistant follows when they apply. Enabled skills are added to every conversation.")
        if (skills.isEmpty()) Hint("No skills yet. Tap + to write one.")
        skills.forEach { skill ->
            SurfaceCard(onClick = { editing = skill }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, style = MaterialTheme.typography.titleMedium)
                        if (skill.description.isNotBlank()) Hint(skill.description)
                    }
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { viewModel.toggleSkill(skill) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Color.Black)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (creating || editing != null) {
        SkillDialog(
            initial = editing,
            onSave = { name, desc, body ->
                viewModel.saveSkill(editing, name, desc, body, editing?.enabled ?: true)
                editing = null
                creating = false
            },
            onDelete = editing?.let { s -> { viewModel.deleteSkill(s); editing = null } },
            onDismiss = { editing = null; creating = false }
        )
    }
}

@Composable
private fun SkillDialog(initial: Skill?, onSave: (String, String, String) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var body by remember { mutableStateOf(initial?.instructions.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (initial == null) "New skill" else "Edit skill", modifier = Modifier.weight(1f))
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "Delete skill", tint = Palette.Error) }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(name, { name = it }, "Name")
                AppTextField(description, { description = it }, "When to use it")
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Instructions") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Transparent)
                )
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && body.isNotBlank(), onClick = { onSave(name, description, body) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
