package com.weeklyshop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ShopApi(private val baseUrl: String) {

    data class InkResult(
        val rawText: String,
        val status: String, // matched | ambiguous | unmatched
        val itemName: String?,
        val candidateNames: List<String>,
    )

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun submitInk(strokes: JSONArray): InkResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("strokes", strokes)
        val request = Request.Builder()
            .url("$baseUrl/ink")
            .post(body.toString().toRequestBody(json))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("server returned ${response.code}")
            val payload = JSONObject(response.body!!.string())
            val candidates = payload.getJSONArray("candidates")
            InkResult(
                rawText = payload.getString("raw_text"),
                status = payload.getString("status"),
                itemName = payload.optJSONObject("item")?.getString("name"),
                candidateNames = (0 until candidates.length()).map {
                    candidates.getJSONObject(it).getString("name")
                },
            )
        }
    }
}
