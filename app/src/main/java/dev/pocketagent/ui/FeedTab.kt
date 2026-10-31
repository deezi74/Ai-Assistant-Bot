package dev.pocketagent.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.data.FeedPostEntity
import dev.pocketagent.ui.theme.Palette

@Composable
fun FeedTab(viewModel: AppViewModel) {
    val posts by viewModel.feed.collectAsStateWithLifecycle()
    val busy by viewModel.feedBusy.collectAsStateWithLifecycle()
    val error by viewModel.feedError.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingSteer by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your feed", style = MaterialTheme.typography.titleLarge)
                    Text("Written by ${settings.assistantName} from what you care about", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
                IconButton(onClick = viewModel::refreshFeed, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Refresh, "Write new posts")
                }
            }
        }
        if (!settings.feedIntroDismissed || editingSteer) {
            item {
                SteerCard(
                    steer = settings.feedSteer,
                    onSave = { text ->
                        viewModel.updateSettings { it.copy(feedSteer = text.trim().ifEmpty { it.feedSteer }, feedIntroDismissed = true) }
                        editingSteer = false
                        if (posts.isEmpty()) viewModel.refreshFeed()
                    },
                    onDismiss = {
                        viewModel.updateSettings { it.copy(feedIntroDismissed = true) }
                        editingSteer = false
                    }
                )
            }
        } else {
            item {
                Column {
                    Text(
                        "“${settings.feedSteer}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextMuted,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    TextButton(onClick = { editingSteer = true }) { Text("Steer the feed") }
                }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent) }
        error?.let { e -> item { Hint(e, Palette.Error) } }
        if (posts.isEmpty() && !busy) {
            item {
                SurfaceCard {
                    Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                    Hint("Tap refresh and ${settings.assistantName} writes a few short posts. The \"Write the feed\" routine in Goals does it every morning.")
                    Button(onClick = viewModel::refreshFeed, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)) {
                        Text("Write my feed")
                    }
                }
            }
        }
        items(posts, key = { it.id }) { post ->
            PostCard(
                post = post,
                onLike = { viewModel.react(post, 1) },
                onDislike = { viewModel.react(post, -1) },
                onDiscuss = { viewModel.discuss(post) },
                onOpen = post.url?.let { url -> { openUrl(context, url) } },
                onDelete = { viewModel.deletePost(post) }
            )
        }
        if (posts.isNotEmpty()) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = viewModel::clearFeed) { Text("Clear feed", color = Palette.TextMuted) }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun SteerCard(steer: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(steer) }
    SurfaceCard {
        Text("Tell me what to write about", style = MaterialTheme.typography.titleMedium)
        Hint("One sentence is enough. Likes and dislikes on posts tune it over time.")
        AppTextField(text, { text = it }, "Feed instruction", singleLine = false, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)) { Text("Save") }
            OutlinedButton(onClick = onDismiss) { Text("Not now") }
        }
    }
}

@Composable
private fun PostCard(
    post: FeedPostEntity,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onDiscuss: () -> Unit,
    onOpen: (() -> Unit)?,
    onDelete: () -> Unit
) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(post.emoji, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(post.title, style = MaterialTheme.typography.titleMedium)
                Text(relativeTime(post.createdAt), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
        }
        Text(post.body, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLike) {
                Icon(
                    if (post.reaction > 0) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "More like this",
                    tint = if (post.reaction > 0) Palette.Accent else Palette.TextMuted, modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDislike) {
                Icon(
                    if (post.reaction < 0) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Less like this",
                    tint = if (post.reaction < 0) Palette.Accent else Palette.TextMuted, modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDiscuss) { Icon(Icons.Filled.ChatBubbleOutline, "Discuss", tint = Palette.TextMuted, modifier = Modifier.size(20.dp)) }
            if (onOpen != null) {
                IconButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open source", tint = Palette.TextMuted, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "Remove", tint = Palette.TextMuted, modifier = Modifier.size(20.dp)) }
        }
    }
}

fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No app can open $url", android.widget.Toast.LENGTH_SHORT).show()
    }
}
