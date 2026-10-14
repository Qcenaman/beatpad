package com.beatpad.voice

import com.beatpad.audio.Biquad
import com.beatpad.audio.SR
import com.beatpad.audio.SampleOps
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Turns humming/singing into notes, then plays them with an instrument. */
object Melody {
    data class Note(val start: Int, val len: Int, val midi: Int)

    enum class Instrument(val label: String) {
        BASS("Bass"), KEYS("Keys"), LEAD("Synth"), PLUCK("Pluck"), PAD("Pad"), BELL("Bell")
    }

    private const val DS = 3          // 48k -> 16k
    private const val SR2 = SR / DS
    private const val W = 1024
    private const val HOP = 256

    fun track(x: FloatArray): List<Note> {
        val lp = Biquad().set(Biquad.LP, 5000f, 0.7f)
        val filtered = FloatArray(x.size) { lp.process(x[it]) }
        val y = FloatArray(x.size / DS) { filtered[it * DS] }
        val tauMin = SR2 / 1000
        val tauMax = SR2 / 60
        val frames = (y.size - W - tauMax) / HOP
        if (frames < 4) return emptyList()

        val rms = FloatArray(frames)
        for (f in 0 until frames) {
            var e = 0f
            val s = f * HOP
            for (j in 0 until W) e += y[s + j] * y[s + j]
            rms[f] = sqrt(e / W)
        }
        val floor = max(0.008f, (rms.maxOrNull() ?: 0f) * 0.1f)

        val midi = FloatArray(frames)
        val d = FloatArray(tauMax + 1)
        for (f in 0 until frames) {
            if (rms[f] < floor) continue
            val s = f * HOP
            for (tau in 1..tauMax) {
                var sum = 0f
                for (j in 0 until W) {
                    val diff = y[s + j] - y[s + j + tau]
                    sum += diff * diff
                }
                d[tau] = sum
            }
            // cumulative mean normalized difference
            var running = 0f
            var found = -1
            val cmnd = FloatArray(tauMax + 1)
            cmnd[0] = 1f
            for (tau in 1..tauMax) {
                running += d[tau]
                cmnd[tau] = if (running > 0f) d[tau] * tau / running else 1f
            }
            var tau = tauMin
            while (tau <= tauMax) {
                if (cmnd[tau] < 0.15f) {
                    while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++
                    found = tau
                    break
                }
                tau++
            }
            if (found < 0) continue
            var better = found.toFloat()
            if (found in 1 until tauMax) {
                val a = cmnd[found - 1]
                val b = cmnd[found]
                val c = cmnd[found + 1]
                val den = a - 2 * b + c
                if (abs(den) > 1e-6f) better = found + 0.5f * (a - c) / den
            }
            val hz = SR2 / better
            midi[f] = (69 + 12 * ln(hz / 440f) / ln(2f))
        }

        // median filter
        val sm = FloatArray(frames)
        for (f in 0 until frames) {
            if (midi[f] == 0f) continue
            val win = (max(0, f - 2)..min(frames - 1, f + 2)).map { midi[it] }.filter { it > 0f }.sorted()
            sm[f] = win[win.size / 2]
        }

        val notes = ArrayList<Note>()
        var start = -1
        val vals = ArrayList<Float>()
        var gap = 0
        var devStart = -1
        fun close(endFrame: Int) {
            if (start >= 0 && endFrame - start >= 4 && vals.isNotEmpty()) {
                val m = vals.sorted()[vals.size / 2].roundToInt()
                notes.add(Note(start * HOP * DS, (endFrame - start) * HOP * DS, m))
            }
            start = -1
            vals.clear()
            devStart = -1
        }
        for (f in 0 until frames) {
            val m = sm[f]
            if (m <= 0f) {
                gap++
                if (gap >= 2 && start >= 0) close(f - gap + 1)
                continue
            }
            gap = 0
            if (start < 0) {
                start = f; vals.add(m); continue
            }
            val med = vals.sorted()[vals.size / 2]
            if (abs(m - med) > 0.75f) {
                if (devStart < 0) devStart = f
                if (f - devStart >= 2) {
                    val ds = devStart
                    close(ds)
                    start = ds
                    for (k in ds..f) if (sm[k] > 0f) vals.add(sm[k])
                }
            } else {
                devStart = -1
                vals.add(m)
            }
        }
        close(frames)

        // merge repeated notes separated by tiny gaps
        val merged = ArrayList<Note>()
        for (n in notes) {
            val p = merged.lastOrNull()
            if (p != null && p.midi == n.midi && n.start - (p.start + p.len) < HOP * DS * 3) {
                merged[merged.size - 1] = Note(p.start, n.start + n.len - p.start, p.midi)
            } else merged.add(n)
        }
        return merged
    }

