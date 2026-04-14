package com.scanner.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

enum class RecordingStrategy {
    /** Primary: mic + in-call audio path. Works reliably on all devices. */
    VOICE_RECOGNITION,
    /** Fallback: forces speakerphone so remote audio leaks into mic.
     *  Only useful if VOICE_RECOGNITION fails to initialize. */
    VOICE_COMMUNICATION
}

class RecordingEngine(private val context: Context) {

    companion object {
        private const val TAG = "WARecorder"
        /** 16 kHz is Google Cloud STT's recommended rate for telephony — 2.75× less data than 44.1 kHz. */
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioProcessor: AudioProcessor? = null
    private var recordingJob: Job? = null
    private var pcmFile: File? = null
    private var outputStream: FileOutputStream? = null
    private var isRecording = false
    private var currentStrategy: RecordingStrategy = RecordingStrategy.VOICE_RECOGNITION
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var wasMode = AudioManager.MODE_NORMAL
    private var wasSpeakerOn = false
    private var totalBytesWritten = 0L
    private var totalReads = 0L

    val activeStrategy: RecordingStrategy get() = currentStrategy

    var onPcmChunk: ((ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startRecording(outputDir: File, scope: CoroutineScope): Boolean {
        if (isRecording) {
            Log.w(TAG, "[Engine] Already recording, returning false")
            return false
        }

        Log.i(TAG, "[Engine] startRecording() outputDir=${outputDir.absolutePath}")
        outputDir.mkdirs()
        val fileName = CallRecordingEventSource.generateFileName()
        pcmFile = File(outputDir, "${fileName}.pcm")
        Log.d(TAG, "[Engine] PCM file will be: ${pcmFile?.absolutePath}")

        // Try strategies in order
        val strategies = RecordingStrategy.entries.toList()
        for (strategy in strategies) {
            Log.d(TAG, "[Engine] Trying strategy: $strategy")
            if (tryStrategy(strategy)) {
                currentStrategy = strategy
                Log.i(TAG, "[Engine] ✓ Strategy $strategy SUCCEEDED — starting recording loop")
                isRecording = true
                totalBytesWritten = 0L
                totalReads = 0L
                startRecordingLoop(scope)
                return true
            } else {
                Log.w(TAG, "[Engine] ✗ Strategy $strategy FAILED")
            }
        }

        Log.e(TAG, "[Engine] ✗ ALL recording strategies failed!")
        return false
    }

    @SuppressLint("MissingPermission")
    private fun tryStrategy(strategy: RecordingStrategy): Boolean {
        // Release previous AudioRecord if any
        releaseAudioRecord()

        // Cube ACR approach: force MODE_IN_COMMUNICATION always. For VOICE_RECOGNITION do NOT
        // change speaker (better voice sharing). For VOICE_COMMUNICATION use speaker as fallback.
        wasMode = audioManager.mode
        wasSpeakerOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        when (strategy) {
            RecordingStrategy.VOICE_COMMUNICATION -> {
                audioManager.isSpeakerphoneOn = true
                Log.d(TAG, "[Engine] VOICE_COMMUNICATION: force in-communication + speakerOn=true (captures remote voice via speaker leak)")
            }
            RecordingStrategy.VOICE_RECOGNITION -> {
                Log.d(TAG, "[Engine] VOICE_RECOGNITION: force in-communication, speaker unchanged (fallback)")
            }
        }
        val audioSource = when (strategy) {
            RecordingStrategy.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            RecordingStrategy.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        return try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            Log.d(TAG, "[Engine] getMinBufferSize=$bufferSize for strategy=$strategy")
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "[Engine] Invalid buffer size ($bufferSize) for strategy $strategy")
                return false
            }

            val record = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            Log.d(TAG, "[Engine] AudioRecord created: state=${record.state} (need ${AudioRecord.STATE_INITIALIZED}) sessionId=${record.audioSessionId}")

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "[Engine] AudioRecord NOT initialized for strategy $strategy (state=${record.state})")
                record.release()
                return false
            }

            audioRecord = record

            // AudioProcessor applies AGC/NS only (AEC is intentionally disabled in AudioProcessor
            // so the speaker audio captured by the mic is NOT cancelled out — that's the signal
            // we need to detect the scammer's voice).
            if (strategy == RecordingStrategy.VOICE_COMMUNICATION) {
                Log.d(TAG, "[Engine] Setting up AudioProcessor (no AEC) for sessionId=${record.audioSessionId}")
                audioProcessor = AudioProcessor(record.audioSessionId)
                audioProcessor?.initialize()
            }

            record.startRecording()
            Log.i(TAG, "[Engine] ✓ AudioRecord.startRecording() called (source=$audioSource, sampleRate=$SAMPLE_RATE, buffer=$bufferSize)")

            // Verify recording state
            val recordingState = record.recordingState
            Log.d(TAG, "[Engine] AudioRecord recordingState=$recordingState (need ${AudioRecord.RECORDSTATE_RECORDING})")

            true
        } catch (e: Exception) {
            Log.e(TAG, "[Engine] Strategy $strategy threw exception", e)
            false
        }
    }

    private fun startRecordingLoop(scope: CoroutineScope) {
        Log.d(TAG, "[Engine] Starting recording loop (no mid-call strategy switch)")
        recordingJob = scope.launch(Dispatchers.IO) {
            val record = audioRecord ?: run {
                Log.e(TAG, "[Engine] audioRecord is null in recording loop!")
                return@launch
            }
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val buffer = ShortArray(bufferSize)
            Log.d(TAG, "[Engine] Recording loop started, bufferSize=$bufferSize")

            try {
                outputStream = FileOutputStream(pcmFile, true)
                val byteBuffer = ByteArray(bufferSize * 2)
                var loopCount = 0L
                var silentBufferCount = 0L

                while (isActive && isRecording) {
                    val readCount = record.read(buffer, 0, buffer.size)
                    loopCount++
                    totalReads++

                    if (readCount > 0) {
                        // Convert shorts to bytes and write
                        for (i in 0 until readCount) {
                            byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                        }
                        outputStream?.write(byteBuffer, 0, readCount * 2)
                        totalBytesWritten += readCount * 2
                        onPcmChunk?.invoke(byteBuffer.copyOf(readCount * 2))

                        // Silence detection: count zero samples
                        var nonZero = 0
                        for (i in 0 until readCount) { if (buffer[i] != 0.toShort()) nonZero++ }
                        if (nonZero == 0) silentBufferCount++

                        if (loopCount == 1L) {
                            Log.i(TAG, "[Engine] >>> First audio data read! readCount=$readCount nonZeroSamples=$nonZero strategy=$currentStrategy")
                        }
                        // Every ~2 seconds at 16kHz, log audio health
                        if (loopCount % 50 == 0L) {
                            val durationSec = totalBytesWritten / (SAMPLE_RATE * 2.0)
                            val silencePct = silentBufferCount * 100 / loopCount
                            val audioStatus = if (silencePct > 90) "⚠️ MOSTLY SILENT — speaker may not be routing to mic" else "✓ audio signal present"
                            Log.i(TAG, "[Engine] Audio health: ~${String.format("%.1f", durationSec)}s recorded | silentBuffers=$silencePct% | strategy=$currentStrategy | $audioStatus")
                        }
                    } else if (readCount < 0) {
                        Log.e(TAG, "[Engine] AudioRecord.read() returned error: $readCount (${if (readCount == AudioRecord.ERROR_INVALID_OPERATION) "ERROR_INVALID_OPERATION" else "ERROR"})")
                    }
                }
                Log.d(TAG, "[Engine] Recording loop ended: isActive=$isActive isRecording=$isRecording totalReads=$totalReads totalBytes=$totalBytesWritten")
            } catch (e: Exception) {
                Log.e(TAG, "[Engine] Recording loop exception", e)
            } finally {
                try { outputStream?.flush() } catch (_: Exception) {}
                try { outputStream?.close() } catch (_: Exception) {}
                outputStream = null
                Log.d(TAG, "[Engine] Recording loop cleanup done")
            }
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) {
            Log.w(TAG, "[Engine] stopRecording() called but not recording")
            return null
        }
        isRecording = false

        Log.i(TAG, "[Engine] stopRecording() — totalReads=$totalReads totalBytes=$totalBytesWritten")

        recordingJob?.cancel()
        recordingJob = null

        try { outputStream?.flush() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null

        releaseAudioRecord()
        restoreAudioState()

        val file = pcmFile
        if (file != null && file.exists() && file.length() > 0) {
            Log.i(TAG, "[Engine] ✓ PCM file ready: ${file.name} (${file.length()} bytes)")
            return file
        }

        Log.w(TAG, "[Engine] ✗ PCM file empty or missing: ${file?.name} exists=${file?.exists()} size=${file?.length()}")
        return null
    }

    private fun releaseAudioRecord() {
        audioProcessor?.release()
        audioProcessor = null

        try {
            audioRecord?.stop()
            Log.d(TAG, "[Engine] AudioRecord stopped")
        } catch (e: Exception) {
            Log.e(TAG, "[Engine] AudioRecord stop error", e)
        }
        try {
            audioRecord?.release()
            Log.d(TAG, "[Engine] AudioRecord released")
        } catch (e: Exception) {
            Log.e(TAG, "[Engine] AudioRecord release error", e)
        }
        audioRecord = null
    }

    private fun restoreAudioState() {
        try {
            audioManager.isSpeakerphoneOn = wasSpeakerOn
            audioManager.mode = wasMode
            Log.d(TAG, "[Engine] Restored audio: mode=$wasMode speaker=$wasSpeakerOn")
        } catch (e: Exception) {
            Log.e(TAG, "[Engine] Failed to restore audio state", e)
        }
    }

    fun release() {
        Log.d(TAG, "[Engine] release() called")
        stopRecording()
    }
}
