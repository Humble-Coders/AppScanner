package com.scanner.app

import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class CallRecording(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String,
    val fileName: String,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val fileSize: Long,
    val strategy: String = "unknown"
)

object CallRecordingEventSource {

    private const val TAG = "WARecorder"

    private val _recordings = MutableSharedFlow<CallRecording>(replay = 50, extraBufferCapacity = 10)
    val recordings: SharedFlow<CallRecording> = _recordings.asSharedFlow()

    fun tryEmit(recording: CallRecording): Boolean {
        val emitted = _recordings.tryEmit(recording)
        Log.i(TAG, "[EventSource] tryEmit: file=${recording.fileName} duration=${recording.durationMs}ms size=${recording.fileSize} emitted=$emitted")
        return emitted
    }

    fun loadExistingRecordings(recordingsDir: File): List<CallRecording> {
        Log.d(TAG, "[EventSource] loadExistingRecordings: dir=${recordingsDir.absolutePath} exists=${recordingsDir.exists()}")

        if (!recordingsDir.exists()) {
            Log.d(TAG, "[EventSource] Directory doesn't exist, returning empty")
            return emptyList()
        }

        val files = recordingsDir.listFiles { file -> file.extension == "m4a" }
        Log.d(TAG, "[EventSource] Found ${files?.size ?: 0} m4a files")

        val recordings = files
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                Log.d(TAG, "[EventSource] Processing file: ${file.name} (${file.length()} bytes)")
                fileToRecording(file)
            }
            ?: emptyList()

        Log.i(TAG, "[EventSource] Loaded ${recordings.size} existing recordings")
        return recordings
    }

    private fun fileToRecording(file: File): CallRecording? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()

            val recording = CallRecording(
                id = file.name,
                filePath = file.absolutePath,
                fileName = file.nameWithoutExtension,
                durationMs = durationStr?.toLongOrNull() ?: 0L,
                timestamp = file.lastModified(),
                fileSize = file.length()
            )
            Log.d(TAG, "[EventSource] Parsed: ${file.name} duration=${recording.durationMs}ms")
            recording
        } catch (e: Exception) {
            Log.e(TAG, "[EventSource] Failed to read metadata for ${file.name}", e)
            null
        }
    }

    fun generateFileName(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "whatsapp_${sdf.format(Date())}"
        Log.d(TAG, "[EventSource] Generated filename: $name")
        return name
    }
}
