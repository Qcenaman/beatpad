package com.beatpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatpad.MainViewModel
import com.beatpad.Storage
import com.beatpad.audio.Instrument
import com.beatpad.audio.NoteTrack
import com.beatpad.audio.SynthPresets
import com.beatpad.audio.isBlackKey
import com.beatpad.audio.noteName
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val TrackColors = listOf(Color(0xFF3DD6C6), Color(0xFF8C7BFF), Color(0xFFFFB020), Color(0xFFFF7AB6))
fun trackColor(i: Int) = TrackColors[((i % TrackColors.size) + TrackColors.size) % TrackColors.size]

val LengthOptions = listOf(1 to "1/16", 2 to "1/8", 4 to "1/4", 8 to "1/2", 16 to "1 bar")

fun lenLabel(len: Int): String = when {
    len == 16 -> "1 bar"
    len % 16 == 0 -> "${len / 16} bars"
    len == 1 -> "1/16"
    len == 2 -> "1/8"
    len == 3 -> "3/16"
    len == 4 -> "1/4"
    len == 6 -> "3/8"
    len == 8 -> "1/2"
    len == 12 -> "3/4"
    else -> "$len/16"
}

fun trackLabel(t: NoteTrack): String = t.patch?.name ?: t.instrument?.label ?: "Pad ${t.padSource + 1}"

@Composable
fun SmallChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(color)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The note track strip under the pads: track picker, note lengths, piano roll and note editing. */
@Composable
fun NoteTrackPanel(vm: MainViewModel, modifier: Modifier = Modifier) {
    val rev = vm.noteRev
    val tracks = vm.engine.noteTracks
    val ti = vm.selectedTrack.coerceIn(0, tracks.size - 1)
    val tr = tracks[ti]
    val sel = vm.selectedNote
    var dialog by remember { mutableStateOf(false) }

    Column(modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tracks.forEachIndexed { i, t ->
                val on = i == ti
                SmallChip(
                    "${i + 1}", if (on) trackColor(i) else PadOff, Modifier.width(38.dp),
                    textColor = if (on) Color.Black else if (t.muted) TextDim else trackColor(i)
                ) { vm.selectTrack(i) }
            }
            if (tracks.size < Storage.MAX_TRACKS) SmallChip("+", PadOff, Modifier.width(38.dp)) { vm.addTrack() }
            SmallChip(
                "♪ ${trackLabel(tr)}${if (tr.muted) " (muted)" else ""} ▾", PadOff, Modifier.weight(1f),
                textColor = trackColor(ti)
            ) { dialog = true }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val current = sel?.len ?: vm.noteLen
            for ((len, label) in LengthOptions) {
                val on = current == len
                SmallChip(
                    label, if (on) trackColor(ti) else PadOff, Modifier.weight(1f),
                    textColor = if (on) Color.Black else Color.White
                ) { vm.chooseNoteLen(len) }
            }
        }
        Spacer(Modifier.height(6.dp))
        PianoRoll(vm, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sel != null) {
                Text(
                    "${noteName(sel.midi)} · ${lenLabel(sel.len)}", color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallChip("▼", PadOff, Modifier.width(38.dp)) { vm.nudgeSelected(0, -1) }
                    SmallChip("▲", PadOff, Modifier.width(38.dp)) { vm.nudgeSelected(0, 1) }
                    SmallChip("◀", PadOff, Modifier.width(38.dp)) { vm.nudgeSelected(-1, 0) }
                    SmallChip("▶", PadOff, Modifier.width(38.dp)) { vm.nudgeSelected(1, 0) }
                    SmallChip("Delete", RecRed) { vm.deleteSelectedNote() }
                }
            } else {
                Text(
                    if (tr.notes.isEmpty()) "Tap the grid to add a note. Help › Note track explains more."
                    else "Tap a note to edit it. Drag it to move, drag its right end to resize.",
                    color = TextDim, fontSize = 12.sp, maxLines = 2
                )
            }
        }
    }
    if (dialog) TrackDialog(vm) { dialog = false }
    if (rev < 0) Text("")
}

private enum class DragMode { PAN, KEYS, MOVE, RESIZE }

