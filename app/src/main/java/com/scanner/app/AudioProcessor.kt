package com.scanner.app

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

class AudioProcessor(private val audioSessionId: Int) {

    companion object {
        private const val TAG = "WARecorder"
    }

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    fun initialize() {
        Log.d(TAG, "[AudioFX] Initializing audio effects for sessionId=$audioSessionId")

        try {
            val aecAvailable = AcousticEchoCanceler.isAvailable()
            Log.d(TAG, "[AudioFX] AcousticEchoCanceler available=$aecAvailable")
            if (aecAvailable) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.also {
                    it.enabled = true
                    Log.d(TAG, "[AudioFX] AcousticEchoCanceler ENABLED")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AudioFX] AcousticEchoCanceler creation failed", e)
        }

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
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        gainControl?.release()
        gainControl = null
        Log.d(TAG, "[AudioFX] Audio effects released")
    }
}
