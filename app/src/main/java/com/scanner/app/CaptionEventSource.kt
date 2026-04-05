package com.scanner.app

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event source for live caption text during calls.
 * CaptionPipeline emits here; CallRecordingService overlay subscribes.
 */
object CaptionEventSource {

    private const val TAG = "WARecorder"

    private val _captions = MutableSharedFlow<CaptionUpdate>(replay = 1, extraBufferCapacity = 10)
    val captions: SharedFlow<CaptionUpdate> = _captions.asSharedFlow()

    fun emitCaption(text: String) {
        Log.d(TAG, "[CaptionEventSource] emitCaption: \"${text.take(60)}${if (text.length > 60) "…" else ""}\"")
        val success = _captions.tryEmit(CaptionUpdate(text = text))
        if (!success) Log.w(TAG, "[CaptionEventSource] Buffer full, caption dropped: \"$text\"")
    }

    fun emitClear() {
        Log.d(TAG, "[CaptionEventSource] emitClear")
        _captions.tryEmit(CaptionUpdate(clear = true))
    }

    data class CaptionUpdate(val text: String = "", val clear: Boolean = false)
}
