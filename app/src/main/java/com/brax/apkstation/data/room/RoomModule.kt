package com.brax.apkstation.data.room

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private const val STORE_DB = "store.db"

/**
 * DATABASE MIGRATION GUIDE
 * 
 * When modifying the database schema (adding/removing columns, tables, etc.):
 * 
 * 1. Update the entity classes (e.g., DBApplication.kt, Download.kt)
 * 2. Increment the version number in @Database annotation (StoreDatabase.kt)
 * 3. Create a new Migration object below (e.g., MIGRATION_6_7)
 * 4. Write SQL to transform old schema to new schema
 * 5. Add the migration to .addMigrations() in getStoreDB()
 * 
 * Example for adding a column:
 *   db.execSQL("ALTER TABLE table_name ADD COLUMN column_name TYPE DEFAULT value")
 * 
 * Example for creating a table:
 *   db.execSQL("CREATE TABLE IF NOT EXISTS new_table (id INTEGER PRIMARY KEY, ...)")
 * 
 * IMPORTANT: Never delete old migrations - users may upgrade from any version!
 */

/**
 * Migration from version 5 to 6: Add isFavorite column to application table
 */
//val MIGRATION_5_6 = object : Migration(5, 6) {
//    override fun migrate(db: SupportSQLiteDatabase) {
//        // Add isFavorite column with default value of 0 (false)
//        db.execSQL("ALTER TABLE application ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
//    }
//}

@Module
@InstallIn(SingletonComponent::class)
class RoomModule {

    @Singleton
    @Provides
    fun getStoreDB(@ApplicationContext context: Context) =
        Room.databaseBuilder(context, StoreDatabase::class.java, STORE_DB)
//            .addMigrations(MIGRATION_5_6) // Add migration to preserve data
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = false) // Only as last resort for unmigrated versions
            .allowMainThreadQueries()
            .build()

    @Provides
    fun providesStoreDao(storeDatabase: StoreDatabase) = storeDatabase.storeDao()
}
