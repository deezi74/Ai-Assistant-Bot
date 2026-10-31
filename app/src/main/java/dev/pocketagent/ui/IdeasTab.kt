package dev.pocketagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.ui.theme.Palette

data class Idea(val emoji: String, val category: String, val title: String, val prompt: String)

val Ideas = listOf(
    Idea("🗓️", "Plan", "Plan my week", "Help me plan this week. Ask me for my fixed commitments first, then propose a realistic schedule and save it to my Library."),
    Idea("🍳", "Plan", "Dinner from my fridge", "I'll list what's in my fridge. Suggest three dinners I can make in under 30 minutes, with steps."),
    Idea("✈️", "Plan", "Weekend trip", "Plan a two-day weekend trip. Ask where I'm starting from and my budget, then build a day-by-day itinerary."),
    Idea("🧾", "Money", "Budget check-in", "Walk me through a quick monthly budget check-in: income, fixed costs, spending categories, and one change to make."),
    Idea("📉", "Money", "Explain a money term", "Explain a personal finance term I keep hearing but don't really understand. Ask me which one."),
    Idea("🛒", "Money", "Compare before I buy", "I'm about to buy something. Ask me what it is, then search for current prices, common complaints and alternatives."),
    Idea("🧠", "Learn", "Teach me in 5 minutes", "Teach me something useful in five minutes. Pick the topic based on what you know about me, then quiz me with 3 questions."),
    Idea("📚", "Learn", "Summarise a long article", "I'll paste a link. Read it and give me the five points that matter plus anything that sounds off."),
    Idea("🗣️", "Learn", "Practice a language", "Let's practice a language in short conversation. Ask which language and my level, then correct me gently."),
    Idea("💪", "Health", "Ten-minute workout", "Give me a 10-minute no-equipment workout I can do right now. Adjust if I say something hurts."),
    Idea("😴", "Health", "Fix my sleep", "Ask me five questions about my sleep habits, then give me a two-week plan with one change at a time."),
    Idea("💬", "People", "Draft a tricky message", "Help me write a message I've been avoiding. Ask who it's for and what I need to say, then give me two versions."),
    Idea("🎁", "People", "Gift ideas", "Help me find a gift. Ask who it's for, what they like and my budget, then suggest five ideas with where to buy."),
    Idea("✍️", "Create", "Write with me", "Let's write something together: a story, a toast, a post. Ask what and for whom, then draft it in my voice."),
    Idea("🎨", "Create", "Name it", "I need a name for something (a project, a pet, a business). Ask about it and give me 15 options in different styles."),
    Idea("🔥", "Fun", "Roast me (nicely)", "Roast my habits based on what you know about me. Keep it funny, finish with one genuinely useful tip."),
    Idea("🎲", "Fun", "Decide for me", "I can't decide between some options. Ask me what they are, weigh them honestly, and pick one."),
    Idea("🧩", "Fun", "Daily puzzle", "Give me a short logic puzzle. Don't give the answer until I try.")
)

@Composable
fun IdeasTab(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val categories = listOf("All") + Ideas.map { it.category }.distinct()
    var selected by rememberSaveable { mutableStateOf("All") }
    val shown = if (selected == "All") Ideas else Ideas.filter { it.category == selected }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text("Ideas", style = MaterialTheme.typography.titleLarge)
                Text("Things ${settings.assistantName} can help with. Tap one to start.", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c ->
                    FilterChip(
                        selected = selected == c,
                        onClick = { selected = c },
                        label = { Text(c) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Palette.AccentSoft,
                            selectedLabelColor = Palette.Accent
                        )
                    )
                }
            }
        }
        items(shown, key = { it.title }) { idea ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.Surface)
                    .clickable { viewModel.prefill(idea.prompt, newSideChat = true) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(idea.emoji, fontSize = 26.sp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(idea.title, style = MaterialTheme.typography.titleMedium)
                    Text(idea.prompt, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 2)
                }
                Text(idea.category, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8A9099))
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}
