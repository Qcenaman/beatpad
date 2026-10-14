package com.beatpad.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
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
 * running on its own audio thread.
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

    private val triggers = ConcurrentLinkedQueue<Int>()
    private val oneShots = ConcurrentLinkedQueue<FloatArray>()
    private val previews = ConcurrentLinkedQueue<IntArray>()

    private class Voice {
        var pad = -1
        var data: FloatArray? = null
        val synth = SynthVoice()
        var isSynth = false
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

    @Volatile private var running = false
    private var thread: Thread? = null

    // sequencer state (audio thread only)
    private var clock = 0L
    private var wasPlaying = false
    private var gridTime = 0.0
    private var nextFire = 0.0
    private var nextStep = 0
    private var globalStep = 0L
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
            startNote(tr, pv[1], (SR * 0.35).toLong())
        }
        for (i in 0 until n) {
            if (playing) {
                if (!wasPlaying) {
                    wasPlaying = true
                    gridTime = clock.toDouble()
                    nextFire = gridTime
                    nextStep = 0
                    globalStep = 0
                }
                if (clock >= nextFire) fireStep()
            } else if (wasPlaying) {
                wasPlaying = false
                playStep = -1
                playPos = -1
                releaseNotes()
            }
            var s = 0f
            for (v in voices) {
                if (!v.active) continue
                val x: Float
                if (v.isSynth) {
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
                s += x * v.gain * v.fade
                if (v.gate > 0) {
                    v.gate--
                    if (v.gate == 0L) {
                        v.gate = -1
                        if (v.isSynth) v.synth.release()
                        else if (v.fadeStep == 0f) v.fadeStep = 1f / (SR * 0.012f)
                    }
                }
                if (v.fadeStep > 0f) {
                    v.fade -= v.fadeStep
                    if (v.fade <= 0f) v.active = false
                }
            }
            s *= 0.9f
            out[i] = if (s > 0.95f || s < -0.95f) tanh(s) else s
            clock++
        }
    }

    private fun fireStep() {
        val spb = SR * 60.0 / bpm / 4
        val step = nextStep
        lastFireTime = nextFire
        lastStep = step
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
                if (note.start == pos) startNote(tr, note.midi, (note.len * spb).toLong())
            }
        }
        if (metronome && step % 4 == 0) startData(-1, if (step == 0) clickHi else clickLo, 1.0, 0.6f)
        globalStep++
        gridTime += spb
        nextStep = (step + 1) % STEPS
        nextFire = gridTime + if (nextStep % 2 == 1) swing * spb else 0.0
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

    private fun grabVoice(): Voice {
        var v = voices.firstOrNull { !it.active }
        if (v == null) v = voices.minByOrNull { it.age }!!
        v.pad = -1
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
        v.pad = p
        v.isSynth = false
        v.data = d
        v.pos = 0.0
        v.rate = rate
        v.gain = gain
        v.active = true
    }

    private fun startNote(tr: NoteTrack, midi: Int, lenSamples: Long) {
        val inst = tr.instrument
        if (inst == null) {
            val pad = pads[tr.padSource.coerceIn(0, 15)]
            val d = pad.sample
            if (d.size < 2) return
            val v = grabVoice()
            v.isSynth = false
            v.data = d
            v.pos = 0.0
            v.rate = 2.0.pow((midi - NoteTrack.SAMPLE_ROOT + pad.pitch) / 12.0)
            v.gain = tr.volume * pad.volume
            v.gate = max(1L, lenSamples)
            v.active = true
        } else {
            val v = grabVoice()
            v.isSynth = true
            v.data = null
            v.synth.start(inst, midiHz(midi))
            v.gain = tr.volume
            v.gate = max(1L, lenSamples)
            v.active = true
        }
    }

    /** Lets held notes ring out when playback stops. */
    private fun releaseNotes() {
        for (v in voices) {
            if (v.active && v.gate > 0) v.gate = 1
        }
    }

    fun clearPattern() {
        for (row in pattern) row.fill(false)
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
        val out = FloatArray(loopLen + (longestNote * spb).toInt() + SR * 3)

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
            for (g in 0 until bars * STEPS) {
                val pos = g % tr.steps
                for (n in tr.notes) {
                    if (n.start != pos) continue
                    val t = stepTime(g)
                    val len = (n.len * spb).toInt()
                    if (inst != null) {
                        sv.start(inst, midiHz(n.midi))
                        var j = t
                        var k = 0
                        while (!sv.done && j < out.size) {
                            if (k == len) sv.release()
                            out[j] += sv.next() * tr.volume
                            j++; k++
                        }
                        total = max(total, j)
                    } else {
                        val pad = pads[tr.padSource.coerceIn(0, 15)]
                        val rate = 2.0.pow((n.midi - NoteTrack.SAMPLE_ROOT + pad.pitch) / 12.0)
                        val end = min(out.size, t + min(len + SR * 12 / 1000, (pad.sample.size / rate).toInt()))
                        total = max(total, mixSample(out, pad.sample, t, end, rate, pad.volume * tr.volume))
                    }
                }
            }
        }
        return FloatArray(min(total, out.size)) {
            val s = out[it] * 0.9f
            if (s > 0.95f || s < -0.95f) tanh(s) else s
        }
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
    }
}
