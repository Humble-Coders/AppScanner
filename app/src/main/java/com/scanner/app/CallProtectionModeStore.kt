package com.scanner.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallProtectionMode {
    ALWAYS_LISTENING,
    ASK_EVERY_TIME,
}

object CallProtectionModeStore {

    private const val PREFS = "call_protection_mode_prefs"
    private const val KEY_MODE = "call_protection_mode"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(CallProtectionMode.ALWAYS_LISTENING)
    val mode: StateFlow<CallProtectionMode> = _mode.asStateFlow()

    fun syncFromDisk(context: Context) {
        _mode.value = readMode(context)
    }

    fun setMode(context: Context, mode: CallProtectionMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    fun getMode(context: Context): CallProtectionMode = readMode(context)

    private fun readMode(context: Context): CallProtectionMode {
        val raw = prefs(context).getString(KEY_MODE, CallProtectionMode.ALWAYS_LISTENING.name)
        return CallProtectionMode.values().firstOrNull { it.name == raw }
            ?: CallProtectionMode.ALWAYS_LISTENING
    }
}

