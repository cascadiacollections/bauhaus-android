package com.cascadiacollections.bauhaus.data

import android.graphics.Bitmap
import java.time.LocalDate

interface BauhausApiClient {
    suspend fun fetchTodayImage(maxWidth: Int = 0, maxHeight: Int = 0): Bitmap
    suspend fun fetchTodayImageRaw(): Pair<ByteArray, String>
    suspend fun fetchTodayMetadata(): ArtworkMetadata
    suspend fun fetchImageForDate(date: LocalDate, maxWidth: Int = 0, maxHeight: Int = 0): Bitmap
    suspend fun fetchImageRawForDate(date: LocalDate): Pair<ByteArray, String>
    suspend fun fetchMetadataForDate(date: LocalDate): ArtworkMetadata
}
