package com.scanner.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PitchPhoneRole {
    GUARDIAN,
    VICTIM
}

object PitchPhoneRoleStore {

    private const val PREFS = "pitch_phone_role_prefs"
    private const val KEY_ROLE = "pitch_phone_role"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _role = MutableStateFlow(PitchPhoneRole.VICTIM)
    val role: StateFlow<PitchPhoneRole> = _role.asStateFlow()

    fun syncFromDisk(context: Context) {
        _role.value = readRole(context)
    }

    fun setRole(context: Context, role: PitchPhoneRole) {
        prefs(context).edit().putString(KEY_ROLE, role.name).apply()
        _role.value = role
    }

    private fun readRole(context: Context): PitchPhoneRole {
        val raw = prefs(context).getString(KEY_ROLE, PitchPhoneRole.VICTIM.name)
        return PitchPhoneRole.values().firstOrNull { it.name == raw } ?: PitchPhoneRole.VICTIM
    }
}
