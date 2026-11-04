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

    private fun tag(trip: TripParser.Trip) = "[ccs-sync:${trip.key()}]"

    /**
     * Puts the trip's duty days in the calendar, first removing what this app
     * added earlier for the same trip (same code, starting within a week), so a
     * newer screenshot after a change replaces the old events instead of
     * doubling them.
     */
    fun replaceTrip(context: Context, calId: Long, trip: TripParser.Trip): Int {
        val cr = context.contentResolver
        val events = TripParser.toEvents(trip)
        val firstStart = trip.duties[0].report.toInstant().toEpochMilli()
        val week = 7L * 24 * 60 * 60 * 1000

        cr.delete(
            Events.CONTENT_URI,
            "${Events.CALENDAR_ID} = ? AND ${Events.DESCRIPTION} LIKE ? AND ${Events.DTSTART} BETWEEN ? AND ?",
            arrayOf(
                calId.toString(), "%[ccs-sync:${trip.pairing}:%",
                (firstStart - week).toString(), (firstStart + week + 10 * 24 * 60 * 60 * 1000L).toString()
            )
        )

        for (e in events) {
            val v = ContentValues().apply {
                put(Events.CALENDAR_ID, calId)
                put(Events.TITLE, e.title)
                put(Events.DESCRIPTION, e.description + tag(trip))
                put(Events.EVENT_LOCATION, e.location)
                put(Events.DTSTART, e.start.toInstant().toEpochMilli())
                put(Events.DTEND, e.end.toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, e.start.zone.id)
                put(Events.EVENT_END_TIMEZONE, e.end.zone.id)
                put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            }
            cr.insert(Events.CONTENT_URI, v)
        }
        return events.size
    }
}
