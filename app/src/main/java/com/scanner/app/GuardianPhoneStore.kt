package com.scanner.app

import android.content.Context

object GuardianPhoneStore {

    private const val PREFS = "guardian_phone_prefs"
    private const val KEY_NORMALIZED_PHONE = "my_normalized_phone"
    private const val KEY_ONBOARDING_DONE = "phone_onboarding_done"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOnboardingDone(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ONBOARDING_DONE, false)

    fun getMyNormalizedPhone(ctx: Context): String? =
        prefs(ctx).getString(KEY_NORMALIZED_PHONE, null)?.takeIf { it.isNotBlank() }

    fun saveMyPhone(ctx: Context, normalizedPhone: String) {
        prefs(ctx).edit()
            .putString(KEY_NORMALIZED_PHONE, normalizedPhone)
            .putBoolean(KEY_ONBOARDING_DONE, true)
            .apply()
    }
}
