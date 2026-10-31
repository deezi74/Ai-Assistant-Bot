package dev.pocketagent.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.agent.ComputerActor
import dev.pocketagent.agent.Growth
import dev.pocketagent.ui.AppTextField
import dev.pocketagent.ui.AppViewModel
import dev.pocketagent.ui.AssistantAvatar
import dev.pocketagent.ui.ConfirmDialog
import dev.pocketagent.ui.Hint
import dev.pocketagent.ui.SectionTitle
import dev.pocketagent.ui.SettingRow
import dev.pocketagent.ui.SubScreen
import dev.pocketagent.ui.SurfaceCard
import dev.pocketagent.ui.SwitchRow
import dev.pocketagent.ui.TextInputDialog
import dev.pocketagent.ui.formatBytes
import dev.pocketagent.ui.theme.Mono
import dev.pocketagent.ui.theme.Palette
import kotlinx.coroutines.launch
import java.io.File

private val monoSmall = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 17.sp)

/** Asks for a task and hands it to the agent in a side chat. */
@Composable
private fun AskToBuildDialog(viewModel: AppViewModel, project: File?, onDismiss: () -> Unit) {
    var task by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("What should it build?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(task, { task = it }, "e.g. a tip calculator, a habit tracker", singleLine = false, minLines = 2)
                Hint("It writes the code on its own computer, runs it to check, and tells you how to use it.")
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.askAgentToBuild(task.trim(), project); onDismiss() }, enabled = task.isNotBlank()) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComputerScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val transcript by viewModel.computerTranscript.collectAsStateWithLifecycle()
    val revision by viewModel.computerRevision.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val prompt = remember(revision, transcript.size) { viewModel.computerPrompt() }

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) list.animateScrollToItem(transcript.lastIndex + 1)
    }
    val submit: () -> Unit = {
        val cmd = input.trim()
        if (cmd.isNotEmpty()) {
            viewModel.runComputerCommand(cmd)
            input = ""
        }
    }

    SubScreen(
        "${settings.assistantName}'s computer", onBack, scrollable = false,
        actions = { TextButton(onClick = { viewModel.clearComputer() }) { Text("Clear") } }
    ) {
        Hint("A private computer that runs on this phone. You and ${settings.assistantName} share it: anything it builds in chat shows up here, marked 🤖.")
        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Text("TruAgent sandbox · JavaScript · type help", style = monoSmall, color = Palette.TextMuted)
            }
            items(transcript, key = { it.id }) { e ->
                Column {
                    Row {
                        if (e.actor == ComputerActor.AGENT) Text("🤖 ", style = monoSmall)
                        Text(e.prompt + " ", style = monoSmall, color = Palette.Accent)
                        Text(e.command, style = monoSmall, color = Palette.Text)
                    }
                    if (e.output.isNotEmpty()) {
                        Text(
                            e.output,
                            style = monoSmall,
                            color = if (e.output.startsWith("error:")) Palette.Error else Palette.Text,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("help", "ls -l", "tree", "run welcome", "history").forEach { c ->
                AssistChip(
                    onClick = { viewModel.runComputerCommand(c) },
                    label = { Text(c, style = monoSmall) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Palette.Text)
                )
            }
            AssistChip(
                onClick = { asking = true },
                label = { Text("Ask ${settings.assistantName} to build…") },
                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, tint = Palette.Accent, modifier = Modifier.size(16.dp)) }
            )
        }
        Row(Modifier.fillMaxWidth().imePadding().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                prefix = { Text("$prompt ", style = monoSmall, color = Palette.Accent) },
                textStyle = monoSmall.copy(color = Palette.Text),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Outline, cursorColor = Palette.Accent),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = submit, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Run", tint = Palette.Accent) }
        }
    }
    if (asking) AskToBuildDialog(viewModel, null) { asking = false }
}

@Composable
fun CodeScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val revision by viewModel.computerRevision.collectAsStateWithLifecycle()
    var projectPath by rememberSaveable { mutableStateOf<String?>(null) }
    var filePath by rememberSaveable { mutableStateOf<String?>(null) }
    val project = projectPath?.let { File(it) }?.takeIf { it.isDirectory }
    val file = filePath?.let { File(it) }

    BackHandler(enabled = file != null || project != null) {
        if (file != null) filePath = null else projectPath = null
    }

    when {
        file != null && project != null -> CodeEditor(viewModel, project, file, onBack = { filePath = null })
        project != null -> ProjectView(
            viewModel, project, revision,
            onOpen = { filePath = it.absolutePath },
            onBack = { projectPath = null }
        )
        else -> ProjectList(viewModel, settings.assistantName, revision, onOpen = { projectPath = it.absolutePath }, onBack = onBack)
    }
}

