package com.nibhaus.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.TimeZone

/** A writable calendar from a device-synced account (Google, Microsoft 365/Exchange, …). */
data class CalendarTarget(val id: Long, val account: String, val displayName: String)

/**
 * Creates events in the user's existing device calendars via the system [CalendarContract] — no
 * OAuth and no network from this app. The OS syncs the event up to whatever account owns the
 * calendar (Gmail, Microsoft 365, …). Requires READ_CALENDAR (to list) and WRITE_CALENDAR (to add).
 *
 * Note: deliberately the native path instead of per-provider OAuth + Graph/Calendar REST — it
 * covers Google and Outlook/Exchange at once with zero credentials. Limitation: a calendar that an
 * app keeps private (e.g. some Outlook-app accounts) won't appear; add it as a system account.
 */
class CalendarGateway(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    /** Calendars the user can add events to (access CONTRIBUTOR+). Empty without permission. */
    fun writableCalendars(): List<CalendarTarget> = runCatching {
        val cols = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val out = mutableListOf<CalendarTarget>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                out += CalendarTarget(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "Calendar")
            }
        }
        out
    }.getOrDefault(emptyList())

    /** Insert a timed or all-day event; returns the new event id, or null on failure/no permission. */
    fun insertEvent(
        calendarId: Long,
        title: String,
        startMs: Long,
        endMs: Long,
        allDay: Boolean = false,
        notes: String? = null,
        location: String? = null,
    ): Long? = runCatching {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
            notes?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        ContentUris.parseId(uri)
    }.getOrNull()
}
