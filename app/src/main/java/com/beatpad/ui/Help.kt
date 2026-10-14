package com.beatpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class HelpSection(
    val glyph: String,
    val title: String,
    val summary: String,
    val color: Color,
    val items: List<String>,
    val numbered: Boolean = false,
)

private val sections = listOf(
    HelpSection(
        "★", "Quick start", "Your first beat in five steps", Color(0xFFFF5A36),
        listOf(
            "**Tap the pads** to hear the built-in drum kit.",
            "Open **Steps** and tap squares to place drum hits.",
            "Press **▶** at the top to play your beat. Press **■** to stop.",
            "Add a melody or bass line in the **note track** under the pads.",
            "Happy with it? **Steps › Export beat as WAV** saves it as an audio file.",
        ),
        numbered = true
    ),
    HelpSection(
        "▦", "The pads (MPC)", "16 pads, one sound each", Color(0xFFFF5A36),
        listOf(
            "The pads work like an **MPC**: pad 1 is bottom-left, pad 16 is top-right.",
            "**Tap** a pad to play its sound. You can hit several pads at once.",
            "The pad with the colored outline is the **selected pad**. Its name shows under the grid.",
            "**Change sound ›** opens the Sample tab for that pad.",
            "Pads light up when the sequencer plays them.",
        )
    ),
    HelpSection(
        "▶", "Play, tempo and REC", "The bar at the top of the screen", Color(0xFFFFB020),
        listOf(
            "**▶ / ■** starts and stops everything: drums and note tracks together.",
            "**BPM** is the speed. Use **−** and **+**, or tap the number for a slider and a **TAP** button (tap along with a song to match its speed).",
            "**♩** turns on a metronome click.",
            "**● REC** records your pad playing: turn it on, press ▶, then tap pads. Each hit lands on the nearest step of the drum pattern.",
            "Turn REC off to jam without recording.",
        )
    ),
    HelpSection(
        "☷", "Drum sequencer (Steps tab)", "Program a drum pattern on a grid", Color(0xFFFFB020),
        listOf(
            "Each **row** is one pad. Each **square** is a 16th note. 16 squares make one bar.",
            "The numbers **1 2 3 4** on top are the beats.",
            "**Tap a square** to add a hit. Tap it again to remove it.",
            "The light column shows where playback is.",
            "**Tap a row's name** to hear that pad and select it.",
            "**Swing** delays every second 16th note for a looser, groovier feel. Try 10–25%.",
            "**Clear pad** empties the selected row. **Clear all** empties the whole pattern.",
        )
    ),
    HelpSection(
        "♪", "Note track (melodies and bass)", "Notes with pitch and length, under the pads", Color(0xFF3DD6C6),
        listOf(
            "The piano keys on the left are the **pitches**. C4 is middle C. Each column is a 16th note, and a thicker line marks each bar.",
            "**Tap an empty spot** to add a note there.",
            "Choose a **length** first (1/16, 1/8, 1/4, 1/2 or 1 bar). New notes get that length.",
            "**Tap a note** to select it (white outline). Then a length button changes its length, **▲ ▼** change its pitch, **◀ ▶** move it, and **Delete** removes it.",
            "**Drag a note** to move it to a new time or pitch. **Drag its right end** to make it longer or shorter.",
            "**Drag an empty area** to scroll. **Tap a piano key** to hear that note.",
            "**1, 2, +**: up to 4 tracks, each with its own sound and loop.",
            "The **♪ sound button** picks the instrument (Bass, Keys, Synth, Pluck, Pad, Bell) or a **pad's sound** played at any pitch. It also sets the loop length (1–8 bars), volume and mute.",
            "**Expand** hides the pads to give the notes more room. **Pads** brings them back.",
        )
    ),
    HelpSection(
        "∿", "Getting sounds (Sample tab)", "Put any sound on any pad", Color(0xFF8C7BFF),
        listOf(
            "First pick the **pad** at the top of the Sample tab.",
            "**Import audio or video file**: use songs, samples, or the sound from a video on your phone.",
            "**Record with mic**: record your voice, an instrument, or anything around you.",
            "**Record phone audio**: captures what another app is playing. Android asks for permission first. Switch to the other app, play the sound, then come back and tap **Stop** (or use Stop in the notification).",
            "Some apps (many streaming apps) **block** phone audio capture, so you get silence. BeatPad then offers **Record through speaker**.",
            "**Record through speaker**: the mic records your phone's speaker. Turn the volume up and stay in a quiet room.",
            "Silence at the start and end is **cut automatically**.",
        )
    ),
    HelpSection(
        "✂", "Editing a sound", "Trim, pitch, volume and more", Color(0xFF8C7BFF),
        listOf(
            "**Tap the waveform** to hear the sound.",
            "Move the two **slider handles** to choose the start and end, then tap **Trim**.",
            "**Louder** brings the sound to full volume. **Reverse** plays it backwards. **Undo** takes back the last change.",
            "**Reset** puts the built-in drum sound back on the pad.",
            "**Pitch** tunes the pad up or down, in semitones.",
            "**Choke group**: pads in the same group cut each other off. The classic use is a closed hi-hat stopping an open hi-hat (pads 3 and 4 start in group 1).",
            "**Rename** gives the pad a name you'll recognize.",
        )
    ),
    HelpSection(
        "◉", "Beatbox → Drums (Voice tab)", "Turn your voice into a drum beat", Color(0xFFFF7AB6),
        listOf(
            "Tap **Record beatbox** and beatbox one or two bars, then tap Stop.",
            "**\"Boom\"** = kick, **\"Pf\"** or **\"K\"** = snare, **\"Ts\"** = hi-hat, a long **\"Tsss\"** = open hi-hat.",
            "Make each sound short and punchy, and start right on the first beat.",
            "**Put into sequencer** writes the hits onto pads 1–4 in the Steps grid. **Use my tempo** sets the BPM to your speed.",
            "**Keep my voice sounds** uses your own beatbox sounds instead of the drum kit.",
            "**Make loop** keeps your exact timing as one sound on a pad.",
            "Wrong sound detected? Fix it in the Steps tab, or record again louder.",
        )
    ),
    HelpSection(
        "♫", "Hum → Instrument (Voice tab)", "Turn humming into an instrument part", Color(0xFFFF7AB6),
        listOf(
            "Tap **Record melody** and hum or sing with \"doo\" or \"da\", one note at a time.",
            "Pick an **instrument** and **octave**, then tap **Preview**.",
            "**Put on note track** sends the notes to the note track under the pads, where you can fix any wrong note. This is usually the best choice.",
            "**Save to pad** turns the whole melody into a single sound on a pad.",
            "**Snap timing** lines the notes up with the tempo.",
        )
    ),
    HelpSection(
        "↓", "Saving and exporting", "Your work is kept automatically", Color(0xFF3DD6C6),
        listOf(
            "Your pads, drum pattern and note tracks are **saved automatically**, even if you close the app.",
            "**Steps › Export beat as WAV** saves 1, 2, 4 or 8 bars, with drums and note tracks, as an audio file.",
            "Choose where to save it (for example Music or Downloads). You can then share it or open it in other apps.",
            "Updating BeatPad keeps your sounds and patterns.",
        )
    ),
    HelpSection(
        "?", "Tips and troubleshooting", "If something doesn't work", Color(0xFF9A9AA6),
        listOf(
            "**No sound?** Turn up the phone's media volume.",
            "**Pads feel late?** Bluetooth headphones add delay. Use the phone speaker or wired headphones for finger drumming.",
            "**Phone audio recorded silence?** That app blocks capture. Use **Record through speaker** instead.",
            "**Can't record?** Allow the microphone: Android Settings › Apps › BeatPad › Permissions › Microphone.",
            "While recording, BeatPad shows a notification with a **Stop** button, so you can stop from any app.",
            "**Recording max** is 2 minutes per take.",
        )
    ),
)

/** Bold any **text** between double asterisks. */
private fun rich(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(part) }
        else append(part)
    }
}

@Composable
fun HelpScreen() {
    var open by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Help", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Tap a topic to open it.", color = TextDim, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        sections.forEachIndexed { i, s ->
            HelpCard(s, open == i) { open = if (open == i) -1 else i }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HelpCard(s: HelpSection, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .clickable(onClick = onToggle).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(s.color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(s.glyph, color = s.color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(s.summary, color = TextDim, fontSize = 13.sp)
            }
            Text(if (expanded) "−" else "+", color = TextDim, fontSize = 22.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            s.items.forEachIndexed { k, item ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        if (s.numbered) "${k + 1}." else "•",
                        color = s.color, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(22.dp)
                    )
                    Text(rich(item), color = Color(0xFFD2D2DA), fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}
