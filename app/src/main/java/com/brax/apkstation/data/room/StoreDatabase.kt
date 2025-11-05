package com.brax.apkstation.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import com.brax.apkstation.data.room.dao.StoreDao
import com.brax.apkstation.data.room.entity.DBApplication
import com.brax.apkstation.data.room.entity.Download

@Database(entities = [Download::class, DBApplication::class], version = 1, exportSchema = false)
abstract class StoreDatabase : RoomDatabase() {
    abstract fun storeDao(): StoreDao
}