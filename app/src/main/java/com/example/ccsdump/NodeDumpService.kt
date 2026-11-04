package com.example.ccsdump

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.graphics.Rect
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic only: dumps the accessibility node tree of the app on screen
 * when you tap the accessibility button (the floating / nav-bar shortcut).
 * Writes to Downloads/ccs-dumps/ccs_dump_<time>.txt.
 *
 * No automatic dumps any more: CCS keeps updating its screen, so waiting for
 * the screen to go quiet never fired there and caught the home screen instead.
 *
 * Usage: turn on the "CCS Node Dump shortcut" in Accessibility settings,
 * open MobileCCS on the screen you want, tap the shortcut button.
 */
class NodeDumpService : AccessibilityService() {

    private var lastDumpAt = 0L

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            dumpNow()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        Toast.makeText(this, "CCS Node Dump ready: open CCS and tap the shortcut", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        super.onDestroy()
    }

    // Events are ignored; dumps happen only on button tap.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** The app window on screen (not the nav bar, keyboard, or our own button). */
    private fun findAppRoot(): AccessibilityNodeInfo? {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val candidates = appWindows.sortedByDescending { it.isActive || it.isFocused }
        for (w in candidates) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != packageName) return root
        }
        return rootInActiveWindow
    }

    private fun dumpNow() {
        val now = System.currentTimeMillis()
        if (now - lastDumpAt < MIN_GAP_MS) return
        val root = findAppRoot()
        if (root == null) {
            toast("Nothing to dump: no app window found")
            return
        }
        val pkg = root.packageName?.toString() ?: "unknown"
        if (pkg in IGNORED_PACKAGES) {
            toast("That's $pkg, not CCS. Open MobileCCS first.")
            return
        }
        lastDumpAt = now

        val sb = StringBuilder()
        sb.append("package: ").append(pkg).append('\n')
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
            toast("Dumped $count nodes from $pkg")
        } else {
            toast("Couldn't save the dump to Downloads")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun quote(cs: CharSequence?) = if (cs == null) "null" else "\"" + cs.toString().replace("\n", "\\n") + "\""

    companion object {
        private const val MIN_GAP_MS = 1500L // ignore accidental double taps
        private const val MAX_NODES = 5000
        private val IGNORED_PACKAGES = setOf(
            "com.miui.home",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
        )
    }
}
