package com.example.ccsdump

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.graphics.Rect
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic only: waits until the screen has been quiet for QUIET_MS,
 * then writes the full accessibility node tree of the foreground window to
 * Downloads/ccs-dumps/ccs_dump_<time>.txt so we can see what the CCS
 * calendar actually exposes (text, descriptions, bounds).
 *
 * Usage: enable the service, open CCS calendar, wait ~3s, check the toast.
 */
class NodeDumpService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastDumpAt = 0L

    private val dumpRunnable = Runnable { dumpNow() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        // Debounce: restart the timer on every event; dump only after things settle.
        handler.removeCallbacks(dumpRunnable)
        handler.postDelayed(dumpRunnable, QUIET_MS)
    }

    override fun onInterrupt() {}

    private fun dumpNow() {
        val now = System.currentTimeMillis()
        if (now - lastDumpAt < MIN_GAP_MS) return
        val root = rootInActiveWindow ?: return
        lastDumpAt = now

        val sb = StringBuilder()
        sb.append("package: ").append(root.packageName).append('\n')
        sb.append("time: ").append(Date(now)).append("\n\n")

        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= MAX_NODES) return
            count++
            val r = Rect()
            node.getBoundsInScreen(r)
            sb.append("  ".repeat(depth))
                .append(node.className)
                .append(" text=").append(quote(node.text))
                .append(" desc=").append(quote(node.contentDescription))
                .append(" id=").append(node.viewIdResourceName)
                .append(" bounds=").append(r.toShortString())
                .append(" click=").append(node.isClickable)
                .append(" sel=").append(node.isSelected)
                .append('\n')
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
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
            Toast.makeText(this, "Dumped $count nodes", Toast.LENGTH_SHORT).show()
        }
    }

    private fun quote(cs: CharSequence?) = if (cs == null) "null" else "\"" + cs.toString().replace("\n", "\\n") + "\""

    companion object {
        private const val QUIET_MS = 2500L   // wait for screen to settle
        private const val MIN_GAP_MS = 8000L // avoid flooding Downloads
        private const val MAX_NODES = 5000
    }
}
