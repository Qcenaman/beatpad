package com.beatpad

import android.content.Context
import com.beatpad.audio.AudioEngine
import com.beatpad.audio.Wav
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps your kit and pattern between launches. */
class Storage(ctx: Context) {
    private val dir = File(ctx.filesDir, "kit").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")

    fun saveSample(i: Int, d: FloatArray) = Wav.write(File(dir, "pad_$i.wav"), d)

    fun loadSample(i: Int): FloatArray? {
        val f = File(dir, "pad_$i.wav")
        return if (f.exists()) Wav.read(f) else null
    }

    fun deleteSample(i: Int) {
        File(dir, "pad_$i.wav").delete()
    }

    fun saveState(e: AudioEngine) {
        val o = JSONObject()
        o.put("bpm", e.bpm.toDouble())
        o.put("swing", e.swing.toDouble())
        val pads = JSONArray()
        for (p in e.pads) {
            pads.put(JSONObject().apply {
                put("name", p.name)
                put("vol", p.volume.toDouble())
                put("pitch", p.pitch)
                put("choke", p.choke)
            })
        }
        o.put("pads", pads)
        val pat = JSONArray()
        for (row in e.pattern) pat.put(row.joinToString("") { if (it) "1" else "0" })
        o.put("pattern", pat)
        stateFile.writeText(o.toString())
    }

    fun loadState(e: AudioEngine): Boolean {
        if (!stateFile.exists()) return false
        return try {
            val o = JSONObject(stateFile.readText())
            e.bpm = o.getDouble("bpm").toFloat()
            e.swing = o.getDouble("swing").toFloat()
            val pads = o.getJSONArray("pads")
            for (i in 0 until minOf(16, pads.length())) {
                val p = pads.getJSONObject(i)
                e.pads[i].name = p.getString("name")
                e.pads[i].volume = p.getDouble("vol").toFloat()
                e.pads[i].pitch = p.getInt("pitch")
                e.pads[i].choke = p.getInt("choke")
            }
            val pat = o.getJSONArray("pattern")
            for (i in 0 until minOf(16, pat.length())) {
                val s = pat.getString(i)
                for (j in 0 until minOf(AudioEngine.STEPS, s.length)) e.pattern[i][j] = s[j] == '1'
            }
            true
        } catch (ex: Exception) {
            false
        }
    }
}
