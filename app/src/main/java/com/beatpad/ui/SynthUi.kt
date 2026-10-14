package com.beatpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatpad.MainViewModel
import com.beatpad.Storage
import com.beatpad.audio.DELAY_LABELS
import com.beatpad.audio.FilterType
import com.beatpad.audio.Knob
import com.beatpad.audio.LfoTarget
import com.beatpad.audio.NoteTrack
import com.beatpad.audio.SynthPatch
import com.beatpad.audio.SynthPresets
import com.beatpad.audio.Wave
import com.beatpad.audio.fmtHz
import com.beatpad.audio.fmtTime
import com.beatpad.audio.noteName
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// ======================= SYNTH TAB =======================

@Composable
fun SynthScreen(vm: MainViewModel) {
    val nrev = vm.noteRev
    val srev = vm.synthRev
    val tracks = vm.engine.noteTracks
    val ti = vm.selectedTrack.coerceIn(0, tracks.size - 1)
    val tr = tracks[ti]
    val patch = tr.patch
    val color = trackColor(ti)
    var presetsOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var padOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        SynthHeader(vm, ti, tracks, patch, color) { presetsOpen = true }
        val panels = ArrayList<PanelDef>()
        panels += PanelDef("keys", "Keyboard", fill = true, defaultHeight = 190.dp) { KeyboardPanel(vm, color) }
        if (patch == null) {
            panels += PanelDef("start", "Make it a synth") { StartPanel(vm, tr, ti) }
        } else {
            panels += PanelDef("osc", "Oscillators") { OscPanel(vm, patch, color) }
            panels += PanelDef("filter", "Filter") { FilterPanel(vm, patch, color) }
            panels += PanelDef("fenv", "Filter envelope") { FilterEnvPanel(vm, patch, color) }
            panels += PanelDef("amp", "Volume envelope") { AmpEnvPanel(vm, patch, color) }
            panels += PanelDef("lfo", "LFO") { LfoPanel(vm, patch, color) }
            panels += PanelDef("voice", "Voice") { VoicePanel(vm, patch, color) }
            panels += PanelDef("fx", "Effects") { FxPanel(vm, patch, color) }
            panels += PanelDef("save", "Save & pads") { SavePanel({ saveOpen = true }, { padOpen = true }) }
        }
        PanelStack(vm, "synth", panels, Modifier.weight(1f))
    }

    if (presetsOpen) PresetDialog(vm) { presetsOpen = false }
    if (saveOpen && patch != null) SavePresetDialog(vm, patch.name) { saveOpen = false }
    if (padOpen && patch != null) PadSoundDialog(vm, patch) { padOpen = false }
    if (nrev < 0 || srev < 0) Text("")
}

@Composable
private fun SynthHeader(vm: MainViewModel, ti: Int, tracks: List<NoteTrack>, patch: SynthPatch?, color: Color, onPresets: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Track", color = TextDim, fontSize = 12.sp)
        tracks.forEachIndexed { i, _ ->
            val on = i == ti
            SmallChip(
                "${i + 1}", if (on) trackColor(i) else PadOff, Modifier.width(36.dp),
                textColor = if (on) Color.Black else trackColor(i)
            ) { vm.selectTrack(i) }
        }
        if (tracks.size < Storage.MAX_TRACKS) SmallChip("+", PadOff, Modifier.width(36.dp)) { vm.addTrack() }
        SmallChip(
            if (patch != null) "${patch.name} ▾" else "Presets ▾", PadOff, Modifier.weight(1f),
            textColor = color
        ) { onPresets() }
    }
}

// ---------- panels ----------

@Composable
private fun PanelBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp), content = content)
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
private fun signedPct(v: Float) = (if (v > 0f) "+" else "") + "${(v * 100).roundToInt()}%"

@Composable
private fun StartPanel(vm: MainViewModel, tr: NoteTrack, ti: Int) {
    PanelBody {
        Text(
            "Track ${ti + 1} plays the simple \"${trackLabel(tr)}\" sound. Pick a starting sound to turn it into a synth you can shape. The keyboard above already plays the current sound.",
            color = TextDim, fontSize = 13.sp
        )
        PresetGrid(vm, null)
    }
}

