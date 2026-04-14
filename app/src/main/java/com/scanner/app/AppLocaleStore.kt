package com.scanner.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLocaleStore {

    private const val PREFS = "app_locale_prefs"
    private const val KEY_LANG = "language_tag"

    const val LANG_EN = "en"
    const val LANG_HI = "hi"
    const val LANG_PA = "pa"
    const val LANG_TA = "ta"
    const val LANG_TE = "te"
    const val LANG_KN = "kn"

    val supportedTags = listOf(LANG_EN, LANG_HI, LANG_PA, LANG_TA, LANG_TE, LANG_KN)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getStoredTag(context: Context): String {
        val raw = prefs(context).getString(KEY_LANG, null)?.takeIf { it in supportedTags }
        return raw ?: LANG_EN
    }

    fun setLocale(context: Context, languageTag: String) {
        val tag = languageTag.takeIf { it in supportedTags } ?: LANG_EN
        prefs(context).edit().putString(KEY_LANG, tag).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun applyStoredLocale(context: Context) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(getStoredTag(context))
        )
    }
}
