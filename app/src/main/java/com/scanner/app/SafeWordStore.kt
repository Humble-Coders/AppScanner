package com.scanner.app

import android.content.Context

object SafeWordStore {

    private const val PREFS = "safe_word_prefs"
    private const val KEY_CUSTOM_WORDS = "custom_safe_words_lines"

    /** Always recognized; cannot be removed. */
    const val BUILT_IN_SAFE_WORD = "humble"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getCustomSafeWords(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_CUSTOM_WORDS, null) ?: return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun setCustomSafeWords(ctx: Context, words: List<String>) {
        val normalized = words
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(BUILT_IN_SAFE_WORD, ignoreCase = true) }
            .distinctBy { it.lowercase() }
        prefs(ctx).edit().putString(KEY_CUSTOM_WORDS, normalized.joinToString("\n")).apply()
    }

    fun addCustomSafeWord(ctx: Context, word: String): Boolean {
        val w = word.trim()
        if (w.isEmpty() || w.length > 48) return false
        if (w.equals(BUILT_IN_SAFE_WORD, ignoreCase = true)) return false
        val current = getCustomSafeWords(ctx).toMutableList()
        if (current.any { it.equals(w, ignoreCase = true) }) return false
        current.add(w)
        setCustomSafeWords(ctx, current)
        return true
    }

    fun removeCustomSafeWord(ctx: Context, word: String) {
        val current = getCustomSafeWords(ctx).filterNot { it.equals(word, ignoreCase = true) }
        setCustomSafeWords(ctx, current)
    }

    /**
     * Built-in plus custom, deduped (case-insensitive). [BUILT_IN_SAFE_WORD] is always first.
     */
    fun getEffectiveSafeWords(ctx: Context): List<String> {
        val custom = getCustomSafeWords(ctx)
        val merged = LinkedHashSet<String>()
        merged.add(BUILT_IN_SAFE_WORD)
        custom.forEach { w ->
            if (w.isNotBlank() && !w.equals(BUILT_IN_SAFE_WORD, ignoreCase = true)) {
                merged.add(w.trim())
            }
        }
        return merged.toList()
    }

    /**
     * Collapses punctuation to spaces so "humble." and "humble" both match.
     */
    private fun normalizeForTokenMatch(text: String): String {
        val collapsed = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        return " ${collapsed.trim().replace(Regex("\\s+"), " ")} "
    }

    fun matchesAnySafetyWord(ctx: Context, text: String): Boolean {
        val norm = normalizeForTokenMatch(text)
        return getEffectiveSafeWords(ctx).any { w ->
            val needle = w.lowercase().trim().replace(Regex("\\s+"), " ")
            if (needle.isEmpty()) return@any false
            norm.contains(" $needle ")
        }
    }
}
