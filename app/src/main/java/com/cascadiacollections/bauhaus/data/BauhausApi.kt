package com.cascadiacollections.bauhaus.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class ArtworkMetadata(
    val title: String = "",
    val artist: String = "",
    val source: String = "",
    val license: String = "",
    val date: String = "",
)

class BauhausApi(private val client: OkHttpClient = OkHttpClient()) {

    companion object {
        private const val BASE_URL = "https://bauhaus.cascadiacollections.workers.dev"
    }

    suspend fun fetchTodayImage(): Bitmap = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/api/today")
            .header("Accept", "image/avif, image/webp, image/jpeg")
            .build()
        val response = client.newCall(request).execute()
        val bytes = response.body.bytes()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Failed to decode image")
    }

    suspend fun fetchTodayMetadata(): ArtworkMetadata = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/api/today.json")
            .build()
        val response = client.newCall(request).execute()
        json.decodeFromString<ArtworkMetadata>(response.body.string())
    }
}
