package com.beatpad.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Built-in drum kit, synthesized from scratch so no sample files are needed. */
object DrumSynth {
    val names = listOf(
        "Kick", "Snare", "Hat", "Open Hat",
        "Clap", "Rim", "Low Tom", "Mid Tom",
        "Hi Tom", "Cowbell", "Crash", "Ride",
        "Shaker", "808", "Snap", "Perc"
    )

    /** Choke groups: closed hat cuts open hat. */
    val chokes = intArrayOf(0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private const val TAU = 2.0 * PI

    fun make(i: Int): FloatArray {
        val d = when (i) {
            0 -> kick()
            1 -> snare()
            2 -> hat(0.045, 0.25)
            3 -> hat(0.35, 1.0)
            4 -> clap()
            5 -> rim()
            6 -> tom(90.0)
            7 -> tom(130.0)
            8 -> tom(185.0)
            9 -> cowbell()
            10 -> cymbal(0.8, 2.0, 4500f)
            11 -> ride()
            12 -> shaker()
            13 -> bass808()
            14 -> snap()
            else -> perc()
        }
        return SampleOps.fadeEdges(SampleOps.normalize(d, 0.9f), 0, 5)
    }

    fun click(accent: Boolean): FloatArray {
        val f = if (accent) 2000.0 else 1500.0
        return FloatArray((0.03 * SR).toInt()) {
            val t = it.toDouble() / SR
            (sin(TAU * f * t) * exp(-t / 0.008) * 0.5).toFloat()
        }
    }

    private fun buf(sec: Double) = FloatArray((sec * SR).toInt())
    private fun noise(rnd: Random) = rnd.nextFloat() * 2f - 1f

    private fun kick(): FloatArray {
        val out = buf(0.6)
        val rnd = Random(1)
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val f = 45.0 + 110.0 * exp(-t / 0.04)
            ph += TAU * f / SR
            val body = sin(ph) * exp(-t / 0.30)
            val clickN = noise(rnd) * exp(-t / 0.002) * 0.3
            out[i] = tanh(1.6 * (body + clickN)).toFloat()
        }
        return out
    }

    private fun snare(): FloatArray {
        val out = buf(0.35)
        val rnd = Random(2)
        val hp = Biquad().set(Biquad.HP, 1800f, 0.7f)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val tone = sin(TAU * 185 * t) * exp(-t / 0.06) * 0.6 + sin(TAU * 330 * t) * exp(-t / 0.04) * 0.3
            val n = hp.process(noise(rnd)) * exp(-t / 0.15) * 0.9
            out[i] = (tone + n).toFloat()
        }
        return out
    }

    private val metalFreqs = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)

    private fun metal(t: Double, mul: Double = 1.0): Double {
        var s = 0.0
        for (f in metalFreqs) s += if (sin(TAU * f * mul * t) >= 0) 1.0 else -1.0
        return s / metalFreqs.size
    }

    private fun hat(decay: Double, len: Double): FloatArray {
        val out = buf(len)
        val rnd = Random(3)
        val bp = Biquad().set(Biquad.BP, 9000f, 1f)
        val hp = Biquad().set(Biquad.HP, 7000f, 0.7f)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val raw = metal(t) * 0.6 + noise(rnd) * 0.4
            out[i] = (hp.process(bp.process(raw.toFloat())) * exp(-t / decay)).toFloat()
        }
        return out
    }

    private fun clap(): FloatArray {
        val out = buf(0.45)
        val rnd = Random(4)
        val bp = Biquad().set(Biquad.BP, 1100f, 2f)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            var env = 0.0
            for (k in 0..2) {
                val dt = t - k * 0.010
                if (dt >= 0) env += exp(-dt / 0.006)
            }
            if (t > 0.03) env += exp(-(t - 0.03) / 0.12) * 0.8
            out[i] = (bp.process(noise(rnd)) * env).toFloat()
        }
        return out
    }

    private fun rim(): FloatArray {
        val out = buf(0.12)
        val rnd = Random(5)
        val bp = Biquad().set(Biquad.BP, 3000f, 2f)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            out[i] = (sin(TAU * 1700 * t) * exp(-t / 0.012) + bp.process(noise(rnd)) * exp(-t / 0.006)).toFloat()
        }
        return out
    }

    private fun tom(base: Double): FloatArray {
        val out = buf(0.7)
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val f = base * (1 + 0.6 * exp(-t / 0.05))
            ph += TAU * f / SR
            out[i] = (sin(ph) * exp(-t / 0.25)).toFloat()
        }
        return out
    }

    private fun cowbell(): FloatArray {
        val out = buf(0.5)
        val bp = Biquad().set(Biquad.BP, 900f, 3f)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val sq = (if (sin(TAU * 540 * t) >= 0) 1.0 else -1.0) + (if (sin(TAU * 800 * t) >= 0) 1.0 else -1.0)
            val env = if (t < 0.02) 1.0 else 0.7 * exp(-(t - 0.02) / 0.12) + 0.3 * exp(-(t - 0.02) / 0.02)
            out[i] = (bp.process(sq.toFloat()) * env).toFloat()
        }
        return out
    }

    private fun cymbal(decay: Double, len: Double, hpF: Float): FloatArray {
        val out = buf(len)
        val rnd = Random(6)
        val hp = Biquad().set(Biquad.HP, hpF, 0.7f)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val raw = metal(t, 1.5) * 0.5 + noise(rnd) * 0.5
            out[i] = (hp.process(raw.toFloat()) * exp(-t / decay)).toFloat()
        }
        return out
    }

    private fun ride(): FloatArray {
        val out = buf(1.5)
        val bp = Biquad().set(Biquad.BP, 6000f, 1.2f)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val raw = metal(t, 2.2)
            val ping = sin(TAU * 3100 * t) * exp(-t / 0.3) * 0.25
            out[i] = (bp.process(raw.toFloat()) * exp(-t / 0.6) + ping).toFloat()
        }
        return out
    }

    private fun shaker(): FloatArray {
        val out = buf(0.25)
        val rnd = Random(7)
        val bp = Biquad().set(Biquad.BP, 7000f, 1.5f)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val env = if (t < 0.015) t / 0.015 else exp(-(t - 0.015) / 0.06)
            out[i] = (bp.process(noise(rnd)) * env).toFloat()
        }
        return out
    }

    private fun bass808(): FloatArray {
        val out = buf(1.6)
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val f = 50.0 + 30.0 * exp(-t / 0.03)
            ph += TAU * f / SR
            out[i] = (tanh(2.0 * sin(ph)) * exp(-t / 0.9) * 0.8).toFloat()
        }
        return out
    }

    private fun snap(): FloatArray {
        val out = buf(0.2)
        val rnd = Random(8)
        val hp = Biquad().set(Biquad.HP, 2500f, 0.7f)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            var env = exp(-t / 0.02)
            if (t > 0.008) env += exp(-(t - 0.008) / 0.015)
            out[i] = (hp.process(noise(rnd)) * env).toFloat()
        }
        return out
    }

    private fun perc(): FloatArray {
        val out = buf(0.3)
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val f = 660.0 + 220.0 * exp(-t / 0.02)
            ph += TAU * f / SR
            out[i] = ((sin(ph) + 0.3 * sin(2 * ph)) * exp(-t / 0.08)).toFloat()
        }
        return out
    }
}
