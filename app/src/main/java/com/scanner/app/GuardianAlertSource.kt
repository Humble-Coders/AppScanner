package com.scanner.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Sent in FCM / HTTP body so the guardian overlay can match the alert type. */
object GuardianAlertKind {
    const val RISKY_APP = "RISKY_APP"
    const val CALL_SCAM = "CALL_SCAM"
    const val CALL_SAFETY = "CALL_SAFETY"
}

data class GuardianAlert(
    val alertKind: String,
    val appName: String,
    val packageName: String,
    val riskLevel: String,
    val riskScore: Int,
    val primaryReason: String?,
    val protectedUserUid: String
)

object GuardianAlertSource {

    private val _alerts = MutableSharedFlow<GuardianAlert>(extraBufferCapacity = 5)
    val alerts: SharedFlow<GuardianAlert> = _alerts.asSharedFlow()

    fun tryEmit(alert: GuardianAlert) {
        _alerts.tryEmit(alert)
    }
}
