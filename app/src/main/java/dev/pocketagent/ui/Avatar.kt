package dev.pocketagent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Original, emoji-based avatars drawn on a gradient disc. */
data class BuiltinAvatar(val id: String, val label: String, val glyph: String, val from: Color, val to: Color)

val BuiltinAvatars = listOf(
    BuiltinAvatar("fox", "Fox", "🦊", Color(0xFFFF8A3D), Color(0xFFB8321C)),
    BuiltinAvatar("owl", "Owl", "🦉", Color(0xFF8D6E63), Color(0xFF3E2723)),
    BuiltinAvatar("cat", "Cat", "🐱", Color(0xFFFFC857), Color(0xFFE07A1F)),
    BuiltinAvatar("robot", "Robot", "🤖", Color(0xFF6CD4FF), Color(0xFF2B5FD9)),
    BuiltinAvatar("alien", "Alien", "👾", Color(0xFFA58BFF), Color(0xFF4B2BB3)),
    BuiltinAvatar("ghost", "Ghost", "👻", Color(0xFFB0BEC5), Color(0xFF455A64)),
    BuiltinAvatar("octopus", "Octopus", "🐙", Color(0xFFFF6F91), Color(0xFF8E1F4A)),
    BuiltinAvatar("dragon", "Dragon", "🐉", Color(0xFF3DD9A4), Color(0xFF0F6B4D)),
    BuiltinAvatar("panda", "Panda", "🐼", Color(0xFFE0E0E0), Color(0xFF424242)),
    BuiltinAvatar("frog", "Frog", "🐸", Color(0xFF9CCC65), Color(0xFF33691E)),
    BuiltinAvatar("star", "Star", "🌟", Color(0xFFFFE082), Color(0xFFFF8F00)),
    BuiltinAvatar("bolt", "Bolt", "⚡", Color(0xFF4C9BFF), Color(0xFF1A237E)),
    BuiltinAvatar("planet", "Planet", "🪐", Color(0xFFCE93D8), Color(0xFF4A148C)),
    BuiltinAvatar("cactus", "Cactus", "🌵", Color(0xFFAED581), Color(0xFF2E7D32)),
    BuiltinAvatar("wave", "Wave", "🌊", Color(0xFF80DEEA), Color(0xFF006064)),
    BuiltinAvatar("mono", "Initial", "", Color(0xFF616A78), Color(0xFF1C2027))
)

fun builtinAvatar(id: String): BuiltinAvatar = BuiltinAvatars.firstOrNull { it.id == id } ?: BuiltinAvatars.first()

/** Loads and downsamples an image file off the main thread. Null while loading or on failure. */
@Composable
fun rememberFileImage(file: File?, maxDim: Int = 512, key: Any? = null): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, file?.absolutePath, key) {
        value = if (file == null || !file.isFile) null else withContext(Dispatchers.IO) { decode(file, maxDim) }
    }
    return image
}

private fun decode(file: File, maxDim: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
fun AssistantAvatar(
    kind: String,
    value: String,
    name: String,
    photoFile: File?,
    size: Dp,
    modifier: Modifier = Modifier,
    photoVersion: Int = 0,
    ring: Color? = null
) {
    val ringModifier = if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier
    if (kind == "photo") {
        val image = rememberFileImage(photoFile, maxDim = 512, key = photoVersion)
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "$name avatar",
                contentScale = ContentScale.Crop,
                modifier = modifier.size(size).clip(CircleShape).then(ringModifier)
            )
            return
        }
    }
    val avatar = builtinAvatar(if (kind == "photo") "mono" else value)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(avatar.from, avatar.to)))
            .then(ringModifier),
        contentAlignment = Alignment.Center
    ) {
        if (avatar.glyph.isEmpty()) {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp
            )
        } else {
            Text(avatar.glyph, fontSize = (size.value * 0.5f).sp)
        }
    }
}
