package dev.pocketagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.data.GoalEntity
import dev.pocketagent.data.RoutineEntity
import dev.pocketagent.ui.theme.Palette
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

val GoalCategories = listOf(
    "Health" to "💪",
    "Relationships" to "💞",
    "Finance" to "💰",
    "Career" to "🚀",
    "Interests" to "🎨",
    "Productivity" to "⏱️",
    "Other" to "✨"
)

@Composable
fun GoalsTab(viewModel: AppViewModel) {
    val goals by viewModel.goals.collectAsStateWithLifecycle()
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RoutineEntity?>(null) }
    var addingGoal by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Goals", style = MaterialTheme.typography.titleLarge) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Tracking", Modifier.weight(1f))
                IconButton(onClick = { addingGoal = true }) { Icon(Icons.Filled.Add, "Add goal", tint = Palette.TextMuted) }
            }
        }
        if (goals.isEmpty()) {
            item { Hint("Nothing tracked yet. Create a goal below and talk it through, or add one directly.") }
        }
        items(goals, key = { "g${it.id}" }) { goal ->
            GoalRow(goal, onToggle = { viewModel.toggleGoal(goal) }, onDelete = { viewModel.deleteGoal(goal) }, onDiscuss = {
                viewModel.prefill("Let's check in on my goal \"${goal.title}\". ${goal.note}".trim(), newSideChat = true)
            })
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Routines", Modifier.weight(1f))
                IconButton(onClick = {
                    editing = RoutineEntity(name = "", kind = "prompt", prompt = "", hour = 9, minute = 0)
                }) { Icon(Icons.Filled.Add, "Add routine", tint = Palette.TextMuted) }
            }
        }
        if (routines.isEmpty()) item { Hint("Routines run a prompt on a schedule and save the result to your Library.") }
        items(routines, key = { "r${it.id}" }) { r ->
            RoutineRow(
                routine = r,
                schedule = viewModel.describeRoutine(r),
                onToggle = { viewModel.toggleRoutine(r) },
                onRun = { viewModel.runRoutineNow(r) },
                onEdit = { editing = r },
                onDelete = { viewModel.deleteRoutine(r) }
            )
        }

        item { SectionTitle("Create a goal") }
        item { CategoryGrid { category -> viewModel.prefill("I'd like to create a $category goal.", newSideChat = true) } }
        item { Spacer(Modifier.height(96.dp)) }
    }

    editing?.let { r ->
        RoutineDialog(initial = r, onSave = { viewModel.saveRoutine(it); editing = null }, onDismiss = { editing = null })
    }
    if (addingGoal) {
        GoalDialog(onSave = { t, n, c -> viewModel.addGoal(t, n, c); addingGoal = false }, onDismiss = { addingGoal = false })
    }
}

@Composable
private fun GoalRow(goal: GoalEntity, onToggle: () -> Unit, onDelete: () -> Unit, onDiscuss: () -> Unit) {
    val emoji = GoalCategories.firstOrNull { it.first == goal.category }?.second ?: "✨"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .clickable(onClick = onDiscuss)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = goal.done,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = Palette.Accent, checkmarkColor = Color.Black)
        )
        Column(Modifier.weight(1f)) {
            Text(
                goal.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (goal.done) TextDecoration.LineThrough else null,
                color = if (goal.done) Palette.TextMuted else Palette.Text
            )
            Text(
                "$emoji ${goal.category}" + if (goal.note.isNotBlank()) " · ${goal.note}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextMuted,
                maxLines = 1
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "Delete goal", tint = Palette.TextMuted, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun RoutineRow(
    routine: RoutineEntity,
    schedule: String,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (routine.kind == "feed") "📰" else "⏰", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(routine.name, style = MaterialTheme.typography.titleMedium)
                Text(schedule, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
            Switch(
                checked = routine.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Color.Black)
            )
        }
        if (routine.kind == "prompt" && routine.prompt.isNotBlank()) {
            Text(routine.prompt, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 2)
        }
        if (routine.lastRunAt > 0) {
            Text(
                "Last run ${relativeTime(routine.lastRunAt)}: ${routine.lastResult}",
                style = MaterialTheme.typography.bodySmall,
                color = if (routine.lastResult.startsWith("Failed")) Palette.Error else Palette.TextMuted,
                maxLines = 2
            )
        }
        Row {
            TextButton(onClick = onRun) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Run now")
            }
            TextButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "Delete routine", tint = Palette.TextMuted, modifier = Modifier.size(20.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryGrid(onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GoalCategories.forEach { (name, emoji) ->
            Column(
                Modifier
                    .width(104.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.Surface)
                    .clickable { onPick(name) }
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(emoji, fontSize = 26.sp)
                Spacer(Modifier.height(6.dp))
                Text(name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineDialog(initial: RoutineEntity, onSave: (RoutineEntity) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var day by remember { mutableIntStateOf(initial.dayOfWeek.coerceIn(1, 7)) }
    val isFeed = initial.kind == "feed"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text(if (initial.id == 0L) "New routine" else "Edit routine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(name, { name = it }, "Name")
                if (isFeed) {
                    Hint("Writes new Feed posts using your feed instruction.")
                } else {
                    AppTextField(prompt, { prompt = it }, "What should it do?", singleLine = false, minLines = 3)
                }
                Text("Time", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { hour = (hour + 23) % 24 }) { Text("−") }
                    Text("%02d".format(hour), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { hour = (hour + 1) % 24 }) { Text("+") }
                    Text(":", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { minute = (minute + 45) % 60 }) { Text("−") }
                    Text("%02d".format(minute), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { minute = (minute + 15) % 60 }) { Text("+") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("daily" to "Daily", "weekdays" to "Weekdays", "weekly" to "Weekly").forEach { (id, label) ->
                        FilterChip(
                            selected = repeat == id, onClick = { repeat = id }, label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.AccentSoft, selectedLabelColor = Palette.Accent)
                        )
                    }
                }
                if (repeat == "weekly") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.values().forEach { d ->
                            FilterChip(
                                selected = day == d.value, onClick = { day = d.value },
                                label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.AccentSoft, selectedLabelColor = Palette.Accent)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (isFeed || prompt.isNotBlank()),
                onClick = {
                    onSave(initial.copy(name = name.trim(), prompt = prompt.trim(), hour = hour, minute = minute, repeat = repeat, dayOfWeek = day, enabled = true))
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalDialog(onSave: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Other") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("Track a goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(title, { title = it }, "Goal")
                AppTextField(note, { note = it }, "Note (optional)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GoalCategories.forEach { (name, emoji) ->
                        FilterChip(
                            selected = category == name, onClick = { category = name }, label = { Text("$emoji $name") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.AccentSoft, selectedLabelColor = Palette.Accent)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onSave(title, note, category) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
