package com.scanner.app

/**
 * Popular UPI / banking / wallet / trading apps (India-focused + common globals).
 * Matched by package name when the foreground app opens after a scam alert.
 */
object FinancePackageRegistry {

    private val PACKAGES = setOf(
        // UPI / wallets
        "com.google.android.apps.nbu.paisa.user", // Google Pay India
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp", // BHIM
        "com.dreamplug.androidapp", // CRED
        "com.mobikwik_new",
        "com.freecharge.android",
        "com.csam.icici.bank.imobile", // iMobile Pay
        "com.icicibank.pockets", // Pockets
        "com.axis.mobile",
        "com.sbi.lotusintouch", // YONO SBI
        "com.sbi.SBIFreedomPlus",
        "com.snapwork.hdfc", // HDFC
        "com.kotak.mahindra.bank",
        "com.idfcfirstbank.optimus",
        "com.YesBank", // Yes Bank
        "com.bankofbaroda.upi", // bob World
        "com.fedmobile", // Federal
        "com.nextbillion.groww",
        "com.zerodha.kite3",
        "com.upstox.pro",
        "com.paypal.android.p2pmobile",
    )

    fun isFinancePackage(packageName: String): Boolean =
        packageName in PACKAGES
}
