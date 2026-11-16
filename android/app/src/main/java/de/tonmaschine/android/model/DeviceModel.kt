package de.tonmaschine.android.model

import org.json.JSONArray
import org.json.JSONObject

data class LevelSpec(val name: String, val field: String, val min: Int, val max: Int, val offset: Int)

object Catalog {
    // Same IDs as the Windows reference at bfb275d9, 15 November 2026.
    val modes = linkedMapOf(
        "AI Sound Pro" to 19, "Standard" to 0, "Cinema" to 7,
        "Clear Voice Pro" to 26, "Sports" to 21, "Music (Meridian)" to 6,
        "Game" to 22, "Bass Blast" to 14
    )
    val inputs = linkedMapOf(
        0 to "WLAN", 1 to "Bluetooth", 4 to "Optisch", 6 to "HDMI 1",
        7 to "HDMI ARC", 10 to "HDMI 2", 11 to "HDMI 3",
        15 to "Optisch / ARC", 18 to "USB 1", 19 to "USB", 20 to "HDMI eARC"
    )
    val levels = listOf(
        LevelSpec("Subwoofer", "i_woofer_level", -15, 6, 15),
        LevelSpec("Center", "i_center_level", -6, 6, 6),
        LevelSpec("Front-Höhe", "i_top_level", -6, 6, 6),
        LevelSpec("Front-Seite", "i_side_level", -6, 6, 6),
        LevelSpec("Rear", "i_rear_level", -6, 6, 6),
        LevelSpec("Rear-Höhe", "i_rear_top_level", -6, 6, 6),
        LevelSpec("Rear-Seite", "i_rear_side_level", -6, 6, 6)
    )
    fun inputChoices(advertised: List<Int>, observed: List<Int>, current: Int?): List<Int> {
        return (advertised + observed + listOfNotNull(current)).distinct().filter(inputs::containsKey)
    }
    fun validIp(ip: String): Boolean {
        val parts = ip.trim().split(".")
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) &&
            (it.toIntOrNull() ?: 999) in 0..255 }
    }
}

enum class Connection { DISCONNECTED, CONNECTING, CONNECTED }

data class DeviceState(
    val connection: Connection = Connection.DISCONNECTED,
    val error: String? = null,
    val volume: Int? = null,
    val currentInput: Int? = null,
    val advertisedInputs: List<Int> = emptyList(),
    val hasInputList: Boolean = false,
    val observedInputs: List<Int> = emptyList(),
    val audioFormat: String = "–",
    val currentMode: Int? = null,
    val advertisedModes: List<Int> = emptyList(),
    val levels: Map<String, Int> = emptyMap(),
    val nightTime: Boolean? = null,
    val drc: Boolean? = null,
    val surround: Boolean? = null,
    val bass: Int? = null,
    val treble: Int? = null,
    val powerOn: Boolean? = null
) {
    val selectableInputs: List<Int> get() = Catalog.inputChoices(advertisedInputs, observedInputs, currentInput)
    val selectableModes: Map<String, Int> get() = Catalog.modes.filterValues {
        advertisedModes.isEmpty() || it in advertisedModes
    }
    val canSelectInput: Boolean get() = connection == Connection.CONNECTED && hasInputList && selectableInputs.size > 1
}

