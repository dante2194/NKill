package com.example.nkill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** text == "" means: block every notification of [pkg]. */
data class Rule(val pkg: String, val label: String, val text: String)

object RuleStore {
    private fun sp(c: Context) = c.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun load(c: Context): List<Rule> {
        val a = JSONArray(sp(c).getString("r", "[]"))
        return List(a.length()) {
            val o = a.getJSONObject(it)
            Rule(o.getString("p"), o.getString("l"), o.getString("t"))
        }
    }

    fun save(c: Context, rules: List<Rule>) {
        val a = JSONArray(rules.map { JSONObject().put("p", it.pkg).put("l", it.label).put("t", it.text) })
        sp(c).edit().putString("r", a.toString()).apply()
    }
}
