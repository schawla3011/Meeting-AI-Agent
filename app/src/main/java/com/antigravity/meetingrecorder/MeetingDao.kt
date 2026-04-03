package com.antigravity.meetingrecorder

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MeetingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(meeting: Meeting): Long

    @Query("SELECT * FROM meetings ORDER BY id DESC")
    suspend fun getAllMeetings(): List<Meeting>

    @Query("SELECT * FROM meetings WHERE id = :id LIMIT 1")
    suspend fun getMeetingById(id: Long): Meeting?

    @Query("SELECT COUNT(*) FROM meetings")
    suspend fun count(): Int
}
