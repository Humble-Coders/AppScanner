package com.scanner.app

import android.util.Log

/** Single tag for end-to-end WhatsApp call + number probe debugging: `adb logcat -s WhatsAppCallJourney` */
object WhatsAppCallJourney {

    const val TAG = "WhatsAppCallJourney"

    fun i(phase: String, msg: String) {
        Log.i(TAG, "[$phase] $msg")
    }

    fun d(phase: String, msg: String) {
        Log.d(TAG, "[$phase] $msg")
    }

    fun w(phase: String, msg: String) {
        Log.w(TAG, "[$phase] $msg")
    }
}
