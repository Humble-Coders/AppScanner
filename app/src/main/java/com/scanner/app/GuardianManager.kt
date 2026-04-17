package com.scanner.app

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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
        val name = context?.let { GuardianPhoneStore.getMyDisplayName(it) }
        if (!phone.isNullOrBlank()) {
            db.collection("phoneDirectory").document(phone)
                .set(
                    mapOf(
                        "userId" to uid,
                        "fcmToken" to token,
                        "displayName" to (name ?: ""),
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
    suspend fun registerMyPhoneNumber(context: Context, displayName: String, rawPhone: String) {
        val normalized = normalizePhoneInput(rawPhone)
        val name = displayName.trim()
        require(name.isNotBlank()) { "Enter your name" }
        require(normalized.isNotBlank()) { "Enter a phone number" }
        GuardianPhoneStore.saveMyProfile(context, name, normalized)
        refreshFcmToken(context)
    }

    /**
     * Protected user: copy guardian's FCM token from [phoneDirectory] into guardians/{myUid}/tokens/{guardianUid}.
     */
    suspend fun linkGuardianByPhoneNumber(context: Context, rawGuardianPhone: String): String? {
        val myUid = getOrCreateUserId()
        val normalized = normalizePhoneInput(rawGuardianPhone)
        require(normalized.isNotBlank()) { "Enter a phone number" }

        val doc = db.collection("phoneDirectory").document(normalized).get().await()
        val guardianUid = doc.getString("userId")
            ?: error("This number is not registered in the app yet")
        val token = doc.getString("fcmToken")
            ?: error("Guardian must open the app once so alerts can be delivered")
        val guardianName = doc.getString("displayName")?.trim()?.takeIf { it.isNotBlank() }

        require(guardianUid != myUid) { "You cannot add yourself as guardian" }

        db.collection("guardians").document(myUid).collection("tokens").document(guardianUid)
            .set(
                mapOf(
                    "fcmToken" to token,
                    "guardianUid" to guardianUid,
                    "linkedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        try {
            db.collection("wardsByGuardian").document(guardianUid).collection("protectedUsers").document(myUid)
                .set(
                    mapOf(
                        "protectedUserId" to myUid,
                        "linkedAt" to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                ).await()
        } catch (e: Exception) {
            Log.e(TAG, "wardsByGuardian mirror failed (deploy firestore.rules?)", e)
        }
        Log.d(TAG, "Linked guardian ${guardianUid.take(8)}… for protected ${myUid.take(8)}… by phone")
        return guardianName
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
        try {
            db.collection("wardsByGuardian").document(myUid).collection("protectedUsers").document(protectedUserId)
                .set(
                    mapOf(
                        "protectedUserId" to protectedUserId,
                        "linkedAt" to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                ).await()
        } catch (e: Exception) {
            Log.e(TAG, "wardsByGuardian mirror failed (deploy firestore.rules?)", e)
        }
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
        alertKind: String = GuardianAlertKind.RISKY_APP,
        protectedUserPhone: String? = null,
        financeLockActive: Boolean = false,
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
                if (!protectedUserPhone.isNullOrBlank()) put("protectedUserPhone", protectedUserPhone)
                put("financeLockActive", financeLockActive)
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

    /**
     * Guardian: allow the protected user to open finance apps until [durationMs] elapses.
     * Requires Firestore rules allowing guardians to update `users/{protectedUserId}`.
     */
    suspend fun grantFinanceUnlockToProtectedUser(
        protectedUserId: String,
        durationMs: Long = 30 * 60 * 1000L,
    ) {
        val until = System.currentTimeMillis() + durationMs
        db.collection("users").document(protectedUserId)
            .update(
                mapOf(
                    "financeUnlockUntilMs" to until,
                    "financeUnlockUpdatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        Log.d(TAG, "grantFinanceUnlock: until=$until for ${protectedUserId.take(8)}…")
    }

    /** One-shot read of [wardsByGuardian] (written when victims link by phone or guardian scans QR). */
    suspend fun listWardsFromRegistry(): List<String> {
        val myUid = getOrCreateUserId()
        return try {
            db.collection("wardsByGuardian").document(myUid).collection("protectedUsers")
                .get()
                .await()
                .documents
                .map { it.id }
        } catch (e: Exception) {
            Log.e(TAG, "listWardsFromRegistry failed", e)
            emptyList()
        }
    }

    /** Legacy listing via collection group (requires composite index); used to merge old links. */
    suspend fun listLegacyProtectedUserIdsFromCollectionGroup(): List<String> {
        val myUid = getOrCreateUserId()
        return try {
            db.collectionGroup("tokens")
                .whereEqualTo("guardianUid", myUid)
                .get()
                .await()
                .documents
                .mapNotNull { it.reference.parent.parent?.id }
        } catch (e: Exception) {
            Log.w(TAG, "listLegacyProtectedUserIdsFromCollectionGroup failed (missing index is OK)", e)
            emptyList()
        }
    }

    /**
     * Live updates when a protected user links this device as guardian by phone
     * (writes [wardsByGuardian] on their device). Callback may run on the main thread.
     */
    fun addWardsRegistryListener(
        guardianUid: String,
        onUpdate: (List<String>) -> Unit,
    ): ListenerRegistration {
        return db.collection("wardsByGuardian").document(guardianUid).collection("protectedUsers")
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.w(TAG, "wardsRegistry listener: ${e.message}")
                    return@addSnapshotListener
                }
                val ids = snap?.documents?.map { it.id }?.distinct().orEmpty()
                Log.d(TAG, "wardsRegistry snapshot: ${ids.size} ward(s)")
                onUpdate(ids)
            }
    }

    /**
     * All user IDs this device is linked as guardian for (phone link from victim and/or QR scan).
     * Merges [wardsByGuardian] with legacy collection-group on `tokens` when the index exists.
     */
    suspend fun listProtectedUserIdsForCurrentGuardian(): List<String> {
        val fromRegistry = listWardsFromRegistry()
        val fromLegacyTokens = listLegacyProtectedUserIdsFromCollectionGroup()
        return (fromRegistry + fromLegacyTokens).distinct()
    }

    /**
     * Clears active post-scam finance lock on every protected user by extending
     * [financeUnlockUntilMs] through the scam protection window (same max effect as guardian alert button).
     */
    suspend fun clearFinanceLockForAllProtectedUsers() {
        val wards = listProtectedUserIdsForCurrentGuardian()
        for (id in wards) {
            grantFinanceUnlockToProtectedUser(id, ScamAlertState.SCAM_FINANCE_PROTECTION_MS)
        }
        Log.d(TAG, "clearFinanceLockForAllProtectedUsers: updated ${wards.size} ward(s)")
    }

    /**
     * Protected user: invalidate any prior finance unlock when a new scam is detected.
     */
    suspend fun clearFinanceUnlockAfterScam(protectedUserId: String) {
        try {
            db.collection("users").document(protectedUserId)
                .update(mapOf("financeUnlockUntilMs" to 0L))
                .await()
        } catch (e: Exception) {
            Log.d(TAG, "clearFinanceUnlockAfterScam: ${e.message}")
        }
    }
}
