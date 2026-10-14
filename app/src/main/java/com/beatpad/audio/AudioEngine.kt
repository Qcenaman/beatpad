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
 * Low-latency mixer + sample-accurate step sequencer running on its own audio thread.
 */
class AudioEngine {
    val pads = Array(16) { Pad(FloatArray(0), "") }
    val pattern = Array(16) { BooleanArray(STEPS) }

    @Volatile var bpm = 96f
    @Volatile var swing = 0f          // 0..0.6
    @Volatile var playing = false
    @Volatile var metronome = false
    @Volatile var recArm = false
    @Volatile var active = true       // false when app is in background and idle
    @Volatile var playStep = -1
    @Volatile var liveRev = 0         // bumps when live recording changes the pattern

    private val triggers = ConcurrentLinkedQueue<Int>()
    private val oneShots = ConcurrentLinkedQueue<FloatArray>()

    private class Voice {
        var pad = -1
        var data: FloatArray? = null
        var pos = 0.0
        var rate = 1.0
        var gain = 0f
        var fade = 1f
        var fadeStep = 0f
        var active = false
        var age = 0L
    }

    private val voices = Array(40) { Voice() }
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
        for (i in 0 until n) {
            if (playing) {
                if (!wasPlaying) {
                    wasPlaying = true
                    gridTime = clock.toDouble()
                    nextFire = gridTime
                    nextStep = 0
                }
                if (clock >= nextFire) fireStep()
            } else if (wasPlaying) {
                wasPlaying = false
                playStep = -1
            }
            var s = 0f
            for (v in voices) {
                if (!v.active) continue
                val d = v.data!!
                val ip = v.pos.toInt()
                if (ip >= d.size - 1) {
                    v.active = false; continue
                }
                val fr = (v.pos - ip).toFloat()
                val x = d[ip] + (d[ip + 1] - d[ip]) * fr
                s += x * v.gain * v.fade
                v.pos += v.rate
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
        for (p in 0 until 16) {
            if (pattern[p][step]) {
                if (suppress[p] == step) suppress[p] = -1 else startPad(p, 1f)
            }
        }
        if (metronome && step % 4 == 0) startData(-1, if (step == 0) clickHi else clickLo, 1.0, 0.6f)
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
        var v = voices.firstOrNull { !it.active }
        if (v == null) v = voices.minByOrNull { it.age }!!
        v.pad = p
        v.data = d
        v.pos = 0.0
        v.rate = rate
        v.gain = gain
        v.fade = 1f
        v.fadeStep = 0f
        v.age = clock
        v.active = true
    }

    fun clearPattern() {
        for (row in pattern) row.fill(false)
    }

    /** Offline render of the pattern for export. */
    fun bounce(bars: Int): FloatArray {
        val spb = SR * 60.0 / bpm / 4
        data class Ev(val time: Int, val pad: Int)
        val events = ArrayList<Ev>()
        for (b in 0 until bars) for (s in 0 until STEPS) {
            val t = (b * STEPS + s) * spb + if (s % 2 == 1) swing * spb else 0.0
            for (p in 0 until 16) if (pattern[p][s] && pads[p].sample.size > 1) events.add(Ev(t.toInt(), p))
        }
        val loopLen = (spb * STEPS * bars).toInt()
        var total = loopLen
        val out = FloatArray(loopLen + SR * 3)
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
            val fadeLen = SR * 4 / 1000
            var pos = 0.0
            for (t in e.time until end) {
                val ip = pos.toInt()
                if (ip >= d.size - 1) break
                val fr = (pos - ip).toFloat()
                var x = (d[ip] + (d[ip + 1] - d[ip]) * fr) * pad.volume
                val left = end - t
                if (left < fadeLen) x *= left.toFloat() / fadeLen
                out[t] += x
                pos += rate
            }
            total = max(total, end)
        }
        return FloatArray(total) {
            val s = out[it] * 0.9f
            if (s > 0.95f || s < -0.95f) tanh(s) else s
        }
    }

    companion object {
        const val STEPS = 16
    }
}
