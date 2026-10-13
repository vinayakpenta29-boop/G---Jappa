package com.example.pricetagreader

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "price_tags")
data class PriceTag(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val no: String,
    val salesman: String,
    val barcode: String,
    val millRate: String,
    val billNo: String,
    val date: String,
    val jappa: String,
    val imagePath: String,
    val secondImagePath: String = "-", // NEW: Stores path for the second image
    val imageTitle: String = "Unknown",
    val imageDate: String = "Unknown"
)
