package com.beatpad.voice

import com.beatpad.audio.FFT
import com.beatpad.audio.SR
import com.beatpad.audio.SampleOps
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Turns a beatbox recording into drum hits. */
object Beatbox {
    enum class Hit(val label: String, val pad: Int) {
        KICK("Kick", 0), SNARE("Snare", 1), HAT("Hat", 2), OPEN_HAT("Open hat", 3)
    }

    /** time/end in samples, measured in the original recording. */
    data class Event(val time: Int, val end: Int, val type: Hit)

    private const val WIN = 1024
    private const val HOP = 256

    fun detect(x: FloatArray): List<Event> {
        val n = (x.size - WIN) / HOP
        if (n < 8) return emptyList()
        val w = FFT.hann(WIN)
        val flux = FloatArray(n)
        val rms = FloatArray(n)
        var prev = FloatArray(WIN / 2)
        for (f in 0 until n) {
            val mags = FFT.magnitudes(x, f * HOP, WIN, w)
            var fl = 0f
            for (k in mags.indices) {
                val m = ln(1f + 10f * mags[k])
                mags[k] = m
                val d = m - prev[k]
                if (d > 0) fl += d
            }
            prev = mags
            flux[f] = fl
            var e = 0f
            for (i in 0 until WIN) {
                val v = x[f * HOP + i]; e += v * v
            }
            rms[f] = sqrt(e / WIN)
        }
        val maxFlux = flux.maxOrNull() ?: 0f
        val maxRms = rms.maxOrNull() ?: 0f
        if (maxRms < 0.005f) return emptyList()
        val floor = max(0.01f, maxRms * 0.08f)
        val minGap = (SR * 0.07 / HOP).toInt()

        val onsets = ArrayList<Int>()
        var last = -minGap
        for (f in 1 until n - 1) {
            var sum = 0f
            var cnt = 0
            for (j in max(0, f - 10)..min(n - 1, f + 10)) {
                sum += flux[j]; cnt++
            }
            val th = sum / cnt * 1.4f + 0.05f * maxFlux
            val louder = rms[min(n - 1, f + 1)] > floor || rms[f] > floor
            if (flux[f] > th && flux[f] >= flux[f - 1] && flux[f] >= flux[f + 1] && louder && f - last >= minGap) {
                onsets.add(f)
                last = f
            }
        }

        val events = ArrayList<Event>()
        val cw = 2048
        val cwin = FFT.hann(cw)
        val binHz = SR.toFloat() / cw
        for ((i, f) in onsets.withIndex()) {
            val t = f * HOP + WIN / 4
            val mags = FFT.magnitudes(x, max(0, t - 256), cw, cwin)
            var low = 0f
            var high = 0f
            var total = 0f
            var cen = 0f
            for (k in 1 until mags.size) {
                val p = mags[k] * mags[k]
                val hz = k * binHz
                total += p
                cen += hz * p
                if (hz < 250f) low += p
                if (hz > 3000f) high += p
            }
            if (total <= 0f) continue
            cen /= total
            val lowR = low / total
            val highR = high / total

            // how long the sound rings
            val nextF = if (i + 1 < onsets.size) onsets[i + 1] else n
            var peak = 0f
            for (j in f until min(nextF, f + 6)) peak = max(peak, rms[j])
            var endF = f
            while (endF < nextF && endF < f + (SR * 0.8 / HOP).toInt() && rms[endF] > peak * 0.25f) endF++
            val lenSec = (endF - f) * HOP.toFloat() / SR

            var type = when {
                lowR > 0.45f || cen < 700f -> Hit.KICK
                highR > 0.55f || cen > 4500f -> Hit.HAT
                else -> Hit.SNARE
            }
            if (type == Hit.HAT && lenSec > 0.16f) type = Hit.OPEN_HAT
            val end = min(x.size, nextF * HOP + WIN / 4)
            events.add(Event(t, end, type))
        }
        return events
    }

    /** Guesses the tempo from hit timing; prefers the result closest to [prior]. */
    fun estimateBpm(events: List<Event>, prior: Float): Float {
        if (events.size < 4) return prior
        val t0 = events[0].time
        val rel = events.map { (it.time - t0).toDouble() / SR }
        val cands = ArrayList<Pair<Double, Double>>()  // bpm, err
        var bpm = 70.0
        while (bpm <= 160.0) {
            val s = 15.0 / bpm
            var err = 0.0
            for (r in rel) {
                val q = r / s
                val d = q - Math.round(q)
                err += d * d
            }
            cands.add(bpm to err / rel.size)
            bpm += 0.5
        }
        val minErr = cands.minOf { it.second }
        val good = cands.filter { it.second <= minErr * 1.25 + 0.002 }
        // group neighbouring tempos, keep the best of each group
        val groups = ArrayList<Pair<Double, Double>>()
        var cur: Pair<Double, Double>? = null
        var lastBpm = -10.0
        for (c in good) {
            if (cur == null || c.first - lastBpm > 1.01) {
                cur?.let { groups.add(it) }
                cur = c
            } else if (c.second < cur.second) {
                cur = c
            }
            lastBpm = c.first
        }
        cur?.let { groups.add(it) }
        val best = groups.minByOrNull { abs(ln(it.first / prior)) } ?: return prior
        return (best.first * 2).roundToInt() / 2f
    }

    /** Step (0..15) for each event, with the first hit on step 1. */
    fun quantize(events: List<Event>, bpm: Float): List<Int> {
        if (events.isEmpty()) return emptyList()
        val s = SR * 15.0 / bpm
        val t0 = events[0].time
        return events.map { (Math.round((it.time - t0) / s).toInt()) % 16 }
    }

    /** Cuts the hit out of the recording so it can become a pad sound. */
    fun chop(x: FloatArray, e: Event): FloatArray {
        val s = max(0, e.time - WIN / 2)
        val end = min(x.size, min(e.end, e.time + SR * 6 / 10))
        if (end - s < 64) return FloatArray(0)
        return SampleOps.normalize(SampleOps.autoTrim(x.copyOfRange(s, end)), 0.9f)
    }

    /** Replays the take with drum sounds at the exact times you beatboxed them. */
    fun renderLoop(events: List<Event>, sounds: Map<Hit, FloatArray>): FloatArray {
        if (events.isEmpty()) return FloatArray(0)
        val t0 = events[0].time
        val lastT = events.last().time - t0
        val out = FloatArray(lastT + SR * 2)
        for ((i, e) in events.withIndex()) {
            val d = sounds[e.type] ?: continue
            val start = e.time - t0
            var stop = start + d.size
            if (e.type == Hit.OPEN_HAT) {
                val nextHat = events.drop(i + 1).firstOrNull { it.type == Hit.HAT || it.type == Hit.OPEN_HAT }
                if (nextHat != null) stop = min(stop, nextHat.time - t0)
            }
            stop = min(stop, out.size)
            for (j in start until stop) {
                var v = d[j - start]
                val left = stop - j
                if (left < 200) v *= left / 200f
                out[j] += v
            }
        }
        return SampleOps.normalize(SampleOps.autoTrim(out), 0.9f)
    }
}