object DeviceReducer {
    private fun number(json: JSONObject, field: String): Int? {
        val value = json.opt(field)
        return when (value) {
            is Int -> value
            is Long -> value.toInt().takeIf { it.toLong() == value }
            else -> null
        }
    }
    private fun flag(json: JSONObject, field: String): Boolean? = json.opt(field) as? Boolean
    private fun knownIds(array: JSONArray?, known: Collection<Int>): List<Int> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull {
            val v = array.opt(it)
            (v as? Int)?.takeIf(known::contains)
        }.distinct()
    }

    /** Never guesses a channel count from audio format or sound mode. */
    fun reduce(old: DeviceState, packet: JSONObject): DeviceState {
        if (packet.optString("result") != "ok") return old
        val data = packet.optJSONObject("data") ?: return old
        val message = packet.optString("msg")
        if (message !in setOf("SPK_LIST_VIEW_INFO", "FUNC_VIEW_INFO", "EQ_VIEW_INFO", "SETTING_VIEW_INFO")) return old
        var updated = old.copy(connection = Connection.CONNECTED, error = null)
        if (message == "FUNC_VIEW_INFO" && data.optJSONArray("ai_func_list") != null) {
            updated = updated.copy(advertisedInputs = knownIds(data.optJSONArray("ai_func_list"), Catalog.inputs.keys),
                hasInputList = true)
        }
        if (message == "EQ_VIEW_INFO" && data.optJSONArray("ai_eq_list") != null) {
            updated = updated.copy(advertisedModes = knownIds(data.optJSONArray("ai_eq_list"), Catalog.modes.values))
        }
        if (message == "SPK_LIST_VIEW_INFO" && data.has("s_audio_source")) {
            val reported = (data.opt("s_audio_source") as? String)?.trim().orEmpty()
            updated = updated.copy(audioFormat = reported.ifEmpty { "–" })
        }
        number(data, "i_curr_func")?.let { code ->
            val observed = if (code in Catalog.inputs && code !in updated.observedInputs) updated.observedInputs + code else updated.observedInputs
            updated = updated.copy(currentInput = code, observedInputs = observed)
        }
        number(data, "i_vol")?.takeIf { it in 0..100 }?.let { updated = updated.copy(volume = it) }
        number(data, "i_curr_eq")?.let { updated = updated.copy(currentMode = it) }
        number(data, "i_bass")?.let { updated = updated.copy(bass = it) }
        number(data, "i_treble")?.let { updated = updated.copy(treble = it) }
        flag(data, "b_powerstatus")?.let { updated = updated.copy(powerOn = it) }
        flag(data, "b_night_time")?.let { updated = updated.copy(nightTime = it) }
        flag(data, "b_drc")?.let { updated = updated.copy(drc = it) }
        flag(data, "b_rear")?.let { updated = updated.copy(surround = it) }
        val levels = updated.levels.toMutableMap()
        Catalog.levels.forEach { spec ->
            number(data, spec.field)?.let { raw ->
                val adjusted = raw - spec.offset
                if (adjusted in spec.min..spec.max) levels[spec.field] = adjusted
            }
        }
        if (levels != updated.levels) updated = updated.copy(levels = levels)
        return updated
    }
}

object SoundbarCommands {
    /** Captured LG semantics: true = on; false = standby.
     * Not yet physically verified on S95QR. Never write read-only b_powerstatus.
     */
    fun power(turnOn: Boolean): JSONObject = set("SPK_LIST_VIEW_INFO", "b_powerkey", turnOn)
    fun get(message: String): JSONObject {
        require(message in setOf("SPK_LIST_VIEW_INFO", "FUNC_VIEW_INFO", "EQ_VIEW_INFO", "SETTING_VIEW_INFO"))
        return JSONObject().put("cmd", "get").put("msg", message)
    }
    private fun set(message: String, field: String, value: Any): JSONObject =
        JSONObject().put("cmd", "set").put("data", JSONObject().put(field, value)).put("msg", message)
    fun volume(value: Int): JSONObject {
        require(value in 0..100)
        return set("SPK_LIST_VIEW_INFO", "i_vol", value)
    }
    fun source(value: Int): JSONObject {
        require(value in Catalog.inputs)
        return set("FUNC_VIEW_INFO", "i_curr_func", value)
    }
    fun mode(value: Int): JSONObject {
        require(value in Catalog.modes.values)
        return set("EQ_VIEW_INFO", "i_curr_eq", value)
    }
    fun option(field: String, value: Boolean): JSONObject {
        require(field in setOf("b_drc", "b_night_time", "b_rear"))
        return set("SETTING_VIEW_INFO", field, value)
    }
    fun level(spec: LevelSpec, value: Int): JSONObject {
        require(value in spec.min..spec.max && spec in Catalog.levels)
        return set("SETTING_VIEW_INFO", spec.field, value + spec.offset)
    }
}
