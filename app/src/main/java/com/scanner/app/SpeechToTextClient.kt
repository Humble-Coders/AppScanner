package com.scanner.app

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for Google Cloud Speech-to-Text REST API.
 * Uses synchronous recognize for batched audio (3–5 sec chunks).
 */
class SpeechToTextClient(private val apiKey: String) {

    companion object {
        private const val TAG = "WARecorder"
        private const val BASE_URL = "https://speech.googleapis.com/v1/speech:recognize"
        private const val SAMPLE_RATE = 44100
        private val JSON = "application/json; charset=utf-8".toMediaType()
        /** HTTP codes that are worth retrying (transient/rate-limit). */
        private val RETRYABLE_CODES = setOf(429, 500, 502, 503)
        private const val MAX_RETRIES = 3
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun recognize(pcmBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "[SpeechToText] API key is BLANK — captions will not work. Set GOOGLE_CLOUD_SPEECH_API_KEY in gradle.properties")
            return@withContext null
        }
        if (apiKey.length < 20) {
            Log.w(TAG, "[SpeechToText] API key seems invalid (too short: ${apiKey.length} chars)")
        }
        if (pcmBytes.size < 1000) {
            Log.d(TAG, "[SpeechToText] Audio too short (${pcmBytes.size} bytes), skipping")
            return@withContext null
        }
        Log.d(TAG, "[SpeechToText] Sending request: ${pcmBytes.size} bytes, sampleRate=$SAMPLE_RATE")
        try {
            val base64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("config", JSONObject().apply {
                    put("encoding", "LINEAR16")
                    put("sampleRateHertz", SAMPLE_RATE)
                    put("languageCode", "en-US")
                })
                put("audio", JSONObject().apply {
                    put("content", base64)
                })
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(body.toRequestBody(JSON))
                .build()

            var response = client.newCall(request).execute()
            var attempt = 0
            while (!response.isSuccessful && response.code in RETRYABLE_CODES && attempt < MAX_RETRIES) {
                response.body?.close()
                val delayMs = (1 shl attempt) * 1000L  // 1s, 2s, 4s
                Log.w(TAG, "[SpeechToText] HTTP ${response.code} — retrying in ${delayMs}ms (attempt ${attempt + 1}/$MAX_RETRIES)")
                delay(delayMs)
                response = client.newCall(request).execute()
                attempt++
            }

            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                Log.e(TAG, "[SpeechToText] API error: HTTP ${response.code} — $body")
                return@withContext null
            }

            val json = response.body?.string() ?: return@withContext null
            Log.d(TAG, "[SpeechToText] API success, parsing response (${json.length} chars)")
            val result = JSONObject(json)
            val results = result.optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null

            val first = results.getJSONObject(0)
            val alternatives = first.optJSONArray("alternatives") ?: return@withContext null
            if (alternatives.length() == 0) return@withContext null

            val transcript = alternatives.getJSONObject(0).optString("transcript", "")
            if (transcript.isNotBlank()) {
                Log.i(TAG, "[SpeechToText] Transcript: \"$transcript\"")
            } else {
                Log.d(TAG, "[SpeechToText] No transcript in alternatives (possible silence)")
            }
            transcript.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "[SpeechToText] Recognition failed: ${e.message}", e)
            null
        }
    }
}
