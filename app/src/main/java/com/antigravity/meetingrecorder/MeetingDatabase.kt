package com.antigravity.meetingrecorder

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Meeting::class], version = 1, exportSchema = false)
abstract class MeetingDatabase : RoomDatabase() {

    abstract fun meetingDao(): MeetingDao

    companion object {
        @Volatile private var INSTANCE: MeetingDatabase? = null

        fun getInstance(context: Context): MeetingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeetingDatabase::class.java,
                    "pravah_meetings.db"
                ).fallbackToDestructiveMigration()
                 .build()
                 .also { INSTANCE = it }
            }
    }
}