@Composable
private fun OscPanel(vm: MainViewModel, p: SynthPatch, color: Color) {
    PanelBody {
        Hint("The raw tone. Saw is bright, Square is hollow, Triangle is soft, Sine is pure.")
        SubLabel("Oscillator 1")
        WavePicker(p.osc1Wave, false, color) { w -> vm.updatePatch { it.copy(osc1Wave = w) } }
        Stepper(
            "Octave", signed(p.osc1Oct),
            { vm.updatePatch { it.copy(osc1Oct = (it.osc1Oct - 1).coerceAtLeast(-2)) } },
            { vm.updatePatch { it.copy(osc1Oct = (it.osc1Oct + 1).coerceAtMost(2)) } }
        )
        ParamSlider("Level", p.osc1Level, pct(p.osc1Level), color) { v -> vm.updatePatch { it.copy(osc1Level = v) } }
        SubLabel("Oscillator 2")
        WavePicker(p.osc2Wave, false, color) { w -> vm.updatePatch { it.copy(osc2Wave = w) } }
        Stepper(
            "Octave", signed(p.osc2Oct),
            { vm.updatePatch { it.copy(osc2Oct = (it.osc2Oct - 1).coerceAtLeast(-2)) } },
            { vm.updatePatch { it.copy(osc2Oct = (it.osc2Oct + 1).coerceAtMost(2)) } }
        )
        Stepper(
            "Semitones", signed(p.osc2Semi),
            { vm.updatePatch { it.copy(osc2Semi = (it.osc2Semi - 1).coerceAtLeast(-12)) } },
            { vm.updatePatch { it.copy(osc2Semi = (it.osc2Semi + 1).coerceAtMost(12)) } }
        )
        ParamSlider("Detune", p.osc2Detune / 0.5f, "${(p.osc2Detune * 100).roundToInt()} cents", color) { v ->
            vm.updatePatch { it.copy(osc2Detune = v * 0.5f) }
        }
        ParamSlider("Level", p.osc2Level, pct(p.osc2Level), color) { v -> vm.updatePatch { it.copy(osc2Level = v) } }
        SubLabel("Extra")
        ParamSlider("Sub", p.subLevel, pct(p.subLevel), color) { v -> vm.updatePatch { it.copy(subLevel = v) } }
        ParamSlider("Noise", p.noiseLevel, pct(p.noiseLevel), color) { v -> vm.updatePatch { it.copy(noiseLevel = v) } }
        ParamSlider("Pulse width", (p.pulseWidth - 0.05f) / 0.9f, pct(p.pulseWidth), color) { v ->
            vm.updatePatch { it.copy(pulseWidth = 0.05f + v * 0.9f) }
        }
    }
}

@Composable
private fun FilterPanel(vm: MainViewModel, p: SynthPatch, color: Color) {
    PanelBody {
        Hint("Shapes the brightness. Lower cutoff is darker; more resonance gives a sharp, squelchy peak.")
        ChoiceRow(FilterType.labels, p.filterType, color) { t -> vm.updatePatch { it.copy(filterType = t) } }
        ParamSlider("Cutoff", p.cutoff, fmtHz(p.cutoffHz()), color) { v -> vm.updatePatch { it.copy(cutoff = v) } }
        ParamSlider("Resonance", p.resonance, pct(p.resonance), color) { v -> vm.updatePatch { it.copy(resonance = v) } }
        ParamSlider("Env amount", (p.filterEnv + 1f) / 2f, signedPct(p.filterEnv), color) { v ->
            vm.updatePatch { it.copy(filterEnv = v * 2f - 1f) }
        }
        ParamSlider("Key follow", p.keyTrack, pct(p.keyTrack), color) { v -> vm.updatePatch { it.copy(keyTrack = v) } }
    }
}

