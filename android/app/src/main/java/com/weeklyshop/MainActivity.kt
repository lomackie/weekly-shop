package com.weeklyshop

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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

        findViewById<Button>(R.id.basket_button).setOnClickListener { showBasket() }
        findViewById<Button>(R.id.undo_button).setOnClickListener { canvas.undo() }
        findViewById<Button>(R.id.clear_button).setOnClickListener {
            canvas.clear()
            status.text = getString(R.string.prompt)
        }
        findViewById<Button>(R.id.done_button).setOnClickListener { submit() }
    }

    override fun onResume() {
        super.onResume()
        canvas.setActive(true)
    }

    override fun onPause() {
        canvas.setActive(false)
        super.onPause()
    }

    private fun submit() {
        if (canvas.isEmpty) return
        val strokes = canvas.strokesAsJson()
        status.text = getString(R.string.sending)

        lifecycleScope.launch {
            try {
                val result = api.submitInk(strokes)
                canvas.clear()
                when (result.status) {
                    "matched" -> status.text = getString(R.string.added, result.itemName)
                    "ambiguous" -> pickCandidate(result)
                    else -> status.text = getString(R.string.unmatched, result.rawText)
                }
            } catch (e: Exception) {
                status.text = getString(R.string.send_failed, e.message)
            }
        }
    }

    /** Ambiguous match: offer the candidates; the server learns the choice. */
    private fun pickCandidate(result: ShopApi.InkResult) {
        status.text = getString(R.string.ambiguous, result.rawText)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pick_title, result.rawText))
            .setItems(result.candidates.map { it.name }.toTypedArray()) { _, which ->
                val chosen = result.candidates[which]
                lifecycleScope.launch {
                    status.text = try {
                        api.resolve(result.basketEntryId, chosen.id)
                        getString(R.string.added, chosen.name)
                    } catch (e: Exception) {
                        getString(R.string.send_failed, e.message)
                    }
                }
            }
            .setNegativeButton(R.string.leave_as_is) { _, _ ->
                status.text = getString(R.string.unmatched, result.rawText)
            }
            .show()
    }

    private fun showBasket() {
        lifecycleScope.launch {
            try {
                val entries = api.basket()
                if (entries.isEmpty()) {
                    status.text = getString(R.string.basket_empty)
                    return@launch
                }
                val labels = entries.map { entry ->
                    when (entry.status) {
                        "matched" -> entry.displayName
                        else -> getString(R.string.basket_unresolved, entry.rawText)
                    }
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.basket_title, entries.size))
                    .setItems(labels.toTypedArray()) { _, which ->
                        confirmDelete(entries[which])
                    }
                    .setNegativeButton(R.string.close, null)
                    .show()
            } catch (e: Exception) {
                status.text = getString(R.string.send_failed, e.message)
            }
        }
    }

    private fun confirmDelete(entry: ShopApi.BasketEntry) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, entry.displayName))
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch {
                    try {
                        api.deleteEntry(entry.id)
                        status.text = getString(R.string.removed, entry.displayName)
                        showBasket()
                    } catch (e: Exception) {
                        status.text = getString(R.string.send_failed, e.message)
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> showBasket() }
            .show()
    }
}
