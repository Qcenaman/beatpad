package com.beatpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatpad.Actions
import com.beatpad.MainViewModel
import com.beatpad.RecTarget
import com.beatpad.audio.AudioEngine
import com.beatpad.audio.RecSource
import com.beatpad.audio.SR
import com.beatpad.voice.Beatbox
import com.beatpad.voice.Melody
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ======================= PADS =======================

@Composable
fun PadsScreen(vm: MainViewModel, onEdit: () -> Unit) {
    val rev = vm.padRev
    val step = vm.playStep
    val patRev = vm.patternRev
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        // MPC layout: pad 1 is bottom-left
        for (r in 0 until 4) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 4) {
                    val i = (3 - r) * 4 + c
                    val seqHit = step >= 0 && vm.engine.pattern[i][step]
                    PadButton(vm, i, seqHit, Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val p = vm.engine.pads[vm.selectedPad]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pad ${vm.selectedPad + 1}", color = TextDim, fontSize = 12.sp)
                Text(p.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onEdit) { Text("Change sound ›") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (vm.recArm) "REC is on: press ▶ and tap pads to record them into the pattern."
            else "Tap pads to play. Turn on REC and press ▶ to record a pattern live.",
            color = TextDim, fontSize = 13.sp
        )
        if (rev < 0 || patRev < 0) Text("")
    }
}

@Composable
fun PadButton(vm: MainViewModel, i: Int, seqHit: Boolean, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val base = padColor(i)
    val selected = vm.selectedPad == i
    val bg = when {
        pressed -> base
        seqHit -> base.copy(alpha = 0.55f)
        else -> PadOff
    }
    Box(
        modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .border(2.dp, if (selected) base else Color.Transparent, RoundedCornerShape(12.dp))
            .pointerInput(i) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    vm.hit(i)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
            .padding(8.dp)
    ) {
        Text("${i + 1}", color = if (pressed) Color.White else base, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            vm.engine.pads[i].name, color = Color.White, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

// ======================= STEPS =======================

@Composable
fun StepsScreen(vm: MainViewModel, actions: Actions) {
    val rev = vm.patternRev
    val padRev = vm.padRev
    val step = vm.playStep
    var exportOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(Modifier.height(18.dp)) {
            Spacer(Modifier.width(64.dp))
            for (s in 0 until AudioEngine.STEPS) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        if (s % 4 == 0) "${s / 4 + 1}" else "·",
                        color = if (s == step) Color.White else TextDim, fontSize = 10.sp
                    )
                }
            }
        }
        for (p in 0 until 16) {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(64.dp).fillMaxHeight().padding(end = 4.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (vm.selectedPad == p) PadOff else Color.Transparent)
                        .clickable { vm.hit(p) }.padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        "${p + 1} ${vm.engine.pads[p].name}", color = padColor(p), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                for (s in 0 until AudioEngine.STEPS) {
                    val on = vm.engine.pattern[p][s]
                    val beatShade = if ((s / 4) % 2 == 0) Color(0xFF2B2B33) else Color(0xFF24242B)
                    Box(
                        Modifier.weight(1f).fillMaxHeight().padding(1.dp).clip(RoundedCornerShape(3.dp))
                            .background(
                                when {
                                    on && s == step -> Color.White
                                    on -> padColor(p)
                                    s == step -> Color(0xFF45454F)
                                    else -> beatShade
                                }
                            )
                            .clickable { vm.toggleStep(p, s) }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Swing ${(vm.swing * 100).toInt()}%", color = Color.White)
        Slider(value = vm.swing, onValueChange = { vm.changeSwing(it) }, valueRange = 0f..0.6f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.clearRow(vm.selectedPad) }, modifier = Modifier.weight(1f)) {
                Text("Clear pad ${vm.selectedPad + 1}")
            }
            OutlinedButton(onClick = { clearOpen = true }, modifier = Modifier.weight(1f)) { Text("Clear all") }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { exportOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Export beat as WAV") }
        Spacer(Modifier.height(24.dp))
        if (rev < 0 || padRev < 0) Text("")
    }
    if (exportOpen) {
        AlertDialog(
            onDismissRequest = { exportOpen = false },
            title = { Text("Export how many bars?") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in listOf(1, 2, 4, 8)) {
                        Chip("$b", PadOff, Modifier.weight(1f)) {
                            exportOpen = false
                            actions.export(b)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { exportOpen = false }) { Text("Cancel") } }
        )
    }
    if (clearOpen) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("Clear the whole pattern?") },
            confirmButton = { TextButton(onClick = { clearOpen = false; vm.clearPattern() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("Cancel") } }
        )
    }
}

