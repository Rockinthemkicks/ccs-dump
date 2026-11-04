package com.example.ccssync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.app.Notification
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Settings and a short history, kept on the phone. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun calendarId(c: Context) = p(c).getLong("calendar", -1L)
    fun setCalendarId(c: Context, id: Long) = p(c).edit().putLong("calendar", id).apply()

    /** Newest screenshot (MediaStore DATE_ADDED, seconds) already looked at. */
    fun lastSeen(c: Context): Long {
        val v = p(c).getLong("lastSeen", 0L)
        if (v != 0L) return v
        // First run: start with the last 3 days so recent trip screenshots are picked up.
        val start = System.currentTimeMillis() / 1000 - 3 * 24 * 3600
        setLastSeen(c, start)
        return start
    }
    fun setLastSeen(c: Context, s: Long) = p(c).edit().putLong("lastSeen", s).apply()

    fun log(c: Context): List<String> = p(c).getString("log", "")!!.split('\n').filter { it.isNotBlank() }
    fun addLog(c: Context, line: String) {
        val stamp = java.text.SimpleDateFormat("MMM d HH:mm", Locale.US).format(java.util.Date())
        val lines = (listOf("$stamp  $line") + log(c)).take(20)
        p(c).edit().putString("log", lines.joinToString("\n")).apply()
    }
}

/** Finds CCS trip screenshots, reads them, and updates the calendar. */
object Sync {
    private val lock = Any()
    private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    sealed class Outcome {
        class Added(val trip: TripParser.Trip, val events: Int, val calendar: String) : Outcome()
        class Outdated(val trip: TripParser.Trip) : Outcome()
        object NotATrip : Outcome()
        class Failed(val message: String) : Outcome()
    }

    fun hasPhotoPermission(c: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCalendarPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * Reads one image; adds it to the calendar if it's a CCS trip page.
     * @param shotMillis when the screenshot was taken; newer screenshots win.
     */
    fun processImage(c: Context, uri: Uri, notify: Boolean, shotMillis: Long): Outcome = synchronized(lock) {
        if (!hasCalendarPermission(c)) return Outcome.Failed("Calendar access is off. Open the app to allow it.")
        val cal = CalendarStore.chosenCalendar(c) ?: return Outcome.Failed("No calendar found to write to.")

        val trip = try {
            val ocr = Ocr.read(c, uri)
            TripParser.parse(ocr.words, ocr.imageWidth, AirportZones.get(c))
        } catch (e: Exception) {
            return Outcome.Failed("Couldn't read the screenshot: ${e.message}")
        } ?: return Outcome.NotATrip

        val result = try {
            CalendarStore.replaceTrip(c, cal.id, trip, shotMillis)
        } catch (e: Exception) {
            return Outcome.Failed("Couldn't write to the calendar: ${e.message}")
        }

        val start = trip.duties.first().report
        if (result.newerShotExists) {
            Prefs.addLog(c, "Skipped ${trip.pairing} (${DAY.format(start)}): a newer screenshot already covers those days")
            return Outcome.Outdated(trip)
        }

        val n = result.added
        var line = "Added ${trip.pairing} (${DAY.format(start)}): $n duty day${if (n == 1) "" else "s"} to ${cal.name}"
        if (result.removedTrips.isNotEmpty()) line += ". Replaced " + result.removedTrips.distinct().joinToString()
        if (trip.warnings.isNotEmpty()) line += ". Check: " + trip.warnings.joinToString("; ")
        // Each duty day exactly as written, in local airport times.
        for (d in trip.duties) {
            val first = d.legs.first()
            val last = d.legs.last()
            line += "\n   ${DAY.format(d.report)}: rpt ${HM.format(d.report)} ${first.from} → " +
                "rel ${HM.format(d.end())} ${last.to} (${d.legs.size} seg)"
        }
        Prefs.addLog(c, line)
        if (notify) Notifier.show(c, "${trip.pairing} added to your calendar", line)
        Outcome.Added(trip, n, cal.name)
    }

    /** When a shared image was taken, from the phone's photo records or its file name. */
    fun shotTimeOf(c: Context, uri: Uri): Long {
        try {
            c.contentResolver.query(
                uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DISPLAY_NAME), null, null, null
            )?.use { cur ->
                if (cur.moveToFirst()) {
                    val taken = cur.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    if (taken >= 0 && !cur.isNull(taken) && cur.getLong(taken) > 0) return cur.getLong(taken)
                    val added = cur.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    if (added >= 0 && !cur.isNull(added) && cur.getLong(added) > 0) return cur.getLong(added) * 1000
                    val name = cur.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    if (name >= 0) timeFromName(cur.getString(name))?.let { return it }
                }
            }
        } catch (e: Exception) {
            // Some apps share images without these details.
        }
        return System.currentTimeMillis()
    }

