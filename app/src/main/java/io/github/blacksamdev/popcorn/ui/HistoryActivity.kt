package io.github.blacksamdev.popcorn.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.blacksamdev.popcorn.R
import io.github.blacksamdev.popcorn.bridge.HistoryBridge
import io.github.blacksamdev.popcorn.bridge.YtdlpBridge
import io.github.blacksamdev.popcorn.databinding.ActivityHistoryBinding
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * HistoryActivity — historique des lectures pOpcOrn.
 * Clic sur une entrée → résolution yt-dlp → PlayerActivity.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val adapter = HistoryAdapter(::onEntryClicked)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.history_clear_title))
                .setMessage(getString(R.string.history_clear_message))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        HistoryBridge.clear()
                        loadHistory()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val entries = HistoryBridge.entries()
            adapter.submit(entries)
            binding.textEmpty.visibility =
                if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun onEntryClicked(entry: HistoryBridge.HistoryEntry) {
        binding.loadingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            val info = YtdlpBridge.fetchInfo(entry.url)
            binding.loadingOverlay.visibility = View.GONE

            if (info == null) {
                Toast.makeText(
                    this@HistoryActivity,
                    getString(R.string.main_resolve_error),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            HistoryBridge.add(entry.url, info.title)

            val playerIntent = Intent(this@HistoryActivity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL, info.streamUrl)
                putExtra(PlayerActivity.EXTRA_TITLE, info.title)
                putExtra(PlayerActivity.EXTRA_SOURCE_URL, entry.url)
            }
            startActivity(playerIntent)
        }
    }

    // ─────────────────────────────
    // Adapter
    // ─────────────────────────────

    private class HistoryAdapter(
        private val onClick: (HistoryBridge.HistoryEntry) -> Unit,
    ) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        private var items: List<HistoryBridge.HistoryEntry> = emptyList()

        fun submit(newItems: List<HistoryBridge.HistoryEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.textItemTitle)
            val date: TextView = view.findViewById(R.id.textItemDate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.title.text = entry.title
            holder.date.text = if (entry.timestampS > 0) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(entry.timestampS * 1000))
            } else {
                ""
            }
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        override fun getItemCount(): Int = items.size
    }
}