// ======================= SAMPLE =======================

@Composable
fun Waveform(data: FloatArray, start: Float, end: Float, color: Color, modifier: Modifier) {
    val bins = remember(data) {
        val n = 240
        FloatArray(n) { b ->
            val a = (b.toLong() * data.size / n).toInt()
            val z = max(a + 1, ((b + 1).toLong() * data.size / n).toInt())
            var m = 0f
            for (k in a until min(z, data.size)) m = max(m, abs(data[k]))
            m
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bw = w / bins.size
        for (b in bins.indices) {
            val x = b * bw
            val frac = b.toFloat() / bins.size
            val inside = frac >= start && frac <= end
            val bh = max(1f, bins[b] * h * 0.95f)
            drawRect(
                if (inside) color else color.copy(alpha = 0.25f),
                Offset(x, (h - bh) / 2), Size(max(1f, bw - 1f), bh)
            )
        }
    }
}

@Composable
fun SampleScreen(vm: MainViewModel, actions: Actions) {
    val i = vm.selectedPad
    val rev = vm.padRev
    val pad = vm.engine.pads[i]
    var range by remember(i, rev) { mutableStateOf(0f..1f) }
    var renameOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        PadPicker(i) { vm.selectedPad = it }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pad ${i + 1}", color = TextDim, fontSize = 12.sp)
                Text(pad.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { renameOpen = true }) { Text("Rename") }
        }
        Text("%.2f s".format(pad.sample.size.toFloat() / SR), color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Waveform(
            pad.sample, range.start, range.endInclusive, padColor(i),
            Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF111114))
                .clickable { vm.hit(i) }
        )
        RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..1f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.hit(i) }, modifier = Modifier.weight(1f)) { Text("▶ Play") }
            OutlinedButton(
                onClick = { vm.cropPad(i, range.start, range.endInclusive) },
                enabled = range.start > 0f || range.endInclusive < 1f,
                modifier = Modifier.weight(1f)
            ) { Text("Trim") }
            OutlinedButton(onClick = { vm.undoPad(i) }, enabled = vm.canUndo(i), modifier = Modifier.weight(1f)) { Text("Undo") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.normalizePad(i) }, modifier = Modifier.weight(1f)) { Text("Louder") }
            OutlinedButton(onClick = { vm.reversePad(i) }, modifier = Modifier.weight(1f)) { Text("Reverse") }
            OutlinedButton(onClick = { vm.resetPad(i) }, modifier = Modifier.weight(1f)) { Text("Reset") }
        }

        SectionTitle("Get a new sound")
        SourceButton("Import audio or video file", "MP3, WAV, M4A, or the sound from a video") { actions.importFile(i) }
        SourceButton("Record with mic", "Your voice, instruments, anything around you") {
            actions.record(RecSource.MIC, RecTarget.ToPad(i))
        }
        SourceButton("Record phone audio", "Captures what another app is playing, like YouTube or a game") {
            actions.record(RecSource.PHONE, RecTarget.ToPad(i))
        }
        SourceButton("Record through speaker", "For apps that block capture: mic picks up the speaker") {
            actions.record(RecSource.SPEAKER, RecTarget.ToPad(i))
        }

        SectionTitle("Pad settings")
        Text("Volume ${(pad.volume * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
        Slider(value = pad.volume, onValueChange = { vm.setVolume(i, it) }, valueRange = 0f..1f)
        Text("Pitch ${if (pad.pitch > 0) "+" else ""}${pad.pitch} semitones", color = Color.White, fontSize = 14.sp)
        Slider(
            value = pad.pitch.toFloat(), onValueChange = { vm.setPitch(i, it.toInt()) },
            valueRange = -24f..24f, steps = 47
        )
        Text("Choke group", color = Color.White, fontSize = 14.sp)
        Text("Pads in the same group cut each other off (like open and closed hi-hats).", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (g in 0..4) {
                Chip(if (g == 0) "Off" else "$g", if (pad.choke == g) padColor(i) else PadOff, Modifier.weight(1f)) {
                    vm.setChoke(i, g)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
    if (renameOpen) {
        var text by remember { mutableStateOf(pad.name) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename pad ${i + 1}") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(16) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renamePad(i, text); renameOpen = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun SourceButton(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Panel)
            .clickable(onClick = onClick).padding(14.dp)
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TextDim, fontSize = 12.sp)
    }
}

// ======================= VOICE =======================

val HitColors = mapOf(
    Beatbox.Hit.KICK to Color(0xFFFF5A36),
    Beatbox.Hit.SNARE to Color(0xFFFFB020),
    Beatbox.Hit.HAT to Color(0xFF3DD6C6),
    Beatbox.Hit.OPEN_HAT to Color(0xFF8C7BFF),
)

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun VoiceScreen(vm: MainViewModel, actions: Actions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card {
            Text("Beatbox → Drums", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Beatbox a beat and it becomes real drums. \"Boom\" = kick, \"Pf\" or \"K\" = snare, \"Ts\" = hi-hat, a long \"Tsss\" = open hat. Headphones help.",
                color = TextDim, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { actions.record(RecSource.MIC, RecTarget.BeatboxTake) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RecRed)
            ) { Text("●  Record beatbox", color = Color.White) }

            val ev = vm.bbEvents
            if (ev.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HitTimeline(ev)
                Spacer(Modifier.height(6.dp))
                val counts = Beatbox.Hit.values().mapNotNull { h ->
                    val c = ev.count { it.type == h }
                    if (c > 0) "$c ${h.label.lowercase()}${if (c > 1) "s" else ""}" else null
                }
                Text("Heard: ${counts.joinToString(", ")}", color = Color.White, fontSize = 14.sp)
                Text("Your tempo: about ${fmtBpm(vm.bbBpm)} BPM", color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ToggleRow("Use my tempo", vm.bbUseTempo) { vm.bbUseTempo = it }
                ToggleRow("Keep my voice sounds (no drum kit)", vm.bbUseVoice) { vm.bbUseVoice = it }
                Text("Kick → pad 1, Snare → pad 2, Hat → pad 3, Open hat → pad 4", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.applyBeatboxPattern() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Put into sequencer")
                }
                Spacer(Modifier.height(10.dp))
                Text("Or make a loop that keeps your exact timing, on pad:", color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                PadPicker(vm.bbLoopPad) { vm.bbLoopPad = it }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { vm.renderBeatboxLoop() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Make loop on pad ${vm.bbLoopPad + 1}")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Card {
            Text("Hum → Instrument", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Hum or sing a melody and it gets played back by an instrument.",
                color = TextDim, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { actions.record(RecSource.MIC, RecTarget.MelodyTake) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RecRed)
            ) { Text("●  Record melody", color = Color.White) }

            val notes = vm.melNotes
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                NoteRoll(notes)
                Text("${notes.size} notes found", color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text("Instrument", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                val insts = Melody.Instrument.values().toList()
                for (row in insts.chunked(3)) {
                    Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (inst in row) {
                            Chip(inst.label, if (vm.melInstrument == inst) Accent else PadOff, Modifier.weight(1f)) {
                                vm.melInstrument = inst
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Octave", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in -2..2) {
                        Chip(if (o > 0) "+$o" else "$o", if (vm.melOctave == o) Accent else PadOff, Modifier.weight(1f)) {
                            vm.melOctave = o
                        }
                    }
                }
                ToggleRow("Snap timing to the tempo", vm.melQuantize) { vm.melQuantize = it }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { vm.previewMelody() }, modifier = Modifier.fillMaxWidth()) { Text("▶ Preview") }
                Spacer(Modifier.height(10.dp))
                Text("Put it on pad:", color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                PadPicker(vm.melPad) { vm.melPad = it }
                Spacer(Modifier.height(6.dp))
                Button(onClick = { vm.melodyToPad() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save to pad ${vm.melPad + 1}")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun HitTimeline(ev: List<Beatbox.Event>) {
    val t0 = ev.first().time
    val span = max(1, ev.last().time - t0)
    Canvas(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111114))) {
        val rows = Beatbox.Hit.values()
        val rowH = size.height / rows.size
        for (e in ev) {
            val x = 8f + (e.time - t0).toFloat() / span * (size.width - 16f)
            val r = rows.indexOf(e.type)
            drawCircle(HitColors[e.type] ?: Color.White, radius = rowH * 0.35f, center = Offset(x, rowH * (r + 0.5f)))
        }
    }
}

@Composable
fun NoteRoll(notes: List<Melody.Note>) {
    val t0 = notes.first().start
    val end = notes.maxOf { it.start + it.len }
    val lo = notes.minOf { it.midi } - 1
    val hi = notes.maxOf { it.midi } + 1
    Canvas(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111114))) {
        val span = max(1, end - t0).toFloat()
        val rowH = size.height / (hi - lo + 1)
        for (n in notes) {
            val x = (n.start - t0) / span * size.width
            val w = max(3f, n.len / span * size.width - 2f)
            val y = (hi - n.midi) * rowH
            drawRect(Accent, Offset(x, y), Size(w, max(3f, rowH - 1f)))
        }
    }
}
