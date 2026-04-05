package com.scanner.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

object AudioEncoder {

    private const val TAG = "WARecorder"
    private const val MIME_TYPE = "audio/mp4a-latm"
    private const val TIMEOUT_US = 10000L

    suspend fun encodeToM4A(
        pcmFile: File,
        outputFile: File,
        sampleRate: Int = 44100,
        channelCount: Int = 1,
        bitRate: Int = 128000
    ): Boolean = withContext(Dispatchers.IO) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputStream: FileInputStream? = null

        Log.i(TAG, "[Encoder] encodeToM4A() START: pcm=${pcmFile.name} (${pcmFile.length()} bytes) → m4a=${outputFile.name}")
        Log.d(TAG, "[Encoder] Config: sampleRate=$sampleRate channels=$channelCount bitRate=$bitRate")

        try {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            }

            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            Log.d(TAG, "[Encoder] MediaCodec created")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Log.d(TAG, "[Encoder] MediaCodec configured")
            codec.start()
            Log.d(TAG, "[Encoder] MediaCodec started")

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            Log.d(TAG, "[Encoder] MediaMuxer created")
            var trackIndex = -1
            var muxerStarted = false

            inputStream = FileInputStream(pcmFile)
            val pcmBuffer = ByteArray(4096)
            var isEos = false
            val bufferInfo = MediaCodec.BufferInfo()
            var inputBytes = 0L
            var outputBytes = 0L
            var frameCount = 0

            while (true) {
                // Feed input
                if (!isEos) {
                    val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                        val bytesRead = inputStream.read(pcmBuffer, 0, minOf(pcmBuffer.size, inputBuffer.remaining()))
                        if (bytesRead <= 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                            Log.d(TAG, "[Encoder] End of PCM input reached (totalInputBytes=$inputBytes)")
                        } else {
                            inputBuffer.put(pcmBuffer, 0, bytesRead)
                            codec.queueInputBuffer(inputBufferIndex, 0, bytesRead, 0, 0)
                            inputBytes += bytesRead
                        }
                    }
                }

                // Drain output
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.d(TAG, "[Encoder] Muxer started with trackIndex=$trackIndex")
                        }
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codec.releaseOutputBuffer(outputBufferIndex, false)
                            continue
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                            outputBytes += bufferInfo.size
                            frameCount++
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "[Encoder] End of stream reached")
                            break
                        }
                    }
                }
            }

            Log.i(TAG, "[Encoder] ✓ Encoding COMPLETE: ${outputFile.name} (${outputFile.length()} bytes) frames=$frameCount inputBytes=$inputBytes outputBytes=$outputBytes")
            // Delete temp PCM file
            val deleted = pcmFile.delete()
            Log.d(TAG, "[Encoder] PCM temp file deleted=$deleted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[Encoder] ✗ Encoding FAILED", e)
            outputFile.delete()
            false
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
