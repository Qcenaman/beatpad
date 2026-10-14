package com.beatpad.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh

class Pad(@Volatile var sample: FloatArray, @Volatile var name: String) {
    @Volatile var volume = 0.8f
    @Volatile var pitch = 0          // semitones
    @Volatile var choke = 0          // 0 = none
}

/**
 * Low-latency mixer + sample-accurate sequencer (drum pattern and note tracks)
 * running on its own audio thread. Note tracks each have an effects bus for synth sounds.
 */
class AudioEngine {
    val pads = Array(16) { Pad(FloatArray(0), "") }
    val pattern = Array(16) { BooleanArray(STEPS) }
    @Volatile var noteTracks: List<NoteTrack> = listOf(NoteTrack())

    @Volatile var bpm = 96f
    @Volatile var swing = 0f          // 0..0.6
    @Volatile var playing = false
    @Volatile var metronome = false
    @Volatile var recArm = false
    @Volatile var active = true       // false when app is in background and idle
    @Volatile var playStep = -1       // drum step 0..15
    @Volatile var playPos = -1L       // steps since play was pressed (for note tracks)
    @Volatile var liveRev = 0         // bumps when live recording changes the pattern

    /** Notes recorded from the keyboard: [track, start step, length, midi]. Read by the UI. */
    val recordedNotes = ConcurrentLinkedQueue<IntArray>()

    private val triggers = ConcurrentLinkedQueue<Int>()
    private val oneShots = ConcurrentLinkedQueue<FloatArray>()
    private val previews = ConcurrentLinkedQueue<IntArray>()
    private val liveEvents = ConcurrentLinkedQueue<IntArray>()

    private class Voice {
        var kind = KIND_DATA
        var pad = -1
        var data: FloatArray? = null
        val synth = SynthVoice()
        val patch = PatchVoice()
        var bus = -1                 // note track index, or -1 for the main mix
        var live = -1                // midi note of a held keyboard key, -1 if not live
        var pos = 0.0
        var rate = 1.0
        var gain = 0f
        var fade = 1f
        var fadeStep = 0f
        var gate = -1L               // samples until note-off; -1 = none
        var active = false
        var age = 0L
    }

    private val voices = Array(48) { Voice() }
    private val clickHi = DrumSynth.click(true)
    private val clickLo = DrumSynth.click(false)

    // per-track effect buses
    private val busIn = FloatArray(MAX_BUS)
    private val busPatch = arrayOfNulls<SynthPatch>(MAX_BUS)
    private val busDelay = IntArray(MAX_BUS)
    private val fx = Array(MAX_BUS) { FxChain() }
    private val lastMonoPitch = DoubleArray(MAX_BUS) { -1.0 }

    // live keyboard state
    private val held = Array(MAX_BUS) { IntArray(16) }
    private val heldCount = IntArray(MAX_BUS)
    private val recStart = Array(MAX_BUS) { DoubleArray(128) { -1.0 } }
    private val supTrack = IntArray(32)
    private val supPos = IntArray(32)
    private val supMidi = IntArray(32)
    private val supStep = LongArray(32)
    private var supCount = 0

    @Volatile private var running = false
    private var thread: Thread? = null

