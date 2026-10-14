package com.beatpad.audio

import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Wave {
    const val SINE = 0
    const val TRIANGLE = 1
    const val SAW = 2
    const val SQUARE = 3
    const val NOISE = 4
    val labels = listOf("Sine", "Triangle", "Saw", "Square", "Noise")
}

object FilterType {
    const val LOW = 0
    const val HIGH = 1
    const val BAND = 2
    val labels = listOf("Low-pass", "High-pass", "Band-pass")
}

object LfoTarget {
    const val PITCH = 0
    const val FILTER = 1
    const val VOLUME = 2
    val labels = listOf("Pitch", "Filter", "Volume")
}

/** Tempo-synced delay times, in 16th notes. */
val DELAY_STEPS = intArrayOf(1, 2, 3, 4, 6, 8)
val DELAY_LABELS = listOf("1/16", "1/8", "3/16", "1/4", "3/8", "1/2")

/** A complete synth sound. Immutable: every change makes a new copy, so the audio thread never sees half an edit. */
data class SynthPatch(
    val name: String = "Init",
    val category: String = "Basic",
    // oscillators
    val osc1Wave: Int = Wave.SAW,
    val osc1Oct: Int = 0,
    val osc1Level: Float = 0.8f,
    val osc2Wave: Int = Wave.SAW,
    val osc2Oct: Int = 0,
    val osc2Semi: Int = 0,
    val osc2Detune: Float = 0.08f,     // semitones (0.08 = 8 cents)
    val osc2Level: Float = 0f,
    val subLevel: Float = 0f,
    val noiseLevel: Float = 0f,
    val pulseWidth: Float = 0.5f,
    // filter
    val filterType: Int = FilterType.LOW,
    val cutoff: Float = 0.75f,         // 0..1 → 30 Hz .. 18 kHz
    val resonance: Float = 0.15f,
    val filterEnv: Float = 0.25f,      // -1..1 → ±6 octaves
    val keyTrack: Float = 0.5f,
    // amp envelope (seconds, sustain 0..1)
    val ampA: Float = 0.005f,
    val ampD: Float = 0.3f,
    val ampS: Float = 0.8f,
    val ampR: Float = 0.25f,
    // filter envelope
    val filA: Float = 0.005f,
    val filD: Float = 0.4f,
    val filS: Float = 0.3f,
    val filR: Float = 0.3f,
    // LFO
    val lfoWave: Int = Wave.SINE,
    val lfoRate: Float = 4f,           // Hz
    val lfoAmount: Float = 0f,
    val lfoTarget: Int = LfoTarget.PITCH,
    // voice
    val mono: Boolean = false,
    val glide: Float = 0f,             // seconds
    val volume: Float = 0.75f,
    // effects
    val drive: Float = 0f,
    val delayMix: Float = 0f,
    val delayTime: Int = 2,            // index into DELAY_STEPS
    val delayFeedback: Float = 0.35f,
    val reverbMix: Float = 0f,
    val reverbSize: Float = 0.6f,
) {
    fun cutoffHz(): Double = cutoffToHz(cutoff)

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("category", category)
        put("osc1Wave", osc1Wave); put("osc1Oct", osc1Oct); put("osc1Level", osc1Level.toDouble())
        put("osc2Wave", osc2Wave); put("osc2Oct", osc2Oct); put("osc2Semi", osc2Semi)
        put("osc2Detune", osc2Detune.toDouble()); put("osc2Level", osc2Level.toDouble())
        put("subLevel", subLevel.toDouble()); put("noiseLevel", noiseLevel.toDouble()); put("pulseWidth", pulseWidth.toDouble())
        put("filterType", filterType); put("cutoff", cutoff.toDouble()); put("resonance", resonance.toDouble())
        put("filterEnv", filterEnv.toDouble()); put("keyTrack", keyTrack.toDouble())
        put("ampA", ampA.toDouble()); put("ampD", ampD.toDouble()); put("ampS", ampS.toDouble()); put("ampR", ampR.toDouble())
        put("filA", filA.toDouble()); put("filD", filD.toDouble()); put("filS", filS.toDouble()); put("filR", filR.toDouble())
        put("lfoWave", lfoWave); put("lfoRate", lfoRate.toDouble()); put("lfoAmount", lfoAmount.toDouble()); put("lfoTarget", lfoTarget)
        put("mono", mono); put("glide", glide.toDouble()); put("volume", volume.toDouble())
        put("drive", drive.toDouble()); put("delayMix", delayMix.toDouble()); put("delayTime", delayTime)
        put("delayFeedback", delayFeedback.toDouble()); put("reverbMix", reverbMix.toDouble()); put("reverbSize", reverbSize.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): SynthPatch {
            val d = SynthPatch()
            fun f(k: String, def: Float): Float = o.optDouble(k, def.toDouble()).toFloat()
            fun i(k: String, def: Int): Int = o.optInt(k, def)
            return SynthPatch(
                name = o.optString("name", d.name),
                category = o.optString("category", "Mine"),
                osc1Wave = i("osc1Wave", d.osc1Wave).coerceIn(0, 3),
                osc1Oct = i("osc1Oct", d.osc1Oct).coerceIn(-2, 2),
                osc1Level = f("osc1Level", d.osc1Level).coerceIn(0f, 1f),
                osc2Wave = i("osc2Wave", d.osc2Wave).coerceIn(0, 3),
                osc2Oct = i("osc2Oct", d.osc2Oct).coerceIn(-2, 2),
                osc2Semi = i("osc2Semi", d.osc2Semi).coerceIn(-12, 12),
                osc2Detune = f("osc2Detune", d.osc2Detune).coerceIn(0f, 0.5f),
                osc2Level = f("osc2Level", d.osc2Level).coerceIn(0f, 1f),
                subLevel = f("subLevel", d.subLevel).coerceIn(0f, 1f),
                noiseLevel = f("noiseLevel", d.noiseLevel).coerceIn(0f, 1f),
                pulseWidth = f("pulseWidth", d.pulseWidth).coerceIn(0.05f, 0.95f),
                filterType = i("filterType", d.filterType).coerceIn(0, 2),
                cutoff = f("cutoff", d.cutoff).coerceIn(0f, 1f),
                resonance = f("resonance", d.resonance).coerceIn(0f, 1f),
                filterEnv = f("filterEnv", d.filterEnv).coerceIn(-1f, 1f),
                keyTrack = f("keyTrack", d.keyTrack).coerceIn(0f, 1f),
                ampA = f("ampA", d.ampA), ampD = f("ampD", d.ampD), ampS = f("ampS", d.ampS).coerceIn(0f, 1f), ampR = f("ampR", d.ampR),
                filA = f("filA", d.filA), filD = f("filD", d.filD), filS = f("filS", d.filS).coerceIn(0f, 1f), filR = f("filR", d.filR),
                lfoWave = i("lfoWave", d.lfoWave).coerceIn(0, 4),
                lfoRate = f("lfoRate", d.lfoRate).coerceIn(0.1f, 20f),
                lfoAmount = f("lfoAmount", d.lfoAmount).coerceIn(0f, 1f),
                lfoTarget = i("lfoTarget", d.lfoTarget).coerceIn(0, 2),
                mono = o.optBoolean("mono", d.mono),
                glide = f("glide", d.glide).coerceIn(0f, 1f),
                volume = f("volume", d.volume).coerceIn(0f, 1f),
                drive = f("drive", d.drive).coerceIn(0f, 1f),
                delayMix = f("delayMix", d.delayMix).coerceIn(0f, 1f),
                delayTime = i("delayTime", d.delayTime).coerceIn(0, DELAY_STEPS.size - 1),
                delayFeedback = f("delayFeedback", d.delayFeedback).coerceIn(0f, 0.9f),
                reverbMix = f("reverbMix", d.reverbMix).coerceIn(0f, 1f),
                reverbSize = f("reverbSize", d.reverbSize).coerceIn(0f, 1f),
            )
        }
    }
}

