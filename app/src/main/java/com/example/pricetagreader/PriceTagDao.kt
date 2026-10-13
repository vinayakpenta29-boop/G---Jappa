package com.example.pricetagreader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PriceTagDao {
    @Query("SELECT * FROM price_tags ORDER BY id ASC")
    suspend fun getAllTags(): List<PriceTag>

    // Smart query that ignores hyphens and spaces when matching barcodes
    @Query("SELECT * FROM price_tags WHERE REPLACE(REPLACE(barcode, '-', ''), ' ', '') = :cleanBarcode LIMIT 1")
    suspend fun getTagByCleanBarcode(cleanBarcode: String): PriceTag?

    @Insert
    suspend fun insertTag(tag: PriceTag)

    @Update
    suspend fun updateTag(tag: PriceTag)

    @Query("DELETE FROM price_tags WHERE id IN (:ids)")
    suspend fun deleteTagsByIds(ids: List<Int>)

    @Query("DELETE FROM price_tags")
    suspend fun deleteAllTags()
}
