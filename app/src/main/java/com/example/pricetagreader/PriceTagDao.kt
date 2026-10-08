package com.example.pricetagreader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PriceTagDao {
    @Query("SELECT * FROM price_tags ORDER BY id ASC")
    suspend fun getAllTags(): List<PriceTag>

    @Insert
    suspend fun insertTag(tag: PriceTag)

    // NEW: Function to update an existing row in the database
    @Update
    suspend fun updateTag(tag: PriceTag)

    @Query("DELETE FROM price_tags")
    suspend fun deleteAllTags()
}
