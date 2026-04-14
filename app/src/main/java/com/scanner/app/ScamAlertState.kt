package com.scanner.app

/**
 * In-memory state for post–scam-call finance app lock. [financeUnlockUntilMs] is also
 * updated from Firestore when a guardian approves. Process death clears local scam time;
 * Firestore can still carry unlock until the user reopens the app (listener syncs).
 */
object ScamAlertState {

    /** How long after a scam we enforce finance lock unless guardian approves. */
    const val SCAM_FINANCE_PROTECTION_MS: Long = 24L * 60L * 60L * 1000L

    @Volatile
    private var lastScamDetectedAtMs: Long = 0L

    @Volatile
    private var financeUnlockUntilMs: Long = 0L

    fun markScamDetected(nowMs: Long = System.currentTimeMillis()) {
        lastScamDetectedAtMs = nowMs
        financeUnlockUntilMs = 0L
    }

    fun setFinanceUnlockUntilMs(ms: Long) {
        financeUnlockUntilMs = ms
    }

    /**
     * Finance apps may open only when this returns false: i.e. no recent scam, protection
     * window expired, or guardian approved (unlock window still valid).
     */
    fun shouldBlockFinanceApps(nowMs: Long = System.currentTimeMillis()): Boolean {
        val scamAt = lastScamDetectedAtMs
        if (scamAt <= 0L) return false
        if (nowMs - scamAt > SCAM_FINANCE_PROTECTION_MS) return false
        if (nowMs < financeUnlockUntilMs) return false
        return true
    }
}