@Composable
private fun ProjectList(viewModel: AppViewModel, assistantName: String, revision: Int, onOpen: (File) -> Unit, onBack: () -> Unit) {
    val projects = remember(revision) { viewModel.codeProjects() }
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }

    SubScreen("Code", onBack) {
        Hint("$assistantName writes and runs code on its own computer, right on this phone. Programs are JavaScript, with no internet access and no access to your other apps.")
        Button(
            onClick = { asking = true },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ask $assistantName to build something")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New project")
            }
            OutlinedButton(onClick = { viewModel.push("computer") }) {
                Icon(Icons.Filled.Terminal, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Terminal")
            }
        }
        SectionTitle("Projects")
        if (projects.isEmpty()) Hint("No projects yet.")
        projects.forEach { p ->
            val count = remember(p, revision) { viewModel.codeFiles(p).size }
            SettingRow(Icons.Filled.Folder, p.name, "$count file${if (count == 1) "" else "s"}", onClick = { onOpen(p) })
        }
    }
    if (creating) {
        TextInputDialog("New project", "", "Project name", confirm = "Create", onConfirm = { name ->
            scope.launch { onOpen(viewModel.createProject(name)) }
        }, onDismiss = { creating = false })
    }
    if (asking) AskToBuildDialog(viewModel, null) { asking = false }
}

@Composable
private fun ProjectView(viewModel: AppViewModel, project: File, revision: Int, onOpen: (File) -> Unit, onBack: () -> Unit) {
    val files = remember(project, revision) { viewModel.codeFiles(project) }
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<File?>(null) }
    val entry = files.firstOrNull { it.name == "main.js" && it.parentFile == project }
        ?: files.firstOrNull { it.name == "index.js" && it.parentFile == project }
        ?: files.firstOrNull { it.extension == "js" }

    SubScreen(project.name, onBack, actions = {
        IconButton(onClick = { deleting = project }) { Icon(Icons.Filled.DeleteOutline, "Delete project", tint = Palette.TextMuted) }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val target = entry ?: return@Button
                    running = true
                    scope.launch {
                        output = viewModel.runCode(target)
                        running = false
                    }
                },
                enabled = entry != null && !running,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
            ) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (entry != null) "Run ${entry.name}" else "Nothing to run")
            }
            OutlinedButton(onClick = { newFile = true }) { Text("New file") }
            OutlinedButton(onClick = { asking = true }) { Text("Ask to change") }
        }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent)
        output?.let { OutputPanel(it) }
        SectionTitle("Files")
        if (files.isEmpty()) Hint("Empty project.")
        files.forEach { f ->
            val rel = f.relativeTo(project).path
            SettingRow(
                if (f.extension in setOf("js", "json", "html", "css")) Icons.Filled.Code else Icons.Filled.Description,
                rel,
                formatBytes(f.length()),
                trailing = { IconButton(onClick = { deleting = f }) { Icon(Icons.Filled.DeleteOutline, "Delete", tint = Palette.TextMuted) } },
                onClick = { onOpen(f) }
            )
        }
    }
    if (newFile) {
        TextInputDialog("New file", "", "Name, e.g. utils.js", confirm = "Create", onConfirm = { name ->
            val f = viewModel.codeFileIn(project, name)
            if (f != null) {
                scope.launch {
                    if (f.exists() || viewModel.saveCode(f, "")) onOpen(f)
                }
            }
        }, onDismiss = { newFile = false })
    }
    if (asking) AskToBuildDialog(viewModel, project) { asking = false }
    deleting?.let { target ->
        ConfirmDialog(
            if (target == project) "Delete ${project.name}?" else "Delete ${target.name}?",
            if (target == project) "All files in this project are removed from the phone." else "This file is removed from the phone.",
            onConfirm = {
                viewModel.deleteCode(target)
                if (target == project) onBack()
            },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun CodeEditor(viewModel: AppViewModel, project: File, file: File, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember(file) { mutableStateOf<String?>(null) }
    var saved by remember(file) { mutableStateOf("") }
    var output by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        val content = viewModel.readCode(file)
        text = content
        saved = content
    }
    val dirty = text != null && text != saved
    /** Writes the editor text and only then reports success, so Run never sees stale code. */
    suspend fun saveNow(): Boolean {
        val current = text ?: return false
        if (current == saved) return true
        return viewModel.saveCode(file, current).also { ok -> if (ok) saved = current }
    }
    val save: () -> Unit = { scope.launch { saveNow() } }

    SubScreen(file.relativeTo(project).path, onBack, actions = {
        IconButton(onClick = save, enabled = dirty) { Icon(Icons.Filled.Save, "Save", tint = if (dirty) Palette.Accent else Palette.TextMuted) }
        if (file.extension == "js") {
            IconButton(onClick = {
                scope.launch {
                    if (saveNow()) output = viewModel.runCode(file)
                }
            }) { Icon(Icons.Filled.PlayArrow, "Run", tint = Palette.Accent) }
        }
    }) {
        val current = text
        if (current == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent)
        } else {
            OutlinedTextField(
                value = current,
                onValueChange = { text = it },
                textStyle = monoSmall.copy(color = Palette.Text),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Outline, cursorColor = Palette.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp)
            )
            if (dirty) Hint("Unsaved changes")
        }
        output?.let { OutputPanel(it) }
    }
}

