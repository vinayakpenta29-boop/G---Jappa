package com.example.pricetagreader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PriceTagDao {
    @Query("SELECT * FROM price_tags ORDER BY id ASC")
    suspend fun getAllTags(): List<PriceTag>

    @Insert
    suspend fun insertTag(tag: PriceTag)

    @Query("DELETE FROM price_tags")
    suspend fun deleteAllTags()
}