@Composable
private fun FilterEnvPanel(vm: MainViewModel, p: SynthPatch, color: Color) {
    PanelBody {
        Hint("How the filter opens and closes on each note. Turn up Env amount in Filter to hear it.")
        EnvGraph(p.filA, p.filD, p.filS, p.filR, color)
        EnvSliders(
            p.filA, p.filD, p.filS, p.filR, color,
            { v -> vm.updatePatch { it.copy(filA = v) } },
            { v -> vm.updatePatch { it.copy(filD = v) } },
            { v -> vm.updatePatch { it.copy(filS = v) } },
            { v -> vm.updatePatch { it.copy(filR = v) } },
        )
    }
}

@Composable
private fun AmpEnvPanel(vm: MainViewModel, p: SynthPatch, color: Color) {
    PanelBody {
        Hint("Attack fades in, Decay drops after the start, Sustain is the level while held, Release fades out after you let go.")
        EnvGraph(p.ampA, p.ampD, p.ampS, p.ampR, color)
        EnvSliders(
            p.ampA, p.ampD, p.ampS, p.ampR, color,
            { v -> vm.updatePatch { it.copy(ampA = v) } },
            { v -> vm.updatePatch { it.copy(ampD = v) } },
            { v -> vm.updatePatch { it.copy(ampS = v) } },
            { v -> vm.updatePatch { it.copy(ampR = v) } },
        )
    }
}

@Composable
private fun LfoPanel(vm: MainViewModel, p: SynthPatch, color: Color) {
    PanelBody {
        Hint("A repeating wobble: vibrato on Pitch, wah or wobble on Filter, tremolo on Volume.")
        ChoiceRow(LfoTarget.labels, p.lfoTarget, color) { t -> vm.updatePatch { it.copy(lfoTarget = t) } }
        WavePicker(p.lfoWave, true, color) { w -> vm.updatePatch { it.copy(lfoWave = w) } }
        ParamSlider("Speed", Knob.rateToU(p.lfoRate), "%.1f Hz".format(p.lfoRate), color) { v ->
            vm.updatePatch { it.copy(lfoRate = Knob.uToRate(v)) }
        }
        ParamSlider("Amount", p.lfoAmount, pct(p.lfoAmount), color) { v -> vm.updatePatch { it.copy(lfoAmount = v) } }
    }
}

@Composable
private fun VoicePanel(vm: MainViewModel, p: SynthPatch, color: Color) {
    PanelBody {
        ChoiceRow(listOf("Poly (chords)", "Mono (one note)"), if (p.mono) 1 else 0, color) { i ->
            vm.updatePatch { it.copy(mono = i == 1) }
        }
        Hint(
            if (p.mono) "Mono plays one note at a time and slides between notes when Glide is up."
            else "Poly lets you play chords. Switch to Mono to use Glide."
        )
        ParamSlider("Glide", Knob.glideToU(p.glide), if (p.glide < 0.003f) "Off" else fmtTime(p.glide), color) { v ->
            vm.updatePatch { it.copy(glide = Knob.uToGlide(v)) }
        }
        ParamSlider("Volume", p.volume, pct(p.volume), color) { v -> vm.updatePatch { it.copy(volume = v) } }
    }
}

@Composable
private fun FxPanel(vm: MainViewModel, p: SynthPatch, color: Color) {
    PanelBody {
        ParamSlider("Drive", p.drive, pct(p.drive), color) { v -> vm.updatePatch { it.copy(drive = v) } }
        SubLabel("Delay (in time with the beat)")
        ParamSlider("Mix", p.delayMix, pct(p.delayMix), color) { v -> vm.updatePatch { it.copy(delayMix = v) } }
        ChoiceRow(DELAY_LABELS, p.delayTime, color) { i -> vm.updatePatch { it.copy(delayTime = i) } }
        ParamSlider("Feedback", p.delayFeedback / 0.9f, pct(p.delayFeedback / 0.9f), color) { v ->
            vm.updatePatch { it.copy(delayFeedback = v * 0.9f) }
        }
        SubLabel("Reverb")
        ParamSlider("Mix", p.reverbMix, pct(p.reverbMix), color) { v -> vm.updatePatch { it.copy(reverbMix = v) } }
        ParamSlider("Size", p.reverbSize, pct(p.reverbSize), color) { v -> vm.updatePatch { it.copy(reverbSize = v) } }
    }
}

