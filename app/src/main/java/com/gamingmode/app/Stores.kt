package com.gamingmode.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Stroke(val startMs: Long, val durMs: Long, val pts: List<Pair<Float, Float>>)
class Macro(val name: String, val strokes: List<Stroke>)

object MacroStore {
    private fun prefs(c: Context) = c.getSharedPreferences("macros", Context.MODE_PRIVATE)

    fun all(c: Context): List<Macro> {
        val raw = prefs(c).getString("data", "[]") ?: "[]"
        val out = ArrayList<Macro>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sa = o.getJSONArray("s")
                val strokes = ArrayList<Stroke>()
                for (j in 0 until sa.length()) {
                    val so = sa.getJSONObject(j)
                    val pa = so.getJSONArray("p")
                    val pts = ArrayList<Pair<Float, Float>>()
                    var k = 0
                    while (k + 1 < pa.length()) {
                        pts.add(Pair(pa.getDouble(k).toFloat(), pa.getDouble(k + 1).toFloat()))
                        k += 2
                    }
                    strokes.add(Stroke(so.getLong("t"), so.getLong("d"), pts))
                }
                out.add(Macro(o.getString("name"), strokes))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun write(c: Context, list: List<Macro>) {
        val arr = JSONArray()
        for (m in list) {
            val sa = JSONArray()
            for (s in m.strokes) {
                val pa = JSONArray()
                for (p in s.pts) {
                    pa.put(p.first.toDouble())
                    pa.put(p.second.toDouble())
                }
                sa.put(JSONObject().put("t", s.startMs).put("d", s.durMs).put("p", pa))
            }
            arr.put(JSONObject().put("name", m.name).put("s", sa))
        }
        prefs(c).edit().putString("data", arr.toString()).apply()
    }

    fun save(c: Context, m: Macro) {
        val l = all(c).filter { it.name != m.name } + m
        write(c, l)
    }

    fun delete(c: Context, name: String) {
        write(c, all(c).filter { it.name != name })
    }
}

/** Standing instructions the AI remembers between sessions. */
object AiMemory {
    private fun prefs(c: Context) = c.getSharedPreferences("ai_memory", Context.MODE_PRIVATE)

    fun all(c: Context): List<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(prefs(c).getString("items", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) {
        }
        return out
    }

    private fun write(c: Context, items: List<String>) {
        val arr = JSONArray()
        for (s in items) arr.put(s)
        prefs(c).edit().putString("items", arr.toString()).apply()
    }

    fun add(c: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val l = all(c)
        if (l.contains(t)) return
        write(c, (l + t).takeLast(40))
    }

    fun remove(c: Context, index: Int) {
        val l = all(c).toMutableList()
        if (index in l.indices) l.removeAt(index)
        write(c, l)
    }

    fun clear(c: Context) = write(c, emptyList())

    fun asPrompt(c: Context): String {
        val l = all(c)
        return if (l.isEmpty()) "" else "Memory (standing instructions):\n" + l.joinToString("\n") { "- $it" } + "\n\n"
    }
}

/** Optional style the user wants the AI to talk in. */
object Persona {
    fun text(c: Context): String {
        val p = c.getSharedPreferences("gm", Context.MODE_PRIVATE).getString("persona", "")?.trim() ?: ""
        return if (p.isEmpty()) "" else "\nStyle/persona for any text you write to the user: $p"
    }
}
