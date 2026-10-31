package dev.pocketagent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketagent.ui.theme.Palette
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** How a prop moves around the avatar. */
private enum class Motion { ORBIT, BOB, WIGGLE, PULSE, TYPE }

private data class Scene(val prop: String, val motion: Motion, val caption: String, val glow: Color? = null)

private fun sceneFor(activity: String): Scene = when {
    activity == "thinking" -> Scene("💭", Motion.BOB, "thinking")
    activity == "writing" -> Scene("✍️", Motion.WIGGLE, "writing")
    activity == "web_search" -> Scene("🔎", Motion.ORBIT, "searching the web")
    activity == "fetch_page" -> Scene("💻", Motion.TYPE, "reading a page")
    activity == "shared_files" -> Scene("🗂️", Motion.TYPE, "looking through your files")
    activity == "save_artifact" -> Scene("📝", Motion.WIGGLE, "saving to your Library")
    activity == "remember" -> Scene("🧠", Motion.PULSE, "remembering that")
    activity == "track_goal" -> Scene("🎯", Motion.PULSE, "adding a goal")
    activity == "current_time" -> Scene("🕒", Motion.WIGGLE, "checking the time")
    activity == "phone:flashlight" || activity == "phone:torch" || activity == "phone:flash" ->
        Scene("🔦", Motion.PULSE, "flipping the flashlight", glow = Color(0xFFFFF3B0))
    activity == "phone:volume" -> Scene("🔊", Motion.PULSE, "adjusting volume")
    activity == "phone:brightness" -> Scene("🔆", Motion.PULSE, "changing brightness", glow = Color(0xFFFFF3B0))
    activity == "phone:alarm" || activity == "phone:timer" -> Scene("⏰", Motion.WIGGLE, "setting a ${activity.removePrefix("phone:")}")
    activity == "phone:media" || activity == "phone:play_pause" || activity == "phone:next" || activity == "phone:previous" ->
        Scene("🎵", Motion.ORBIT, "controlling music")
    activity == "phone:open_app" || activity == "phone:launch" -> Scene("📲", Motion.BOB, "opening an app")
    activity == "phone:dial" || activity == "phone:call" -> Scene("📞", Motion.WIGGLE, "opening the dialer")
    activity == "phone:sms" -> Scene("💬", Motion.BOB, "drafting a text")
    activity == "phone:map" || activity == "phone:navigate" -> Scene("🗺️", Motion.BOB, "opening the map")
    activity == "phone:dnd" -> Scene("🌙", Motion.PULSE, "setting Do Not Disturb")
    activity == "phone:battery" -> Scene("🔋", Motion.PULSE, "checking the battery")
    activity.startsWith("phone") -> Scene("📱", Motion.WIGGLE, "using your phone")
    activity == "computer:write" -> Scene("⌨️", Motion.TYPE, "coding on its computer")
    activity == "computer:run" -> Scene("🖥️", Motion.TYPE, "running a program", glow = Color(0xFF9BE7A0))
    activity == "computer:read" -> Scene("🖥️", Motion.TYPE, "looking through its files")
    activity == "computer:delete" -> Scene("🧹", Motion.WIGGLE, "tidying its computer")
    activity.startsWith("computer") -> Scene("💻", Motion.TYPE, "working on its computer")
    activity == "run_code" -> Scene("🧪", Motion.WIGGLE, "running code")
    activity == "learn_skill" -> Scene("📚", Motion.BOB, "learning a new skill")
    activity == "learning" -> Scene("🌱", Motion.PULSE, "learning from that", glow = Color(0xFF9BE7A0))
    activity == "levelup" -> Scene("⭐", Motion.ORBIT, "levelled up!", glow = Color(0xFFFFD54F))
    activity == "change_character" -> Scene("🎨", Motion.WIGGLE, "drawing a new look")
    else -> Scene("⚙️", Motion.ORBIT, "using ${activity.replace('_', ' ')}")
}

