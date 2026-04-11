package com.scanner.app

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Best-effort: reads text from WhatsApp VoIP UI via accessibility (event source + full window tree).
 * Logs structured steps under [WhatsAppCallJourney]; number hits also under [WhatsAppCallUiNum].
 *
 * Logcat: `adb logcat -s WhatsAppCallJourney WhatsAppCallUiNum WARecorder`
 */
object WhatsAppCallUiNumberProbe {

    const val TAG = "WhatsAppCallUiNum"

    private val WHATSAPP_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    private val PLUS_91_MOBILE = Regex(
        """\+91[\s\-.()]*([6-9]\d{9})""",
        RegexOption.IGNORE_CASE
    )

    private val PLUS_91_ANY_10 = Regex(
        """\+91[\s\-.()]*(\d{10})""",
        RegexOption.IGNORE_CASE
    )

    /** 91 without + then 10 digits */
    private val PREFIX_91 = Regex(
        """(?<![\d])91[\s\-.()]*([6-9]\d{9})(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    /** 10-digit Indian mobile with optional spaces/dots between digits (no +91 on screen). */
    private val LOOSE_10_MOBILE = Regex(
        """(?<![\d])([6-9](?:[\s\-.]*\d){9})(?!\d)"""
    )

    private val DIGIT_RUN = Regex("""\d{8,15}""")

    private var lastFullScanMs = 0L
    private const val MIN_SCAN_INTERVAL_MS = 800L
    private val recentlyLogged = LinkedHashMap<String, Long>()
    private const val DEDUPE_WINDOW_MS = 8000L
    private var lastThrottleLogMs = 0L

    fun tryLogFromActiveWindow(
        service: AccessibilityService,
        event: AccessibilityEvent,
        detectorInCall: Boolean,
        detectorState: WhatsAppCallDetector.State
    ) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WHATSAPP_PACKAGES) return

        val type = eventTypeName(event.eventType)
        val className = event.className?.toString() ?: "?"
        val eventLine =
            "WhatsApp evt type=$type class=$className detectorState=$detectorState detectorInCall=$detectorInCall eventText=${summarize(event.text)}"
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            WhatsAppCallJourney.i("event", eventLine)
        } else {
            WhatsAppCallJourney.d("event", eventLine)
        }

        val now = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastFullScanMs < MIN_SCAN_INTERVAL_MS) {
                if (now - lastThrottleLogMs > 10_000L) {
                    lastThrottleLogMs = now
                    WhatsAppCallJourney.d("scan", "throttled content_changed (interval=${MIN_SCAN_INTERVAL_MS}ms)")
                }
                return
            }
        }
        lastFullScanMs = now

        val found = LinkedHashSet<String>()
        val textSamples = ArrayList<String>()
        val nodeCount = intArrayOf(0)

        var eventSource: AccessibilityNodeInfo? = null
        try {
            eventSource = event.source
            if (eventSource != null) {
                WhatsAppCallJourney.i("scan", "using event.source subtree (partial tree for this event)")
                collectFromNode(eventSource, found, textSamples, nodeCount)
            } else {
                WhatsAppCallJourney.i("scan", "event.source is null — will use rootInActiveWindow only")
            }
        } finally {
            eventSource?.recycle()
        }

        val root = service.rootInActiveWindow
        if (root == null) {
            WhatsAppCallJourney.w("scan", "rootInActiveWindow=null — cannot walk full tree (another app may have focus)")
            return
        }

        try {
            val rootPkg = root.packageName?.toString()
            val rootClass = root.className?.toString()
            WhatsAppCallJourney.i(
                "scan",
                "rootInActiveWindow pkg=$rootPkg class=$rootClass (event was from $pkg)"
            )
            if (rootPkg != null && rootPkg !in WHATSAPP_PACKAGES) {
                WhatsAppCallJourney.w(
                    "scan",
                    "Active window is NOT WhatsApp — number may live in WhatsApp but tree here is $rootPkg. " +
                        "Try answering or ensure WhatsApp call screen is foreground."
                )
            }
            collectFromNode(root, found, textSamples, nodeCount)
        } finally {
            root.recycle()
        }

        event.text?.forEach { cs ->
            extractNumbers(cs?.toString(), found, preferTenDigit = true)
        }

        WhatsAppCallJourney.i(
            "scan",
            "walk done nodes=${nodeCount[0]} textSamplesWithDigits=${textSamples.size} rawCandidates=${found.size}"
        )
        textSamples.take(25).forEachIndexed { i, s ->
            WhatsAppCallJourney.d("sample", "#$i ${s.take(120)}")
        }
        if (textSamples.size > 25) {
            WhatsAppCallJourney.d("sample", "... and ${textSamples.size - 25} more (truncated in log)")
        }

        pruneRecent(now)
        var loggedAny = false
        for (raw in found) {
            val normalized = normalizeIndian(raw) ?: continue
            val last = recentlyLogged[normalized]
            if (last != null && now - last < DEDUPE_WINDOW_MS) continue
            recentlyLogged[normalized] = now
            loggedAny = true
            WhatsAppCallJourney.i("number", "normalized=$normalized (from raw=${raw.take(48)})")
            Log.i(TAG, "Call UI number candidate pkg=$pkg normalized=$normalized rawSnippet=${raw.take(32)}")
        }
        if (!loggedAny && found.isNotEmpty()) {
            WhatsAppCallJourney.w("number", "had raw fragments but none normalized: $found")
        }
        if (!loggedAny && found.isEmpty()) {
            WhatsAppCallJourney.w(
                "number",
                "no +91/91/10-digit patterns in collected strings — if UI shows digits only in a graphic/WebView, accessibility may not expose them"
            )
        }
    }

    private fun summarize(text: List<CharSequence>?): String {
        if (text.isNullOrEmpty()) return "[]"
        return text.joinToString(" | ") { it.toString().take(80) }
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        else -> "TYPE_$type"
    }

    private fun pruneRecent(now: Long) {
        val it = recentlyLogged.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > DEDUPE_WINDOW_MS * 4) it.remove()
        }
    }

    private fun collectFromNode(
        node: AccessibilityNodeInfo?,
        out: MutableSet<String>,
        textSamples: MutableList<String>,
        nodeCount: IntArray
    ) {
        if (node == null) return
        nodeCount[0]++
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() else null
        listOfNotNull(t, d, h).forEach { s ->
            if (s.isNotBlank()) {
                extractNumbers(s, out, preferTenDigit = true)
                if (s.any { it.isDigit() } && textSamples.size < 80) {
                    val oneLine = s.replace('\n', ' ').trim()
                    if (oneLine.isNotEmpty()) textSamples.add(oneLine)
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            try {
                collectFromNode(child, out, textSamples, nodeCount)
            } finally {
                child?.recycle()
            }
        }
    }

    private fun extractNumbers(s: String?, out: MutableSet<String>, preferTenDigit: Boolean) {
        if (s.isNullOrBlank()) return
        val compactLine = s.replace('\n', ' ')
        if (s.contains("+91", ignoreCase = true)) {
            PLUS_91_MOBILE.findAll(s).forEach { m ->
                m.groups[1]?.value?.let { out.add("+91$it") }
            }
            PLUS_91_ANY_10.findAll(s).forEach { m ->
                m.groups[1]?.value?.let { out.add("+91$it") }
            }
        }
        PREFIX_91.findAll(s).forEach { m ->
            m.groups[1]?.value?.let { out.add("+91$it") }
        }
        if (preferTenDigit) {
            LOOSE_10_MOBILE.findAll(compactLine).forEach { m ->
                val digits = m.groups[1]?.value?.filter { it.isDigit() } ?: return@forEach
                if (digits.length == 10) out.add("+91$digits")
            }
            DIGIT_RUN.findAll(s).forEach { m ->
                val run = m.value
                if (run.length == 10 && run[0] in "6789") {
                    out.add("+91$run")
                }
                if (run.length == 12 && run.startsWith("91") && run[2] in "6789") {
                    out.add("+${run}")
                }
            }
        }
    }

    private fun normalizeIndian(raw: String): String? {
        val compact = raw.replace(Regex("""[\s\-.()]"""), "")
        Regex("""\+91([6-9]\d{9})""").find(compact)?.let { return "+91${it.groupValues[1]}" }
        Regex("""\+91(\d{10})""").find(compact)?.let { return "+91${it.groupValues[1]}" }
        Regex("""^91([6-9]\d{9})$""").find(compact)?.let { return "+91${it.groupValues[1]}" }
        return null
    }
}
