package com.example.ccssync

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

    private fun tag(trip: TripParser.Trip, shotMillis: Long) = "[ccs-sync:${trip.key()}@$shotMillis]"

    /** Result of trying to put a trip in the calendar. */
    class WriteResult(val added: Int, val removedTrips: List<String>, val newerShotExists: Boolean)

    /**
     * Puts the trip's duty days in the calendar so the newest screenshot wins:
     * - Any trip this app added that overlaps the new trip (or is the same trip
     *   code within a week) is removed entirely, all its duty days, unless it
     *   came from a newer screenshot.
     * - If an overlapping trip came from a newer screenshot, this screenshot is
     *   out of date and nothing is changed.
     */
    fun replaceTrip(context: Context, calId: Long, trip: TripParser.Trip, shotMillis: Long): WriteResult {
        val cr = context.contentResolver
        val events = TripParser.toEvents(trip)
        val newStart = events.minOf { it.start.toInstant().toEpochMilli() }
        val newEnd = events.maxOf { it.end.toInstant().toEpochMilli() }
        val week = 7L * 24 * 60 * 60 * 1000

        // App-made events that overlap in time, or share this trip code nearby.
        val conflicting = HashMap<String, Long>() // trip key -> its screenshot time
        cr.query(
            Events.CONTENT_URI, arrayOf(Events.DESCRIPTION),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0 AND ${Events.DESCRIPTION} LIKE ? AND (" +
                "(${Events.DTSTART} < ? AND ${Events.DTEND} > ?) OR " +
                "(${Events.DESCRIPTION} LIKE ? AND ${Events.DTSTART} BETWEEN ? AND ?))",
            arrayOf(
                calId.toString(), "%[ccs-sync:%",
                newEnd.toString(), newStart.toString(),
                "%[ccs-sync:${trip.pairing}:%", (newStart - week).toString(), (newEnd + week).toString()
            ),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val m = TAG.find(c.getString(0) ?: "") ?: continue
                val key = m.groupValues[1]
                val shot = m.groupValues[2].toLongOrNull() ?: 0L
                conflicting[key] = maxOf(conflicting[key] ?: 0L, shot)
            }
        }

        if (conflicting.values.any { it > shotMillis }) {
            return WriteResult(0, emptyList(), newerShotExists = true)
        }

        for (key in conflicting.keys) deleteTripKey(context, calId, key)

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
        val removed = conflicting.keys.filter { it != trip.key() }.map { it.substringBefore(':') }
        return WriteResult(events.size, removed, newerShotExists = false)
    }

    /** Removes every duty day of one trip this app added. */
    private fun deleteTripKey(context: Context, calId: Long, key: String) {
        context.contentResolver.delete(
            Events.CONTENT_URI,
            "${Events.CALENDAR_ID} = ? AND (${Events.DESCRIPTION} LIKE ? OR ${Events.DESCRIPTION} LIKE ?)",
            arrayOf(calId.toString(), "%[ccs-sync:$key@%", "%[ccs-sync:$key]%")
        )
    }

    /** Removes everything this app has added to the calendar (for a clean rebuild). */
    fun deleteAllAppEvents(context: Context, calId: Long): Int =
        context.contentResolver.delete(
            Events.CONTENT_URI,
            "${Events.CALENDAR_ID} = ? AND ${Events.DESCRIPTION} LIKE ?",
            arrayOf(calId.toString(), "%[ccs-sync:%")
        )
}
