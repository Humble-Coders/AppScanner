package com.scanner.app

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CallerInfo(
    val valid: Boolean,
    val number: String,
    val localFormat: String,
    val internationalFormat: String,
    val countryPrefix: String,
    val countryCode: String,
    val countryName: String,
    val location: String,
    val carrier: String,
    val lineType: String
)

object NumLookupClient {

    private const val TAG = "NumLookup"
    private const val API_KEY = "num_live_u2UeguIFvaJdXIuLO9tZPjfL9jRK5qW2eaMN65z3"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Synchronous network call — must be called from a background thread / IO dispatcher.
     * Returns null if the request fails or the number is invalid according to the API.
     *
     * The API does NOT return a caller name; it provides location, carrier, and line type only.
     */
    fun lookup(number: String): CallerInfo? {
        return try {
            val encoded = number.replace("+", "%2B")
            val url = "https://api.numlookupapi.com/v1/validate/$encoded?apikey=$API_KEY"
            Log.d(TAG, "lookup: requesting $url")
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.w(TAG, "lookup: empty response for $number (http=${response.code})")
                return null
            }
            Log.d(TAG, "lookup response for $number: $body")
            val json = JSONObject(body)
            CallerInfo(
                valid = json.optBoolean("valid", false),
                number = json.optString("number", number),
                localFormat = json.optString("local_format", ""),
                internationalFormat = json.optString("international_format", number),
                countryPrefix = json.optString("country_prefix", ""),
                countryCode = json.optString("country_code", ""),
                countryName = json.optString("country_name", ""),
                location = json.optString("location", ""),
                carrier = json.optString("carrier", ""),
                lineType = json.optString("line_type", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "lookup failed for $number", e)
            null
        }
    }
}
