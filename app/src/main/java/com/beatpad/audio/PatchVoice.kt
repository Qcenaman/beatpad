package com.beatpad.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

private const val TABLE = 4096
private val SINE_TABLE = FloatArray(TABLE + 1) { sin(2.0 * PI * it / TABLE).toFloat() }

/** Sine of a phase given in cycles (0..1 = one full turn), from a lookup table. */
fun fastSin(phase: Double): Float {
    val p = (phase - floor(phase)) * TABLE
    var i = p.toInt()
    if (i >= TABLE) i = TABLE - 1
    if (i < 0) i = 0
    val f = (p - i).toFloat()
    return SINE_TABLE[i] + (SINE_TABLE[i + 1] - SINE_TABLE[i]) * f
}

/** Attack / decay / sustain / release envelope. */
class Adsr {
    var level = 0.0
        private set
    var stage = IDLE
        private set
    private var aInc = 1.0
    private var dCoef = 0.0
    private var rCoef = 0.0
    private var sus = 1.0

    fun setTimes(a: Float, d: Float, s: Float, r: Float) {
        aInc = 1.0 / (max(0.0005f, a) * SR)
        dCoef = exp(-6.9 / (max(0.001f, d) * SR))
        rCoef = exp(-6.9 / (max(0.001f, r) * SR))
        sus = s.toDouble().coerceIn(0.0, 1.0)
    }

    /** Starts (or restarts) the envelope from wherever it currently is, so retriggers don't click. */
    fun gateOn() {
        stage = ATTACK
    }

    fun gateOff() {
        if (stage != IDLE) stage = RELEASE
    }

    fun reset() {
        level = 0.0
        stage = IDLE
    }

    fun next(): Double {
        when (stage) {
            ATTACK -> {
                level += aInc
                if (level >= 1.0) {
                    level = 1.0
                    stage = DECAY
                }
            }
            DECAY -> {
                level = sus + (level - sus) * dCoef
                if (abs(level - sus) < 1e-4) {
                    level = sus
                    stage = SUSTAIN
                }
            }
            SUSTAIN -> level = sus
            RELEASE -> {
                level *= rCoef
                if (level < 1e-4) {
                    level = 0.0
                    stage = IDLE
                }
            }
            else -> {}
        }
        return level
    }

    companion object {
        const val IDLE = 0
        const val ATTACK = 1
        const val DECAY = 2
        const val SUSTAIN = 3
        const val RELEASE = 4
    }
}

/**
 * One note of a [SynthPatch]: two band-limited oscillators + sub + noise → resonant
 * state-variable filter → amp. Two envelopes, an LFO and glide. Allocation-free.
 */
class PatchVoice {
    /** When set, the voice follows this track's current patch, so knob moves are heard on held notes. */
    var track: NoteTrack? = null
    private var p = SynthPatch()
    private var applied: SynthPatch? = null
    private val amp = Adsr()
    private val fil = Adsr()
    var done = true
        private set
    var released = false
        private set
    var pitchNow = 60.0
        private set
    private var target = 60.0
    private var glideCoef = 0.0
    private var ph1 = 0.0
    private var ph2 = 0.0
    private var phS = 0.0
    private var inc1 = 0.0
    private var inc2 = 0.0
    private var incS = 0.0
    private var ic1 = 0.0
    private var ic2 = 0.0
    private var fa1 = 0.0
    private var fa2 = 0.0
    private var fa3 = 0.0
    private var fk = 2.0
    private var lfoPh = 0.0
    private var lfoHeld = 0.0
    private var trem = 1.0
    private var tick = 0
    private var seed = 22222

    fun start(patch: SynthPatch, midi: Int, fromPitch: Double = -1.0) {
        p = patch
        applied = null
        target = midi.toDouble()
        pitchNow = if (fromPitch > 0) fromPitch else target
        released = false
        done = false
        ph1 = 0.0
        ph2 = 0.37
        phS = 0.0
        ic1 = 0.0
        ic2 = 0.0
        lfoPh = 0.0
        tick = 0
        amp.reset()
        fil.reset()
        amp.gateOn()
        fil.gateOn()
        control()
    }

