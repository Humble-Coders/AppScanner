package com.scanner.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.Volatile

/**
 * Batches PCM from RecordingEngine and sends to Google Cloud Speech-to-Text.
 * Emits transcriptions to CaptionEventSource for overlay display.
 */
class CaptionPipeline(
    private val scope: CoroutineScope,
    private val speechClient: SpeechToTextClient,
    private val onTranscript: (String) -> Unit
) {

    companion object {
        private const val TAG = "WARecorder"
        private const val BATCH_DURATION_MS = 3500L
        private val BYTES_PER_SECOND = RecordingEngine.SAMPLE_RATE * 2 // 16-bit mono
    }

    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var totalQueuedBytes = 0
    private var processJob: Job? = null

    private var feedCount = 0L

    fun start() {
        stop()
        feedCount = 0L
        Log.i(TAG, "[CaptionPipeline] Started — will batch ~${BATCH_DURATION_MS}ms of PCM (target ${(BATCH_DURATION_MS / 1000.0 * BYTES_PER_SECOND).toInt()} bytes) before STT")
        processJob = scope.launch {
            while (isActive) {
                delay(BATCH_DURATION_MS)
                processBatch()
            }
        }
    }

    fun stop() {
        processJob?.cancel()
        processJob = null
        pcmQueue.clear()
        totalQueuedBytes = 0
        Log.i(TAG, "[CaptionPipeline] Stopped (received $feedCount PCM feeds)")
    }

    fun feedPcm(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        pcmQueue.offer(pcmBytes)
        totalQueuedBytes += pcmBytes.size
        feedCount++
        if (feedCount == 1L) {
            Log.i(TAG, "[CaptionPipeline] First PCM chunk received (${pcmBytes.size} bytes)")
        } else if (feedCount % 100 == 0L) {
            Log.d(TAG, "[CaptionPipeline] PCM feed #$feedCount — queued=${totalQueuedBytes} bytes (~${totalQueuedBytes / BYTES_PER_SECOND}s)")
        }
    }

    private suspend fun processBatch() {
        val targetBytes = (BATCH_DURATION_MS / 1000.0 * BYTES_PER_SECOND).toInt()
        val minBytes = targetBytes / 2
        if (totalQueuedBytes < minBytes) {
            Log.d(TAG, "[CaptionPipeline] Batch skip — queued=$totalQueuedBytes < min=$minBytes")
            return
        }

        val batch = ArrayList<ByteArray>()
        var collected = 0
        while (collected < targetBytes && pcmQueue.isNotEmpty()) {
            val chunk = pcmQueue.poll() ?: break
            batch.add(chunk)
            collected += chunk.size
            totalQueuedBytes -= chunk.size
        }
        if (batch.isEmpty()) return

        val combined = ByteArray(collected)
        var offset = 0
        for (chunk in batch) {
            System.arraycopy(chunk, 0, combined, offset, chunk.size)
            offset += chunk.size
        }

        Log.i(TAG, "[CaptionPipeline] Sending batch to STT: ${combined.size} bytes (~${combined.size / BYTES_PER_SECOND}s)")
        scope.launch {
            val transcript = speechClient.recognize(combined)
            if (!transcript.isNullOrBlank()) {
                val normalized = transcript.trim()
                Log.i(TAG, "[CaptionPipeline] STT result: \"$normalized\"")
                onTranscript(normalized)
            } else {
                Log.d(TAG, "[CaptionPipeline] STT returned empty (silence or no speech)")
            }
        }
    }
}
