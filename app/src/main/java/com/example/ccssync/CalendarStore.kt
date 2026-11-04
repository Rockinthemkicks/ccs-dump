package com.example.ccssync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events

/** Reads the phone's calendars and writes trips into the chosen one. */
object CalendarStore {

    class Cal(val id: Long, val name: String, val account: String, val primary: Boolean) {
        override fun toString() = if (name == account) name else "$name ($account)"
    }

    fun writableCalendars(context: Context): List<Cal> {
        val out = ArrayList<Cal>()
        val proj = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
            Calendars.IS_PRIMARY, Calendars.CALENDAR_ACCESS_LEVEL
        )
        context.contentResolver.query(
            Calendars.CONTENT_URI, proj,
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(Cal(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "", c.getInt(3) == 1))
            }
        }
        return out.sortedWith(compareByDescending<Cal> { it.primary }.thenBy { it.name })
    }

    /** The calendar to write to: the one picked in the app, else the primary Google calendar. */
    fun chosenCalendar(context: Context): Cal? {
        val cals = writableCalendars(context)
        val saved = Prefs.calendarId(context)
        return cals.firstOrNull { it.id == saved } ?: cals.firstOrNull()
    }

    // Each event's description ends with [ccs-sync:<trip key>@<screenshot time ms>].
    // Events from the first version have no "@time" and count as oldest.
    private val TAG = Regex("""\[ccs-sync:([^\]@]+)(?:@(\d+))?]""")
    // Titles start with "✈ <trip code>": used too, in case a calendar sync
    // changed the description.
    private val TITLE = Regex("""^\u2708\s*(\S+)""")
    private val APP_EVENTS =
        "(${Events.TITLE} LIKE '\u2708%' OR ${Events.DESCRIPTION} LIKE '%[ccs-sync:%')"

    private fun tag(trip: TripParser.Trip, shotMillis: Long) = "[ccs-sync:${trip.key()}@$shotMillis]"

    private class AppEvent(
        val id: Long, val start: Long, val end: Long, val pairing: String?, val key: String?, val shot: Long,
        val title: String, val allDay: Boolean
    )

    private fun appEvents(context: Context, calId: Long, from: Long, to: Long): List<AppEvent> {
        val out = ArrayList<AppEvent>()
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.DTSTART, Events.DTEND, Events.TITLE, Events.DESCRIPTION, Events.ALL_DAY),
            // Every calendar, not just the chosen one: after switching calendars
            // (or a reinstall resetting the choice) old copies live elsewhere.
            "${Events.DELETED} = 0 AND $APP_EVENTS AND " +
                "${Events.DTSTART} < ? AND ${Events.DTSTART} > ?",
            arrayOf(to.toString(), from.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(3) ?: ""
                val desc = c.getString(4) ?: ""
                val m = TAG.find(desc)
                val key = m?.groupValues?.get(1)
                val pairing = key?.substringBefore(':') ?: TITLE.find(title)?.groupValues?.get(1)
                val end = if (c.isNull(2)) c.getLong(1) else c.getLong(2)
                out.add(AppEvent(c.getLong(0), c.getLong(1), end, pairing, key,
                    m?.groupValues?.get(2)?.toLongOrNull() ?: 0L, title, c.getInt(5) == 1))
            }
        }
        return out
    }

    /** Result of trying to put a trip in the calendar. */
    class WriteResult(val added: Int, val removedTrips: List<String>, val newerShotExists: Boolean)

    /**
     * Puts the trip's duty days in the calendar so the newest screenshot wins:
     * - Any trip this app added that overlaps the new trip in time, or has the
     *   same trip code within about a week, is removed entirely (all its duty
     *   days), unless it came from a newer screenshot.
     * - If an overlapping trip came from a newer screenshot, this screenshot is
     *   out of date and nothing is changed.
     */
    fun replaceTrip(context: Context, calId: Long, trip: TripParser.Trip, shotMillis: Long): WriteResult {
        val cr = context.contentResolver
        val events = TripParser.toEvents(trip)
        val newStart = events.minOf { it.start.toInstant().toEpochMilli() }
        val newEnd = events.maxOf { it.end.toInstant().toEpochMilli() }
        val day = 24L * 60 * 60 * 1000

        val nearby = appEvents(context, calId, newStart - 12 * day, newEnd + 12 * day)
        val week = 7 * day
        val direct = nearby.filter { e ->
            (!e.allDay && e.start < newEnd && e.end > newStart) ||
                (e.pairing == trip.pairing && e.start > newStart - week && e.start < newEnd + week)
        }
        // Whole trips: every event sharing a key (or, for untagged ones, a trip code) with a direct hit.
        val keys = direct.mapNotNull { it.key }.toSet()
        val untaggedCodes = direct.filter { it.key == null }.mapNotNull { it.pairing }.toSet()
        val toRemove = nearby.filter { e ->
            e in direct || (e.key != null && e.key in keys) ||
                (e.key == null && e.pairing in untaggedCodes && e.start > newStart - week && e.start < newEnd + week)
        }

        if (toRemove.any { it.shot > shotMillis }) {
            return WriteResult(0, emptyList(), newerShotExists = true)
        }

        for (e in toRemove) cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, e.id), null, null)

        for (e in events) {
            val v = ContentValues().apply {
                put(Events.CALENDAR_ID, calId)
                put(Events.TITLE, e.title)
                put(Events.DESCRIPTION, e.description + tag(trip, shotMillis))
                put(Events.EVENT_LOCATION, e.location)
                put(Events.DTSTART, e.start.toInstant().toEpochMilli())
                put(Events.DTEND, e.end.toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, e.start.zone.id)
                put(Events.EVENT_END_TIMEZONE, e.end.zone.id)
                put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            }
            cr.insert(Events.CONTENT_URI, v)
        }
        // All-day bar across the days the trip covers (multi-day trips only).
        val span = TripParser.toSpan(trip)
        if (span.lastDay.isAfter(span.firstDay)) {
            val utc = java.time.ZoneOffset.UTC
            val v = ContentValues().apply {
                put(Events.CALENDAR_ID, calId)
                put(Events.TITLE, span.title)
                put(Events.DESCRIPTION, span.description + tag(trip, shotMillis))
                put(Events.ALL_DAY, 1)
                put(Events.DTSTART, span.firstDay.atStartOfDay(utc).toInstant().toEpochMilli())
                put(Events.DTEND, span.lastDay.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
            }
            cr.insert(Events.CONTENT_URI, v)
        }
        removeExactDuplicates(context, newStart - 12 * day, newEnd + 12 * day)

        val removed = toRemove.mapNotNull { it.pairing }.filter { it != trip.pairing }.distinct()
        return WriteResult(events.size, removed, newerShotExists = false)
    }

    /** Safety net: if the same duty day exists more than once, keep only the newest copy. */
    private fun removeExactDuplicates(context: Context, from: Long, to: Long) {
        val all = appEvents(context, -1, from, to)
        all.groupBy { Triple(it.title, it.start, it.end) }.values.forEach { copies ->
            copies.sortedByDescending { it.id }.drop(1).forEach {
                context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, it.id), null, null)
            }
        }
    }

    /** Removes everything this app has added to the calendar (for a clean rebuild). */
    fun deleteAllAppEvents(context: Context, calId: Long): Int {
        var n = 0
        val ids = ArrayList<Long>()
        context.contentResolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.DELETED} = 0 AND $APP_EVENTS",
            null, null
        )?.use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        for (id in ids) n += context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
        return n
    }
}
