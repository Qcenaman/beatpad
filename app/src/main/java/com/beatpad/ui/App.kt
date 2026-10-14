package com.beatpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.beatpad.Actions
import com.beatpad.MainViewModel
import com.beatpad.RecTarget
import com.beatpad.audio.RecSource
import com.beatpad.audio.RecordingHub
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

val Bg = Color(0xFF16161A)
val Panel = Color(0xFF202027)
val PadOff = Color(0xFF2B2B33)
val TextDim = Color(0xFF9A9AA6)
val Accent = Color(0xFFFF5A36)
val RecRed = Color(0xFFFF3B4E)
val RowColors = listOf(Color(0xFFFF5A36), Color(0xFFFFB020), Color(0xFF3DD6C6), Color(0xFF8C7BFF))

fun padColor(i: Int) = RowColors[i / 4]

const val TAB_PADS = 0
const val TAB_STEPS = 1
const val TAB_SYNTH = 2
const val TAB_SAMPLE = 3
const val TAB_VOICE = 4
const val TAB_HELP = 5

@Composable
fun BeatPadApp(vm: MainViewModel, actions: Actions) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Color(0xFF3DD6C6),
            background = Bg,
            surface = Panel,
            surfaceVariant = PadOff,
        )
    ) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val snack = remember { SnackbarHostState() }
        val rec by RecordingHub.state.collectAsState()

        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { }
                vm.poll()
            }
        }
        LaunchedEffect(vm.ready) {
            if (vm.ready && vm.consumeHelpIntro()) {
                val r = snack.showSnackbar(
                    "New to BeatPad? The Help tab shows how everything works.",
                    actionLabel = "Open Help",
                    duration = SnackbarDuration.Long
                )
                if (r == SnackbarResult.ActionPerformed) tab = TAB_HELP
            }
        }
        LaunchedEffect(vm.message) {
            val m = vm.message
            if (m != null) {
                vm.message = null
                snack.showSnackbar(m)
            }
        }

        Scaffold(
            containerColor = Bg,
            snackbarHost = { SnackbarHost(snack) },
            topBar = { TransportBar(vm) },
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    val items = listOf(
                        "Pads" to "▦", "Steps" to "☷", "Synth" to "≈",
                        "Sample" to "∿", "Voice" to "◉", "Help" to "?"
                    )
                    items.forEachIndexed { i, (label, glyph) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(glyph, fontSize = 20.sp) },
                            label = { Text(label, fontSize = 11.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = PadOff)
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (!vm.ready) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else when (tab) {
                    TAB_PADS -> PadsScreen(vm) { tab = TAB_SAMPLE }
                    TAB_STEPS -> StepsScreen(vm, actions)
                    TAB_SYNTH -> SynthScreen(vm)
                    TAB_SAMPLE -> SampleScreen(vm, actions)
                    TAB_VOICE -> VoiceScreen(vm, actions)
                    else -> HelpScreen()
                }
            }
        }

        val r = rec
        if (r is RecordingHub.State.Recording) RecordingDialog(vm, r)

        vm.speakerFallbackPad?.let { pad ->
            AlertDialog(
                onDismissRequest = { vm.speakerFallbackPad = null },
                title = { Text("Nothing was captured") },
                text = {
                    Text("That app blocks audio capture (common with streaming apps). Record it through the speaker with the mic instead? Turn the volume up and keep the room quiet.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.speakerFallbackPad = null
                        actions.record(RecSource.SPEAKER, RecTarget.ToPad(pad))
                    }) { Text("Record through speaker") }
                },
                dismissButton = { TextButton(onClick = { vm.speakerFallbackPad = null }) { Text("Cancel") } }
            )
        }

        vm.busy?.let { label ->
            Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Panel).padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(label, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun TransportBar(vm: MainViewModel) {
    var showTempo by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Chip(if (vm.playing) "■" else "▶", if (vm.playing) Accent else PadOff, Modifier.width(56.dp)) { vm.togglePlay() }
        Chip("● REC", if (vm.recArm) RecRed else PadOff, Modifier.width(76.dp)) { vm.toggleRec() }
        Spacer(Modifier.weight(1f))
        Chip("−", PadOff, Modifier.width(40.dp)) { vm.changeBpm((vm.bpm - 1).roundToInt().toFloat()) }
        Column(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { showTempo = true }.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(fmtBpm(vm.bpm), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("BPM", color = TextDim, fontSize = 10.sp)
        }
        Chip("+", PadOff, Modifier.width(40.dp)) { vm.changeBpm((vm.bpm + 1).roundToInt().toFloat()) }
        Chip("♩", if (vm.metronome) Color(0xFF3DD6C6) else PadOff, Modifier.width(40.dp)) { vm.toggleMetronome() }
    }
    if (showTempo) TempoDialog(vm) { showTempo = false }
}

fun fmtBpm(b: Float) = if (b % 1f == 0f) b.toInt().toString() else "%.1f".format(b)

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TempoDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val taps = remember { mutableStateListOf<Long>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tempo: ${fmtBpm(vm.bpm)} BPM") },
        text = {
            Column {
                Slider(value = vm.bpm, onValueChange = { vm.changeBpm(it.roundToInt().toFloat()) }, valueRange = 60f..200f)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                        taps.add(now)
                        if (taps.size > 5) taps.removeAt(0)
                        if (taps.size >= 2) {
                            val avg = (taps.last() - taps.first()).toFloat() / (taps.size - 1)
                            vm.changeBpm((60000f / avg).roundToInt().toFloat())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PadOff)
                ) { Text("TAP", fontSize = 22.sp, color = Color.White) }
                Text("Tap along to set the tempo", color = TextDim, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun RecordingDialog(vm: MainViewModel, r: RecordingHub.State.Recording) {
    var level by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(r) {
        while (true) {
            level = level * 0.7f + RecordingHub.level * 0.3f
            elapsed = (System.currentTimeMillis() - r.startedAt) / 1000
            delay(50)
        }
    }
    val target = vm.recTarget
    val title = when (target) {
        RecTarget.BeatboxTake -> "Beatbox now"
        RecTarget.MelodyTake -> "Hum your melody"
        is RecTarget.ToPad -> "Recording pad ${target.index + 1}"
        null -> "Recording"
    }
    val hint = when (r.source) {
        RecSource.MIC -> when (target) {
            RecTarget.BeatboxTake -> "Boom = kick, Pf / K = snare, Ts = hi-hat, Tsss = open hat. Start on the downbeat."
            RecTarget.MelodyTake -> "Hum or sing one note at a time. \"Doo\" or \"da\" sounds work best."
            else -> "Make your sound into the mic."
        }
        RecSource.SPEAKER -> "Switch to the other app and play the sound at full volume. Come back here and tap Stop, or use Stop in the notification."
        RecSource.PHONE -> "Switch to the other app and play the sound. Come back here and tap Stop, or use Stop in the notification."
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(hint, color = TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            Text("● %d:%02d".format(elapsed / 60, elapsed % 60), color = RecRed, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(PadOff)) {
                Box(
                    Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(14.dp)
                        .background(if (level > 0.9f) RecRed else Color(0xFF3DD6C6))
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.stopRecording() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RecRed)
            ) { Text("■  Stop", fontSize = 18.sp, color = Color.White) }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Panel)
            .border(1.dp, PadOff, RoundedCornerShape(16.dp)).padding(16.dp)
    ) { content() }
}

@Composable
fun PadPicker(selected: Int, onPick: (Int) -> Unit) {
    Column {
        for (r in 0 until 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 8) {
                    val i = r * 8 + c
                    Box(
                        Modifier.weight(1f).height(34.dp).padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (i == selected) padColor(i) else PadOff).clickable { onPick(i) },
                        contentAlignment = Alignment.Center
                    ) { Text("${i + 1}", color = Color.White, fontSize = 13.sp) }
                }
            }
        }
    }
}
