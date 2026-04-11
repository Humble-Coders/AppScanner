package com.scanner.app

import android.util.Log
import android.view.accessibility.AccessibilityEvent

sealed class CallStateChange {
    object CallStarted : CallStateChange()
    object CallEnded : CallStateChange()
    /** The call UI disappeared temporarily — might be a false alarm (e.g. systemui overlay). */
    object CallMaybeEnded : CallStateChange()
    /** The call UI reappeared after a MaybeEnded — cancel the end timer. */
    object CallResumed : CallStateChange()
}

class WhatsAppCallDetector {

    companion object {
        private const val TAG = "WARecorder"

        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )

        // Exact activity names (add discovered ones here)
        private val CALL_ACTIVITY_NAMES = setOf(
            "com.whatsapp.voipcalling.VoipActivity",
            "com.whatsapp.voipcalling.VoipActivityV2",
            "com.whatsapp.calling.CallingActivity",
            "com.whatsapp.calling.ui.VoipActivityV2",  // discovered from user's device
            "com.whatsapp.voip.VoipActivity"
        )

        // Broader patterns to also catch by partial match
        private val CALL_CLASS_KEYWORDS = listOf(
            "voip", "calling", "call", "incall"
        )

        // Packages that commonly steal focus during calls but DON'T mean the call ended
        private val IGNORABLE_PACKAGES = setOf(
            "com.android.systemui",         // notification shade, status bar, quick settings
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.inputmethod.latin",  // keyboard
            "com.google.android.gms",       // Play Services dialogs
            "com.android.settings",         // settings from quick tile
            "com.scanner.app"               // our own caption overlay & UI — must NOT trigger MAYBE_ENDED
        )
    }

    enum class State {
        IDLE,           // No call
        IN_CALL,        // Actively in call
        MAYBE_ENDED     // Call UI gone, waiting to confirm
    }

    private var state: State = State.IDLE
    private var lastLoggedEvent = ""
    private var maybeEndedTimestamp = 0L

    fun processAccessibilityEvent(event: AccessibilityEvent): CallStateChange? {
        val pkg = event.packageName?.toString() ?: return null
        val className = event.className?.toString() ?: return null
        val eventType = event.eventType

        // Log ALL WhatsApp events to discover actual class names
        if (pkg in WHATSAPP_PACKAGES) {
            val eventDesc = accessibilityEventTypeToString(eventType)
            val logKey = "$pkg|$className|$eventType"
            if (logKey != lastLoggedEvent) {
                Log.d(TAG, "[Detector] WhatsApp event: type=$eventDesc pkg=$pkg class=$className text=${event.text} state=$state")
                lastLoggedEvent = logKey
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val isWhatsApp = pkg in WHATSAPP_PACKAGES
            val isExactCallActivity = className in CALL_ACTIVITY_NAMES
            val isPartialCallMatch = isWhatsApp && CALL_CLASS_KEYWORDS.any {
                className.contains(it, ignoreCase = true)
            }
            val isCallActivity = isExactCallActivity || isPartialCallMatch
            val isIgnorablePackage = pkg in IGNORABLE_PACKAGES

            if (isWhatsApp || state != State.IDLE) {
                Log.d(TAG, "[Detector] WINDOW_STATE_CHANGED: pkg=$pkg class=$className isCallActivity=$isCallActivity isIgnorable=$isIgnorablePackage state=$state")
            }

            return when (state) {
                State.IDLE -> {
                    if (isWhatsApp && isCallActivity) {
                        state = State.IN_CALL
                        Log.i(TAG, "[Detector] >>> CALL STARTED: pkg=$pkg class=$className (exact=$isExactCallActivity)")
                        WhatsAppCallJourney.i(
                            "detector",
                            "CALL_STARTED pkg=$pkg activity=$className exactCallActivity=$isExactCallActivity"
                        )
                        CallStateChange.CallStarted
                    } else null
                }

                State.IN_CALL -> {
                    if (isWhatsApp && isCallActivity) {
                        // Still on call screen, ignore
                        Log.d(TAG, "[Detector] Still in call UI: class=$className")
                        null
                    } else if (isIgnorablePackage) {
                        // SystemUI or permission dialog stole focus — don't end the call yet
                        Log.d(TAG, "[Detector] Ignorable package took focus: $pkg — NOT ending call")
                        null
                    } else if (isWhatsApp && !isCallActivity) {
                        // WhatsApp but not call screen (e.g. chat list) — maybe call ended
                        state = State.MAYBE_ENDED
                        maybeEndedTimestamp = System.currentTimeMillis()
                        Log.i(TAG, "[Detector] ??? MAYBE ENDED: WhatsApp non-call UI: class=$className — waiting for confirmation")
                        WhatsAppCallJourney.i(
                            "detector",
                            "MAYBE_ENDED reason=whatsapp_non_call_ui class=$className"
                        )
                        CallStateChange.CallMaybeEnded
                    } else {
                        // Different app entirely — maybe call ended
                        state = State.MAYBE_ENDED
                        maybeEndedTimestamp = System.currentTimeMillis()
                        Log.i(TAG, "[Detector] ??? MAYBE ENDED: switched to pkg=$pkg class=$className — waiting for confirmation")
                        WhatsAppCallJourney.i(
                            "detector",
                            "MAYBE_ENDED reason=other_app pkg=$pkg class=$className"
                        )
                        CallStateChange.CallMaybeEnded
                    }
                }

                State.MAYBE_ENDED -> {
                    if (isWhatsApp && isCallActivity) {
                        // False alarm! Call UI came back
                        state = State.IN_CALL
                        val elapsed = System.currentTimeMillis() - maybeEndedTimestamp
                        Log.i(TAG, "[Detector] >>> CALL RESUMED after ${elapsed}ms: class=$className — false alarm!")
                        WhatsAppCallJourney.i(
                            "detector",
                            "CALL_RESUMED after ${elapsed}ms activity=$className"
                        )
                        CallStateChange.CallResumed
                    } else if (isIgnorablePackage) {
                        // Still in MAYBE_ENDED, ignore system overlays
                        Log.d(TAG, "[Detector] Ignorable package during MAYBE_ENDED: $pkg — still waiting")
                        null
                    } else {
                        // Another non-call window appeared. Stay in MAYBE_ENDED.
                        // The timer in AppScannerService will confirm the end.
                        Log.d(TAG, "[Detector] Still MAYBE_ENDED, another window: pkg=$pkg class=$className")
                        null
                    }
                }
            }
        }

        // Also check WINDOW_CONTENT_CHANGED from WhatsApp while in MAYBE_ENDED
        // If WhatsApp is still updating content, the call might still be active
        if (state == State.MAYBE_ENDED && eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (pkg in WHATSAPP_PACKAGES) {
                // WhatsApp content is updating — could be call timer. Don't log excessively.
            }
        }

        return null
    }

    /**
     * Called by the service after the debounce timer expires.
     * If we're still in MAYBE_ENDED state, confirm the call has truly ended.
     */
    fun confirmCallEnded(): Boolean {
        if (state == State.MAYBE_ENDED) {
            state = State.IDLE
            val elapsed = System.currentTimeMillis() - maybeEndedTimestamp
            Log.i(TAG, "[Detector] <<< CALL CONFIRMED ENDED after ${elapsed}ms debounce")
            WhatsAppCallJourney.i("detector", "CALL_CONFIRMED_ENDED after ${elapsed}ms debounce")
            return true
        }
        return false
    }

    fun isInCall(): Boolean = state != State.IDLE

    fun getState(): State = state

    fun reset() {
        Log.d(TAG, "[Detector] reset() called, was state=$state")
        state = State.IDLE
    }

    private fun accessibilityEventTypeToString(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        else -> "TYPE_$type"
    }
}
