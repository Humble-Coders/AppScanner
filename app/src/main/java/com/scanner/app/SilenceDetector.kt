package com.scanner.app

import android.util.Log
import kotlin.math.sqrt

class SilenceDetector(
    private val silenceThresholdRms: Double = 200.0,
    private val silenceDurationMs: Long = 5000L,
    private val onSilenceDetected: () -> Unit
) {

    companion object {
        private const val TAG = "WARecorder"
    }

    private var silenceStartTime: Long = 0L
    private var isSilent = false
    private var hasFired = false
    private var sampleCount = 0L

    fun feedSamples(buffer: ShortArray, readCount: Int) {
        if (readCount <= 0 || hasFired) return
        sampleCount++

        val rms = calculateRms(buffer, readCount)

        // Log RMS periodically so we can see if audio is actually being captured
        if (sampleCount == 1L) {
            Log.d(TAG, "[Silence] First sample RMS=%.1f threshold=%.1f".format(rms, silenceThresholdRms))
        }
        if (sampleCount % 100 == 0L) {
            Log.d(TAG, "[Silence] Sample #$sampleCount RMS=%.1f isSilent=$isSilent hasFired=$hasFired".format(rms))
        }

        if (rms < silenceThresholdRms) {
            if (!isSilent) {
                isSilent = true
                silenceStartTime = System.currentTimeMillis()
                Log.d(TAG, "[Silence] Silence started (RMS=%.1f < threshold=%.1f)".format(rms, silenceThresholdRms))
            } else {
                val elapsed = System.currentTimeMillis() - silenceStartTime
                if (elapsed >= silenceDurationMs) {
                    Log.w(TAG, "[Silence] Silence confirmed for ${elapsed}ms — triggering strategy switch!")
                    hasFired = true
                    onSilenceDetected()
                }
            }
        } else {
            if (isSilent) {
                Log.d(TAG, "[Silence] Silence broken (RMS=%.1f > threshold=%.1f)".format(rms, silenceThresholdRms))
            }
            isSilent = false
        }
    }

    fun reset() {
        Log.d(TAG, "[Silence] reset()")
        silenceStartTime = 0L
        isSilent = false
        hasFired = false
        sampleCount = 0L
    }

    private fun calculateRms(buffer: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / count)
    }
}
