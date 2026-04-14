package com.scanner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class CallRecordingService : Service() {

    companion object {
        private const val TAG = "WARecorder"
        const val ACTION_START_RECORDING = "com.scanner.app.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.scanner.app.STOP_RECORDING"
        const val CHANNEL_ID = "call_recording_channel"
        private const val NOTIFICATION_ID = 3000
        private const val RECORDINGS_DIR = "WhatsAppRecordings"

        /** Letters may be split by spaces/punctuation (STT), e.g. "digital arrest" vs "digital-arrest". */
        private fun flexibleAdjacentWords(vararg words: String): Regex {
            val combined = words.joinToString("") { it.lowercase() }
            val pattern = combined.map { Regex.escape(it.toString()) }.joinToString("\\W*")
            return Regex(pattern, RegexOption.IGNORE_CASE)
        }

        private val SCAM_PHRASE_PATTERNS = listOf(
            // Multi-word phrases (flexible: tolerates STT punctuation/spaces between words)
            flexibleAdjacentWords("digital", "arrest"),
            flexibleAdjacentWords("account", "block"),
            flexibleAdjacentWords("share", "otp"),
            flexibleAdjacentWords("share", "the", "otp"),
            flexibleAdjacentWords("income", "tax"),
            flexibleAdjacentWords("send", "money"),
            flexibleAdjacentWords("courier", "drug"),
            flexibleAdjacentWords("drug", "parcel"),
            // Standalone high-risk words — each is a strong individual scam signal
            Regex("\\bdigital\\b", RegexOption.IGNORE_CASE),
            Regex("\\barrest\\b", RegexOption.IGNORE_CASE),
            Regex("\\bCBI\\b"),
            Regex("\\bnarcotics\\b", RegexOption.IGNORE_CASE),
            Regex("\\bED\\b"),
            Regex("\\baadhar\\s*block\\b", RegexOption.IGNORE_CASE),
            Regex("\\bfreeze\\b", RegexOption.IGNORE_CASE),
        )

        /** Returns the first matching scam phrase text, or null if none found. */
        fun firstScamMatch(text: String): String? {
            for (pattern in SCAM_PHRASE_PATTERNS) {
                val m = pattern.find(text)
                if (m != null) return m.value
            }
            return null
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var recordingEngine: RecordingEngine? = null
    private var captionPipeline: CaptionPipeline? = null
    private var scamWarningOverlayView: View? = null
    /** Small "AppShield AI is protecting this call" badge shown while STT pipeline is active. */
    private var protectionBadgeView: View? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRecordingActive = false
    private var hasRaisedScamAlertForCall = false
    private var hasRaisedSafetyAlertForCall = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[RecService] onCreate() — service instance created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "[RecService] onStartCommand() action=${intent?.action} isRecordingActive=$isRecordingActive")
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            else -> Log.w(TAG, "[RecService] Unknown/null action: ${intent?.action}")
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (isRecordingActive) {
            Log.w(TAG, "[RecService] startRecording() — SKIPPED, already active")
            return
        }
        hasRaisedScamAlertForCall = false
        hasRaisedSafetyAlertForCall = false

        Log.i(TAG, "")
        Log.i(TAG, "┌─────────────────────────────────────────┐")
        Log.i(TAG, "│  >>> WHATSAPP CALL STARTED               │")
        Log.i(TAG, "│  Scam phrase detection: ACTIVE           │")
        Log.i(TAG, "└─────────────────────────────────────────┘")
        Log.i(TAG, "[RecService] startRecording() — beginning setup")

        // Start foreground immediately
        try {
            startForeground(NOTIFICATION_ID, createNotification("Recording WhatsApp call..."))
            Log.d(TAG, "[RecService] startForeground() succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "[RecService] startForeground() FAILED", e)
            cleanupAndStop()
            return
        }

        // Acquire wake lock
        acquireWakeLock()

        // Create and start recording engine
        val engine = RecordingEngine(this)
        recordingEngine = engine

        val apiKey = BuildConfig.GOOGLE_CLOUD_SPEECH_API_KEY
        if (apiKey.isNotBlank()) {
            Log.i(TAG, "[RecService] Caption pipeline: API key present (${apiKey.take(8)}…), enabling")
            val speechClient = SpeechToTextClient(apiKey)
            val pipeline = CaptionPipeline(
                scope = serviceScope,
                speechClient = speechClient,
                onTranscript = { transcript -> onTranscriptReceived(transcript) }
            )
            captionPipeline = pipeline
            engine.onPcmChunk = { pcm -> pipeline.feedPcm(pcm) }
            pipeline.start()
            Log.i(TAG, "[RecService] Caption pipeline started (scam phrase detection enabled)")
            showProtectionBadge()
        } else {
            Log.w(TAG, "[RecService] GOOGLE_CLOUD_SPEECH_API_KEY not set in gradle.properties — captions disabled")
        }

        val outputDir = getRecordingsDir()
        Log.d(TAG, "[RecService] Output directory: ${outputDir.absolutePath} exists=${outputDir.exists()}")

        if (engine.startRecording(outputDir, serviceScope)) {
            isRecordingActive = true
            Log.i(TAG, "[RecService] ✓ Recording STARTED successfully with strategy: ${engine.activeStrategy}")
            updateNotification("Recording WhatsApp call (${engine.activeStrategy})")
        } else {
            Log.e(TAG, "[RecService] ✗ Recording FAILED to start — all strategies failed")
            cleanupAndStop()
        }
    }

    private fun stopRecording() {
        Log.i(TAG, "")
        Log.i(TAG, "└─────────────────────────────────────────┘")
        Log.i(TAG, "│  <<< WHATSAPP CALL ENDED                 │")
        Log.i(TAG, "│  Scam phrase detection: STOPPING         │")
        Log.i(TAG, "┌─────────────────────────────────────────┘")
        Log.i(TAG, "[RecService] stopRecording() called, isRecordingActive=$isRecordingActive")

        if (!isRecordingActive) {
            Log.w(TAG, "[RecService] No active recording to stop, cleaning up")
            cleanupAndStop()
            return
        }
        isRecordingActive = false
        hasRaisedScamAlertForCall = false
        hasRaisedSafetyAlertForCall = false

        captionPipeline?.stop()
        captionPipeline = null
        recordingEngine?.onPcmChunk = null
        removeScamWarningOverlay()
        removeProtectionBadge()

        updateNotification("Processing recording...")

        val engine = recordingEngine ?: run {
            Log.e(TAG, "[RecService] recordingEngine is null!")
            cleanupAndStop()
            return
        }

        val strategy = engine.activeStrategy.name
        Log.d(TAG, "[RecService] Stopping engine (strategy=$strategy)")
        val pcmFile = engine.stopRecording()

        Log.d(TAG, "[RecService] PCM result: file=${pcmFile?.name} exists=${pcmFile?.exists()} size=${pcmFile?.length() ?: 0}")

        if (pcmFile != null && pcmFile.exists() && pcmFile.length() > 0) {
            Log.i(TAG, "[RecService] PCM file ready: ${pcmFile.name} (${pcmFile.length()} bytes) — starting encoding")
            val engineThatFinished = engine
            // Encode PCM to M4A
            serviceScope.launch(Dispatchers.IO) {
                val outputDir = getRecordingsDir()
                val m4aFile = File(outputDir, pcmFile.nameWithoutExtension + ".m4a")
                Log.d(TAG, "[RecService] Encoding to: ${m4aFile.absolutePath}")

                val success = AudioEncoder.encodeToM4A(
                    pcmFile = pcmFile,
                    outputFile = m4aFile,
                    sampleRate = RecordingEngine.SAMPLE_RATE,
                    channelCount = 1,
                    bitRate = 192000
                )

                Log.d(TAG, "[RecService] Encoding result: success=$success m4aExists=${m4aFile.exists()} m4aSize=${m4aFile.length()}")

                if (success && m4aFile.exists()) {
                    val durationMs = getDurationMs(m4aFile)
                    val recording = CallRecording(
                        filePath = m4aFile.absolutePath,
                        fileName = m4aFile.nameWithoutExtension,
                        durationMs = durationMs,
                        fileSize = m4aFile.length(),
                        strategy = strategy
                    )
                    val emitted = CallRecordingEventSource.tryEmit(recording)
                    Log.i(TAG, "[RecService] ✓ Recording SAVED: ${m4aFile.name} duration=${durationMs}ms size=${m4aFile.length()} emitted=$emitted")
                } else {
                    Log.e(TAG, "[RecService] ✗ Encoding FAILED — deleting PCM")
                    pcmFile.delete()
                }

                cleanupAfterEncoding(engineThatFinished)
            }
        } else {
            Log.w(TAG, "[RecService] ✗ No PCM data recorded (file=${pcmFile?.name} exists=${pcmFile?.exists()} size=${pcmFile?.length()})")
            pcmFile?.delete()
            cleanupAndStop()
        }
    }

    private fun getDurationMs(file: File): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            Log.d(TAG, "[RecService] getDurationMs: ${file.name} → ${duration}ms")
            duration
        } catch (e: Exception) {
            Log.e(TAG, "[RecService] getDurationMs FAILED for ${file.name}", e)
            0L
        }
    }

    private fun getRecordingsDir(): File {
        val dir = File(getExternalFilesDir(null), RECORDINGS_DIR)
        val created = dir.mkdirs()
        Log.d(TAG, "[RecService] getRecordingsDir: ${dir.absolutePath} exists=${dir.exists()} created=$created")
        return dir
    }

    private fun cleanupAndStop() {
        Log.d(TAG, "[RecService] cleanupAndStop()")
        captionPipeline?.stop()
        captionPipeline = null
        recordingEngine?.onPcmChunk = null
        recordingEngine?.release()
        recordingEngine = null
        removeScamWarningOverlay()
        removeProtectionBadge()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Called when encoding finishes. Only release the engine that finished and stop the service
     * if no new recording has started (avoids killing a second call's recording when user calls
     * again soon).
     */
    private fun cleanupAfterEncoding(engineThatFinished: RecordingEngine) {
        Log.d(TAG, "[RecService] cleanupAfterEncoding()")
        engineThatFinished.release()
        if (recordingEngine == engineThatFinished) {
            recordingEngine = null
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            Log.d(TAG, "[RecService] New recording active, keeping service running")
        }
    }

    private fun onTranscriptReceived(transcript: String) {
        Log.i(TAG, "[STT] Transcript received: \"$transcript\"")

        val matched = firstScamMatch(transcript)
        if (matched != null && !hasRaisedScamAlertForCall) {
            hasRaisedScamAlertForCall = true
            Log.w(TAG, "")
            Log.w(TAG, "⚠️⚠️⚠️ [SCAM WORD DETECTED] ⚠️⚠️⚠️")
            Log.w(TAG, "  Matched phrase : \"$matched\"")
            Log.w(TAG, "  Full transcript: \"$transcript\"")
            Log.w(TAG, "  Action         : alerting guardian + showing overlay")
            Log.w(TAG, "")
            ScamAlertState.markScamDetected()
            showScamWarningOverlay()
            notifyGuardiansScamAlert()
        } else if (matched == null) {
            Log.d(TAG, "[STT] No scam phrases found in transcript (scamAlreadyRaised=$hasRaisedScamAlertForCall)")
        }

        if (SafeWordStore.matchesAnySafetyWord(this, transcript) && !hasRaisedSafetyAlertForCall) {
            hasRaisedSafetyAlertForCall = true
            Log.i(TAG, "🛡 [SAFE WORD DETECTED] in transcript: \"$transcript\"")
            showGuardianContactedOverlay()
            notifyGuardiansSafetyAlert()
        }
    }

    private fun containsScamPhrase(text: String): Boolean = firstScamMatch(text) != null

    private fun notifyGuardiansScamAlert() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val uid = GuardianManager.getOrCreateUserId()
                GuardianManager.clearFinanceUnlockAfterScam(uid)
                GuardianManager.notifyGuardians(
                    protectedUserId = uid,
                    appName = "WhatsApp Call",
                    packageName = "com.whatsapp",
                    riskLevel = "CRITICAL",
                    riskScore = 95,
                    primaryReason = "Possible scam detected: wording such as digital arrest, account block, share OTP, or related threat language heard during call. Finance apps are locked until you approve access.",
                    alertKind = GuardianAlertKind.CALL_SCAM,
                    protectedUserPhone = GuardianPhoneStore.getMyNormalizedPhone(this@CallRecordingService),
                    financeLockActive = true,
                )
                Log.i(TAG, "[RecService] Guardian alert sent for call scam phrase (finance lock)")
            } catch (e: Exception) {
                Log.e(TAG, "[RecService] Failed to send guardian scam alert", e)
            }
        }
    }

    private fun notifyGuardiansSafetyAlert() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val uid = GuardianManager.getOrCreateUserId()
                GuardianManager.notifyGuardians(
                    protectedUserId = uid,
                    appName = "WhatsApp Call",
                    packageName = "com.whatsapp",
                    riskLevel = "HIGH",
                    riskScore = 90,
                    primaryReason = "Safety word detected: user may not be feeling safe on this call.",
                    alertKind = GuardianAlertKind.CALL_SAFETY,
                    protectedUserPhone = GuardianPhoneStore.getMyNormalizedPhone(this@CallRecordingService)
                )
                Log.i(TAG, "[RecService] Guardian safety alert sent for safe word")
            } catch (e: Exception) {
                Log.e(TAG, "[RecService] Failed to send guardian safety alert", e)
            }
        }
    }

    private fun showScamWarningOverlay() {
        handler.post {
            if (scamWarningOverlayView != null) {
                Log.d(TAG, "[RecService] Scam warning overlay already shown")
                return@post
            }
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this)
            } else {
                true
            }
            if (!canOverlay) {
                Log.e(TAG, "[RecService] CANNOT show scam warning overlay — overlay permission not granted.")
                return@post
            }
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val root = LayoutInflater.from(this).inflate(R.layout.caption_overlay, null)
                scamWarningOverlayView = root
                root.findViewById<TextView>(R.id.caption_label).text = getString(R.string.scam_warning_label)
                root.findViewById<TextView>(R.id.caption_text).text = getString(R.string.scam_warning_body)
                val params = WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    format = android.graphics.PixelFormat.TRANSLUCENT
                }
                wm.addView(root, params)
                Log.w(TAG, "[RecService] Scam warning overlay shown to user")
            } catch (e: Exception) {
                Log.e(TAG, "[RecService] Failed to show scam warning overlay", e)
            }
        }
    }

    private fun showGuardianContactedOverlay() {
        handler.post {
            if (scamWarningOverlayView != null) {
                Log.d(TAG, "[RecService] Safety overlay already shown")
                return@post
            }
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this)
            } else {
                true
            }
            if (!canOverlay) {
                Log.e(TAG, "[RecService] CANNOT show safety overlay — overlay permission not granted.")
                return@post
            }
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val root = LayoutInflater.from(this).inflate(R.layout.caption_overlay, null)
                scamWarningOverlayView = root
                root.findViewById<TextView>(R.id.caption_label).text = getString(R.string.safety_alert_label)
                root.findViewById<TextView>(R.id.caption_text).text = getString(R.string.safety_alert_body)
                val params = WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    format = android.graphics.PixelFormat.TRANSLUCENT
                }
                wm.addView(root, params)
                Log.w(TAG, "[RecService] Safety overlay shown to user")
            } catch (e: Exception) {
                Log.e(TAG, "[RecService] Failed to show safety overlay", e)
            }
        }
    }

    private fun showProtectionBadge() {
        handler.post {
            if (protectionBadgeView != null) return@post
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this)
            } else true
            if (!canOverlay) return@post
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val root = LayoutInflater.from(this).inflate(R.layout.protection_badge_overlay, null)
                protectionBadgeView = root
                val params = WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.WRAP_CONTENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    format = android.graphics.PixelFormat.TRANSLUCENT
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    x = 16
                    y = 48
                }
                wm.addView(root, params)
                Log.d(TAG, "[RecService] Protection badge shown")
            } catch (e: Exception) {
                Log.e(TAG, "[RecService] Failed to show protection badge", e)
            }
        }
    }

    private fun removeProtectionBadge() {
        handler.post {
            protectionBadgeView?.let { view ->
                try {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(view)
                } catch (_: Exception) {}
                protectionBadgeView = null
                Log.d(TAG, "[RecService] Protection badge removed")
            }
        }
    }

    private fun removeScamWarningOverlay() {
        handler.post {
            scamWarningOverlayView?.let { view ->
                try {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(view)
                } catch (_: Exception) {}
                scamWarningOverlayView = null
                Log.d(TAG, "[RecService] Scam warning overlay removed")
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "com.scanner.app:CallRecording"
            ).apply {
                acquire(60 * 60 * 1000L) // 1 hour max
            }
            Log.d(TAG, "[RecService] Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "[RecService] Wake lock acquire FAILED", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "[RecService] Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.call_recording_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_call_recording_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
            Log.d(TAG, "[RecService] Notification channel created")
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.whatsapp_call_recorder_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "[RecService] onDestroy()")
        captionPipeline?.stop()
        captionPipeline = null
        removeProtectionBadge()
        removeScamWarningOverlay()
        recordingEngine?.release()
        recordingEngine = null
        releaseWakeLock()
        serviceScope.cancel()
    }
}