    fun render(notes: List<Note>, inst: Instrument, octave: Int, quantizeBpm: Float?): FloatArray {
        if (notes.isEmpty()) return FloatArray(0)
        val t0 = notes[0].start
        val placed = notes.map {
            if (quantizeBpm != null) {
                val s = SR * 15.0 / quantizeBpm
                val st = (Math.round((it.start - t0) / s) * s).toInt()
                val ln = (max(1L, Math.round(it.len / s)) * s).toInt()
                Note(st, ln, it.midi)
            } else Note(it.start - t0, it.len, it.midi)
        }
        val release = when (inst) {
            Instrument.PAD -> 0.8
            Instrument.BELL, Instrument.KEYS -> 0.6
            Instrument.PLUCK -> 0.5
            else -> 0.15
        }
        val end = placed.maxOf { it.start + it.len } + (release * SR).toInt() + SR / 10
        val out = FloatArray(end)
        for (n in placed) {
            val f = 440.0 * 2.0.pow((n.midi + 12 * octave - 69) / 12.0)
            val voice = voice(inst, f, n.len, (release * SR).toInt())
            for (i in voice.indices) {
                val j = n.start + i
                if (j < out.size) out[j] += voice[i]
            }
        }
        return SampleOps.normalize(out, 0.9f)
    }

    private const val TAU = 2.0 * PI

    private fun saw(ph: Double): Double {
        val p = ph / TAU
        return 2.0 * (p - Math.floor(p)) - 1.0
    }

    private fun voice(inst: Instrument, f: Double, len: Int, rel: Int): FloatArray {
        val total = len + rel
        val out = FloatArray(total)
        val lp = Biquad()
        val rnd = Random(f.toInt())
        // Karplus-Strong delay line for pluck
        val ksLen = max(2, (SR / f).toInt())
        val ks = FloatArray(ksLen) { rnd.nextFloat() * 2f - 1f }
        var ksIdx = 0
        var ph1 = 0.0
        var ph2 = 0.0
        var ph3 = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / SR
            val gate = if (i < len) 1.0 else exp(-(i - len).toDouble() / (rel / 4.0 + 1))
            var v = 0.0
            when (inst) {
                Instrument.BASS -> {
                    if (i % 64 == 0) lp.set(Biquad.LP, (180 + 1400 * exp(-t / 0.08)).toFloat(), 1.2f)
                    ph1 += TAU * f / SR
                    ph2 += TAU * f / 2 / SR
                    val raw = saw(ph1) * 0.6 + (if (sin(ph2) >= 0) 0.4 else -0.4)
                    v = lp.process(raw.toFloat()) * min(1.0, t / 0.004) * gate
                }
                Instrument.KEYS -> {
                    val idx = 1.6 * exp(-t / 0.3)
                    ph1 += TAU * f / SR
                    v = sin(ph1 + idx * sin(ph1)) * exp(-t / 1.4) * min(1.0, t / 0.003) * gate
                }
                Instrument.LEAD -> {
                    if (i % 64 == 0) lp.set(Biquad.LP, 2600f, 1.0f)
                    ph1 += TAU * f * 1.004 / SR
                    ph2 += TAU * f * 0.996 / SR
                    v = lp.process(((saw(ph1) + saw(ph2)) * 0.5).toFloat()) * min(1.0, t / 0.01) * gate
                }
                Instrument.PLUCK -> {
                    val a = ks[ksIdx]
                    val b = ks[(ksIdx + 1) % ksLen]
                    val damp = if (i < len) 0.996f else 0.95f
                    ks[ksIdx] = (a + b) * 0.5f * damp
                    ksIdx = (ksIdx + 1) % ksLen
                    v = a.toDouble() * gate
                }
                Instrument.PAD -> {
                    if (i % 64 == 0) lp.set(Biquad.LP, 1500f, 0.8f)
                    ph1 += TAU * f * 1.006 / SR
                    ph2 += TAU * f * 0.994 / SR
                    ph3 += TAU * f * 2.0 / SR
                    val raw = (saw(ph1) + saw(ph2) + 0.4 * saw(ph3)) / 2.4
                    v = lp.process(raw.toFloat()) * min(1.0, t / 0.2) * gate
                }
                Instrument.BELL -> {
                    val idx = 3.0 * exp(-t / 0.5)
                    ph1 += TAU * f / SR
                    v = sin(ph1 + idx * sin(ph1 * 3.5)) * exp(-t / 1.5) * min(1.0, t / 0.002) * gate
                }
            }
            out[i] = (v * 0.5).toFloat()
        }
        return out
    }
}
