package com.wajub.mobile.mapper

import org.json.JSONArray
import org.json.JSONObject

// Lenient readers for the API's JSON (spec PR-01…PR-08). They never use
// org.json's opt*/get* coercions: on Android the platform's org.json turns a
// JSON null into the string "null" and a boolean into "true", which is how
// branding colours used to arrive as "null".

/** The value under [key], or null when it is absent or JSON null. */
internal fun JSONObject.value(key: String): Any? {
    val v = opt(key)
    return if (v == null || v == JSONObject.NULL) null else v
}

/** A string; numbers and booleans are written as text. Blank is null. */
internal fun JSONObject.str(key: String): String? =
    when (val v = value(key)) {
        null, is JSONObject, is JSONArray -> null
        is String -> v.takeIf { it.isNotBlank() }
        is Double, is Float -> (v as Number).toDouble().let { d ->
            if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
        }
        else -> v.toString()
    }

/** A number given as an integer, a decimal or a numeric string. */
internal fun JSONObject.num(key: String): Double? = toNumber(value(key))

internal fun JSONObject.int(key: String): Int? = num(key)?.takeIf { !it.isNaN() }?.toInt()

/** `true`/`false`, `0`/`1` or `"true"`/`"false"`. */
internal fun JSONObject.bool(key: String): Boolean? =
    when (val v = value(key)) {
        is Boolean -> v
        is Number -> when (v.toDouble()) {
            1.0 -> true
            0.0 -> false
            else -> null
        }
        is String -> when (v.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }

internal fun JSONObject.obj(key: String): JSONObject? = value(key) as? JSONObject

internal fun JSONObject.arr(key: String): JSONArray? = value(key) as? JSONArray

/** Every string of an array (other values skipped); absent is empty. */
internal fun JSONObject.strings(key: String): List<String> {
    val array = arr(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        when (val v = array.opt(i)) {
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> null
        }
    }
}

/** Every object of an array (other values skipped). */
internal fun JSONObject.objects(key: String): List<JSONObject> {
    val array = arr(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.opt(it) as? JSONObject }
}

/** A `{field: [messages]}` map reduced to its first message per field. */
internal fun JSONObject.firstMessages(key: String): Map<String, String> {
    val errors = obj(key) ?: return emptyMap()
    val out = linkedMapOf<String, String>()
    errors.keys().forEach { field ->
        val message = when (val v = errors.opt(field)) {
            is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { (v.opt(it) as? String) }
            is String -> v
            else -> null
        }
        if (message != null) out[field] = message
    }
    return out
}

internal fun toNumber(v: Any?): Double? =
    when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

/** Parses a body that may be empty or not JSON at all (a proxy's HTML page). */
internal fun parseObject(text: String?): JSONObject {
    if (text.isNullOrBlank()) return JSONObject()
    return try {
        JSONObject(text)
    } catch (_: Exception) {
        JSONObject()
    }
}
