package com.antigravity.meetingrecorder

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single meeting record stored locally via Room (SQLite).
 */
@Entity(tableName = "meetings")
data class Meeting(
    @PrimaryKey
    val id:           Long   = System.currentTimeMillis(),
    val date:         String = "",
    val filename:     String = "",
    val durationSecs: Int    = 0,
    val summary:      String = "",
    val tasksJson:    String = "[]",
    val transcript:   String = ""
)