@Composable
private fun OutputPanel(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface)
            .padding(10.dp)
    ) {
        Text("OUTPUT", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
        Text(
            text.ifEmpty { "(no output)" },
            style = monoSmall,
            color = if (text.startsWith("error:") || text.contains("\nerror:")) Palette.Error else Palette.Text,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
    }
}

@Composable
fun GrowthScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val growth by viewModel.growth.collectAsStateWithLifecycle()
    val lessons by viewModel.lessons.collectAsStateWithLifecycle()
    val version by viewModel.avatarVersion.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    SubScreen("Growth", onBack) {
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantAvatar(settings.avatarKind, settings.avatarValue, settings.assistantName, viewModel.avatarPhoto(settings), 64.dp, photoVersion = version, ring = Palette.Accent)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Level ${growth.level} · ${growth.title}", style = MaterialTheme.typography.titleLarge)
                    Text("${growth.xp} XP · ${growth.nextLevelXp - growth.xp} to level ${growth.level + 1}", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
            }
            LinearProgressIndicator(
                progress = { growth.progress },
                modifier = Modifier.fillMaxWidth(),
                color = Palette.Accent,
                trackColor = Palette.SurfaceHigh
            )
            GrowthStats(growth)
        }
        SwitchRow(
            "Learn after each task",
            "Reflects on finished tasks to save facts about you, lessons and new skills. Uses a little extra model time.",
            settings.autoLearn
        ) { on -> viewModel.updateSettings { it.copy(autoLearn = on) } }
        Hint("${settings.assistantName} earns XP for every finished task, more for tool use and programs. Everything it learns is plain text you can read and delete here, in Memory and in Skills.")
        SettingRow(Icons.Filled.Description, "Learned facts", "Memory", onClick = { viewModel.push("memory") })
        SettingRow(Icons.Filled.Code, "Learned skills", "Skills marked \"Learned\"", onClick = { viewModel.push("skills") })

        SectionTitle("Lessons (${lessons.size})")
        if (lessons.isEmpty()) Hint("No lessons yet. They appear after tasks where something could be done better next time.")
        lessons.asReversed().forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.text, style = MaterialTheme.typography.bodyMedium)
                    if (l.date.isNotEmpty()) Text(l.date, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
                IconButton(onClick = { viewModel.deleteLesson(l) }) { Icon(Icons.Filled.DeleteOutline, "Delete lesson", tint = Palette.TextMuted) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lessons.isNotEmpty()) OutlinedButton(onClick = { confirmClear = true }) { Text("Clear lessons") }
            OutlinedButton(onClick = { confirmReset = true }) { Text("Reset level") }
        }
    }
    if (confirmReset) ConfirmDialog("Reset level?", "XP and stats go back to zero. Memory, lessons and skills are kept.", "Reset", { viewModel.resetGrowth() }, { confirmReset = false })
    if (confirmClear) ConfirmDialog("Clear all lessons?", "The assistant forgets every lesson it has learned.", "Clear", { viewModel.clearLessons() }, { confirmClear = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrowthStats(g: Growth) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "Tasks" to g.tasks, "Tool uses" to g.toolCalls, "Programs" to g.programs,
            "Facts" to g.facts, "Lessons" to g.lessons, "Skills" to g.skills
        ).forEach { (label, n) ->
            Column(Modifier.clickable(enabled = false) {}) {
                Text(n.toString(), style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
        }
    }
}

/** Compact level badge for headers. */
@Composable
fun LevelBadge(g: Growth, modifier: Modifier = Modifier) {
    Text(
        "Lv ${g.level}",
        style = MaterialTheme.typography.labelSmall,
        color = Color.Black,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Accent)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Suppress("unused")
private fun Modifier.fullSize() = this.fillMaxSize()