/** Short status line for the header while the agent works. */
fun activityCaption(activity: String?): String? = activity?.let { sceneFor(it).caption }

/**
 * The assistant's avatar with a small animated scene for what it is doing:
 * a magnifier circling while it searches, a laptop with typing dots while it reads,
 * a glowing flashlight while it uses the phone, and so on.
 */
@Composable
fun ActivityAvatar(
    kind: String,
    value: String,
    name: String,
    photoFile: File?,
    size: Dp,
    activity: String?,
    photoVersion: Int = 0
) {
    val transition = rememberInfiniteTransition(label = "activity")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "t")
    val breathe by transition.animateFloat(
        1f, 1.06f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe"
    )
    val scene = activity?.let { sceneFor(it) }
    val box = size * 1.7f

    Box(Modifier.size(box), contentAlignment = Alignment.Center) {
        if (scene != null) {
            // Rotating ring that tells the user the agent is busy.
            Canvas(Modifier.size(size + 8.dp).rotate(t * 360f)) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, Palette.Accent, Color.Transparent)),
                    startAngle = 0f, sweepAngle = 300f, useCenter = false,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
            scene.glow?.let { glow ->
                Canvas(Modifier.size(box)) {
                    drawCircle(
                        Brush.radialGradient(listOf(glow.copy(alpha = 0.45f * breathe.coerceAtMost(1.05f)), Color.Transparent)),
                        radius = this.size.minDimension / 2
                    )
                }
            }
        }
        Box(Modifier.scale(if (scene != null) breathe else 1f)) {
            AssistantAvatar(kind, value, name, photoFile, size, photoVersion = photoVersion)
        }
        AnimatedContent(
            targetState = scene,
            transitionSpec = { (fadeIn() + scaleIn()) togetherWith (fadeOut() + scaleOut()) },
            label = "prop"
        ) { s ->
            if (s != null) Prop(s, t, size)
        }
    }
}

@Composable
private fun Prop(scene: Scene, t: Float, avatar: Dp) {
    val propSize = avatar * 0.5f
    val radius = avatar.value * 0.62f
    val angle = t * 2 * Math.PI
    val (dx, dy, rot, sc) = when (scene.motion) {
        Motion.ORBIT -> Quad((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat(), 0f, 1f)
        Motion.BOB -> Quad(radius * 0.7f, -radius * 0.7f + (sin(angle) * 4).toFloat(), 0f, 1f)
        Motion.WIGGLE -> Quad(radius * 0.72f, radius * 0.55f, (sin(angle * 3) * 18).toFloat(), 1f)
        Motion.PULSE -> Quad(radius * 0.72f, radius * 0.55f, 0f, 1f + (sin(angle * 2) * 0.15).toFloat())
        Motion.TYPE -> Quad(0f, radius * 0.95f, 0f, 1f)
    }
    Box(
        Modifier
            .offset { IntOffset((dx * density).roundToInt(), (dy * density).roundToInt()) }
            .rotate(rot)
            .scale(sc),
        contentAlignment = Alignment.Center
    ) {
        if (scene.motion == Motion.TYPE) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.SurfaceHigh)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(scene.prop, fontSize = (propSize.value * 0.7f).sp)
                TypingDots(t)
            }
        } else {
            Box(
                Modifier
                    .size(propSize)
                    .clip(CircleShape)
                    .background(Palette.SurfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Text(scene.prop, fontSize = (propSize.value * 0.58f).sp)
            }
        }
    }
}

@Composable
private fun TypingDots(t: Float) {
    Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
        for (i in 0 until 3) {
            val phase = ((t * 3f) - i).mod(3f)
            val up = if (phase < 1f) sin(phase * Math.PI).toFloat() else 0f
            drawCircle(
                Palette.Accent,
                radius = 1.8.dp.toPx(),
                center = Offset(3.dp.toPx() + i * 6.dp.toPx(), this.size.height / 2 - up * 2.dp.toPx())
            )
        }
    }
}

private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)
