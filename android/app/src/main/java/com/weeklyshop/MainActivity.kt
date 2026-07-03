package com.weeklyshop

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var canvas: InkCanvasView
    private lateinit var status: TextView
    private lateinit var api: ShopApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        api = ShopApi(getString(R.string.server_url))
        canvas = findViewById(R.id.ink_canvas)
        status = findViewById(R.id.status_text)

        findViewById<Button>(R.id.clear_button).setOnClickListener {
            canvas.clear()
            status.text = getString(R.string.prompt)
        }

        findViewById<Button>(R.id.done_button).setOnClickListener { submit() }
    }

    private fun submit() {
        if (canvas.isEmpty) return
        val strokes = canvas.strokesAsJson()
        status.text = getString(R.string.sending)

        lifecycleScope.launch {
            status.text = try {
                val result = api.submitInk(strokes)
                canvas.clear()
                when (result.status) {
                    "matched" -> getString(R.string.added, result.itemName)
                    // TODO: replace with a multiple-choice picker that calls
                    // POST /basket/{id}/resolve with the chosen candidate.
                    "ambiguous" -> getString(
                        R.string.ambiguous,
                        result.rawText,
                        result.candidateNames.joinToString(" / "),
                    )
                    else -> getString(R.string.unmatched, result.rawText)
                }
            } catch (e: Exception) {
                getString(R.string.send_failed, e.message)
            }
        }
    }
}
