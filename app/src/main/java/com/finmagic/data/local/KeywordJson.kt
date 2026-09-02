package com.finmagic.data.local

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * JSON serialization helper for keyword lists (Plan §3).
 * 
 * The plan specifies: "Use JSON adapters or a small serialization helper for 
 * keyword lists. Do not encode lists with ad hoc comma splitting because 
 * keywords may contain punctuation or spaces."
 * 
 * This replaces the previous pipe-delimited format which would break on 
 * keywords like "Rocket|Nagad" or "A/C#123".
 */
object KeywordJson {
    private val gson = Gson()
    
    /**
     * Encodes a list of keywords to JSON format.
     * Deduplicates and filters blank entries.
     */
    fun encode(keywords: List<String>): String {
        val cleaned = keywords
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return gson.toJson(cleaned)
    }
    
    /**
     * Decodes a JSON string back to a list of keywords.
     * Returns empty list on malformed JSON.
     */
    fun decode(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            // Fallback: try pipe-delimited format for backward compatibility
            json.split('|').map { it.trim() }.filter { it.isNotBlank() }
        }
    }
    
    /**
     * Encodes a comma-separated string to JSON format.
     * Used for UI input where users type comma-separated keywords.
     */
    fun encodeFromCommaSeparated(text: String): String {
        val keywords = text.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return encode(keywords)
    }
    
    /**
     * Decodes a JSON string to comma-separated format for UI display.
     */
    fun decodeToCommaSeparated(json: String?): String {
        return decode(json).joinToString(", ")
    }
}
