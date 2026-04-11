package com.scanner.app

/**
 * Very simple in-memory state used to show a payment warning after a scam phrase is detected.
 * No edge-case handling by design (process death clears it).
 */
object ScamAlertState {

    @Volatile
    private var lastScamDetectedAtMs: Long = 0L

    @Volatile
    private var paymentWarningShown: Boolean = false

    fun markScamDetected(nowMs: Long = System.currentTimeMillis()) {
        lastScamDetectedAtMs = nowMs
        paymentWarningShown = false
    }

    fun shouldWarnOnPayments(nowMs: Long = System.currentTimeMillis(), windowMs: Long): Boolean {
        val t = lastScamDetectedAtMs
        return t > 0L && !paymentWarningShown && (nowMs - t) in 0..windowMs
    }

    fun markPaymentWarningShown() {
        paymentWarningShown = true
    }
}

