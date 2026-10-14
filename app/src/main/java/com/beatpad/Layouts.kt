package com.beatpad

import org.json.JSONArray
import org.json.JSONObject

/** How one screen's sections are arranged: their order, custom heights (dp) and which are folded. */
data class PanelLayout(
    val order: List<String> = emptyList(),
    val heights: Map<String, Float> = emptyMap(),
    val collapsed: Set<String> = emptySet(),
) {
    fun toJson(): String = JSONObject().apply {
        put("order", JSONArray(order))
        put("h", JSONObject().apply { for ((k, v) in heights) put(k, v.toDouble()) })
        put("c", JSONArray(collapsed.toList()))
    }.toString()

    companion object {
        fun fromJson(s: String): PanelLayout? = try {
            val o = JSONObject(s)
            val ord = o.optJSONArray("order") ?: JSONArray()
            val h = o.optJSONObject("h") ?: JSONObject()
            val c = o.optJSONArray("c") ?: JSONArray()
            PanelLayout(
                order = (0 until ord.length()).map { ord.getString(it) },
                heights = h.keys().asSequence().associateWith { h.getDouble(it).toFloat() },
                collapsed = (0 until c.length()).map { c.getString(it) }.toSet(),
            )
        } catch (e: Exception) {
            null
        }

        val SCREENS = listOf("pads", "steps", "synth", "sample", "voice")
    }
}
