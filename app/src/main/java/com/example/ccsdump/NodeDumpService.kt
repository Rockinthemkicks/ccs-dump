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

    /** True for the MobileCCS app only (matched by its name or package). */
    private fun isCcs(pkg: String): Boolean = ccsCache.getOrPut(pkg) {
        if (pkg == packageName) return@getOrPut false
        val label = try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
        label.equals("MobileCCS", ignoreCase = true) ||
            pkg.contains("mobileccs", ignoreCase = true)
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
        val root = findCcsRoot() ?: return
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
        if (fingerprint == lastFingerprint) return
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
    }
}
