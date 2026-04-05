package com.scanner.app

import android.media.MediaPlayer
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecordingPlaybackManager(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "PlaybackManager"
        private const val PROGRESS_UPDATE_MS = 200L
    }

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var pendingRecordingId: String? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentRecordingId = MutableStateFlow<String?>(null)
    val currentRecordingId: StateFlow<String?> = _currentRecordingId.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration.asStateFlow()

    fun play(recording: CallRecording) {
        if (_currentRecordingId.value == recording.id && mediaPlayer != null) {
            // Toggle play/pause for same recording
            if (_isPlaying.value) {
                pause()
            } else {
                resume()
            }
            return
        }

        // New recording - stop current and start new
        stop()

        val file = File(recording.filePath)
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "Recording file does not exist or is empty: ${recording.filePath}")
            return
        }

        pendingRecordingId = recording.id
        try {
            val player = MediaPlayer().apply {
                setDataSource(recording.filePath)
                setOnCompletionListener {
                    _isPlaying.value = false
                    _progress.value = 1f
                    progressJob?.cancel()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    true
                }
            }

            player.setOnPreparedListener {
                if (pendingRecordingId != recording.id) {
                    player.release()
                    return@setOnPreparedListener
                }
                try {
                    player.start()
                    mediaPlayer = player
                    _currentRecordingId.value = recording.id
                    _isPlaying.value = true
                    _duration.value = player.duration.coerceAtLeast(0)
                    _progress.value = 0f
                    startProgressTracking()
                    Log.d(TAG, "Playing: ${recording.fileName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start playback after prepare", e)
                    player.release()
                }
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play recording", e)
            stop()
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        _isPlaying.value = false
        progressJob?.cancel()
    }

    private fun resume() {
        mediaPlayer?.start()
        _isPlaying.value = true
        startProgressTracking()
    }

    fun stop() {
        pendingRecordingId = null
        progressJob?.cancel()
        progressJob = null

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        _isPlaying.value = false
        _currentRecordingId.value = null
        _progress.value = 0f
        _duration.value = 0
    }

    fun seekTo(fraction: Float) {
        val player = mediaPlayer ?: return
        val position = (fraction * player.duration).toInt()
        player.seekTo(position)
        _progress.value = fraction
    }

    fun release() {
        stop()
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                val player = mediaPlayer
                if (player != null && player.isPlaying) {
                    val current = player.currentPosition.toFloat()
                    val total = player.duration.toFloat()
                    if (total > 0) {
                        _progress.value = current / total
                    }
                }
                delay(PROGRESS_UPDATE_MS)
            }
        }
    }
}
