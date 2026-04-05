package com.scanner.app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GuardianFCMService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "GuardianFCMService"
    }

    /** Called when Firebase generates a new registration token. Re-register in Firestore. */
    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                GuardianManager.refreshFcmToken(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh in Firestore failed", e)
            }
        }
    }

    /**
     * Handles incoming FCM data messages.
     * When type == "GUARDIAN_ALERT" the message is forwarded to [GuardianAlertSource]
     * so that [AppScannerService] can show an on-screen overlay.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.d(TAG, "FCM received: type=${data["type"]}")

        if (data["type"] == "GUARDIAN_ALERT") {
            GuardianAlertSoundPlayer.playEmergencyAlert(applicationContext)
            val kind = data["alertKind"]?.takeIf { it.isNotBlank() } ?: GuardianAlertKind.RISKY_APP
            val alert = GuardianAlert(
                alertKind = kind,
                appName = data["appName"] ?: "Unknown App",
                packageName = data["packageName"] ?: "",
                riskLevel = data["riskLevel"] ?: "UNKNOWN",
                riskScore = data["riskScore"]?.toIntOrNull() ?: 0,
                primaryReason = data["primaryReason"]?.takeIf { it.isNotBlank() },
                protectedUserUid = data["protectedUserUid"] ?: ""
            )
            GuardianAlertSource.tryEmit(alert)
        }
    }
}