    /** Mono playing: move to a new note, sliding if glide is on. Restarts the envelopes only if asked. */
    fun glideTo(midi: Int, retrigger: Boolean) {
        target = midi.toDouble()
        if (glideCoef <= 0.0) pitchNow = target
        if (retrigger || released) {
            released = false
            amp.gateOn()
            fil.gateOn()
        }
        done = false
    }

    fun release() {
        if (!released) {
            released = true
            amp.gateOff()
            fil.gateOff()
        }
    }

    private fun apply(q: SynthPatch) {
        p = q
        amp.setTimes(q.ampA, q.ampD, q.ampS, q.ampR)
        fil.setTimes(q.filA, q.filD, q.filS, q.filR)
        glideCoef = if (q.glide <= 0.002f) 0.0 else exp(-CONTROL * 4.6 / (q.glide * SR))
        fk = 2.0 - 1.96 * q.resonance.coerceIn(0f, 1f)
        applied = q
    }

    private fun hz(semis: Double): Double = 440.0 * 2.0.pow((semis - 69.0) / 12.0)

    /** Runs every [CONTROL] samples: glide, LFO, pitch and filter coefficients. */
    private fun control() {
        val q = track?.patch ?: p
        if (q !== applied) apply(q)
        pitchNow = if (glideCoef > 0.0) target + (pitchNow - target) * glideCoef else target

        lfoPh += p.lfoRate * CONTROL / SR
        if (lfoPh >= 1.0) {
            lfoPh -= floor(lfoPh)
            lfoHeld = noise()
        }
        val lfo = when (p.lfoWave) {
            Wave.SINE -> fastSin(lfoPh).toDouble()
            Wave.TRIANGLE -> 1.0 - 4.0 * abs(lfoPh - 0.5)
            Wave.SAW -> 1.0 - 2.0 * lfoPh
            Wave.SQUARE -> if (lfoPh < 0.5) 1.0 else -1.0
            else -> lfoHeld
        }
        val amt = p.lfoAmount.toDouble()
        val vib = if (p.lfoTarget == LfoTarget.PITCH) lfo * amt * amt * 12.0 else 0.0
        val base = pitchNow + vib
        inc1 = hz(base + 12.0 * p.osc1Oct) / SR
        inc2 = hz(base + 12.0 * p.osc2Oct + p.osc2Semi + p.osc2Detune) / SR
        incS = hz(base + 12.0 * p.osc1Oct - 12.0) / SR

        val oct = p.filterEnv * fil.level * 6.0 +
            (if (p.lfoTarget == LfoTarget.FILTER) lfo * amt * 4.0 else 0.0) +
            p.keyTrack * (pitchNow - 60.0) / 12.0
        val fc = (p.cutoffHz() * 2.0.pow(oct)).coerceIn(20.0, SR * 0.45)
        val g = tan(PI * fc / SR)
        fa1 = 1.0 / (1.0 + g * (g + fk))
        fa2 = g * fa1
        fa3 = g * fa2
        trem = if (p.lfoTarget == LfoTarget.VOLUME) 1.0 - amt * 0.5 * (1.0 - lfo) else 1.0
    }

    fun next(): Float {
        if (done) return 0f
        if (tick == 0) control()
        tick++
        if (tick >= CONTROL) tick = 0
        val a = amp.next()
        fil.next()
        if (amp.stage == Adsr.IDLE) {
            done = true
            return 0f
        }
        var x = 0.0
        if (p.osc1Level > 0f) x += osc(p.osc1Wave, ph1, inc1) * p.osc1Level
        if (p.osc2Level > 0f) x += osc(p.osc2Wave, ph2, inc2) * p.osc2Level
        if (p.subLevel > 0f) x += square(phS, incS, 0.5) * p.subLevel
        if (p.noiseLevel > 0f) x += noise() * p.noiseLevel
        ph1 += inc1
        if (ph1 >= 1.0) ph1 -= 1.0
        ph2 += inc2
        if (ph2 >= 1.0) ph2 -= 1.0
        phS += incS
        if (phS >= 1.0) phS -= 1.0

        // zero-delay-feedback state-variable filter
        val v3 = x - ic2
        val v1 = fa1 * ic1 + fa2 * v3
        val v2 = ic2 + fa2 * ic1 + fa3 * v3
        ic1 = 2.0 * v1 - ic1
        ic2 = 2.0 * v2 - ic2
        val y = when (p.filterType) {
            FilterType.HIGH -> x - fk * v1 - v2
            FilterType.BAND -> v1 * fk
            else -> v2
        }
        return (y * a * trem * p.volume * 0.35).toFloat()
    }

