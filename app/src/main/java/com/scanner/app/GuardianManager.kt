package com.scanner.app

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GuardianManager {

    private const val TAG = "GuardianManager"

    /**
     * Replace with your deployed Cloud Function URL after running:
     * firebase deploy --only functions
     */
    private const val CLOUD_FN_URL =
        "https://us-central1-app-shield-f8b62.cloudfunctions.net/notifyGuardians"

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    /** Returns the existing anonymous UID or signs in anonymously to create one. */
    suspend fun getOrCreateUserId(): String {
        auth.currentUser?.let { return it.uid }
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: error("Anonymous sign-in returned null user")
    }

    /** Returns the UID if already signed in, null otherwise. */
    fun getCurrentUserId(): String? = auth.currentUser?.uid

    /** Retrieves the device's current FCM registration token. */
    private suspend fun getFcmToken(): String =
        FirebaseMessaging.getInstance().token.await()

    /**
     * Stores (or updates) this device's FCM token under users/{uid}/fcmToken.
     * If [context] is provided and this user saved a phone number, also updates
     * [phoneDirectory] so others can link as guardian by phone.
     */
    suspend fun refreshFcmToken(context: Context? = null) {
        val uid = getOrCreateUserId()
        val token = getFcmToken()
        db.collection("users").document(uid)
            .set(
                mapOf("fcmToken" to token, "updatedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge()
            ).await()
        val phone = context?.let { GuardianPhoneStore.getMyNormalizedPhone(it) }
        if (!phone.isNullOrBlank()) {
            db.collection("phoneDirectory").document(phone)
                .set(
                    mapOf(
                        "userId" to uid,
                        "fcmToken" to token,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
        }
        Log.d(TAG, "FCM token stored for uid=${uid.take(8)}…")
    }

    /** Digits only, used as Firestore document id. */
    fun normalizePhoneInput(raw: String): String = raw.filter { it.isDigit() }

    /**
     * Saves local phone + publishes [userId] and FCM token under phoneDirectory for guardian lookup.
     */
    suspend fun registerMyPhoneNumber(context: Context, rawPhone: String) {
        val normalized = normalizePhoneInput(rawPhone)
        require(normalized.isNotBlank()) { "Enter a phone number" }
        GuardianPhoneStore.saveMyPhone(context, normalized)
        refreshFcmToken(context)
    }

    /**
     * Protected user: copy guardian's FCM token from [phoneDirectory] into guardians/{myUid}/tokens/{guardianUid}.
     */
    suspend fun linkGuardianByPhoneNumber(context: Context, rawGuardianPhone: String) {
        val myUid = getOrCreateUserId()
        val normalized = normalizePhoneInput(rawGuardianPhone)
        require(normalized.isNotBlank()) { "Enter a phone number" }

        val doc = db.collection("phoneDirectory").document(normalized).get().await()
        val guardianUid = doc.getString("userId")
            ?: error("This number is not registered in the app yet")
        val token = doc.getString("fcmToken")
            ?: error("Guardian must open the app once so alerts can be delivered")

        require(guardianUid != myUid) { "You cannot add yourself as guardian" }

        db.collection("guardians").document(myUid).collection("tokens").document(guardianUid)
            .set(
                mapOf(
                    "fcmToken" to token,
                    "guardianUid" to guardianUid,
                    "linkedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        Log.d(TAG, "Linked guardian ${guardianUid.take(8)}… for protected ${myUid.take(8)}… by phone")
    }

    /**
     * Links the current device as a guardian for [protectedUserId].
     * Stores this device's FCM token under guardians/{protectedUserId}/tokens/{myUid}.
     * Using myUid as the document ID prevents duplicate guardian entries.
     */
    suspend fun linkAsGuardian(protectedUserId: String) {
        val myUid = getOrCreateUserId()
        val token = getFcmToken()
        db.collection("guardians")
            .document(protectedUserId)
            .collection("tokens")
            .document(myUid)
            .set(
                mapOf(
                    "fcmToken" to token,
                    "guardianUid" to myUid,
                    "linkedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        Log.d(TAG, "uid=${myUid.take(8)} is now guardian for ${protectedUserId.take(8)}")
    }

    /**
     * Calls the Cloud Function to send FCM alerts to all guardians of [protectedUserId].
     * Runs a plain HTTP POST so it doesn't require the Firebase Functions SDK.
     */
    suspend fun notifyGuardians(
        protectedUserId: String,
        appName: String,
        packageName: String,
        riskLevel: String,
        riskScore: Int,
        primaryReason: String?,
        alertKind: String = GuardianAlertKind.RISKY_APP
    ) {
        try {
            val body = JSONObject().apply {
                put("userId", protectedUserId)
                put("appName", appName)
                put("packageName", packageName)
                put("riskLevel", riskLevel)
                put("riskScore", riskScore)
                put("alertKind", alertKind)
                if (primaryReason != null) put("primaryReason", primaryReason)
            }.toString()

            val conn = URL(CLOUD_FN_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            Log.d(TAG, "notifyGuardians: HTTP $code for userId=${protectedUserId.take(8)}")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "notifyGuardians failed", e)
        }
    }
}
