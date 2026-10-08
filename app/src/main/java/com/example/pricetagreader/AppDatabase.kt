package com.example.pricetagreader

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PriceTag::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun priceTagDao(): PriceTagDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Safe Migration from Version 2 to 3 to prevent data loss
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE price_tags ADD COLUMN imageTitle TEXT NOT NULL DEFAULT 'Unknown'")
                database.execSQL("ALTER TABLE price_tags ADD COLUMN imageDate TEXT NOT NULL DEFAULT 'Unknown'")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "price_tag_database"
                )
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