fun cutoffToHz(c: Float): Double = 30.0 * 600.0.pow(c.toDouble())

/** Slider position (0..1) ↔ real value, shared by the UI so the knobs feel even. */
object Knob {
    fun timeToU(t: Float): Float = Math.cbrt(((t - 0.001f) / 5f).coerceIn(0f, 1f).toDouble()).toFloat()
    fun uToTime(u: Float): Float = 0.001f + u * u * u * 5f
    fun rateToU(hz: Float): Float = (ln(hz / 0.1f) / ln(200f)).coerceIn(0f, 1f)
    fun uToRate(u: Float): Float = 0.1f * 200f.pow(u)
    fun glideToU(g: Float): Float = sqrt(g.coerceIn(0f, 1f))
    fun uToGlide(u: Float): Float = u * u
}

fun fmtTime(t: Float): String = if (t < 1f) "${(t * 1000).roundToInt()} ms" else "%.2f s".format(t)
fun fmtHz(hz: Double): String = if (hz < 1000) "${hz.roundToInt()} Hz" else "%.1f kHz".format(hz / 1000)

object SynthPresets {
    val all: List<SynthPatch> = listOf(
        SynthPatch(name = "Init Saw", category = "Basic"),
        SynthPatch(
            name = "Fat Bass", category = "Bass",
            osc1Wave = Wave.SAW, osc1Oct = -1, osc2Wave = Wave.SQUARE, osc2Oct = -1, osc2Detune = 0.08f, osc2Level = 0.6f,
            subLevel = 0.5f, cutoff = 0.45f, resonance = 0.25f, filterEnv = 0.35f,
            filA = 0.001f, filD = 0.25f, filS = 0.2f, filR = 0.15f,
            ampA = 0.003f, ampD = 0.3f, ampS = 0.9f, ampR = 0.12f, mono = true, glide = 0.04f
        ),
        SynthPatch(
            name = "Deep Sub", category = "Bass",
            osc1Wave = Wave.SINE, osc1Oct = -1, osc1Level = 1f, cutoff = 1f, filterEnv = 0f, keyTrack = 0f,
            ampA = 0.002f, ampD = 0.5f, ampS = 1f, ampR = 0.15f, mono = true, glide = 0.05f, drive = 0.25f, volume = 0.85f
        ),
        SynthPatch(
            name = "Acid", category = "Bass",
            osc1Wave = Wave.SAW, osc1Oct = -1, cutoff = 0.32f, resonance = 0.78f, filterEnv = 0.6f,
            filA = 0.001f, filD = 0.18f, filS = 0f, filR = 0.1f,
            ampA = 0.002f, ampD = 0.2f, ampS = 0.8f, ampR = 0.08f, mono = true, glide = 0.05f,
            drive = 0.35f, delayMix = 0.2f, delayTime = 2, delayFeedback = 0.35f
        ),
        SynthPatch(
            name = "Wobble", category = "Bass",
            osc1Wave = Wave.SAW, osc1Oct = -1, osc2Wave = Wave.SQUARE, osc2Oct = -1, osc2Level = 0.5f, subLevel = 0.4f,
            cutoff = 0.33f, resonance = 0.5f, filterEnv = 0f,
            lfoWave = Wave.SINE, lfoRate = 3f, lfoAmount = 0.7f, lfoTarget = LfoTarget.FILTER,
            ampA = 0.003f, ampD = 0.3f, ampS = 1f, ampR = 0.1f, mono = true, drive = 0.4f
        ),
        SynthPatch(
            name = "Classic Lead", category = "Lead",
            osc1Wave = Wave.SAW, osc2Wave = Wave.SAW, osc2Detune = 0.12f, osc2Level = 0.7f,
            cutoff = 0.66f, resonance = 0.3f, filterEnv = 0.2f,
            ampA = 0.005f, ampD = 0.3f, ampS = 0.85f, ampR = 0.2f,
            lfoWave = Wave.SINE, lfoRate = 5.5f, lfoAmount = 0.12f, lfoTarget = LfoTarget.PITCH,
            mono = true, glide = 0.06f, delayMix = 0.22f, delayTime = 2, delayFeedback = 0.35f, reverbMix = 0.15f
        ),
        SynthPatch(
            name = "Chiptune", category = "Lead",
            osc1Wave = Wave.SQUARE, pulseWidth = 0.5f, cutoff = 1f, filterEnv = 0f, keyTrack = 0f,
            ampA = 0.001f, ampD = 0.1f, ampS = 0.9f, ampR = 0.03f,
            lfoWave = Wave.SINE, lfoRate = 6f, lfoAmount = 0.1f, lfoTarget = LfoTarget.PITCH, mono = true
        ),
        SynthPatch(
            name = "Supersaw", category = "Lead",
            osc1Wave = Wave.SAW, osc2Wave = Wave.SAW, osc2Detune = 0.25f, osc2Level = 0.8f, subLevel = 0.3f,
            cutoff = 0.8f, resonance = 0.1f, filterEnv = 0.1f,
            ampA = 0.01f, ampD = 0.5f, ampS = 0.9f, ampR = 0.4f, reverbMix = 0.3f, delayMix = 0.15f, delayTime = 3
        ),
        SynthPatch(
            name = "Brass", category = "Lead",
            osc1Wave = Wave.SAW, osc2Wave = Wave.SAW, osc2Detune = 0.1f, osc2Level = 0.5f,
            cutoff = 0.4f, resonance = 0.15f, filterEnv = 0.45f,
            filA = 0.06f, filD = 0.4f, filS = 0.5f, filR = 0.2f,
            ampA = 0.04f, ampD = 0.3f, ampS = 0.85f, ampR = 0.2f,
            lfoRate = 5f, lfoAmount = 0.05f, lfoTarget = LfoTarget.PITCH, reverbMix = 0.15f
        ),
        SynthPatch(
            name = "Warm Pad", category = "Pad",
            osc1Wave = Wave.SAW, osc2Wave = Wave.SAW, osc2Detune = 0.15f, osc2Level = 0.8f, noiseLevel = 0.03f,
            cutoff = 0.45f, resonance = 0.1f, filterEnv = 0.15f,
            filA = 0.9f, filD = 1.5f, filS = 0.5f, filR = 1f,
            ampA = 0.9f, ampD = 1f, ampS = 0.85f, ampR = 1.4f,
            lfoWave = Wave.SINE, lfoRate = 0.3f, lfoAmount = 0.15f, lfoTarget = LfoTarget.FILTER,
            reverbMix = 0.45f, reverbSize = 0.85f
        ),
        SynthPatch(
            name = "Strings", category = "Pad",
            osc1Wave = Wave.SAW, osc2Wave = Wave.SAW, osc2Detune = 0.2f, osc2Level = 0.8f,
            cutoff = 0.55f, resonance = 0.05f, filterEnv = 0.1f,
            ampA = 0.3f, ampD = 0.5f, ampS = 0.9f, ampR = 0.7f,
            lfoWave = Wave.SINE, lfoRate = 5f, lfoAmount = 0.06f, lfoTarget = LfoTarget.PITCH,
            reverbMix = 0.35f, reverbSize = 0.7f
        ),
        SynthPatch(
            name = "Pluck", category = "Keys",
            osc1Wave = Wave.SAW, osc2Wave = Wave.SQUARE, osc2Oct = 1, osc2Level = 0.35f,
            cutoff = 0.25f, resonance = 0.3f, filterEnv = 0.7f,
            filA = 0.001f, filD = 0.18f, filS = 0f, filR = 0.15f,
            ampA = 0.001f, ampD = 0.4f, ampS = 0f, ampR = 0.3f,
            delayMix = 0.3f, delayTime = 2, delayFeedback = 0.4f, reverbMix = 0.2f
        ),
        SynthPatch(
            name = "Soft Keys", category = "Keys",
            osc1Wave = Wave.TRIANGLE, osc2Wave = Wave.SINE, osc2Oct = 1, osc2Level = 0.3f,
            cutoff = 0.6f, resonance = 0.05f, filterEnv = 0.15f,
            ampA = 0.003f, ampD = 1f, ampS = 0.25f, ampR = 0.4f, reverbMix = 0.2f
        ),
        SynthPatch(
            name = "Noise Sweep", category = "FX",
            osc1Level = 0f, noiseLevel = 0.9f, filterType = FilterType.BAND, cutoff = 0.45f, resonance = 0.6f, filterEnv = 0f,
            lfoWave = Wave.TRIANGLE, lfoRate = 0.4f, lfoAmount = 0.8f, lfoTarget = LfoTarget.FILTER,
            ampA = 0.4f, ampD = 1f, ampS = 1f, ampR = 1.2f, reverbMix = 0.5f, reverbSize = 0.9f, delayMix = 0.3f, delayTime = 3
        ),
        SynthPatch(
            name = "Laser", category = "FX",
            osc1Wave = Wave.SQUARE, osc1Oct = 1, cutoff = 0.9f, resonance = 0.4f, filterEnv = -0.5f,
            filA = 0.001f, filD = 0.3f, filS = 0f, filR = 0.2f,
            lfoWave = Wave.SAW, lfoRate = 8f, lfoAmount = 0.45f, lfoTarget = LfoTarget.PITCH,
            ampA = 0.001f, ampD = 0.35f, ampS = 0f, ampR = 0.2f, delayMix = 0.35f, delayTime = 1, delayFeedback = 0.5f
        ),
    )
}
