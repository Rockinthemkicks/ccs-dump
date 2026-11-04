package com.example.ccssync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var permsText: TextView
    private lateinit var permsButton: Button
    private lateinit var calendarSpinner: Spinner
    private lateinit var scanButton: Button
    private lateinit var rebuildButton: Button
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private var calendars: List<CalendarStore.Cal> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 2)
        }

        col.addView(text("CCS Calendar Sync", 22f, bold = true))
        col.addView(text(
            "In MobileCCS, open a trip's details page (the one with Check-In, DP 01 and the flights) " +
                "and take a screenshot. Within a few seconds this app reads it on your phone and puts " +
                "each duty day in your calendar. Screenshot a trip again after it changes and the " +
                "calendar is updated. You can also share a screenshot to this app."
        ))

        permsText = text("")
        col.addView(permsText)
        permsButton = Button(this).apply { setOnClickListener { askPermissions() } }
        col.addView(permsButton)

        col.addView(text("Calendar to use", bold = true))
        calendarSpinner = Spinner(this)
        col.addView(calendarSpinner)

        scanButton = Button(this).apply {
            text = "Check screenshots from the last 3 days"
            setOnClickListener {
                runScan(System.currentTimeMillis() / 1000 - 3 * 24 * 3600)
            }
        }
        col.addView(scanButton)

        rebuildButton = Button(this).apply {
            text = "Rebuild calendar from screenshots"
            setOnClickListener { confirmRebuild() }
        }
        col.addView(rebuildButton)

        statusText = text("")
        col.addView(statusText)
        col.addView(text("Recent activity", bold = true))
        logText = text("", 13f)
        col.addView(logText)

        setContentView(ScrollView(this).apply { addView(col) })
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (Sync.hasPhotoPermission(this)) {
            ScreenshotJob.schedule(this)
            runScan(null) // catch up on anything the background check missed
        }
    }

    private fun refresh() {
        val photos = Sync.hasPhotoPermission(this)
        val cal = Sync.hasCalendarPermission(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        permsText.text = "Photos: ${if (photos) "allowed" else "needed"}   " +
            "Calendar: ${if (cal) "allowed" else "needed"}   " +
            "Notifications: ${if (notif) "allowed" else "optional"}"
        permsButton.visibility = if (photos && cal && notif) View.GONE else View.VISIBLE
        permsButton.text = "Allow access"
        scanButton.isEnabled = photos && cal
        rebuildButton.isEnabled = photos && cal

        if (cal) {
            calendars = CalendarStore.writableCalendars(this)
            calendarSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, calendars)
            val chosen = CalendarStore.chosenCalendar(this)
            val idx = calendars.indexOfFirst { it.id == chosen?.id }
            if (idx >= 0) calendarSpinner.setSelection(idx)
            calendarSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    Prefs.setCalendarId(this@MainActivity, calendars[pos].id)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val log = Prefs.log(this)
        logText.text = if (log.isEmpty()) "Nothing yet." else log.joinToString("\n\n")
    }

    private fun askPermissions() {
        val wanted = ArrayList<String>()
        wanted += if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        wanted += Manifest.permission.READ_CALENDAR
        wanted += Manifest.permission.WRITE_CALENDAR
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
        if (Sync.hasPhotoPermission(this)) {
            ScreenshotJob.schedule(this)
            runScan(null)
        }
    }

    private fun runScan(sinceSeconds: Long?) {
        if (!Sync.hasPhotoPermission(this) || !Sync.hasCalendarPermission(this)) return
        statusText.text = "Checking screenshots…"
        worker.execute {
            val results = try {
                Sync.scanNew(applicationContext, notify = false, sinceSeconds = sinceSeconds)
            } catch (e: Exception) {
                listOf(Sync.Outcome.Failed(e.message ?: "error"))
            }
            runOnUiThread {
                statusText.text = summarize(results)
                refresh()
            }
        }
    }

    private fun confirmRebuild() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Rebuild calendar?")
            .setMessage(
                "This removes every trip this app added to your calendar (nothing else), then " +
                    "re-reads your screenshots from the last 30 days, oldest first, so the newest " +
                    "version of each day wins.\n\nTip: delete old CCS screenshots you don't need first."
            )
            .setPositiveButton("Rebuild") { _, _ ->
                statusText.text = "Rebuilding… this can take a minute."
                worker.execute {
                    val (removed, results) = try {
                        Sync.rebuild(applicationContext, 30)
                    } catch (e: Exception) {
                        0 to listOf(Sync.Outcome.Failed(e.message ?: "error"))
                    }
                    runOnUiThread {
                        statusText.text = "Removed $removed old event${if (removed == 1) "" else "s"}.\n" + summarize(results)
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)) ?: return
        statusText.text = "Reading the shared screenshot…"
        worker.execute {
            val r = Sync.processImage(applicationContext, uri, notify = false,
                shotMillis = Sync.shotTimeOf(applicationContext, uri))
            runOnUiThread {
                statusText.text = summarize(listOf(r))
                refresh()
            }
        }
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun summarize(results: List<Sync.Outcome>): String {
        val added = results.filterIsInstance<Sync.Outcome.Added>()
        val failed = results.filterIsInstance<Sync.Outcome.Failed>()
        val outdated = results.filterIsInstance<Sync.Outcome.Outdated>()
        val skipped = if (outdated.isNotEmpty())
            "\nSkipped as out of date: " + outdated.joinToString { it.trip.pairing } else ""
        return when {
            added.isNotEmpty() -> "Updated: " + added.joinToString { it.trip.pairing } + skipped +
                (if (failed.isNotEmpty()) "\nProblems: " + failed.joinToString { it.message } else "")
            outdated.isNotEmpty() -> "Nothing changed." + skipped
            failed.isNotEmpty() -> "Problem: " + failed.joinToString { it.message }
            results.isEmpty() -> "No new screenshots."
            else -> "No CCS trip pages in the new screenshots."
        }
    }
}
