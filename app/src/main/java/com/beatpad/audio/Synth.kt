package com.beatpad.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class Instrument(val label: String, val release: Double) {
    BASS("Bass", 0.15),
    KEYS("Keys", 0.6),
    LEAD("Synth", 0.15),
    PLUCK("Pluck", 0.5),
    PAD("Pad", 0.8),
    BELL("Bell", 0.6),
}

fun midiHz(m: Int): Double = 440.0 * 2.0.pow((m - 69) / 12.0)

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
fun noteName(m: Int): String = NOTE_NAMES[((m % 12) + 12) % 12] + (m / 12 - 1)
fun isBlackKey(m: Int): Boolean = when (((m % 12) + 12) % 12) { 1, 3, 6, 8, 10 -> true; else -> false }

/**
 * One instrument note, generated sample by sample. Used live by the engine and offline for export,
 * so a note sounds the same in both. Allocation-free after construction.
 */
class SynthVoice {
    private var inst = Instrument.KEYS
    private var f = 440.0
    private var i = 0L
    private var relAt = -1L
    private var relLen = 1
    var done = true
        private set
    private val lp = Biquad()
    private val ks = FloatArray(4096)
    private var ksLen = 2
    private var ksIdx = 0
    private var ph1 = 0.0
    private var ph2 = 0.0
    private var ph3 = 0.0
    private val rnd = Random(42)

    fun start(instrument: Instrument, freq: Double) {
        inst = instrument
        f = freq
        i = 0
        relAt = -1
        relLen = (instrument.release * SR).toInt().coerceAtLeast(64)
        ph1 = 0.0; ph2 = 0.0; ph3 = 0.0
        lp.reset()
        when (instrument) {
            Instrument.LEAD -> lp.set(Biquad.LP, 2600f, 1.0f)
            Instrument.PAD -> lp.set(Biquad.LP, 1500f, 0.8f)
            Instrument.BASS -> lp.set(Biquad.LP, 1580f, 1.2f)
            Instrument.PLUCK -> {
                ksLen = (SR / f).toInt().coerceIn(2, ks.size)
                for (k in 0 until ksLen) ks[k] = rnd.nextFloat() * 2f - 1f
                ksIdx = 0
            }
            else -> {}
        }
        done = false
    }

    /** Ends the note: the instrument's release tail plays out, then [done] becomes true. */
    fun release() {
        if (!done && relAt < 0) relAt = i
    }

    fun next(): Float {
        if (done) return 0f
        val t = i.toDouble() / SR
        var gate = 1.0
        if (relAt >= 0) {
            val r = (i - relAt).toDouble()
            if (r >= relLen) {
                done = true
                return 0f
            }
            gate = exp(-r / (relLen / 4.0 + 1)) * (1.0 - r / relLen)
        }
        val v: Double = when (inst) {
            Instrument.BASS -> {
                if (i % 64 == 0L) lp.set(Biquad.LP, (180 + 1400 * exp(-t / 0.08)).toFloat(), 1.2f)
                ph1 += TAU * f / SR
                ph2 += TAU * f / 2 / SR
                val raw = saw(ph1) * 0.6 + (if (sin(ph2) >= 0) 0.4 else -0.4)
                lp.process(raw.toFloat()) * min(1.0, t / 0.004)
            }
            Instrument.KEYS -> {
                val idx = 1.6 * exp(-t / 0.3)
                ph1 += TAU * f / SR
                sin(ph1 + idx * sin(ph1)) * exp(-t / 1.4) * min(1.0, t / 0.003)
            }
            Instrument.LEAD -> {
                ph1 += TAU * f * 1.004 / SR
                ph2 += TAU * f * 0.996 / SR
                lp.process(((saw(ph1) + saw(ph2)) * 0.5).toFloat()) * min(1.0, t / 0.01)
            }
            Instrument.PLUCK -> {
                val a = ks[ksIdx]
                val b = ks[(ksIdx + 1) % ksLen]
                val damp = if (relAt < 0) 0.996f else 0.95f
                ks[ksIdx] = (a + b) * 0.5f * damp
                ksIdx = (ksIdx + 1) % ksLen
                a.toDouble()
            }
            Instrument.PAD -> {
                ph1 += TAU * f * 1.006 / SR
                ph2 += TAU * f * 0.994 / SR
                ph3 += TAU * f * 2.0 / SR
                val raw = (saw(ph1) + saw(ph2) + 0.4 * saw(ph3)) / 2.4
                lp.process(raw.toFloat()) * min(1.0, t / 0.2)
            }
            Instrument.BELL -> {
                val idx = 3.0 * exp(-t / 0.5)
                ph1 += TAU * f / SR
                sin(ph1 + idx * sin(ph1 * 3.5)) * exp(-t / 1.5) * min(1.0, t / 0.002)
            }
        }
        i++
        return (v * gate * 0.5).toFloat()
    }

    private fun saw(ph: Double): Double {
        val p = ph / TAU
        return 2.0 * (p - floor(p)) - 1.0
    }

    companion object {
        private const val TAU = 2.0 * PI
    }
}
