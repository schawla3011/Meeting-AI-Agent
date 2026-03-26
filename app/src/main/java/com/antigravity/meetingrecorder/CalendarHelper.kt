package com.antigravity.meetingrecorder

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import java.util.Calendar
import java.util.Locale

/**
 * Helper to add a task/action-item to the device calendar (Google, Outlook, etc.)
 * via Android's standard Calendar Intent — no OAuth required.
 */
object CalendarHelper {

    private const val TAG = "CalendarHelper"

    /**
     * Opens the default calendar app with a pre-filled event for [taskText].
     * [deadlineText] is a natural-language string from GPT ("next Friday", "March 30", etc.)
     */
    fun addTaskToCalendar(context: Context, taskText: String, owner: String, deadlineText: String) {
        val startMillis = parseDeadline(deadlineText) ?: defaultStartMillis()
        val endMillis   = startMillis + 60 * 60 * 1000L  // 1-hour slot

        val description = buildString {
            append("Action item from meeting recording.\n\n")
            append("Owner: $owner\n")
            if (!deadlineText.equals("not specified", ignoreCase = true)) {
                append("Deadline: $deadlineText\n")
            }
            append("\nAdded by Anti Gravity Meeting Recorder")
        }

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data  = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE,       taskText)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME,   endMillis)
            putExtra(CalendarContract.Events.ALL_DAY, false)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "No calendar app found", e)
        }
    }

    /**
     * Attempts to parse common deadline formats from GPT output.
     * Returns epoch ms or null if unparseable.
     */
    private fun parseDeadline(text: String): Long? {
        if (text.isBlank() || text.equals("not specified", ignoreCase = true)) return null

        val lower = text.lowercase(Locale.getDefault()).trim()
        val now   = Calendar.getInstance()

        return when {
            lower.contains("today") -> {
                now.apply { set(Calendar.HOUR_OF_DAY, 17); set(Calendar.MINUTE, 0) }.timeInMillis
            }
            lower.contains("tomorrow") -> {
                now.apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) }.timeInMillis
            }
            lower.contains("next week") || lower.contains("end of week") -> {
                now.apply { add(Calendar.DAY_OF_YEAR, 7); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) }.timeInMillis
            }
            lower.contains("monday")    -> nextWeekday(Calendar.MONDAY)
            lower.contains("tuesday")   -> nextWeekday(Calendar.TUESDAY)
            lower.contains("wednesday") -> nextWeekday(Calendar.WEDNESDAY)
            lower.contains("thursday")  -> nextWeekday(Calendar.THURSDAY)
            lower.contains("friday")    -> nextWeekday(Calendar.FRIDAY)
            // Try to parse "Month Day" e.g. "March 30", "Apr 15"
            else -> parseMonthDay(lower)
        }
    }

    private fun nextWeekday(targetDay: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 9)
        cal.set(Calendar.MINUTE, 0)
        while (cal.get(Calendar.DAY_OF_WEEK) != targetDay) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
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
                val dayMatch = Regex("(\\d{1,2})").find(text) ?: continue
                val day = dayMatch.value.toIntOrNull() ?: continue
                val cal = Calendar.getInstance()
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, day)
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
                if (cal.timeInMillis < System.currentTimeMillis()) {
                    cal.add(Calendar.YEAR, 1)
                }
                return cal.timeInMillis
            }
        }
        return null
    }

    private fun defaultStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 9)
        cal.set(Calendar.MINUTE, 0)
        return cal.timeInMillis
    }
}
