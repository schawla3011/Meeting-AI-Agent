package com.antigravity.meetingrecorder

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object CalendarHelper {

    private const val TAG = "CalendarHelper"

    // ── Silent batch insert (Option A) ────────────────────────────────────
    /**
     * Inserts all tasks directly into the device's primary calendar
     * (Google Calendar, Outlook, Samsung — whatever is synced) without
     * opening any dialog. Requires WRITE_CALENDAR permission.
     * Returns the number of events successfully inserted.
     */
    fun addAllTasksSilently(
        context: Context,
        tasks: List<Triple<String, String, String>>  // task, owner, deadline
    ): Int {
        val calId = getPrimaryCalendarId(context) ?: run {
            Log.w(TAG, "No calendar found on device")
            return 0
        }
        var count = 0
        for ((taskText, owner, deadlineText) in tasks) {
            if (taskText.isBlank()) continue
            val startMs = parseDeadline(deadlineText) ?: defaultStartMillis()
            val endMs   = startMs + 60 * 60 * 1000L

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID,    calId)
                put(CalendarContract.Events.TITLE,          taskText)
                put(CalendarContract.Events.DESCRIPTION,
                    "Owner: $owner\n\nAdded automatically by Pravah AI.")
                put(CalendarContract.Events.DTSTART,        startMs)
                put(CalendarContract.Events.DTEND,          endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            try {
                context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                count++
                Log.i(TAG, "Inserted: $taskText")
            } catch (e: Exception) {
                Log.e(TAG, "Insert failed for: $taskText — $e")
            }
        }
        return count
    }

    /** Returns the _ID of the primary visible calendar, or null if none found. */
    private fun getPrimaryCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"
        val sortOrder = "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, selection, null, sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst())
                cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
            else null
        }
    }

    // ── Intent-based fallback ─────────────────────────────────────────────
    /** Opens the calendar app with a pre-filled event (used when permission denied). */
    fun addTaskToCalendar(context: Context, taskText: String, owner: String, deadlineText: String) {
        val startMs = parseDeadline(deadlineText) ?: defaultStartMillis()
        val endMs   = startMs + 60 * 60 * 1000L
        val description = buildString {
            append("Action item from Pravah AI meeting.\n\nOwner: $owner")
            if (!deadlineText.equals("not specified", ignoreCase = true))
                append("\nDeadline: $deadlineText")
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE,                    taskText)
            putExtra(CalendarContract.Events.DESCRIPTION,              description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,          startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME,            endMs)
        }
        try { context.startActivity(intent) }
        catch (e: Exception) { Log.e(TAG, "No calendar app found", e) }
    }

    // ── Date parsing helpers ──────────────────────────────────────────────
    private fun parseDeadline(text: String): Long? {
        if (text.isBlank() || text.equals("not specified", ignoreCase = true)) return null
        val lower = text.lowercase(Locale.getDefault()).trim()
        val now   = Calendar.getInstance()
        return when {
            lower.contains("today")     -> now.apply { set(Calendar.HOUR_OF_DAY, 17); set(Calendar.MINUTE, 0) }.timeInMillis
            lower.contains("tomorrow")  -> now.apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) }.timeInMillis
            lower.contains("next week") || lower.contains("end of week") ->
                now.apply { add(Calendar.DAY_OF_YEAR, 7); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) }.timeInMillis
            lower.contains("monday")    -> nextWeekday(Calendar.MONDAY)
            lower.contains("tuesday")   -> nextWeekday(Calendar.TUESDAY)
            lower.contains("wednesday") -> nextWeekday(Calendar.WEDNESDAY)
            lower.contains("thursday")  -> nextWeekday(Calendar.THURSDAY)
            lower.contains("friday")    -> nextWeekday(Calendar.FRIDAY)
            else -> parseMonthDay(lower)
        }
    }

    private fun nextWeekday(targetDay: Int): Long {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) }
        while (cal.get(Calendar.DAY_OF_WEEK) != targetDay) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private val MONTHS = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3,
        "may" to 4, "jun" to 5, "jul" to 6, "aug" to 7,
        "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11
    )

    private fun parseMonthDay(text: String): Long? {
        for ((abbr, month) in MONTHS) {
            if (text.contains(abbr)) {
                val day = Regex("(\\d{1,2})").find(text)?.value?.toIntOrNull() ?: continue
                val cal = Calendar.getInstance().apply {
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                }
                if (cal.timeInMillis < System.currentTimeMillis()) cal.add(Calendar.YEAR, 1)
                return cal.timeInMillis
            }
        }
        return null
    }

    private fun defaultStartMillis(): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
}
