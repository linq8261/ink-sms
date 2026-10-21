package com.ink.sms.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ink.sms.data.dao.RecycleBinDao
import com.ink.sms.data.entity.RecycledMessage

@Database(entities = [RecycledMessage::class], version = 1, exportSchema = false)
abstract class RecycleBinDatabase : RoomDatabase() {
    abstract fun dao(): RecycleBinDao
}