    private fun osc(w: Int, ph: Double, inc: Double): Double = when (w) {
        Wave.SINE -> fastSin(ph).toDouble()
        Wave.TRIANGLE -> 4.0 * abs(ph - 0.5) - 1.0
        Wave.SAW -> 2.0 * ph - 1.0 - blep(ph, inc)
        Wave.SQUARE -> square(ph, inc, p.pulseWidth.toDouble().coerceIn(0.05, 0.95))
        else -> noise()
    }

    private fun square(ph: Double, inc: Double, pw: Double): Double {
        var v = if (ph < pw) 1.0 else -1.0
        v += blep(ph, inc)
        var t2 = ph - pw
        if (t2 < 0.0) t2 += 1.0
        v -= blep(t2, inc)
        return v
    }

    /** PolyBLEP: smooths the jumps in saw and square waves so high notes don't sound harsh. */
    private fun blep(t: Double, dt: Double): Double {
        if (dt <= 0.0) return 0.0
        return if (t < dt) {
            val x = t / dt
            x + x - x * x - 1.0
        } else if (t > 1.0 - dt) {
            val x = (t - 1.0) / dt
            x * x + x + x + 1.0
        } else 0.0
    }

    private fun noise(): Double {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 9) and 0x3FFFFF) / 2097152.0 - 1.0
    }

    companion object {
        const val CONTROL = 16
    }
}

/** Per-track effects: drive → tempo-synced delay → reverb (Freeverb-style). Allocation-free. */
class FxChain {
    private val dl = FloatArray(SR * 2)
    private var dw = 0
    private val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { FloatArray(it * SR / 44100) }.toTypedArray()
    private val combIdx = IntArray(8)
    private val combStore = FloatArray(8)
    private val aps = intArrayOf(556, 441, 341, 225).map { FloatArray(it * SR / 44100) }.toTypedArray()
    private val apIdx = IntArray(4)

    fun process(x: Float, p: SynthPatch, delaySamples: Int): Float {
        var s = x
        if (p.drive > 0.001f) {
            val g = 1f + p.drive * 12f
            s = tanh(s * g) / sqrt(g)
        }
        var out = s
        if (p.delayMix > 0.001f) {
            var r = dw - delaySamples.coerceIn(1, dl.size - 1)
            if (r < 0) r += dl.size
            val y = dl[r]
            dl[dw] = s + y * p.delayFeedback.coerceIn(0f, 0.9f)
            out += y * p.delayMix
        } else {
            dl[dw] = 0f
        }
        dw++
        if (dw >= dl.size) dw = 0
        if (p.reverbMix > 0.001f) {
            val fb = 0.7f + p.reverbSize * 0.28f
            val damp = 0.2f + (1f - p.reverbSize) * 0.3f
            val input = s * 0.015f
            var acc = 0f
            for (c in 0 until 8) {
                val buf = combs[c]
                val i = combIdx[c]
                val o = buf[i]
                combStore[c] = o * (1f - damp) + combStore[c] * damp
                buf[i] = input + combStore[c] * fb
                combIdx[c] = if (i + 1 >= buf.size) 0 else i + 1
                acc += o
            }
            for (a in 0 until 4) {
                val buf = aps[a]
                val i = apIdx[a]
                val bo = buf[i]
                val o = -acc + bo
                buf[i] = acc + bo * 0.5f
                apIdx[a] = if (i + 1 >= buf.size) 0 else i + 1
                acc = o
            }
            out += acc * p.reverbMix * 3f
        }
        return out
    }

    companion object {
        fun delaySamples(p: SynthPatch, bpm: Float): Int {
            val spb = SR * 60.0 / bpm / 4
            return (DELAY_STEPS[p.delayTime.coerceIn(0, DELAY_STEPS.size - 1)] * spb).toInt()
        }
    }
}
