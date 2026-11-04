package com.example.ccsdump

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic only: when MobileCCS is on screen, waits a few seconds for the
 * page to load, then writes its accessibility node tree to
 * Downloads/ccs-dumps/ccs_dump_<time>.txt. Other apps are ignored.
 *
 * Also keeps Downloads/ccs-dumps/status_<time>.txt, a log of what it saw.
 *
 * Read-only: never clicks or types, and the app has no internet permission.
 * A short buzz confirms each saved dump (Xiaomi can hide toasts).
 */
class NodeDumpService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var lastDumpAt = 0L
    private var lastFingerprint = 0

    private val dumpRunnable = Runnable {
        pending = false
        dumpNow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        log("service started")
        buzz()
        toast("CCS Node Dump is on. Open MobileCCS.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!isCcs(pkg)) return
        // Fixed delay from the first event (not restarted by later ones),
        // because CCS keeps updating its screen and never goes fully quiet.
        if (!pending) {
            pending = true
            handler.postDelayed(dumpRunnable, LOAD_DELAY_MS)
        }
    }

    override fun onInterrupt() {}

    private val ccsCache = HashMap<String, Boolean>()

    /**
     * True for MobileCCS. Matches "ccs" in the app's name or package, but not
     * this app, other home-made test apps (com.example.*), or launcher/system UI.
     */
    private fun isCcs(pkg: String): Boolean = ccsCache.getOrPut(pkg) {
        val label = try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            "?"
        }
        val match = pkg != packageName &&
            !pkg.startsWith("com.example.") &&
            pkg !in IGNORED_PACKAGES &&
            (label.contains("ccs", ignoreCase = true) || pkg.contains("ccs", ignoreCase = true))
        log("saw app: $pkg (name: $label) -> ${if (match) "CCS, will capture" else "ignored"}")
        match
    }

    // ---- status.txt: a running log so we can see what the service is doing ----
    private val statusLines = ArrayList<String>()
    private var statusUri: android.net.Uri? = null

    private fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        statusLines.add("$stamp  $line")
        while (statusLines.size > 200) statusLines.removeAt(0)
        try {
            if (statusUri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "status_" +
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ccs-dumps")
                }
                statusUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }
            statusUri?.let { uri ->
                contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(statusLines.joinToString("\n", postfix = "\n").toByteArray())
                }
            }
        } catch (e: Exception) {
            // status logging must never break the service
        }
    }

    /** The MobileCCS window on screen, if any. */
    private fun findCcsRoot(): AccessibilityNodeInfo? {
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root ?: continue
            if (isCcs(root.packageName?.toString() ?: "")) return root
        }
        val active = rootInActiveWindow ?: return null
        return if (isCcs(active.packageName?.toString() ?: "")) active else null
    }

    private fun dumpNow() {
        val now = System.currentTimeMillis()
        if (now - lastDumpAt < MIN_GAP_MS) return
        val root = findCcsRoot()
        if (root == null) {
            log("CCS event, but couldn't read the CCS window")
            return
        }
        val pkg = root.packageName?.toString() ?: "unknown"

        val body = StringBuilder()
        val visibleText = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= MAX_NODES) return
            count++
            val r = Rect()
            node.getBoundsInScreen(r)
            body.append("  ".repeat(depth))
                .append(node.className)
                .append(" text=").append(quote(node.text))
                .append(" desc=").append(quote(node.contentDescription))
                .append(" id=").append(node.viewIdResourceName)
                .append(" bounds=").append(r.toShortString())
                .append(" click=").append(node.isClickable)
                .append(" sel=").append(node.isSelected)
                .append('\n')
            node.text?.let { visibleText.append(it).append('|') }
            node.contentDescription?.let { visibleText.append(it).append('|') }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)

        // Skip if the screen's text hasn't changed since the last saved dump.
        // "Last updated ... ago" changes on its own, so leave it out.
        val fingerprint = visibleText.toString()
            .replace(Regex("Last updated[^|]*\\|[^|]*\\|"), "")
            .hashCode()
        if (fingerprint == lastFingerprint) {
            log("CCS screen unchanged ($count items), not saved again")
            return
        }
        lastFingerprint = fingerprint
        lastDumpAt = now

        val sb = StringBuilder()
        sb.append("package: ").append(pkg).append('\n')
        sb.append("time: ").append(Date(now)).append("\n\n")
        sb.append(body)
        sb.append("\nnodes: ").append(count).append('\n')

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "ccs_dump_$stamp.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ccs-dumps")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
            log("saved ccs_dump_$stamp.txt ($count items, ${visibleText.length} chars of text)")
            buzz()
            toast("Saved CCS screen ($count items)")
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun quote(cs: CharSequence?) = if (cs == null) "null" else "\"" + cs.toString().replace("\n", "\\n") + "\""

    companion object {
        private const val LOAD_DELAY_MS = 3000L // let the CCS page load
        private const val MIN_GAP_MS = 5000L
        private const val MAX_NODES = 5000
        private val IGNORED_PACKAGES = setOf(
            "com.miui.home",
            "com.android.settings",
            "com.android.systemui",
        )
    }
}
