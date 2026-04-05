package com.scanner.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppScannerService : AccessibilityService() {

    companion object {
        private const val TAG = "AppScanner"
        const val CHANNEL_ID = "app_scanner_channel"
        private const val POLL_INTERVAL_MS = 3000L
        private var notifId = 2000
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        /** How long to wait before confirming a call has truly ended (ms).
         * Increased to 18s to avoid splitting recordings when user briefly switches apps. */
        private const val CALL_END_DEBOUNCE_MS = 18000L
        /** Cube ACR: delay before starting recording to let audio path stabilize. */
        private const val RECORDING_START_DELAY_MS = 5000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var knownPackages = mutableSetOf<String>()
    private var polling = false
    private var currentOverlayView: View? = null
    private val callDetector = WhatsAppCallDetector()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service.onCreate")
        // Start collecting guardian alerts that arrive via FCM
        serviceScope.launch {
            GuardianAlertSource.alerts.collect { alert ->
                showGuardianAlertOverlay(alert)
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                checkForNewPackages()
            } catch (e: Exception) {
                Log.e(TAG, "Poll error", e)
            }
            if (polling) {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private var pollCount = 0

    private fun checkForNewPackages() {
        pollCount++
        val current = getCurrentPackageNames()
        val newPackages = current - knownPackages
        val removedPackages = knownPackages - current

        if (pollCount % 10 == 1 || newPackages.isNotEmpty() || removedPackages.isNotEmpty()) {
            Log.d(TAG, "Poll #$pollCount: current=${current.size} known=${knownPackages.size} new=${newPackages.size} removed=${removedPackages.size}")
        }

        if (newPackages.isNotEmpty()) {
            Log.d(TAG, "Poll: new packages: $newPackages")
            for (pkg in newPackages) {
                if (pkg == packageName) continue
                val appName = getAppLabel(pkg)
                Log.d(TAG, "Poll: NEW INSTALL -> appName=$appName pkg=$pkg")
                handleAppInstalled(appName, pkg)
            }
        }

        knownPackages = current.toMutableSet()
    }

    private fun getCurrentPackageNames(): Set<String> {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PackageManager.ApplicationInfoFlags.of(0)
            } else {
                null
            }
            val apps: List<ApplicationInfo> = if (flags != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(flags)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
            apps.map { it.packageName }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentPackageNames failed", e)
            knownPackages
        }
    }

    private fun getAppLabel(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun isFromPlayStore(packageName: String): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
            installer == PLAY_STORE_PACKAGE
        } catch (e: Exception) {
            Log.e(TAG, "isFromPlayStore failed for $packageName", e)
            false
        }
    }

    // ── Main entry point for each new install ──────────────────────────

    private fun handleAppInstalled(appName: String, packageName: String) {
        Log.d(TAG, "handleAppInstalled: appName=$appName pkg=$packageName")
        showScanningNotification(appName)

        serviceScope.launch {
            val response = withContext(Dispatchers.IO) {
                val cert = AppShieldApi.extractCertificateSha256(this@AppScannerService, packageName)
                val installer = AppShieldApi.extractInstallerPackage(this@AppScannerService, packageName)
                val perms = AppShieldApi.extractPermissions(this@AppScannerService, packageName)
                Log.d(TAG, "Extracted: cert=${cert?.take(16)}…, installer=$installer, perms=${perms.size}")
                AppShieldApi.verifyApp(packageName, cert, installer, perms)
            }

            if (response != null) {
                Log.d(TAG, "AppShield result: ${response.riskLevel} (${response.riskScore})")
                InstallEventSource.tryEmit(
                    appName, packageName,
                    response.riskLevel, response.riskScore, response.primaryReason
                )

                when (response.riskLevel) {
                    "SAFE" -> showResultNotification(appName, packageName, response)
                    else -> {
                        showResultNotification(appName, packageName, response)
                        showWarningOverlay(appName, packageName, response)
                        notifyGuardiansAsync(appName, packageName, response)
                    }
                }
            } else {
                Log.w(TAG, "AppShield API unavailable, falling back to Play Store check")
                InstallEventSource.tryEmit(appName, packageName)
                if (!isFromPlayStore(packageName)) {
                    val fallback = AppShieldResponse(
                        status = "fallback",
                        riskScore = 50,
                        riskLevel = "MEDIUM",
                        primaryReason = "Could not verify — app was not installed from the Play Store",
                        secondaryFlags = listOf("Installed outside Play Store", "AppShield verification unavailable"),
                        matchedEntity = null
                    )
                    showWarningOverlay(appName, packageName, fallback)
                    notifyGuardiansAsync(appName, packageName, fallback)
                }
                showResultNotification(appName, packageName, null)
            }
        }
    }

    // ── Warning overlay (risk-level aware) ─────────────────────────────

    private fun showWarningOverlay(appName: String, packageName: String, response: AppShieldResponse) {
        handler.post {
            try {
                removeCurrentOverlay()
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val root = LayoutInflater.from(this).inflate(R.layout.harmful_app_warning, null)
                val riskColor = riskColor(response.riskLevel)

                // Badge
                val badge = root.findViewById<TextView>(R.id.risk_level_badge)
                badge.text = response.riskLevel
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12f)
                    setColor(riskColor)
                }

                // Title
                val title = root.findViewById<TextView>(R.id.title)
                title.text = when (response.riskLevel) {
                    "LOW" -> getString(R.string.risk_title_low)
                    "MEDIUM" -> getString(R.string.risk_title_medium)
                    "HIGH" -> getString(R.string.risk_title_high)
                    "CRITICAL" -> getString(R.string.risk_title_critical)
                    else -> getString(R.string.harmful_app_warning_title)
                }
                title.setTextColor(riskColor)

                // Message
                root.findViewById<TextView>(R.id.message).text =
                    response.primaryReason ?: getString(R.string.harmful_app_warning_message)

                // App info
                root.findViewById<TextView>(R.id.app_name).text = appName
                root.findViewById<TextView>(R.id.package_name).text = packageName

                // Risk score
                root.findViewById<TextView>(R.id.risk_score).text =
                    "Risk Score: ${response.riskScore}/100"

                // Secondary flags
                val flagsView = root.findViewById<TextView>(R.id.secondary_flags)
                if (response.secondaryFlags.isNotEmpty()) {
                    flagsView.visibility = View.VISIBLE
                    flagsView.text = response.secondaryFlags.joinToString("\n") { "• $it" }
                }

                // Uninstall button — visible for MEDIUM and above
                val uninstallBtn = root.findViewById<Button>(R.id.uninstall_button)
                if (response.riskLevel in listOf("MEDIUM", "HIGH", "CRITICAL")) {
                    uninstallBtn.visibility = View.VISIBLE
                    uninstallBtn.text = if (response.riskLevel == "CRITICAL")
                        getString(R.string.uninstall_now) else getString(R.string.uninstall_app)
                    uninstallBtn.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_DELETE).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        removeCurrentOverlay()
                    }
                }

                // Dismiss button
                val okBtn = root.findViewById<Button>(R.id.ok_button)
                okBtn.text = when (response.riskLevel) {
                    "LOW" -> getString(R.string.harmful_app_warning_ok)
                    "MEDIUM" -> getString(R.string.risk_dismiss_understand)
                    "HIGH", "CRITICAL" -> getString(R.string.risk_dismiss_keep)
                    else -> getString(R.string.harmful_app_warning_ok)
                }
                if (response.riskLevel in listOf("HIGH", "CRITICAL")) {
                    okBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2A3040"))
                    okBtn.setTextColor(Color.parseColor("#7A8394"))
                }
                okBtn.setOnClickListener { removeCurrentOverlay() }

                val params = WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                    flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    format = android.graphics.PixelFormat.TRANSLUCENT
                }
                wm.addView(root, params)
                currentOverlayView = root
                Log.d(TAG, "showWarningOverlay: shown for $appName (${response.riskLevel})")
            } catch (e: Exception) {
                Log.e(TAG, "showWarningOverlay failed", e)
            }
        }
    }

    // ── Guardian: notify guardians of this device's user ──────────────

    private fun notifyGuardiansAsync(appName: String, packageName: String, response: AppShieldResponse) {
        val uid = GuardianManager.getCurrentUserId() ?: return
        serviceScope.launch(Dispatchers.IO) {
            GuardianManager.notifyGuardians(
                protectedUserId = uid,
                appName = appName,
                packageName = packageName,
                riskLevel = response.riskLevel,
                riskScore = response.riskScore,
                primaryReason = response.primaryReason
            )
        }
    }

    // ── Guardian: show on-screen overlay when acting as guardian ──────

    private fun showGuardianAlertOverlay(alert: GuardianAlert) {
        handler.post {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val root = LayoutInflater.from(this).inflate(R.layout.guardian_alert_overlay, null)

                val badge = root.findViewById<TextView>(R.id.guardian_badge)
                val riskBadge = root.findViewById<TextView>(R.id.risk_level_badge)
                val title = root.findViewById<TextView>(R.id.title)
                val subtitle = root.findViewById<TextView>(R.id.subtitle)
                val appNameLabel = root.findViewById<TextView>(R.id.app_name_label)
                val appName = root.findViewById<TextView>(R.id.app_name)
                val packageName = root.findViewById<TextView>(R.id.package_name)
                val riskScore = root.findViewById<TextView>(R.id.risk_score)
                val reasonLabel = root.findViewById<TextView>(R.id.primary_reason_label)
                val reasonView = root.findViewById<TextView>(R.id.primary_reason)
                val icon = root.findViewById<ImageView>(R.id.guardian_icon)
                val ackBtn = root.findViewById<Button>(R.id.acknowledge_button)

                when (alert.alertKind) {
                    GuardianAlertKind.CALL_SCAM -> {
                        val accent = Color.parseColor("#FF1744")
                        val badgeBg = Color.parseColor("#FF1744")
                        icon.setImageResource(android.R.drawable.ic_dialog_alert)
                        badge.text = getString(R.string.guardian_badge_call_scam)
                        badge.background = pillDrawable(badgeBg)
                        riskBadge.text = getString(R.string.guardian_tag_call_scam)
                        riskBadge.background = pillDrawable(Color.parseColor("#BF360C"))
                        title.text = getString(R.string.guardian_title_call_scam)
                        title.setTextColor(accent)
                        subtitle.text = getString(R.string.guardian_subtitle_call_scam)
                        appNameLabel.text = getString(R.string.guardian_label_what_happened)
                        appName.visibility = View.GONE
                        packageName.visibility = View.GONE
                        riskScore.visibility = View.GONE
                        reasonLabel.visibility = View.VISIBLE
                        reasonView.visibility = View.VISIBLE
                        reasonView.text = alert.primaryReason?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.guardian_detail_fallback)
                        ackBtn.backgroundTintList = ColorStateList.valueOf(accent)
                        ackBtn.setTextColor(Color.WHITE)
                    }
                    GuardianAlertKind.CALL_SAFETY -> {
                        val accent = Color.parseColor("#00C853")
                        val badgeBg = Color.parseColor("#00C853")
                        icon.setImageResource(android.R.drawable.ic_dialog_info)
                        badge.text = getString(R.string.guardian_badge_call_safety)
                        badge.background = pillDrawable(badgeBg)
                        riskBadge.text = getString(R.string.guardian_tag_call_safety)
                        riskBadge.background = pillDrawable(Color.parseColor("#2E7D32"))
                        title.text = getString(R.string.guardian_title_call_safety)
                        title.setTextColor(accent)
                        subtitle.text = getString(R.string.guardian_subtitle_call_safety)
                        appNameLabel.text = getString(R.string.guardian_label_what_happened)
                        appName.visibility = View.GONE
                        packageName.visibility = View.GONE
                        riskScore.visibility = View.GONE
                        reasonLabel.visibility = View.VISIBLE
                        reasonView.visibility = View.VISIBLE
                        reasonView.text = alert.primaryReason?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.guardian_detail_fallback)
                        ackBtn.backgroundTintList = ColorStateList.valueOf(accent)
                        ackBtn.setTextColor(Color.WHITE)
                    }
                    else -> {
                        val riskColor = riskColor(alert.riskLevel)
                        icon.setImageResource(android.R.drawable.ic_dialog_alert)
                        badge.text = getString(R.string.guardian_badge_risky_app)
                        badge.background = pillDrawable(Color.parseColor("#448AFF"))
                        riskBadge.text = alert.riskLevel
                        riskBadge.background = pillDrawable(riskColor)
                        title.text = when (alert.riskLevel) {
                            "LOW" -> getString(R.string.risk_title_low)
                            "MEDIUM" -> getString(R.string.risk_title_medium)
                            "HIGH" -> getString(R.string.risk_title_high)
                            "CRITICAL" -> getString(R.string.risk_title_critical)
                            else -> getString(R.string.guardian_alert_title)
                        }
                        title.setTextColor(riskColor)
                        subtitle.text = getString(R.string.guardian_alert_message)
                        appNameLabel.text = getString(R.string.guardian_label_app_installed)
                        appName.visibility = View.VISIBLE
                        packageName.visibility = View.VISIBLE
                        riskScore.visibility = View.VISIBLE
                        appName.text = alert.appName
                        packageName.text = alert.packageName
                        riskScore.text = getString(R.string.guardian_risk_score_fmt, alert.riskScore)
                        reasonLabel.visibility = View.GONE
                        if (!alert.primaryReason.isNullOrBlank()) {
                            reasonView.visibility = View.VISIBLE
                            reasonView.text = alert.primaryReason
                        } else {
                            reasonView.visibility = View.GONE
                        }
                        ackBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#448AFF"))
                        ackBtn.setTextColor(Color.parseColor("#FF0D0F14"))
                    }
                }

                ackBtn.setOnClickListener {
                    try {
                        wm.removeView(root)
                    } catch (e: Exception) {
                        Log.e(TAG, "removeView guardian overlay failed", e)
                    }
                }

                val params = WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                    flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    format = android.graphics.PixelFormat.TRANSLUCENT
                }
                wm.addView(root, params)
                Log.d(TAG, "showGuardianAlertOverlay: kind=${alert.alertKind} app=${alert.appName} risk=${alert.riskLevel}")
            } catch (e: Exception) {
                Log.e(TAG, "showGuardianAlertOverlay failed", e)
            }
        }
    }

    private fun pillDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12f)
            setColor(color)
        }

    private fun removeCurrentOverlay() {
        currentOverlayView?.let { view ->
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "removeCurrentOverlay failed", e)
            }
            currentOverlayView = null
        }
    }

    // ── Notifications ──────────────────────────────────────────────────

    private fun showScanningNotification(appName: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            notifId++,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(getString(R.string.scanning_app, appName))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun showResultNotification(appName: String, packageName: String, response: AppShieldResponse?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val level = response?.riskLevel ?: "UNKNOWN"
        val title = when (level) {
            "SAFE" -> getString(R.string.app_verified_safe, appName)
            else -> "$appName — $level risk"
        }
        val body = response?.primaryReason
            ?: "Package: $packageName"

        nm.notify(
            notifId++,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(
                    if (level == "SAFE") android.R.drawable.ic_dialog_info
                    else android.R.drawable.ic_dialog_alert
                )
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$body"))
                .setPriority(
                    if (level in listOf("HIGH", "CRITICAL")) NotificationCompat.PRIORITY_MAX
                    else NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(true)
                .build()
        )
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service.onServiceConnected")
        createNotificationChannel()
        startPolling()
    }

    private fun startPolling() {
        if (polling) {
            Log.d(TAG, "startPolling: already polling, skip")
            return
        }
        knownPackages = getCurrentPackageNames().toMutableSet()
        Log.d(TAG, "startPolling: baseline snapshot has ${knownPackages.size} packages")
        polling = true
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        Log.d(TAG, "startPolling: polling every ${POLL_INTERVAL_MS}ms")
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        Log.d(TAG, "stopPolling: stopped")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Scanner Alerts",
            NotificationManager.IMPORTANCE_HIGH
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private var accessibilityEventCount = 0L
    private var callEndDebounceRunnable: Runnable? = null
    private var pendingStartRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        accessibilityEventCount++

        // Log once every 500 events to confirm events are flowing
        if (accessibilityEventCount == 1L) {
            Log.i("WARecorder", "[Service] First accessibility event received! Event delivery is working. pkg=${event.packageName} type=${event.eventType}")
        } else if (accessibilityEventCount % 500 == 0L) {
            Log.d("WARecorder", "[Service] Accessibility event #$accessibilityEventCount (events are flowing)")
        }

        val stateChange = callDetector.processAccessibilityEvent(event) ?: return

        when (stateChange) {
            is CallStateChange.CallStarted -> {
                cancelCallEndDebounce()
                cancelPendingStart()
                Log.i("WARecorder", "[Service] >>> CALL STARTED — scheduling recording in ${RECORDING_START_DELAY_MS}ms (Cube ACR style)")
                pendingStartRunnable = Runnable {
                    pendingStartRunnable = null
                    if (!callDetector.isInCall()) return@Runnable
                    Log.i("WARecorder", "[Service] Starting recording now (delay elapsed)")
                    try {
                        val intent = Intent(this@AppScannerService, CallRecordingService::class.java).apply {
                            action = CallRecordingService.ACTION_START_RECORDING
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        Log.i("WARecorder", "[Service] CallRecordingService start intent sent")
                    } catch (e: Exception) {
                        Log.e("WARecorder", "[Service] FAILED to start CallRecordingService", e)
                    }
                }
                handler.postDelayed(pendingStartRunnable!!, RECORDING_START_DELAY_MS)
            }
            is CallStateChange.CallMaybeEnded -> {
                Log.i("WARecorder", "[Service] ??? MAYBE ENDED — starting ${CALL_END_DEBOUNCE_MS}ms debounce (recording will stop only if still MAYBE_ENDED after ${CALL_END_DEBOUNCE_MS / 1000}s)")
                cancelCallEndDebounce()
                cancelPendingStart()  // Only matters if we hadn't started yet
                callEndDebounceRunnable = Runnable {
                    if (callDetector.confirmCallEnded()) {
                        Log.i("WARecorder", "[Service] <<< CALL CONFIRMED ENDED after debounce — stopping recording")
                        try {
                            val intent = Intent(this, CallRecordingService::class.java).apply {
                                action = CallRecordingService.ACTION_STOP_RECORDING
                            }
                            startService(intent)
                            Log.i("WARecorder", "[Service] CallRecordingService stop intent sent successfully")
                        } catch (e: Exception) {
                            Log.e("WARecorder", "[Service] FAILED to stop CallRecordingService", e)
                        }
                    } else {
                        Log.d("WARecorder", "[Service] Debounce fired but call was already resumed/ended")
                    }
                    callEndDebounceRunnable = null
                }
                handler.postDelayed(callEndDebounceRunnable!!, CALL_END_DEBOUNCE_MS)
            }
            is CallStateChange.CallResumed -> {
                Log.i("WARecorder", "[Service] >>> CALL RESUMED — cancelling debounce (no stop will be sent); scheduling recording start if needed")
                cancelCallEndDebounce()  // Prevents stop — we stay on one continuous recording
                cancelPendingStart()
                pendingStartRunnable = Runnable {
                    pendingStartRunnable = null
                    if (!callDetector.isInCall()) return@Runnable
                    Log.i("WARecorder", "[Service] Starting recording now (resumed)")
                    try {
                        val intent = Intent(this@AppScannerService, CallRecordingService::class.java).apply {
                            action = CallRecordingService.ACTION_START_RECORDING
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    } catch (e: Exception) {
                        Log.e("WARecorder", "[Service] FAILED to start CallRecordingService", e)
                    }
                }
                handler.postDelayed(pendingStartRunnable!!, RECORDING_START_DELAY_MS)
            }
            is CallStateChange.CallEnded -> {
                cancelPendingStart()
                cancelCallEndDebounce()
                Log.i("WARecorder", "[Service] <<< CALL ENDED (immediate) — stopping recording")
                try {
                    val intent = Intent(this, CallRecordingService::class.java).apply {
                        action = CallRecordingService.ACTION_STOP_RECORDING
                    }
                    startService(intent)
                } catch (e: Exception) {
                    Log.e("WARecorder", "[Service] FAILED to stop CallRecordingService", e)
                }
            }
        }
    }

    private fun cancelCallEndDebounce() {
        callEndDebounceRunnable?.let {
            handler.removeCallbacks(it)
            Log.d("WARecorder", "[Service] Debounce timer cancelled")
        }
        callEndDebounceRunnable = null
    }

    private fun cancelPendingStart() {
        pendingStartRunnable?.let {
            handler.removeCallbacks(it)
            Log.d("WARecorder", "[Service] Pending recording start cancelled")
        }
        pendingStartRunnable = null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service.onDestroy")
        stopPolling()
        removeCurrentOverlay()
        cancelPendingStart()
        cancelCallEndDebounce()
        callDetector.reset()
        serviceScope.cancel()
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun riskColor(level: String): Int = when (level) {
        "LOW" -> Color.parseColor("#448AFF")
        "MEDIUM" -> Color.parseColor("#FFB300")
        "HIGH" -> Color.parseColor("#FF6D00")
        "CRITICAL" -> Color.parseColor("#FF1744")
        else -> Color.parseColor("#7A8394")
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
