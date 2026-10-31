package dev.pocketagent.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.data.ArtifactEntity
import dev.pocketagent.data.MediaEntity
import dev.pocketagent.ui.theme.Mono
import dev.pocketagent.ui.theme.Palette
import java.io.File

@Composable
fun LibraryTab(viewModel: AppViewModel) {
    var section by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Library", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Artifacts", "Media").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = section == i,
                        onClick = { section = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Palette.AccentSoft,
                            activeContentColor = Palette.Accent,
                            activeBorderColor = Palette.Accent,
                            inactiveContainerColor = Palette.Surface,
                            inactiveContentColor = Palette.TextMuted,
                            inactiveBorderColor = Palette.Outline
                        ),
                        label = { Text(label) }
                    )
                }
            }
        }
        if (section == 0) ArtifactsSection(viewModel) else MediaSection(viewModel)
    }
}

@Composable
private fun ArtifactsSection(viewModel: AppViewModel) {
    val artifacts by viewModel.artifacts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var open by remember { mutableStateOf<ArtifactEntity?>(null) }
    var writing by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Hint("Documents ${viewModel.settings.value.assistantName} saved for you, plus your own notes.") }
                IconButton(onClick = { writing = true }) { Icon(Icons.AutoMirrored.Filled.NoteAdd, "New note") }
            }
        }
        if (artifacts.isEmpty()) {
            item {
                SurfaceCard {
                    Text("No artifacts yet", style = MaterialTheme.typography.titleMedium)
                    Hint("Ask for a plan, itinerary or summary and say \"save it\". Routines also land here.")
                }
            }
        }
        items(artifacts, key = { it.id }) { a ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Palette.Surface)
                    .clickable { open = a }
                    .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Description, null, tint = Palette.Accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        "${a.format.uppercase()} · ${formatBytes(a.sizeBytes)} · ${relativeTime(a.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextMuted
                    )
                }
                IconButton(onClick = { shareFile(context, viewModel.artifactFile(a), mimeFor(a.format)) }) {
                    Icon(Icons.Filled.Share, "Share", tint = Palette.TextMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }

    open?.let { a ->
        ArtifactViewer(
            artifact = a,
            load = { viewModel.readArtifact(a) },
            onShare = { shareFile(context, viewModel.artifactFile(a), mimeFor(a.format)) },
            onDiscuss = { text ->
                open = null
                viewModel.prefill("About my saved document \"${a.title}\":\n\n${text.take(3000)}\n\n", newSideChat = true)
            },
            onDelete = {
                viewModel.deleteArtifact(a)
                open = null
            },
            onClose = { open = null }
        )
    }
    if (writing) NoteDialog(onSave = { t, b -> viewModel.newNote(t, b); writing = false }, onDismiss = { writing = false })
}

@Composable
private fun ArtifactViewer(
    artifact: ArtifactEntity,
    load: suspend () -> String,
    onShare: () -> Unit,
    onDiscuss: (String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    val text by produceState<String?>(null, artifact.id) { value = load() }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.Background)
                .statusBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                Text(artifact.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { text?.let(onDiscuss) }) { Icon(Icons.Filled.ChatBubbleOutline, "Discuss") }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteOutline, "Delete") }
            }
            SelectionContainer {
                Text(
                    text = text ?: "Loading…",
                    style = if (artifact.format == "md" || artifact.format == "txt") MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                )
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete ${artifact.title}?", "The file is removed from your Library.", onConfirm = onDelete, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun NoteDialog(onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("New note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(title, { title = it }, "Title")
                AppTextField(body, { body = it }, "Note", singleLine = false, minLines = 5)
            }
        },
        confirmButton = { TextButton(enabled = body.isNotBlank(), onClick = { onSave(title, body) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MediaSection(viewModel: AppViewModel) {
    val media by viewModel.media.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var open by remember { mutableStateOf<MediaEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importMedia(uri)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Hint("Images you shared in chat or added here.") }
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Filled.AddPhotoAlternate, "Add image")
                }
            }
        }
        if (media.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SurfaceCard {
                    Text("No media yet", style = MaterialTheme.typography.titleMedium)
                    Hint("Attach a photo in chat with + and it shows up here.")
                }
            }
        }
        items(media, key = { it.id }) { m ->
            val image = rememberFileImage(viewModel.mediaFile(m), maxDim = 320)
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.SurfaceHigh)
                    .clickable { open = m }
            ) {
                if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(96.dp)) }
    }

    open?.let { m ->
        val file = viewModel.mediaFile(m)
        val image = rememberFileImage(file, maxDim = 1600)
        var confirmDelete by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { open = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Palette.Background)
                    .statusBarsPadding()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                    Text(relativeTime(m.createdAt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { open = null; viewModel.attachExisting(m) }) { Icon(Icons.Filled.ChatBubbleOutline, "Ask about it") }
                    IconButton(onClick = { shareFile(context, file, "image/jpeg") }) { Icon(Icons.Filled.Share, "Share") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteOutline, "Delete") }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (image != null) Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (confirmDelete) {
            ConfirmDialog("Delete image?", "It is removed from Media.", onConfirm = {
                viewModel.deleteMedia(m)
                open = null
            }, onDismiss = { confirmDelete = false })
        }
    }
}

fun mimeFor(format: String): String = when (format.lowercase()) {
    "md" -> "text/markdown"
    "csv" -> "text/csv"
    "html" -> "text/html"
    "json" -> "application/json"
    "zip" -> "application/zip"
    else -> "text/plain"
}

fun shareFile(context: Context, file: File, mime: String) {
    if (!file.isFile) {
        android.widget.Toast.makeText(context, "File is missing", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share ${file.name}"))
}
