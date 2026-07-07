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

    data class Candidate(val id: Int, val name: String)

    /** One handwritten line of a submission, recognised as one item. */
    data class InkLine(
        val rawText: String,
        val status: String, // matched | ambiguous | unmatched
        val itemName: String?,
        val candidates: List<Candidate>,
        val basketEntryId: Int?, // null when unmatched: nothing was basketed
        val strokeIndices: List<Int>, // which submitted strokes form this line
    )

    data class BasketEntry(
        val id: Int,
        val rawText: String,
        val status: String,
        val itemName: String?,
    ) {
        val displayName: String get() = itemName ?: rawText
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /** The server splits the ink into lines (one item each) and reports on
     *  every line; strokeIndices map each outcome back to the sent strokes. */
    suspend fun submitInk(strokes: JSONArray): List<InkLine> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("strokes", strokes)
        val payload = execute(
            Request.Builder()
                .url("$baseUrl/ink")
                .post(body.toString().toRequestBody(json))
                .build()
        )
        val lines = payload.getJSONArray("lines")
        (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val candidates = line.getJSONArray("candidates")
            val indices = line.getJSONArray("stroke_indices")
            InkLine(
                rawText = line.getString("raw_text"),
                status = line.getString("status"),
                itemName = line.optJSONObject("item")?.getString("name"),
                candidates = (0 until candidates.length()).map {
                    val c = candidates.getJSONObject(it)
                    Candidate(c.getInt("id"), c.getString("name"))
                },
                basketEntryId = if (line.isNull("basket_entry_id")) null
                                else line.getInt("basket_entry_id"),
                strokeIndices = (0 until indices.length()).map { indices.getInt(it) },
            )
        }
    }

    /** Fix up an ambiguous/unmatched entry; the server learns the alias. */
    suspend fun resolve(entryId: Int, itemId: Int): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().put("item_id", itemId)
        execute(
            Request.Builder()
                .url("$baseUrl/basket/$entryId/resolve")
                .post(body.toString().toRequestBody(json))
                .build()
        )
    }

    suspend fun basket(): List<BasketEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/basket").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("server returned ${response.code}")
            val entries = JSONArray(response.body!!.string())
            (0 until entries.length()).map {
                val e = entries.getJSONObject(it)
                BasketEntry(
                    id = e.getInt("id"),
                    rawText = e.getString("raw_text"),
                    status = e.getString("status"),
                    itemName = if (e.isNull("item_name")) null else e.getString("item_name"),
                )
            }
        }
    }

    suspend fun deleteEntry(entryId: Int): Unit = withContext(Dispatchers.IO) {
        execute(Request.Builder().url("$baseUrl/basket/$entryId").delete().build())
    }

    private fun execute(request: Request): JSONObject =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("server returned ${response.code}")
            JSONObject(response.body!!.string())
        }
}
