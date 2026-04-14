package com.scanner.app

import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

class AudioProcessor(private val audioSessionId: Int) {

    companion object {
        private const val TAG = "WARecorder"
    }

    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    fun initialize() {
        Log.d(TAG, "[AudioFX] Initializing audio effects for sessionId=$audioSessionId")

        // AEC is intentionally NOT enabled: we run in VOICE_COMMUNICATION mode with the speaker
        // on so that the remote caller's audio leaks into the mic. AEC would cancel exactly that
        // signal, making scam detection impossible. AGC + NS are kept to improve mic clarity.
        Log.d(TAG, "[AudioFX] AcousticEchoCanceler SKIPPED (intentional — preserving speaker audio for scam detection)")

        try {
            val nsAvailable = NoiseSuppressor.isAvailable()
            Log.d(TAG, "[AudioFX] NoiseSuppressor available=$nsAvailable")
            if (nsAvailable) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.also {
                    it.enabled = true
                    Log.d(TAG, "[AudioFX] NoiseSuppressor ENABLED")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AudioFX] NoiseSuppressor creation failed", e)
        }

        try {
            val agcAvailable = AutomaticGainControl.isAvailable()
            Log.d(TAG, "[AudioFX] AutomaticGainControl available=$agcAvailable")
            if (agcAvailable) {
                gainControl = AutomaticGainControl.create(audioSessionId)?.also {
                    it.enabled = true
                    Log.d(TAG, "[AudioFX] AutomaticGainControl ENABLED")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AudioFX] AutomaticGainControl creation failed", e)
        }
    }

    fun release() {
        noiseSuppressor?.release()
        noiseSuppressor = null
        gainControl?.release()
        gainControl = null
        Log.d(TAG, "[AudioFX] Audio effects released")
    }
}
