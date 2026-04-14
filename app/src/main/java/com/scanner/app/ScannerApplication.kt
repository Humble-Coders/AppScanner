package com.scanner.app

import android.app.Application

class ScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocaleStore.applyStoredLocale(this)
    }
}
