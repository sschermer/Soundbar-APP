package de.tonmaschine.android.model

import android.content.Context
import org.json.JSONObject

data class SoundProfile(
    val mode: Int, val levels: Map<String, Int>,
    val drc: Boolean, val nightTime: Boolean, val surround: Boolean?
) {
    fun valid(): Boolean = mode in Catalog.modes.values &&
        Catalog.levels.all { spec -> levels[spec.field]?.let { it in spec.min..spec.max } == true }
}

object ProfileCodec {
    /** The JSON fields match Tonmaschine_Profile.json from the Windows app. */
    fun encode(profiles: Map<String, SoundProfile>): String {
        val result = JSONObject()
        profiles.toSortedMap().forEach { (name, p) ->
            require(name.isNotBlank() && p.valid())
            val entry = JSONObject().put("mode", p.mode)
                .put("levels", JSONObject(p.levels))
                .put("drc", p.drc)
                .put("night_time", p.nightTime)
            if (p.surround != null) entry.put("surround", p.surround)
            result.put(name, entry)
        }
        return result.toString(2)
    }

    fun decode(text: String): Map<String, SoundProfile> {
        val root = JSONObject(text)
        val result = linkedMapOf<String, SoundProfile>()
        root.keys().forEach { name ->
            require(name.isNotBlank())
            val obj = root.getJSONObject(name)
            val levelObj = obj.getJSONObject("levels")
            val levels = Catalog.levels.associate { spec ->
                val raw = levelObj.get(spec.field)
                require(raw is Int) { "Invalid level: " + spec.field }
                spec.field to raw
            }
            val mode = obj.get("mode")
            require(mode is Int)
            val drc = obj.get("drc")
            val night = obj.get("night_time")
            require(drc is Boolean && night is Boolean)
            val surround = if (obj.has("surround")) obj.get("surround") as? Boolean
                ?: throw IllegalArgumentException("Invalid surround") else null
            val profile = SoundProfile(mode, levels, drc, night, surround)
            require(profile.valid()) { "Invalid profile: " + name }
            result[name] = profile
        }
        return result
    }
}

class ProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("tonmaschine_local", Context.MODE_PRIVATE)
    fun load(): Map<String, SoundProfile> =
        preferences.getString("profiles", null)?.let(ProfileCodec::decode) ?: emptyMap()
    fun save(all: Map<String, SoundProfile>) {
        val encoded = ProfileCodec.encode(all)
        check(preferences.edit().putString("profiles", encoded).commit()) { "Could not save profiles" }
    }
    fun ip(): String = preferences.getString("soundbar_ip", "").orEmpty()
    fun saveIp(ip: String) {
        check(preferences.edit().putString("soundbar_ip", ip).commit())
    }
}
