package com.beatpad

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beatpad.audio.AudioDecoder
import com.beatpad.audio.AudioEngine
import com.beatpad.audio.CaptureService
import com.beatpad.audio.DrumSynth
import com.beatpad.audio.Instrument
import com.beatpad.audio.NoteTrack
import com.beatpad.audio.RecSource
import com.beatpad.audio.RecordingHub
import com.beatpad.audio.SR
import com.beatpad.audio.SampleOps
import com.beatpad.audio.SeqNote
import com.beatpad.audio.SynthPatch
import com.beatpad.audio.SynthPresets
import com.beatpad.audio.Wav
import com.beatpad.voice.Beatbox
import com.beatpad.voice.Melody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class RecTarget {
    data class ToPad(val index: Int) : RecTarget()
    object BeatboxTake : RecTarget()
    object MelodyTake : RecTarget()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val engine = AudioEngine()
    private val storage = Storage(app)

    var ready by mutableStateOf(false)
    var selectedPad by mutableIntStateOf(0)
    var padRev by mutableIntStateOf(0)
    var patternRev by mutableIntStateOf(0)
    var playStep by mutableIntStateOf(-1)
    var bpm by mutableFloatStateOf(96f)
    var swing by mutableFloatStateOf(0f)
    var playing by mutableStateOf(false)
    var recArm by mutableStateOf(false)
    var metronome by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var speakerFallbackPad by mutableStateOf<Int?>(null)

    // Note tracks
    var noteRev by mutableIntStateOf(0)
    var selectedTrack by mutableIntStateOf(0)
    var selectedNoteId by mutableStateOf<Int?>(null)
    var noteLen by mutableIntStateOf(4)
    var playPos by mutableLongStateOf(-1L)

    // Screen layouts (movable / resizable sections)
    var layoutLocked by mutableStateOf(true)
    val layouts = mutableStateMapOf<String, PanelLayout>()

    init {
        layoutLocked = storage.layoutLocked
        for (screen in PanelLayout.SCREENS) {
            storage.loadLayout(screen)?.let { PanelLayout.fromJson(it) }?.let { layouts[screen] = it }
        }
    }

    // Synth
    var synthRev by mutableIntStateOf(0)
    var userPresets by mutableStateOf<List<SynthPatch>>(emptyList())
    var kbOctave by mutableIntStateOf(3)
    private var saveJob: Job? = null

    var recTarget: RecTarget? = null
    private var lastLiveRev = 0
    private val undo = arrayOfNulls<FloatArray>(16)

    // Beatbox
    var bbRaw: FloatArray? = null
    var bbEvents by mutableStateOf<List<Beatbox.Event>>(emptyList())
    var bbBpm by mutableFloatStateOf(96f)
    var bbUseVoice by mutableStateOf(false)
    var bbUseTempo by mutableStateOf(true)
    var bbLoopPad by mutableIntStateOf(14)

    // Melody
    var melNotes by mutableStateOf<List<Melody.Note>>(emptyList())
    var melInstrument by mutableStateOf(Instrument.KEYS)
    var melOctave by mutableIntStateOf(0)
    var melQuantize by mutableStateOf(true)
    var melPad by mutableIntStateOf(15)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            for (i in 0 until 16) {
                engine.pads[i].name = DrumSynth.names[i]
                engine.pads[i].choke = DrumSynth.chokes[i]
            }
            storage.loadState(engine)
            val mine = storage.loadUserPresets()
            for (i in 0 until 16) {
                engine.pads[i].sample = storage.loadSample(i) ?: DrumSynth.make(i)
            }
            withContext(Dispatchers.Main) {
                bpm = engine.bpm
                swing = engine.swing
                padRev++
                patternRev++
                noteRev++
                userPresets = mine
                ready = true
            }
        }
        engine.start()
        viewModelScope.launch {
            RecordingHub.state.collect { onRecState(it) }
        }
    }

    override fun onCleared() {
        engine.stop()
    }

    /** Called every frame by the UI. */
    fun poll() {
        val s = engine.playStep
        if (s != playStep) playStep = s
        val pp = engine.playPos
        if (pp != playPos) playPos = pp
        var recorded = false
        while (true) {
            val r = engine.recordedNotes.poll() ?: break
            val t = engine.noteTracks.getOrNull(r[0]) ?: continue
            t.notes = t.notes + SeqNote(SeqNote.newId(), r[1], r[2], r[3])
            recorded = true
        }
        if (recorded) {
            noteRev++
            saveSoon()
        }
        val lr = engine.liveRev
        if (lr != lastLiveRev) {
            lastLiveRev = lr
            patternRev++
        }
    }

    fun toast(msg: String) {
        message = msg
    }

    // ---------- layouts ----------
    /** The screen's layout, with any sections that are new (or gone) since it was saved fixed up. */
    fun layoutFor(screen: String, ids: List<String>): PanelLayout {
        val base = layouts[screen] ?: PanelLayout(order = ids)
        val order = base.order.filter { it in ids } + ids.filter { it !in base.order }
        return if (order == base.order) base else base.copy(order = order)
    }

    private fun putLayout(screen: String, l: PanelLayout) {
        layouts[screen] = l
        storage.saveLayout(screen, l.toJson())
    }

    fun movePanel(screen: String, ids: List<String>, id: String, to: Int) {
        val l = layoutFor(screen, ids)
        val ord = l.order.toMutableList()
        val from = ord.indexOf(id)
        if (from < 0) return
        ord.removeAt(from)
        ord.add(to.coerceIn(0, ord.size), id)
        putLayout(screen, l.copy(order = ord))
    }

    fun setPanelHeight(screen: String, ids: List<String>, id: String, dp: Float) {
        val l = layoutFor(screen, ids)
        putLayout(screen, l.copy(heights = l.heights + (id to dp)))
    }

    fun setCollapsed(screen: String, ids: List<String>, id: String, collapsed: Boolean) {
        val l = layoutFor(screen, ids)
        putLayout(screen, l.copy(collapsed = if (collapsed) l.collapsed + id else l.collapsed - id))
    }

    fun collapseAll(screen: String, ids: List<String>, collapsed: Boolean) {
        val l = layoutFor(screen, ids)
        putLayout(screen, l.copy(collapsed = if (collapsed) ids.toSet() else emptySet()))
    }

    fun resetLayout(screen: String) {
        layouts.remove(screen)
        storage.saveLayout(screen, null)
    }

    fun toggleLayoutLock() {
        layoutLocked = !layoutLocked
        storage.layoutLocked = layoutLocked
    }

    /** True the first time the app is opened, so the UI can point at the Help tab once. */
    fun consumeHelpIntro(): Boolean {
        if (storage.seenHelp) return false
        storage.seenHelp = true
        return true
    }

    // ---------- note tracks ----------
    val track: NoteTrack
        get() {
            val t = engine.noteTracks
            return t.getOrElse(selectedTrack) { t[0] }
        }

    val selectedNote: SeqNote?
        get() {
            val id = selectedNoteId ?: return null
            return track.notes.firstOrNull { it.id == id }
        }

    fun selectTrack(i: Int) {
        if (i !in engine.noteTracks.indices) return
        selectedTrack = i
        selectedNoteId = null
        noteRev++
    }

    fun addTrack() {
        val list = engine.noteTracks
        if (list.size >= Storage.MAX_TRACKS) return
        val t = NoteTrack()
        val used = list.mapNotNull { it.instrument }.toSet()
        t.instrument = listOf(Instrument.BASS, Instrument.LEAD, Instrument.PAD, Instrument.PLUCK, Instrument.BELL, Instrument.KEYS)
            .firstOrNull { it !in used } ?: Instrument.KEYS
        t.bars = list.maxOf { it.bars }
        engine.noteTracks = list + t
        selectTrack(engine.noteTracks.size - 1)
        saveState()
    }

    fun deleteTrack(i: Int) {
        val list = engine.noteTracks
        if (list.size <= 1 || i !in list.indices) return
        engine.noteTracks = list.filterIndexed { k, _ -> k != i }
        selectTrack((selectedTrack.coerceAtMost(engine.noteTracks.size - 1)))
        saveState()
    }

    fun setTrackInstrument(inst: Instrument?) {
        track.patch = null
        track.instrument = inst
        noteRev++
        saveState()
    }

    fun setTrackPad(p: Int) {
        track.padSource = p
        track.patch = null
        track.instrument = null
        noteRev++
        saveState()
    }

    fun setTrackVolume(v: Float) {
        track.volume = v
        noteRev++
    }

    fun setTrackBars(b: Int) {
        track.bars = b
        noteRev++
        saveState()
    }

    fun toggleTrackMute() {
        track.muted = !track.muted
        noteRev++
        saveState()
    }

    fun clearTrack() {
        track.notes = emptyList()
        selectedNoteId = null
        noteRev++
        saveState()
    }

    fun previewNote(midi: Int) {
        engine.previewNote(selectedTrack, midi)
    }

    /** Saves a moment after the last change, so dragging a slider doesn't write the file 60 times a second. */
    fun saveSoon() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            saveState()
        }
    }

    // ---------- synth ----------
    /** Makes the selected note track a synth with this sound (a copy, so edits don't change the preset). */
    fun loadPreset(p: SynthPatch) {
        track.patch = p.copy()
        synthRev++
        noteRev++
        saveState()
        engine.previewNote(selectedTrack, if (p.category == "Bass") 36 else 60)
    }

    fun updatePatch(change: (SynthPatch) -> SynthPatch) {
        val p = track.patch ?: return
        track.patch = change(p)
        synthRev++
        saveSoon()
    }

    fun saveUserPreset(name: String) {
        val p = track.patch ?: return
        val clean = name.trim().take(24).ifEmpty { "My sound" }
        val np = p.copy(name = clean, category = "Mine")
        track.patch = np
        userPresets = userPresets.filter { it.name != clean } + np
        val list = userPresets
        viewModelScope.launch(Dispatchers.IO) { try { storage.saveUserPresets(list) } catch (_: Exception) {} }
        synthRev++
        noteRev++
        saveState()
        toast("Saved \"$clean\" to your presets")
    }

    fun deleteUserPreset(p: SynthPatch) {
        userPresets = userPresets.filter { it.name != p.name }
        val list = userPresets
        viewModelScope.launch(Dispatchers.IO) { try { storage.saveUserPresets(list) } catch (_: Exception) {} }
    }

    fun liveNoteOn(midi: Int) = engine.liveNoteOn(selectedTrack, midi)
    fun liveNoteOff(track: Int, midi: Int) = engine.liveNoteOff(track, midi)

    /** Renders one note of the selected track's synth sound onto a pad. */
    fun synthToPad(pad: Int, midi: Int, lenSteps: Int) {
        val patch = track.patch ?: return
        busy = "Making the pad sound…"
        viewModelScope.launch(Dispatchers.Default) {
            val d = SampleOps.normalize(SampleOps.autoTrim(engine.renderPatchNote(patch, midi, lenSteps)), 0.9f)
            withContext(Dispatchers.Main) {
                busy = null
                setPadSample(pad, d, patch.name.take(16))
                toast("${patch.name} is on pad ${pad + 1}")
            }
        }
    }

    fun addNote(start: Int, midi: Int) {
        val t = track
        val len = noteLen.coerceAtMost(t.steps - start).coerceAtLeast(1)
        val n = SeqNote(SeqNote.newId(), start, len, midi)
        t.notes = t.notes + n
        selectedNoteId = n.id
        noteRev++
        previewNote(midi)
        saveState()
    }

    /** Replaces a note's position, length or pitch (used live while dragging). */
    fun updateNote(id: Int, start: Int, len: Int, midi: Int) {
        val t = track
        val old = t.notes.firstOrNull { it.id == id } ?: return
        val n = old.copy(
            start = start.coerceIn(0, t.steps - 1),
            len = len.coerceIn(1, t.steps),
            midi = midi.coerceIn(NoteTrack.MIN_NOTE, NoteTrack.MAX_NOTE)
        )
        if (n == old) return
        t.notes = t.notes.map { if (it.id == id) n else it }
        noteRev++
    }

    fun selectNote(id: Int?) {
        selectedNoteId = id
        noteRev++
    }

    fun deleteSelectedNote() {
        val id = selectedNoteId ?: return
        track.notes = track.notes.filter { it.id != id }
        selectedNoteId = null
        noteRev++
        saveState()
    }

    fun nudgeSelected(dStep: Int, dPitch: Int) {
        val n = selectedNote ?: return
        updateNote(n.id, n.start + dStep, n.len, n.midi + dPitch)
        if (dPitch != 0) previewNote((n.midi + dPitch).coerceIn(NoteTrack.MIN_NOTE, NoteTrack.MAX_NOTE))
        saveState()
    }

    /** Sets the length for new notes, and for the selected note if there is one. */
    fun chooseNoteLen(len: Int) {
        noteLen = len
        val n = selectedNote
        if (n != null) {
            updateNote(n.id, n.start, len, n.midi)
            saveState()
        }
        noteRev++
    }

    // ---------- transport ----------
    fun hit(i: Int) {
        engine.trigger(i)
        selectedPad = i
    }

    fun togglePlay() {
        playing = !playing
        engine.playing = playing
    }

    fun stopPlayback() {
        playing = false
        engine.playing = false
    }

    fun changeBpm(v: Float) {
        bpm = v.coerceIn(40f, 220f)
        engine.bpm = bpm
    }

    fun changeSwing(v: Float) {
        swing = v
        engine.swing = v
    }

    fun toggleRec() {
        recArm = !recArm
        engine.recArm = recArm
    }

    fun toggleMetronome() {
        metronome = !metronome
        engine.metronome = metronome
    }

    fun toggleStep(p: Int, s: Int) {
        engine.pattern[p][s] = !engine.pattern[p][s]
        patternRev++
    }

    fun clearPattern() {
        engine.clearPattern()
        patternRev++
    }

    fun clearRow(p: Int) {
        engine.pattern[p].fill(false)
        patternRev++
    }

    // ---------- pad editing ----------
    fun setPadSample(i: Int, d: FloatArray, name: String) {
        if (d.size < 64) {
            toast("That sound was empty"); return
        }
        undo[i] = engine.pads[i].sample
        engine.pads[i].sample = d
        engine.pads[i].name = name
        padRev++
        viewModelScope.launch(Dispatchers.IO) {
            try { storage.saveSample(i, d); storage.saveState(engine) } catch (_: Exception) {}
        }
    }

    fun undoPad(i: Int) {
        val u = undo[i] ?: return
        undo[i] = engine.pads[i].sample
        engine.pads[i].sample = u
        padRev++
        viewModelScope.launch(Dispatchers.IO) { storage.saveSample(i, u) }
    }

    fun canUndo(i: Int) = undo[i] != null

    fun cropPad(i: Int, a: Float, b: Float) {
        setPadSample(i, SampleOps.crop(engine.pads[i].sample, a, b), engine.pads[i].name)
    }

    fun normalizePad(i: Int) = setPadSample(i, SampleOps.normalize(engine.pads[i].sample), engine.pads[i].name)
    fun reversePad(i: Int) = setPadSample(i, SampleOps.reverse(engine.pads[i].sample), engine.pads[i].name)

    fun resetPad(i: Int) {
        setPadSample(i, DrumSynth.make(i), DrumSynth.names[i])
        engine.pads[i].choke = DrumSynth.chokes[i]
        viewModelScope.launch(Dispatchers.IO) { storage.deleteSample(i) }
    }

    fun renamePad(i: Int, name: String) {
        engine.pads[i].name = name.take(16)
        padRev++
    }

    fun setVolume(i: Int, v: Float) {
        engine.pads[i].volume = v; padRev++
    }

    fun setPitch(i: Int, v: Int) {
        engine.pads[i].pitch = v; padRev++
    }

    fun setChoke(i: Int, v: Int) {
        engine.pads[i].choke = v; padRev++
    }

    fun saveState() {
        viewModelScope.launch(Dispatchers.IO) {
            try { storage.saveState(engine) } catch (_: Exception) {}
        }
    }

    // ---------- import / export ----------
    fun importSample(uri: Uri, pad: Int) {
        busy = "Loading sound…"
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val d = SampleOps.normalize(SampleOps.autoTrim(AudioDecoder.decode(getApplication(), uri)))
                val name = (uri.lastPathSegment ?: "Sample").substringAfterLast('/').substringBeforeLast('.').take(16)
                withContext(Dispatchers.Main) {
                    setPadSample(pad, d, name)
                    toast("Loaded onto pad ${pad + 1}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Couldn't open that file: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) { busy = null }
            }
        }
    }

    fun exportBeat(uri: Uri, bars: Int) {
        busy = "Exporting…"
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val d = engine.bounce(bars)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { Wav.write(it, d) }
                withContext(Dispatchers.Main) { toast("Beat exported") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Export failed: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) { busy = null }
            }
        }
    }

    // ---------- recording ----------
    fun startRecording(source: RecSource, target: RecTarget, code: Int = 0, data: Intent? = null) {
        if (RecordingHub.state.value is RecordingHub.State.Recording) return
        recTarget = target
        if (target !is RecTarget.ToPad || source == RecSource.PHONE) stopPlayback()
        val ctx = getApplication<Application>()
        val i = Intent(ctx, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_SOURCE, source.name)
            .putExtra(CaptureService.EXTRA_CODE, code)
        if (data != null) i.putExtra(CaptureService.EXTRA_DATA, data)
        try {
            ContextCompat.startForegroundService(ctx, i)
        } catch (e: Exception) {
            recTarget = null
            toast("Couldn't start recording: ${e.message}")
        }
    }

    fun stopRecording() {
        CaptureService.stop(getApplication())
    }

    private fun onRecState(s: RecordingHub.State) {
        if (s !is RecordingHub.State.Done) return
        RecordingHub.state.value = RecordingHub.State.Idle
        val t = recTarget
        recTarget = null
        if (s.error != null) {
            toast("Recording failed: ${s.error}"); return
        }
        if (t == null) return
        val data = s.data
        if (data.size < SR / 10) {
            toast("That recording was too short"); return
        }
        val silent = SampleOps.peak(data) < 2e-4f
        when (t) {
            is RecTarget.ToPad -> {
                if (silent) {
                    if (s.source == RecSource.PHONE) speakerFallbackPad = t.index
                    else toast("Nothing was heard. Try again closer to the sound.")
                    return
                }
                val name = when (s.source) {
                    RecSource.MIC -> "Mic"
                    RecSource.SPEAKER -> "Speaker"
                    RecSource.PHONE -> "Phone"
                } + " ${t.index + 1}"
                setPadSample(t.index, SampleOps.normalize(SampleOps.autoTrim(data)), name)
                toast("Recorded onto pad ${t.index + 1}")
            }
            RecTarget.BeatboxTake -> if (silent) toast("Nothing was heard") else analyzeBeatbox(data)
            RecTarget.MelodyTake -> if (silent) toast("Nothing was heard") else analyzeMelody(data)
        }
    }

    // ---------- beatbox ----------
    private fun analyzeBeatbox(data: FloatArray) {
        busy = "Listening to your beat…"
        viewModelScope.launch(Dispatchers.Default) {
            val ev = Beatbox.detect(data)
            val tempo = Beatbox.estimateBpm(ev, engine.bpm)
            withContext(Dispatchers.Main) {
                busy = null
                bbRaw = data
                bbEvents = ev
                bbBpm = tempo
                if (ev.isEmpty()) toast("No hits found. Try beatboxing louder and punchier.")
            }
        }
    }

    private fun chopVoiceSounds() {
        val raw = bbRaw ?: return
        for (type in Beatbox.Hit.values()) {
            val e = bbEvents.firstOrNull { it.type == type } ?: continue
            val d = Beatbox.chop(raw, e)
            if (d.size > 64) setPadSample(type.pad, d, "Voice ${type.label}")
        }
    }

    fun applyBeatboxPattern() {
        val ev = bbEvents
        if (ev.isEmpty()) return
        if (bbUseTempo) changeBpm(bbBpm)
        if (bbUseVoice) chopVoiceSounds()
        val steps = Beatbox.quantize(ev, engine.bpm)
        for (h in Beatbox.Hit.values()) engine.pattern[h.pad].fill(false)
        ev.forEachIndexed { i, e -> engine.pattern[e.type.pad][steps[i]] = true }
        patternRev++
        saveState()
        toast("Beat is in the sequencer. Press play!")
    }

    fun renderBeatboxLoop() {
        val ev = bbEvents
        if (ev.isEmpty()) return
        if (bbUseVoice) chopVoiceSounds()
        val sounds = Beatbox.Hit.values().toList().associateWith { engine.pads[it.pad].sample }
        val pad = bbLoopPad
        busy = "Building loop…"
        viewModelScope.launch(Dispatchers.Default) {
            val loop = Beatbox.renderLoop(ev, sounds)
            withContext(Dispatchers.Main) {
                busy = null
                setPadSample(pad, loop, "Beat loop")
                toast("Loop is on pad ${pad + 1}")
            }
        }
    }

    // ---------- melody ----------
    private fun analyzeMelody(data: FloatArray) {
        busy = "Finding the notes…"
        viewModelScope.launch(Dispatchers.Default) {
            val notes = Melody.track(data)
            withContext(Dispatchers.Main) {
                busy = null
                melNotes = notes
                if (notes.isEmpty()) toast("No clear notes found. Try humming steadier and a bit louder.")
            }
        }
    }

    private suspend fun renderMelody(): FloatArray = withContext(Dispatchers.Default) {
        Melody.render(melNotes, melInstrument, melOctave, if (melQuantize) engine.bpm else null)
    }

    fun previewMelody() {
        if (melNotes.isEmpty()) return
        viewModelScope.launch {
            val d = renderMelody()
            engine.playOneShot(d)
        }
    }

    fun melodyToTrack() {
        val notes = melNotes
        if (notes.isEmpty()) return
        val s = SR * 15.0 / engine.bpm
        val t0 = notes[0].start
        val seq = notes.map {
            SeqNote(
                SeqNote.newId(),
                Math.round((it.start - t0) / s).toInt(),
                Math.round(it.len / s).toInt().coerceAtLeast(1),
                (it.midi + 12 * melOctave).coerceIn(NoteTrack.MIN_NOTE, NoteTrack.MAX_NOTE)
            )
        }
        val needed = seq.maxOf { it.start + 1 }
        val bars = listOf(1, 2, 4, 8).firstOrNull { it * 16 >= needed } ?: 8
        val t = track
        t.patch = null
        t.instrument = melInstrument
        t.bars = bars
        t.notes = seq.filter { it.start < bars * 16 }
        selectedNoteId = null
        noteRev++
        saveState()
        toast("Melody is on note track ${selectedTrack + 1}. Edit it under the pads.")
    }

    fun melodyToPad() {
        if (melNotes.isEmpty()) return
        val pad = melPad
        viewModelScope.launch {
            val d = renderMelody()
            setPadSample(pad, d, "${melInstrument.label} line")
            toast("Melody is on pad ${pad + 1}")
        }
    }
}