@Composable
private fun SavePanel(onSave: () -> Unit, onPad: () -> Unit) {
    PanelBody {
        Hint("Keep this sound as your own preset, or put one note of it on a pad.")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Save preset") }
            OutlinedButton(onClick = onPad, modifier = Modifier.weight(1f)) { Text("Make pad sound") }
        }
    }
}

@Composable
private fun KeyboardPanel(vm: MainViewModel, color: Color) {
    Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SmallChip("◀ Oct", PadOff) { vm.kbOctave = (vm.kbOctave - 1).coerceAtLeast(1) }
            val rec = vm.recArm
            Text(
                when {
                    rec && vm.playing -> "● Recording into track ${vm.selectedTrack + 1}"
                    rec -> "REC is on: press ▶ to record"
                    else -> "Octave ${vm.kbOctave}"
                },
                color = if (rec) RecRed else TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            SmallChip("Oct ▶", PadOff) { vm.kbOctave = (vm.kbOctave + 1).coerceAtMost(6) }
        }
        PianoKeyboard(vm, color, Modifier.fillMaxWidth().weight(1f))
    }
}

// ---------- presets ----------

@Composable
fun PresetGrid(vm: MainViewModel, current: String?) {
    var toDelete by remember { mutableStateOf<SynthPatch?>(null) }
    for ((cat, list) in SynthPresets.all.groupBy { it.category }) {
        Text(cat, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        for (row in list.chunked(2)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in row) {
                    val on = p.name == current
                    SmallChip(p.name, if (on) Accent else PadOff, Modifier.weight(1f)) { vm.loadPreset(p) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    val mine = vm.userPresets
    if (mine.isNotEmpty()) {
        Text("My presets", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        for (p in mine) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip(p.name, if (p.name == current) Accent else PadOff, Modifier.weight(1f)) { vm.loadPreset(p) }
                SmallChip("✕", PadOff, Modifier.width(44.dp)) { toDelete = p }
            }
        }
    }
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("Tracks already using this sound keep it.") },
            confirmButton = { TextButton(onClick = { vm.deleteUserPreset(p); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PresetDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val srev = vm.synthRev
    val current = vm.track.patch?.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Synth sounds") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Tap a sound to load it on note track ${vm.selectedTrack + 1}. You'll hear a short preview.", color = TextDim, fontSize = 13.sp)
                PresetGrid(vm, current)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
    if (srev < 0) Text("")
}

@Composable
private fun SavePresetDialog(vm: MainViewModel, initial: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(if (initial.startsWith("My ")) initial else "My $initial".take(24)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as preset") },
        text = {
            Column {
                Text("Give your sound a name:", color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveUserPreset(name); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PadSoundDialog(vm: MainViewModel, patch: SynthPatch, onDismiss: () -> Unit) {
    var pad by remember { mutableIntStateOf(vm.selectedPad) }
    var note by remember { mutableIntStateOf(if (patch.category == "Bass") 36 else 60) }
    var lenIdx by remember { mutableIntStateOf(1) }
    val lens = listOf(4, 8, 16, 32)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make a pad sound") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Plays one note of \"${patch.name}\" and puts it on a pad.", color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Stepper(
                    "Note", noteName(note),
                    { note = (note - 1).coerceAtLeast(NoteTrack.MIN_NOTE); vm.previewNote(note) },
                    { note = (note + 1).coerceAtMost(NoteTrack.MAX_NOTE); vm.previewNote(note) }
                )
                SubLabel("Length")
                ChoiceRow(listOf("1/4", "1/2", "1 bar", "2 bars"), lenIdx, Accent) { lenIdx = it }
                SubLabel("Pad")
                PadPicker(pad) { pad = it }
                Spacer(Modifier.height(6.dp))
                Text("Tip: a note track can play that pad at any pitch.", color = TextDim, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { vm.synthToPad(pad, note, lens[lenIdx]); onDismiss() }) { Text("Make it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------- controls ----------

@Composable
fun Hint(text: String) {
    Text(text, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
fun SubLabel(text: String) {
    Text(
        text, color = Color(0xFFD2D2DA), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
fun ParamSlider(label: String, value: Float, display: String, color: Color, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(88.dp))
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = PadOff)
        )
        Text(
            display, color = TextDim, fontSize = 12.sp, textAlign = TextAlign.End, maxLines = 1,
            modifier = Modifier.width(64.dp)
        )
    }
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(88.dp))
        SmallChip("−", PadOff, Modifier.width(48.dp)) { onMinus() }
        Text(
            value, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        SmallChip("+", PadOff, Modifier.width(48.dp)) { onPlus() }
    }
}

@Composable
fun ChoiceRow(labels: List<String>, selected: Int, color: Color, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            SmallChip(l, if (on) color else PadOff, Modifier.weight(1f), textColor = if (on) Color.Black else Color.White) { onPick(i) }
        }
    }
}

@Composable
private fun WavePicker(selected: Int, withRandom: Boolean, color: Color, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val count = if (withRandom) 5 else 4
        for (w in 0 until count) {
            val on = w == selected
            Column(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) color.copy(alpha = 0.22f) else PadOff)
                    .border(1.5.dp, if (on) color else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onPick(w) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                WaveIcon(w, if (on) color else TextDim)
                Text(
                    if (w == Wave.NOISE) "Random" else Wave.labels[w],
                    color = if (on) Color.White else TextDim, fontSize = 10.sp, maxLines = 1
                )
            }
        }
    }
}

private val RANDOM_STEPS = floatArrayOf(0.3f, -0.6f, 0.9f, -0.2f, 0.5f, -0.9f, 0.1f, 0.7f)

@Composable
private fun WaveIcon(wave: Int, color: Color) {
    Canvas(Modifier.width(34.dp).height(16.dp)) {
        val mid = size.height / 2f
        val amp = size.height * 0.42f
        val path = Path()
        val n = 64
        for (i in 0..n) {
            val t = i / n.toFloat()
            val ph = (t * 2f) % 1f
            val y = when (wave) {
                Wave.SINE -> sin(2.0 * PI * ph).toFloat()
                Wave.TRIANGLE -> 1f - 4f * abs(ph - 0.5f)
                Wave.SAW -> 2f * ph - 1f
                Wave.SQUARE -> if (ph < 0.5f) 1f else -1f
                else -> RANDOM_STEPS[(t * 8).toInt().coerceIn(0, 7)]
            }
            val x = t * size.width
            val yy = mid - y * amp
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        drawPath(path, color, style = Stroke(width = 1.6.dp.toPx()))
    }
}

@Composable
private fun EnvGraph(a: Float, d: Float, s: Float, r: Float, color: Color) {
    Canvas(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).height(70.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFF141418))
    ) {
        val pad = 8.dp.toPx()
        val ua = 0.05f + Knob.timeToU(a) * 0.3f
        val ud = 0.05f + Knob.timeToU(d) * 0.3f
        val us = 0.2f
        val ur = 0.05f + Knob.timeToU(r) * 0.3f
        val total = ua + ud + us + ur
        val sx = (size.width - 2 * pad) / total
        val top = pad
        val bottom = size.height - pad
        val sy = bottom - (bottom - top) * s.coerceIn(0f, 1f)
        val x0 = pad
        val x1 = x0 + ua * sx
        val x2 = x1 + ud * sx
        val x3 = x2 + us * sx
        val x4 = x3 + ur * sx
        fun shape(closed: Boolean): Path = Path().apply {
            moveTo(x0, bottom)
            lineTo(x1, top)
            quadraticBezierTo(x1 + (x2 - x1) * 0.1f, sy, x2, sy)
            lineTo(x3, sy)
            quadraticBezierTo(x3 + (x4 - x3) * 0.1f, bottom, x4, bottom)
            if (closed) {
                lineTo(x0, bottom)
                close()
            }
        }
        drawPath(shape(true), color.copy(alpha = 0.15f))
        drawPath(shape(false), color, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun EnvSliders(
    a: Float, d: Float, s: Float, r: Float, color: Color,
    onA: (Float) -> Unit, onD: (Float) -> Unit, onS: (Float) -> Unit, onR: (Float) -> Unit,
) {
    ParamSlider("Attack", Knob.timeToU(a), fmtTime(a), color) { onA(Knob.uToTime(it)) }
    ParamSlider("Decay", Knob.timeToU(d), fmtTime(d), color) { onD(Knob.uToTime(it)) }
    ParamSlider("Sustain", s, pct(s), color) { onS(it) }
    ParamSlider("Release", Knob.timeToU(r), fmtTime(r), color) { onR(Knob.uToTime(it)) }
}

// ---------- keyboard ----------

private val WHITE_STEPS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

/** Multi-touch piano keyboard. Slide a finger across keys to play them one after another. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun PianoKeyboard(vm: MainViewModel, color: Color, modifier: Modifier) {
    val density = LocalDensity.current
    val minWhite = with(density) { 40.dp.toPx() }
    val measurer = rememberTextMeasurer()
    var box by remember { mutableStateOf(IntSize.Zero) }
    val held = remember { mutableStateMapOf<Long, Int>() }
    val track = vm.selectedTrack
    val base = (vm.kbOctave + 1) * 12
    val whites = if (box.width > 0) max(7, (box.width / minWhite).toInt()) else 10

    fun whiteMidi(i: Int): Int = base + 12 * (i / 7) + WHITE_STEPS[i % 7]
    fun blackAfter(i: Int): Boolean = (i % 7) != 2 && (i % 7) != 6

    fun keyAt(pos: Offset): Int? {
        val w = box.width
        val h = box.height
        if (w <= 0 || pos.x < 0f || pos.x >= w || pos.y < 0f || pos.y > h) return null
        val ww = w.toFloat() / whites
        val i = (pos.x / ww).toInt().coerceIn(0, whites - 1)
        if (pos.y < h * 0.6f) {
            val rel = pos.x / ww - i
            if (rel > 0.7f && i < whites - 1 && blackAfter(i)) return whiteMidi(i) + 1
            if (rel < 0.3f && i > 0 && blackAfter(i - 1)) return whiteMidi(i - 1) + 1
        }
        return whiteMidi(i)
    }

    DisposableEffect(track, base) {
        onDispose {
            for (m in held.values.toList()) vm.liveNoteOff(track, m)
            held.clear()
        }
    }

    Canvas(
        modifier.clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(track, base, whites) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        for (ch in ev.changes) {
                            val id = ch.id.value
                            val prev = held[id]
                            if (ch.pressed) {
                                val m = keyAt(ch.position)
                                if (m != prev) {
                                    if (prev != null) vm.liveNoteOff(track, prev)
                                    if (m != null) {
                                        vm.engine.liveNoteOn(track, m)
                                        held[id] = m
                                    } else {
                                        held.remove(id)
                                    }
                                }
                                ch.consume()
                            } else if (prev != null) {
                                vm.liveNoteOff(track, prev)
                                held.remove(id)
                            }
                        }
                    }
                }
            }
    ) {
        val ww = size.width / whites
        val h = size.height
        val down = held.values.toSet()
        for (i in 0 until whites) {
            val m = whiteMidi(i)
            val x = i * ww
            drawRoundRect(
                if (m in down) color else Color(0xFFE9E9EF),
                Offset(x + 1.5f, 0f), Size(ww - 3f, h), CornerRadius(8f, 8f)
            )
            if (m % 12 == 0) {
                val label = measurer.measure(
                    AnnotatedString(noteName(m)),
                    TextStyle(fontSize = 10.sp, color = Color(0xFF5A5A66), fontWeight = FontWeight.Bold)
                )
                drawText(label, topLeft = Offset(x + (ww - label.size.width) / 2f, h - label.size.height - 6f))
            }
        }
        for (i in 0 until whites - 1) {
            if (!blackAfter(i)) continue
            val m = whiteMidi(i) + 1
            val cx = (i + 1) * ww
            drawRoundRect(
                if (m in down) color else Color(0xFF1C1C22),
                Offset(cx - ww * 0.3f, 0f), Size(ww * 0.6f, h * 0.6f), CornerRadius(6f, 6f)
            )
        }
    }
}
