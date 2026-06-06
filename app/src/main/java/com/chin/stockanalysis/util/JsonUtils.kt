package com.chin.stockanalysis.util

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser

object JsonUtils {
    private const val TAG = "JsonUtils"

    /** Remove common leading BOM and whitespace. Returns null if input is null or blank. */
    fun sanitizeJsonString(raw: String?): String? {
        if (raw == null) return null
        // Trim common whitespace
        var s = raw.trim()
        if (s.isEmpty()) return null
        // Remove UTF-8 BOM if present
        if (s.first() == '\uFEFF') s = s.drop(1)
        // Some servers return a non-JSON prefix (like ) or HTML error page - try to find first { or [
        val firstObjectIndex = s.indexOfFirst { it == '{' || it == '[' }
        if (firstObjectIndex > 0) {
            // Log truncated prefix for debugging
            Log.w(TAG, "Trimming prefix before JSON: '${s.substring(0, firstObjectIndex)}'")
            s = s.substring(firstObjectIndex)
        }
        if (s.isEmpty()) return null
        return s
    }

    /**
     * Try to parse a JSON string to a JsonElement safely. Returns null on parse failure.
     * Use this for quick validation before decoding into your data class.
     */
    fun tryParseToElement(raw: String?): JsonElement? {
        val s = sanitizeJsonString(raw) ?: return null
        return try {
            JsonParser.parseString(s)
        } catch (e: Exception) {
            Log.w(TAG, "tryParseToElement failed: ${e.message}")
            null
        }
    }

    /**
     * A configured Json instance for decoding data classes. Lenient + ignore unknown keys.
     */
    // A permissive Gson instance (Gson ignores unknown fields by default when deserializing)
    val permissiveGson: Gson = GsonBuilder()
        .serializeNulls()
        .create()
}

