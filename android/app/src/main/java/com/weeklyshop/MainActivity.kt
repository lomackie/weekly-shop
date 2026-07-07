package com.weeklyshop

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * No submit button: ink is sent automatically once the pen has been idle for
 * [IDLE_MS]. Matched ink vanishes from the page (that is the confirmation);
 * ink the server can't parse stays put inside a dashed highlight until it is
 * rubbed out. The status line and the basket badge carry everything else.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var canvas: InkCanvasView
    private lateinit var status: TextView
    private lateinit var badge: TextView
    private lateinit var basketOverlay: View
    private lateinit var basketList: LinearLayout
    private lateinit var basketCount: TextView
    private lateinit var pageLeft: ImageButton
    private lateinit var pageRight: ImageButton
    private lateinit var pageIndicator: TextView
    private lateinit var api: ShopApi

    private var submitJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        api = ShopApi(getString(R.string.server_url))
        canvas = findViewById(R.id.ink_canvas)
        status = findViewById(R.id.status_text)
        badge = findViewById(R.id.basket_badge)
        basketOverlay = findViewById(R.id.basket_overlay)
        basketList = findViewById(R.id.basket_list)
        basketCount = findViewById(R.id.basket_count)

        pageLeft = findViewById(R.id.page_left)
        pageRight = findViewById(R.id.page_right)
        pageIndicator = findViewById(R.id.page_indicator)

        canvas.onStrokesChanged = {
            scheduleSubmit(IDLE_MS)
            updatePager()
        }
        canvas.onPageChanged = { updatePager() }
        canvas.onEntryErased = { entryId -> deleteErasedEntry(entryId) }
        pageLeft.setOnClickListener { canvas.goLeft() }
        pageRight.setOnClickListener { canvas.goRight() }

        findViewById<ImageButton>(R.id.basket_button).setOnClickListener { openBasket() }
        findViewById<ImageButton>(R.id.basket_close).setOnClickListener { closeBasket() }
        basketOverlay.setOnClickListener { closeBasket() }
        // Taps inside the panel must not fall through to the scrim.
        findViewById<View>(R.id.basket_panel).setOnClickListener { }

        // Keep the pen from inking over the toolbar icons and page chevrons.
        val toolbar = findViewById<View>(R.id.toolbar)
        toolbar.post {
            canvas.setExcludeRects(
                listOf(toolbar, pageLeft, pageRight).map {
                    Rect(it.left, it.top, it.right, it.bottom)
                }
            )
        }

        lifecycleScope.launch { refreshBadge() }
    }

    override fun onResume() {
        super.onResume()
        if (basketOverlay.visibility != View.VISIBLE) canvas.setActive(true)
    }

    override fun onPause() {
        canvas.setActive(false)
        super.onPause()
    }

    // ------------------------------------------------------------ sending

    private fun scheduleSubmit(delayMs: Long) {
        submitJob?.cancel()
        if (!canvas.hasFresh) return
        submitJob = lifecycleScope.launch {
            delay(delayMs)
            submit()
        }
    }

    private suspend fun submit() {
        for (batch in canvas.beginPendingBatches()) {
            try {
                val result = api.submitInk(batch.strokes)
                when (result.status) {
                    "matched" -> {
                        status.text = getString(R.string.status_added, result.itemName)
                        canvas.linkPending(batch.id, result.basketEntryId)
                        refreshBadge()
                    }
                    "ambiguous" -> {
                        canvas.linkPending(batch.id, result.basketEntryId)
                        refreshBadge()
                        pickCandidate(result)
                    }
                    else -> {
                        status.text = if (result.rawText.isBlank()) {
                            getString(R.string.status_unparsed_blank)
                        } else {
                            getString(R.string.status_unparsed, result.rawText)
                        }
                        canvas.failPending(batch.id, result.unparsedRegion)
                    }
                }
            } catch (e: Exception) {
                // Offline or server down: keep the ink and retry quietly.
                status.text = getString(R.string.status_offline)
                canvas.releasePending(batch.id)
                scheduleSubmit(RETRY_MS)
            }
        }
    }

    /** Ink of a basketed item was rubbed out: the entry goes with it. */
    private fun deleteErasedEntry(entryId: Int) {
        lifecycleScope.launch {
            try {
                api.deleteEntry(entryId)
            } catch (e: Exception) {
                status.text = getString(R.string.status_offline)
                canvas.flushDisplay()
            }
            refreshBadge()
        }
    }

    private fun updatePager() {
        // INVISIBLE, not GONE: the pen-exclusion rects need their bounds.
        pageLeft.visibility = if (canvas.canGoLeft) View.VISIBLE else View.INVISIBLE
        pageRight.visibility = if (canvas.canGoRight) View.VISIBLE else View.INVISIBLE
        if (canvas.pageCount > 1) {
            pageIndicator.text =
                getString(R.string.page_indicator, canvas.pageNumber, canvas.pageCount)
            pageIndicator.visibility = View.VISIBLE
        } else {
            pageIndicator.visibility = View.GONE
        }
    }

    /** Ambiguous match: offer the candidates; the server learns the choice. */
    private fun pickCandidate(result: ShopApi.InkResult) {
        val entryId = result.basketEntryId ?: return
        canvas.setActive(false)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pick_title, result.rawText))
            .setItems(result.candidates.map { it.name }.toTypedArray()) { _, which ->
                val chosen = result.candidates[which]
                lifecycleScope.launch {
                    status.text = try {
                        api.resolve(entryId, chosen.id)
                        getString(R.string.status_added, chosen.name)
                    } catch (e: Exception) {
                        getString(R.string.status_offline)
                    }
                }
            }
            .setNegativeButton(R.string.leave_as_is) { _, _ ->
                status.text = getString(R.string.status_kept, result.rawText)
            }
            .setOnDismissListener {
                if (basketOverlay.visibility != View.VISIBLE) canvas.setActive(true)
            }
            .show()
    }

    private suspend fun refreshBadge() {
        val count = try {
            api.basket().size
        } catch (e: Exception) {
            return // stale badge beats a scary error on the wall
        }
        badge.text = count.toString()
        badge.visibility = if (count == 0) View.GONE else View.VISIBLE
        canvas.flushDisplay() // badge sits over the frozen raw layer
    }

    // ------------------------------------------------------------- basket

    private fun openBasket() {
        submitJob?.cancel()
        canvas.setActive(false)
        basketList.removeAllViews()
        basketCount.text = ""
        basketOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                renderBasket(api.basket())
            } catch (e: Exception) {
                basketCount.text = getString(R.string.status_offline)
            }
        }
    }

    private fun closeBasket() {
        basketOverlay.visibility = View.GONE
        canvas.setActive(true)
        scheduleSubmit(IDLE_MS) // anything written just before opening still goes
    }

    private fun renderBasket(entries: List<ShopApi.BasketEntry>) {
        basketCount.text = entries.size.toString()
        basketList.removeAllViews()
        badge.text = entries.size.toString()
        badge.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        if (entries.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.basket_empty)
                textSize = 18f
                setPadding(0, 24, 0, 24)
            }
            basketList.addView(empty)
            return
        }

        for (entry in entries) {
            val row = layoutInflater.inflate(R.layout.basket_row, basketList, false)
            row.findViewById<TextView>(R.id.row_name).apply {
                if (entry.status == "matched") {
                    text = entry.displayName
                } else {
                    text = getString(R.string.basket_unresolved, entry.rawText)
                    alpha = 0.55f
                }
            }
            row.findViewById<ImageButton>(R.id.row_delete).setOnClickListener {
                lifecycleScope.launch {
                    try {
                        api.deleteEntry(entry.id)
                        canvas.removeEntryInk(entry.id)
                        renderBasket(api.basket())
                    } catch (e: Exception) {
                        basketCount.text = getString(R.string.status_offline)
                    }
                }
            }
            basketList.addView(row)
        }
    }

    companion object {
        /** Pen-idle time before ink is sent. Long enough for the gap between
         *  words of one item, short enough to feel automatic. */
        private const val IDLE_MS = 2000L
        private const val RETRY_MS = 8000L
    }
}