    // sequencer state (audio thread only)
    private var clock = 0L
    private var wasPlaying = false
    private var gridTime = 0.0
    private var nextFire = 0.0
    private var nextStep = 0
    private var globalStep = 0L
    private var lastGlobalFired = 0L
    private var lastFireTime = 0.0
    private var lastStep = 0
    private val suppress = IntArray(16) { -1 }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "BeatPadAudio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    fun trigger(pad: Int) {
        triggers.add(pad)
    }

    fun playOneShot(d: FloatArray) {
        oneShots.add(d)
    }

    /** Plays a short note on a note track (for auditioning while editing). */
    fun previewNote(track: Int, midi: Int) {
        previews.add(intArrayOf(track, midi))
    }

    /** Keyboard key pressed: the note sounds until [liveNoteOff]. */
    fun liveNoteOn(track: Int, midi: Int) {
        liveEvents.add(intArrayOf(1, track, midi))
    }

    fun liveNoteOff(track: Int, midi: Int) {
        liveEvents.add(intArrayOf(0, track, midi))
    }

    fun allLiveOff() {
        liveEvents.add(intArrayOf(2, -1, -1))
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, 192 * 4 * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val block = 192
        val buf = FloatArray(block)
        track.play()
        var trackOn = true
        while (running) {
            if (!active && !playing) {
                if (trackOn) {
                    track.pause(); track.flush(); trackOn = false
                }
                try { Thread.sleep(40) } catch (_: InterruptedException) {}
                continue
            }
            if (!trackOn) {
                track.play(); trackOn = true
            }
            render(buf, block)
            track.write(buf, 0, block, AudioTrack.WRITE_BLOCKING)
        }
        track.stop()
        track.release()
    }

    private fun render(out: FloatArray, n: Int) {
        while (true) {
            val p = triggers.poll() ?: break
            startPad(p, 1f)
            if (recArm && playing && wasPlaying) recordHit(p)
        }
        while (true) {
            val d = oneShots.poll() ?: break
            startData(-1, d, 1.0, 0.9f)
        }
        while (true) {
            val pv = previews.poll() ?: break
            val tr = noteTracks.getOrNull(pv[0]) ?: continue
            startNote(pv[0], tr, pv[1], (SR * 0.35).toLong(), false)
        }
        while (true) {
            val e = liveEvents.poll() ?: break
            when (e[0]) {
                1 -> liveOn(e[1], e[2])
                0 -> liveOff(e[1], e[2])
                else -> {
                    for (v in voices) if (v.active && v.live >= 0) {
                        v.live = -1
                        v.gate = 1
                    }
                    heldCount.fill(0)
                }
            }
        }
        val tracks = noteTracks
        val spbNow = SR * 60.0 / bpm / 4
        for (b in 0 until MAX_BUS) {
            val bp = tracks.getOrNull(b)?.patch
            busPatch[b] = bp
            if (bp != null) busDelay[b] = (DELAY_STEPS[bp.delayTime.coerceIn(0, DELAY_STEPS.size - 1)] * spbNow).toInt()
        }

        for (i in 0 until n) {
            if (playing) {
                if (!wasPlaying) {
                    wasPlaying = true
                    gridTime = clock.toDouble()
                    nextFire = gridTime
                    nextStep = 0
                    globalStep = 0
                    supCount = 0
                }
                if (clock >= nextFire) fireStep()
            } else if (wasPlaying) {
                wasPlaying = false
                playStep = -1
                playPos = -1
                releaseNotes()
                for (row in recStart) row.fill(-1.0)
            }
            var s = 0f
            for (v in voices) {
                if (!v.active) continue
                val x: Float
                if (v.kind == KIND_PATCH) {
                    x = v.patch.next()
                    if (v.patch.done) v.active = false
                } else if (v.kind == KIND_SYNTH) {
                    x = v.synth.next()
                    if (v.synth.done) v.active = false
                } else {
                    val d = v.data!!
                    val ip = v.pos.toInt()
                    if (ip >= d.size - 1) {
                        v.active = false; continue
                    }
                    val fr = (v.pos - ip).toFloat()
                    x = d[ip] + (d[ip + 1] - d[ip]) * fr
                    v.pos += v.rate
                }
                val y = x * v.gain * v.fade
                if (v.bus >= 0) busIn[v.bus] += y else s += y
                if (v.gate > 0) {
                    v.gate--
                    if (v.gate == 0L) {
                        v.gate = -1
                        noteOff(v)
                    }
                }
                if (v.fadeStep > 0f) {
                    v.fade -= v.fadeStep
                    if (v.fade <= 0f) v.active = false
                }
            }
            for (b in 0 until MAX_BUS) {
                val inp = busIn[b]
                busIn[b] = 0f
                val bp = busPatch[b]
                s += if (bp != null) fx[b].process(inp, bp, busDelay[b]) else inp
            }
            s *= 0.9f
            out[i] = if (s > 0.95f || s < -0.95f) tanh(s) else s
            clock++
        }
    }

    private fun noteOff(v: Voice) {
        when (v.kind) {
            KIND_PATCH -> v.patch.release()
            KIND_SYNTH -> v.synth.release()
            else -> if (v.fadeStep == 0f) v.fadeStep = 1f / (SR * 0.012f)
        }
    }

    private fun fireStep() {
        val spb = SR * 60.0 / bpm / 4
        val step = nextStep
        lastFireTime = nextFire
        lastStep = step
        lastGlobalFired = globalStep
        playStep = step
        playPos = globalStep
        for (p in 0 until 16) {
            if (pattern[p][step]) {
                if (suppress[p] == step) suppress[p] = -1 else startPad(p, 1f)
            }
        }
        val tracks = noteTracks
        for (ti in tracks.indices) {
            val tr = tracks[ti]
            if (tr.muted) continue
            val pos = (globalStep % tr.steps).toInt()
            val ns = tr.notes
            for (k in ns.indices) {
                val note = ns[k]
                if (note.start != pos) continue
                if (isSuppressed(ti, pos, note.midi)) continue
                startNote(ti, tr, note.midi, (note.len * spb).toLong(), false)
            }
        }
        // forget suppressions for steps that have passed
        var k = 0
        while (k < supCount) {
            if (supStep[k] <= globalStep) {
                supCount--
                supTrack[k] = supTrack[supCount]; supPos[k] = supPos[supCount]
                supMidi[k] = supMidi[supCount]; supStep[k] = supStep[supCount]
            } else k++
        }
        if (metronome && step % 4 == 0) startData(-1, if (step == 0) clickHi else clickLo, 1.0, 0.6f)
        globalStep++
        gridTime += spb
        nextStep = (step + 1) % STEPS
        nextFire = gridTime + if (nextStep % 2 == 1) swing * spb else 0.0
    }

    private fun isSuppressed(ti: Int, pos: Int, midi: Int): Boolean {
        for (k in 0 until supCount) {
            if (supStep[k] == globalStep && supTrack[k] == ti && supPos[k] == pos && supMidi[k] == midi) return true
        }
        return false
    }

    private fun recordHit(p: Int) {
        val spb = SR * 60.0 / bpm / 4
        val elapsed = clock - lastFireTime
        if (elapsed > spb / 2) {
            val step = (lastStep + 1) % STEPS
            pattern[p][step] = true
            suppress[p] = step   // we already played it live
        } else {
            pattern[p][lastStep] = true
        }
        liveRev++
    }

    /** Where playback is right now, in steps since play was pressed (with fractions). */
    private fun posNow(): Double {
        val spb = SR * 60.0 / bpm / 4
        return lastGlobalFired + (clock - lastFireTime) / spb
    }

    private fun liveOn(ti: Int, midi: Int) {
        val tr = noteTracks.getOrNull(ti) ?: return
        if (ti !in 0 until MAX_BUS || midi !in 0 until 128) return
        if (heldCount[ti] < held[ti].size) {
            held[ti][heldCount[ti]] = midi
            heldCount[ti]++
        }
        if (recArm && playing && wasPlaying) recStart[ti][midi] = posNow()
        startNote(ti, tr, midi, GATE_HOLD, true)
    }

    private fun liveOff(ti: Int, midi: Int) {
        if (ti !in 0 until MAX_BUS || midi !in 0 until 128) return
        // remove from the held-keys list
        val h = held[ti]
        var c = heldCount[ti]
        var k = 0
        while (k < c) {
            if (h[k] == midi) {
                for (j in k until c - 1) h[j] = h[j + 1]
                c--
            } else k++
        }
        heldCount[ti] = c
        // finish a recorded note
        val st = recStart[ti][midi]
        val tr = noteTracks.getOrNull(ti)
        if (st >= 0 && tr != null) {
            recStart[ti][midi] = -1.0
            val startStep = Math.round(st)
            val len = Math.round(posNow() - st).toInt().coerceIn(1, tr.steps)
            val pos = (startStep % tr.steps).toInt()
            recordedNotes.add(intArrayOf(ti, pos, len, midi))
            if (startStep > lastGlobalFired && supCount < supStep.size) {
                supTrack[supCount] = ti; supPos[supCount] = pos; supMidi[supCount] = midi; supStep[supCount] = startStep
                supCount++
            }
        }
        // mono synth: fall back to the last key still held
        val patch = tr?.patch
        if (patch != null && patch.mono && c > 0) {
            val back = h[c - 1]
            for (v in voices) {
                if (v.active && v.kind == KIND_PATCH && v.bus == ti && v.live == midi) {
                    v.patch.glideTo(back, false)
                    v.live = back
                }
            }
            return
        }
        for (v in voices) {
            if (v.active && v.bus == ti && v.live == midi) {
                v.live = -1
                v.gate = 1
            }
        }
    }

    private fun grabVoice(): Voice {
        var v = voices.firstOrNull { !it.active }
        if (v == null) v = voices.minByOrNull { it.age }!!
        v.pad = -1
        v.bus = -1
        v.live = -1
        v.fade = 1f
        v.fadeStep = 0f
        v.gate = -1
        v.age = clock
        return v
    }

    private fun startPad(p: Int, vel: Float) {
        if (p !in 0 until 16) return
        val pad = pads[p]
        val d = pad.sample
        if (d.size < 2) return
        if (pad.choke > 0) {
            for (v in voices) {
                if (v.active && v.pad >= 0 && v.pad != p && pads[v.pad].choke == pad.choke) {
                    v.fadeStep = 1f / (SR * 0.004f)
                }
            }
        }
        startData(p, d, 2.0.pow(pad.pitch / 12.0), pad.volume * vel)
    }

    private fun startData(p: Int, d: FloatArray, rate: Double, gain: Float) {
        val v = grabVoice()
        v.kind = KIND_DATA
        v.pad = p
        v.data = d
        v.pos = 0.0
        v.rate = rate
        v.gain = gain
        v.active = true
    }

    private fun startNote(ti: Int, tr: NoteTrack, midi: Int, lenSamples: Long, live: Boolean) {
        val bus = if (ti in 0 until MAX_BUS) ti else -1
        val gate = max(1L, lenSamples)
        val patch = tr.patch
        val inst = tr.instrument
        if (patch != null) {
            if (patch.mono) {
                var mv: Voice? = null
                for (v in voices) {
                    if (v.active && v.kind == KIND_PATCH && v.bus == bus && (mv == null || v.age > mv.age)) mv = v
                }
                if (mv != null) {
                    mv.patch.glideTo(midi, mv.patch.released)
                    mv.gate = gate
                    mv.live = if (live) midi else -1
                    mv.age = clock
                    mv.fade = 1f
                    mv.fadeStep = 0f
                    if (bus >= 0) lastMonoPitch[bus] = midi.toDouble()
                    return
                }
            }
            val v = grabVoice()
            v.kind = KIND_PATCH
            v.data = null
            v.patch.track = tr
            val from = if (patch.mono && patch.glide > 0.002f && bus >= 0) lastMonoPitch[bus] else -1.0
            v.patch.start(patch, midi, from)
            if (bus >= 0) lastMonoPitch[bus] = midi.toDouble()
            v.gain = tr.volume
            v.bus = bus
            v.live = if (live) midi else -1
            v.gate = gate
            v.active = true
        } else if (inst != null) {
            val v = grabVoice()
            v.kind = KIND_SYNTH
            v.data = null
            v.synth.start(inst, midiHz(midi))
            v.gain = tr.volume
            v.bus = bus
            v.live = if (live) midi else -1
            v.gate = gate
            v.active = true
        } else {
            val pad = pads[tr.padSource.coerceIn(0, 15)]
            val d = pad.sample
            if (d.size < 2) return
            val v = grabVoice()
            v.kind = KIND_DATA
            v.data = d
            v.pos = 0.0
            v.rate = 2.0.pow((midi - NoteTrack.SAMPLE_ROOT + pad.pitch) / 12.0)
            v.gain = tr.volume * pad.volume
            v.bus = bus
            v.live = if (live) midi else -1
            v.gate = gate
            v.active = true
        }
    }

    /** Lets sequenced notes ring out when playback stops (keys you're holding keep sounding). */
    private fun releaseNotes() {
        for (v in voices) {
            if (v.active && v.gate > 0 && v.live < 0) v.gate = 1
        }
    }

    fun clearPattern() {
        for (row in pattern) row.fill(false)
    }

    /** Renders one held note of a synth sound (with its effects tail), e.g. to put on a pad. */
    fun renderPatchNote(patch: SynthPatch, midi: Int, lenSteps: Int): FloatArray {
        val spb = SR * 60.0 / bpm / 4
        val gate = (lenSteps * spb).toInt()
        val fxTail = if (patch.delayMix > 0.001f || patch.reverbMix > 0.001f) SR * 3 else 0
        val total = gate + (patch.ampR * SR * 1.5f).toInt() + fxTail + SR / 10
        val buf = FloatArray(total)
        val pv = PatchVoice()
        pv.start(patch, midi)
        val chain = FxChain()
        val ds = FxChain.delaySamples(patch, bpm)
        for (j in 0 until total) {
            if (j == gate) pv.release()
            val x = if (pv.done) 0f else pv.next()
            buf[j] = chain.process(x, patch, ds)
        }
        return buf
    }

    /** Offline render of the drum pattern and note tracks for export. */
    fun bounce(bars: Int): FloatArray {
        val spb = SR * 60.0 / bpm / 4
        fun stepTime(g: Int): Int = (g * spb + if (g % 2 == 1) swing * spb else 0.0).toInt()

        data class Ev(val time: Int, val pad: Int)
        val events = ArrayList<Ev>()
        for (b in 0 until bars) for (s in 0 until STEPS) {
            val t = stepTime(b * STEPS + s)
            for (p in 0 until 16) if (pattern[p][s] && pads[p].sample.size > 1) events.add(Ev(t, p))
        }
        val tracks = noteTracks.filter { !it.muted }
        val longestNote = tracks.flatMap { it.notes }.maxOfOrNull { it.len } ?: 0
        val loopLen = (spb * STEPS * bars).toInt()
        var total = loopLen
        val out = FloatArray(loopLen + (longestNote * spb).toInt() + SR * 4)

        for ((idx, e) in events.withIndex()) {
            val pad = pads[e.pad]
            val d = pad.sample
            val rate = 2.0.pow(pad.pitch / 12.0)
            var end = e.time + (d.size / rate).toInt()
            if (pad.choke > 0) {
                for (j in idx + 1 until events.size) {
                    val o = events[j]
                    if (o.pad != e.pad && pads[o.pad].choke == pad.choke) {
                        end = min(end, o.time); break
                    }
                }
            }
            end = min(end, out.size)
            total = max(total, mixSample(out, d, e.time, end, rate, pad.volume))
        }

        val sv = SynthVoice()
        for (tr in tracks) {
            val inst = tr.instrument
            val patch = tr.patch
            // [time, length in samples, midi], in time order
            val evs = ArrayList<IntArray>()
            for (g in 0 until bars * STEPS) {
                val pos = g % tr.steps
                for (n in tr.notes) if (n.start == pos) evs.add(intArrayOf(stepTime(g), (n.len * spb).toInt(), n.midi))
            }
            if (patch != null) {
                val wet = renderPatchTrack(patch, evs, out.size, tr.volume)
                val chain = FxChain()
                val ds = FxChain.delaySamples(patch, bpm)
                var lastSound = 0
                for (j in wet.indices) {
                    val y = chain.process(wet[j], patch, ds)
                    out[j] += y
                    if (abs(y) > 1e-4f) lastSound = j
                }
                total = max(total, lastSound + 1)
            } else if (inst != null) {
                for (e in evs) {
                    sv.start(inst, midiHz(e[2]))
                    var j = e[0]
                    var k = 0
                    while (!sv.done && j < out.size) {
                        if (k == e[1]) sv.release()
                        out[j] += sv.next() * tr.volume
                        j++; k++
                    }
                    total = max(total, j)
                }
            } else {
                val pad = pads[tr.padSource.coerceIn(0, 15)]
                for (e in evs) {
                    val rate = 2.0.pow((e[2] - NoteTrack.SAMPLE_ROOT + pad.pitch) / 12.0)
                    val end = min(out.size, e[0] + min(e[1] + SR * 12 / 1000, (pad.sample.size / rate).toInt()))
                    total = max(total, mixSample(out, pad.sample, e[0], end, rate, pad.volume * tr.volume))
                }
            }
        }
        return FloatArray(min(total, out.size)) {
            val s = out[it] * 0.9f
            if (s > 0.95f || s < -0.95f) tanh(s) else s
        }
    }

    /** Plays a list of note events through a synth sound, the same way the live engine does (incl. mono glide). */
    private fun renderPatchTrack(patch: SynthPatch, evs: List<IntArray>, length: Int, volume: Float): FloatArray {
        val outT = FloatArray(length)
        val pool = Array(16) { PatchVoice() }
        val gates = LongArray(16)
        val ages = LongArray(16)
        var ei = 0
        var lastPitch = -1.0
        for (j in 0 until length) {
            while (ei < evs.size && evs[ei][0] <= j) {
                val e = evs[ei]
                ei++
                if (patch.mono) {
                    val v = pool[0]
                    if (!v.done) v.glideTo(e[2], v.released)
                    else v.start(patch, e[2], if (patch.glide > 0.002f) lastPitch else -1.0)
                    gates[0] = max(1, e[1]).toLong()
                    lastPitch = e[2].toDouble()
                } else {
                    var k = -1
                    for (q in pool.indices) if (pool[q].done) { k = q; break }
                    if (k < 0) {
                        k = 0
                        for (q in pool.indices) if (ages[q] < ages[k]) k = q
                    }
                    pool[k].start(patch, e[2])
                    gates[k] = max(1, e[1]).toLong()
                    ages[k] = j.toLong()
                }
            }
            var s = 0f
            for (k in pool.indices) {
                val v = pool[k]
                if (v.done) continue
                s += v.next()
                if (gates[k] > 0) {
                    gates[k]--
                    if (gates[k] == 0L) v.release()
                }
            }
            outT[j] = s * volume
        }
        return outT
    }

    private fun mixSample(out: FloatArray, d: FloatArray, start: Int, end: Int, rate: Double, gain: Float): Int {
        val fadeLen = SR * 4 / 1000
        var pos = 0.0
        var t = start
        while (t < end) {
            val ip = pos.toInt()
            if (ip >= d.size - 1) break
            val fr = (pos - ip).toFloat()
            var x = (d[ip] + (d[ip + 1] - d[ip]) * fr) * gain
            val left = end - t
            if (left < fadeLen) x *= left.toFloat() / fadeLen
            out[t] += x
            pos += rate
            t++
        }
        return t
    }

    companion object {
        const val STEPS = 16
        const val MAX_BUS = 4
        private const val KIND_DATA = 0
        private const val KIND_SYNTH = 1
        private const val KIND_PATCH = 2
        private const val GATE_HOLD = Long.MAX_VALUE
    }
}