    private val NAME_TIME = Regex("""(20\d\d)[-_]?(\d\d)[-_]?(\d\d)[-_](\d\d)[-_]?(\d\d)[-_]?(\d\d)""")

    private fun timeFromName(name: String?): Long? {
        val m = NAME_TIME.find(name ?: return null) ?: return null
        val (y, mo, d, h, mi, se) = m.destructured
        return try {
            java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt())
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clean start: removes everything this app put in the calendar, then reads
     * the last `days` days of screenshots oldest-first, so the newest wins.
     */
    fun rebuild(c: Context, days: Int): Pair<Int, List<Outcome>> = synchronized(lock) {
        val cal = CalendarStore.chosenCalendar(c) ?: return 0 to listOf(Outcome.Failed("No calendar found."))
        val removed = CalendarStore.deleteAllAppEvents(c, cal.id)
        Prefs.addLog(c, "Rebuild: removed $removed old event${if (removed == 1) "" else "s"}, re-reading $days days of screenshots")
        removed to scanNew(c, notify = false, sinceSeconds = System.currentTimeMillis() / 1000 - days * 24L * 3600)
    }

    /**
     * Looks at screenshots saved since the last check. Non-CCS screenshots are
     * skipped by file name when the phone records the app in it (Xiaomi does),
     * otherwise they're read and ignored unless they're a CCS trip page.
     */
    fun scanNew(c: Context, notify: Boolean, sinceSeconds: Long? = null): List<Outcome> = synchronized(lock) {
        if (!hasPhotoPermission(c)) return emptyList()
        val since = sinceSeconds ?: Prefs.lastSeen(c)
        val results = ArrayList<Outcome>()
        var newest = since

        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN
        )
        c.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Images.Media.DATE_ADDED} > ?", arrayOf(since.toString()),
            "${MediaStore.Images.Media.DATE_TAKEN} ASC, ${MediaStore.Images.Media.DATE_ADDED} ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val name = cur.getString(1) ?: ""
                val path = cur.getString(2) ?: ""
                val added = cur.getLong(3)
                val taken = if (cur.isNull(4)) 0L else cur.getLong(4)
                newest = maxOf(newest, added)
                if (!isCandidate(name, path)) continue
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val shot = if (taken > 0) taken else added * 1000
                results.add(processImage(c, uri, notify, shot))
            }
        }
        if (sinceSeconds == null || newest > Prefs.lastSeen(c)) Prefs.setLastSeen(c, maxOf(newest, Prefs.lastSeen(c)))
        results
    }

    private val PACKAGE_IN_NAME = Regex("_([a-zA-Z][\\w]*(?:\\.[\\w]+)+)\\.(?:jpe?g|png|webp)$")

    private fun isCandidate(name: String, path: String): Boolean {
        val isScreenshot = path.contains("screenshot", ignoreCase = true) || name.startsWith("Screenshot", ignoreCase = true)
        if (!isScreenshot) return false
        val pkg = PACKAGE_IN_NAME.find(name)?.groupValues?.get(1) ?: return true
        return pkg.contains("CrewServices", ignoreCase = true) || pkg.contains("ccs", ignoreCase = true)
    }
}

object Notifier {
    private const val CHANNEL = "sync"

    fun show(c: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Calendar updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
}
