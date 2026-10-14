package com.beatpad.voice

import com.beatpad.audio.Biquad
import com.beatpad.audio.Instrument
import com.beatpad.audio.SR
import com.beatpad.audio.SampleOps
import com.beatpad.audio.SynthVoice
import com.beatpad.audio.midiHz
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Turns humming/singing into notes, then plays them with an instrument. */
object Melody {
    data class Note(val start: Int, val len: Int, val midi: Int)

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
        val rel = (inst.release * SR).toInt()
        val end = placed.maxOf { it.start + it.len } + rel + SR / 10
        val out = FloatArray(end)
        val sv = SynthVoice()
        for (n in placed) {
            sv.start(inst, midiHz(n.midi + 12 * octave))
            var j = n.start
            var k = 0
            while (!sv.done && j < out.size) {
                if (k == n.len) sv.release()
                out[j] += sv.next()
                j++; k++
            }
        }
        return SampleOps.normalize(out, 0.9f)
    }
}
