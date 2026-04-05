package com.scanner.app

import android.content.Context
import android.content.res.Resources.NotFoundException
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import android.util.Log

/**
 * Plays [R.raw.emergency] (`res/raw/emergency.mp3`) when a guardian alert FCM arrives.
 */
object GuardianAlertSoundPlayer {

    private const val TAG = "GuardianAlertSound"

    @Volatile
    private var player: MediaPlayer? = null
    private val lock = Any()

    fun playEmergencyAlert(context: Context) {
        val appCtx = context.applicationContext
        synchronized(lock) {
            stopLocked()
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setWakeMode(appCtx, PowerManager.PARTIAL_WAKE_LOCK)
                appCtx.resources.openRawResourceFd(R.raw.emergency).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                mp.prepare()
                mp.setOnCompletionListener {
                    synchronized(lock) {
                        try {
                            it.release()
                        } catch (_: Exception) {
                        }
                        if (player === it) player = null
                    }
                }
                mp.start()
                player = mp
            } catch (e: NotFoundException) {
                Log.e(TAG, "Missing res/raw/emergency.mp3", e)
                mp.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play guardian emergency sound", e)
                try {
                    mp.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopLocked() {
        val p = player
        player = null
        if (p != null) {
            try {
                if (p.isPlaying) p.stop()
            } catch (_: Exception) {
            }
            try {
                p.release()
            } catch (_: Exception) {
            }
        }
    }
}
