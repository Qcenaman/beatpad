package com.beatpad

import android.content.Context
import com.beatpad.audio.AudioEngine
import com.beatpad.audio.Instrument
import com.beatpad.audio.NoteTrack
import com.beatpad.audio.SeqNote
import com.beatpad.audio.SynthPatch
import com.beatpad.audio.Wav
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps your kit, pattern and note tracks between launches. */
class Storage(ctx: Context) {
    private val dir = File(ctx.filesDir, "kit").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")
    private val presetFile = File(dir, "synth_presets.json")
    private val prefs = ctx.getSharedPreferences("beatpad", Context.MODE_PRIVATE)

    var seenHelp: Boolean
        get() = prefs.getBoolean("seenHelp", false)
        set(v) = prefs.edit().putBoolean("seenHelp", v).apply()

    var layoutLocked: Boolean
        get() = prefs.getBoolean("layoutLocked", true)
        set(v) = prefs.edit().putBoolean("layoutLocked", v).apply()

    fun loadLayout(screen: String): String? = prefs.getString("layout_$screen", null)

    fun saveLayout(screen: String, json: String?) {
        val e = prefs.edit()
        if (json == null) e.remove("layout_$screen") else e.putString("layout_$screen", json)
        e.apply()
    }

    fun saveSample(i: Int, d: FloatArray) = Wav.write(File(dir, "pad_$i.wav"), d)

    fun loadSample(i: Int): FloatArray? {
        val f = File(dir, "pad_$i.wav")
        return if (f.exists()) Wav.read(f) else null
    }

    fun deleteSample(i: Int) {
        File(dir, "pad_$i.wav").delete()
    }

    @Synchronized
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
        val tracks = JSONArray()
        for (t in e.noteTracks) {
            val notes = JSONArray()
            for (n in t.notes) notes.put(JSONArray().put(n.start).put(n.len).put(n.midi))
            tracks.put(JSONObject().apply {
                t.patch?.let { put("patch", it.toJson()) }
                put("inst", t.instrument?.name ?: "")
                put("pad", t.padSource)
                put("vol", t.volume.toDouble())
                put("bars", t.bars)
                put("muted", t.muted)
                put("notes", notes)
            })
        }
        o.put("tracks", tracks)
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(stateFile)
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
            val tracks = o.optJSONArray("tracks")
            if (tracks != null && tracks.length() > 0) {
                val list = ArrayList<NoteTrack>()
                for (i in 0 until minOf(MAX_TRACKS, tracks.length())) {
                    val to = tracks.getJSONObject(i)
                    val t = NoteTrack()
                    val inst = to.optString("inst", "")
                    t.instrument = if (inst.isEmpty()) null else Instrument.values().firstOrNull { it.name == inst } ?: Instrument.KEYS
                    t.padSource = to.optInt("pad", 0).coerceIn(0, 15)
                    t.volume = to.optDouble("vol", 0.7).toFloat()
                    t.bars = to.optInt("bars", 1).coerceIn(1, 8)
                    t.muted = to.optBoolean("muted", false)
                    t.patch = to.optJSONObject("patch")?.let { SynthPatch.fromJson(it) }
                    val ns = to.optJSONArray("notes") ?: JSONArray()
                    val notes = ArrayList<SeqNote>()
                    for (k in 0 until ns.length()) {
                        val a = ns.getJSONArray(k)
                        notes.add(
                            SeqNote(
                                SeqNote.newId(),
                                a.getInt(0).coerceAtLeast(0),
                                a.getInt(1).coerceAtLeast(1),
                                a.getInt(2).coerceIn(NoteTrack.MIN_NOTE, NoteTrack.MAX_NOTE)
                            )
                        )
                    }
                    t.notes = notes
                    list.add(t)
                }
                e.noteTracks = list
            }
            true
        } catch (ex: Exception) {
            false
        }
    }

    fun loadUserPresets(): List<SynthPatch> = try {
        if (!presetFile.exists()) emptyList() else {
            val a = JSONArray(presetFile.readText())
            (0 until a.length()).map { SynthPatch.fromJson(a.getJSONObject(it)).copy(category = "Mine") }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun saveUserPresets(list: List<SynthPatch>) {
        val a = JSONArray()
        for (p in list) a.put(p.toJson())
        val tmp = File(dir, "synth_presets.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(presetFile)
    }

    companion object {
        const val MAX_TRACKS = 4
    }
}