@OptIn(ExperimentalTextApi::class)
@Composable
fun PianoRoll(vm: MainViewModel, modifier: Modifier) {
    val density = LocalDensity.current
    val cellW = with(density) { 24.dp.toPx() }
    val rowH = with(density) { 22.dp.toPx() }
    val keyW = with(density) { 40.dp.toPx() }
    val edgeZone = with(density) { 14.dp.toPx() }
    val corner = with(density) { 4.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val lo = NoteTrack.MIN_NOTE
    val hi = NoteTrack.MAX_NOTE
    val rows = hi - lo + 1
    val rev = vm.noteRev
    val pp = vm.playPos
    val ti = vm.selectedTrack
    val color = trackColor(ti)

    var box by remember { mutableStateOf(IntSize.Zero) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var centeredFor by remember { mutableIntStateOf(-1) }

    fun maxX(): Float = max(0f, vm.track.steps * cellW - (box.width - keyW))
    fun maxY(): Float = max(0f, rows * rowH - box.height)

    // Show the track's notes (or middle C) when a track is opened.
    LaunchedEffect(ti, box) {
        if (box.height > 0 && centeredFor != ti) {
            val notes = vm.track.notes
            val target = if (notes.isEmpty()) 60 else notes.map { it.midi }.average().roundToInt()
            scrollY = ((hi - target) * rowH - box.height / 2f + rowH / 2f).coerceIn(0f, maxY())
            scrollX = 0f
            centeredFor = ti
        }
    }

    val gestures = Modifier.pointerInput(ti) {
        awaitEachGesture {
            val down = awaitFirstDown()
            scrollX = scrollX.coerceIn(0f, maxX())
            scrollY = scrollY.coerceIn(0f, maxY())
            val p0 = down.position
            val tr = vm.track
            val col0 = floor((p0.x - keyW + scrollX) / cellW).toInt()
            val midi0 = hi - floor((p0.y + scrollY) / rowH).toInt()
            val hit = if (p0.x < keyW) null else tr.notes.lastOrNull {
                it.midi == midi0 && it.start < tr.steps && col0 >= it.start && col0 < it.start + it.len
            }
            val mode = when {
                p0.x < keyW -> DragMode.KEYS
                hit == null -> DragMode.PAN
                else -> {
                    val right = keyW + (hit.start + hit.len) * cellW - scrollX
                    if (p0.x > right - min(edgeZone, hit.len * cellW * 0.45f)) DragMode.RESIZE else DragMode.MOVE
                }
            }
            if (hit != null && vm.selectedNoteId != hit.id) vm.selectNote(hit.id)
            val slop = viewConfiguration.touchSlop
            var dragging = false
            var last = p0
            var total = Offset.Zero
            var lastMidi = hit?.midi ?: -1
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                val d = ch.position - last
                last = ch.position
                total += d
                if (!dragging && total.getDistance() > slop) dragging = true
                if (!dragging) continue
                ch.consume()
                when (mode) {
                    DragMode.PAN -> {
                        scrollX = (scrollX - d.x).coerceIn(0f, maxX())
                        scrollY = (scrollY - d.y).coerceIn(0f, maxY())
                    }
                    DragMode.KEYS -> scrollY = (scrollY - d.y).coerceIn(0f, maxY())
                    DragMode.MOVE -> if (hit != null) {
                        val ns = hit.start + (total.x / cellW).roundToInt()
                        val nm = (hit.midi - (total.y / rowH).roundToInt()).coerceIn(lo, hi)
                        vm.updateNote(hit.id, ns, hit.len, nm)
                        if (nm != lastMidi) {
                            lastMidi = nm
                            vm.previewNote(nm)
                        }
                    }
                    DragMode.RESIZE -> if (hit != null) {
                        val nl = hit.len + (total.x / cellW).roundToInt()
                        vm.updateNote(hit.id, hit.start, nl, hit.midi)
                    }
                }
            }
            if (!dragging) {
                when (mode) {
                    DragMode.KEYS -> if (midi0 in lo..hi) vm.previewNote(midi0)
                    DragMode.PAN -> if (col0 in 0 until tr.steps && midi0 in lo..hi) vm.addNote(col0, midi0)
                    else -> if (hit != null) vm.previewNote(hit.midi)
                }
            } else if (mode == DragMode.MOVE || mode == DragMode.RESIZE) {
                vm.saveState()
            }
        }
    }

    Canvas(
        modifier.clipToBounds().background(Color(0xFF141418))
            .onSizeChanged { box = it }
            .then(gestures)
    ) {
        if (rev < 0) return@Canvas
        val tr = vm.track
        val steps = tr.steps
        val w = size.width
        val h = size.height
        val sx = scrollX.coerceIn(0f, max(0f, steps * cellW - (w - keyW)))
        val sy = scrollY.coerceIn(0f, max(0f, rows * rowH - h))
        val r0 = floor(sy / rowH).toInt().coerceAtLeast(0)
        val r1 = min(rows - 1, floor((sy + h) / rowH).toInt())

        // pitch rows
        for (r in r0..r1) {
            val m = hi - r
            val y = r * rowH - sy
            drawRect(if (isBlackKey(m)) Color(0xFF1B1B21) else Color(0xFF24242B), Offset(keyW, y), Size(w - keyW, rowH))
            if (m % 12 == 0) drawLine(Color(0xFF3C3C48), Offset(keyW, y + rowH - 0.5f), Offset(w, y + rowH - 0.5f), 1f)
        }
        // step, beat and bar lines
        val c0 = floor(sx / cellW).toInt().coerceAtLeast(0)
        val c1 = min(steps, ceil((sx + w - keyW) / cellW).toInt())
        for (c in c0..c1) {
            val x = keyW + c * cellW - sx
            val col = when {
                c % 16 == 0 -> Color(0xFF7A7A88)
                c % 4 == 0 -> Color(0xFF44444F)
                else -> Color(0xFF2E2E37)
            }
            drawLine(col, Offset(x, 0f), Offset(x, h), if (c % 16 == 0) 2f else 1f)
        }
        // past the end of the loop
        val endX = keyW + steps * cellW - sx
        if (endX < w) {
            val from = max(endX, keyW)
            drawRect(Color(0xE60E0E11), Offset(from, 0f), Size(w - from, h))
        }
        // playhead
        val pos = if (pp >= 0) (pp % steps).toInt() else -1
        if (pos >= 0) drawRect(Color.White.copy(alpha = 0.10f), Offset(keyW + pos * cellW - sx, 0f), Size(cellW, h))
        // notes
        val selId = vm.selectedNoteId
        for (n in tr.notes) {
            if (n.start >= steps) continue
            val x = keyW + n.start * cellW - sx
            val y = (hi - n.midi) * rowH - sy
            val nw = n.len * cellW
            if (x > w || x + nw < keyW || y > h || y + rowH < 0f) continue
            val sounding = pos >= 0 && pos >= n.start && pos < n.start + n.len
            val topLeft = Offset(x + 1f, y + 1f)
            val sz = Size(nw - 2f, rowH - 2f)
            drawRoundRect(if (sounding) Color.White else color, topLeft, sz, CornerRadius(corner, corner))
            drawRect(Color.Black.copy(alpha = 0.35f), Offset(x + nw - 7f, y + rowH * 0.3f), Size(3f, rowH * 0.4f))
            if (n.id == selId) drawRoundRect(Color.White, topLeft, sz, CornerRadius(corner, corner), style = Stroke(4f))
        }
        // piano keys
        drawRect(Color(0xFF0F0F12), Offset.Zero, Size(keyW, h))
        for (r in r0..r1) {
            val m = hi - r
            val y = r * rowH - sy
            val black = isBlackKey(m)
            drawRect(if (black) Color(0xFF2A2A31) else Color(0xFFDADAE1), Offset(0f, y + 0.5f), Size(keyW - 3f, rowH - 1f))
            val label = measurer.measure(
                AnnotatedString(noteName(m)),
                TextStyle(
                    fontSize = 9.sp,
                    color = if (black) Color(0xFFB4B4BE) else Color(0xFF26262C),
                    fontWeight = if (m % 12 == 0) FontWeight.Bold else FontWeight.Normal
                )
            )
            drawText(label, topLeft = Offset(5f, y + (rowH - label.size.height) / 2f))
        }
    }
}

@Composable
fun TrackDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val rev = vm.noteRev
    val tr = vm.track
    val ti = vm.selectedTrack
    val color = trackColor(ti)
    var confirm by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { vm.saveState(); onDismiss() },
        title = { Text("Note track ${ti + 1}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val synthName = tr.patch?.name
                Text("Synth", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                SmallChip(
                    if (synthName != null) "Synth · $synthName" else "Use a synth sound",
                    if (synthName != null) color else PadOff, Modifier.fillMaxWidth(),
                    textColor = if (synthName != null) Color.Black else Color.White
                ) { if (tr.patch == null) vm.loadPreset(SynthPresets.all[0]) }
                Text("Shape synth sounds and pick presets in the Synth tab.", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text("Simple instruments", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                for (row in Instrument.values().toList().chunked(3)) {
                    Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (inst in row) {
                            val on = tr.patch == null && tr.instrument == inst
                            SmallChip(
                                inst.label, if (on) color else PadOff, Modifier.weight(1f),
                                textColor = if (on) Color.Black else Color.White
                            ) {
                                vm.setTrackInstrument(inst)
                                vm.previewNote(60)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Or play a pad's sound at any pitch", color = Color.White, fontWeight = FontWeight.Bold)
                Text("The pad plays at its normal pitch on C4.", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                PadPicker(if (tr.patch == null && tr.instrument == null) tr.padSource else -1) {
                    vm.setTrackPad(it)
                    vm.previewNote(60)
                }
                Spacer(Modifier.height(12.dp))
                Text("Loop length", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in listOf(1, 2, 4, 8)) {
                        val on = tr.bars == b
                        SmallChip(
                            if (b == 1) "1 bar" else "$b bars", if (on) color else PadOff, Modifier.weight(1f),
                            textColor = if (on) Color.Black else Color.White
                        ) { vm.setTrackBars(b) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Volume ${(tr.volume * 100).toInt()}%", color = Color.White)
                Slider(value = tr.volume, onValueChange = { vm.setTrackVolume(it) }, valueRange = 0f..1f)
                ToggleRow("Mute this track", tr.muted) { vm.toggleTrackMute() }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirm = "clear" }, modifier = Modifier.weight(1f)) { Text("Clear notes") }
                    if (vm.engine.noteTracks.size > 1) {
                        OutlinedButton(onClick = { confirm = "delete" }, modifier = Modifier.weight(1f)) { Text("Delete track") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveState(); onDismiss() }) { Text("Done") } }
    )

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "clear") "Clear all notes on track ${ti + 1}?" else "Delete track ${ti + 1}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (what == "clear") vm.clearTrack() else {
                        vm.deleteTrack(ti)
                        onDismiss()
                    }
                }) { Text(if (what == "clear") "Clear" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
    if (rev < 0) Text("")
}
