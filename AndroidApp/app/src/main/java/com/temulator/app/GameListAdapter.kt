package com.temulator.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.temulator.app.data.db.GameRecord

class GameListAdapter(
    private val onGameClick: (GameRecord) -> Unit,
    private val onGameLongClick: (GameRecord) -> Unit,
    private val onBackupClick: (GameRecord) -> Unit
) : ListAdapter<GameRecord, GameListAdapter.GameViewHolder>(GameDiffCallback) {

    object GameDiffCallback : DiffUtil.ItemCallback<GameRecord>() {
        override fun areItemsTheSame(oldItem: GameRecord, newItem: GameRecord): Boolean {
            return oldItem.gameId == newItem.gameId
        }

        override fun areContentsTheSame(oldItem: GameRecord, newItem: GameRecord): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_game_card, parent, false)
        return GameViewHolder(view)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val game = getItem(position)
        holder.bind(game)
    }

    inner class GameViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvBadge: TextView = itemView.findViewById(R.id.tvConsoleBadge)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvGameTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvGameSubtitle)
        private val tvConsoleTag: TextView? = itemView.findViewById(R.id.tvConsoleTag)
        private val btnCardBackup: MaterialButton = itemView.findViewById(R.id.btnCardBackup)

        fun bind(game: GameRecord) {
            val context = itemView.context
            tvBadge.text = getConsoleBadge(game.consoleType)
            tvTitle.text = game.displayName

            val sourceLabel = when (game.source) {
                "scan" -> context.getString(R.string.games_source_scan)
                "restore" -> "Cloud"
                else -> context.getString(R.string.games_source_manual)
            }

            if (tvConsoleTag != null) {
                tvConsoleTag.text = getConsoleShortCode(game.consoleType)
                tvSubtitle.text = "• $sourceLabel"
            } else {
                tvSubtitle.text = "${getConsoleLabel(game.consoleType)} • $sourceLabel"
            }

            itemView.setOnClickListener {
                onGameClick(game)
            }

            itemView.setOnLongClickListener {
                onGameLongClick(game)
                true
            }

            btnCardBackup.setOnClickListener {
                onBackupClick(game)
            }
        }
    }

    companion object {
        fun getConsoleBadge(type: Int): String {
            return when (type) {
                0 -> "🎮"
                1 -> "🕹️"
                2 -> "🎮"
                3 -> "⚡"
                4 -> "🎯"
                5 -> "📱"
                6 -> "🦢"
                else -> "👾"
            }
        }

        fun getConsoleLabel(type: Int): String {
            return when (type) {
                0 -> "SNES (.sfc)"
                1 -> "GB / GBC"
                2 -> "NES (.nes)"
                3 -> "PCE (.pce)"
                4 -> "SMS (.sms)"
                5 -> "GBA (.gba)"
                6 -> "WS (.ws)"
                else -> "ROM"
            }
        }

        fun getConsoleShortCode(type: Int): String {
            return when (type) {
                0 -> "SNES"
                1 -> "GB/GBC"
                2 -> "NES"
                3 -> "PCE"
                4 -> "SMS"
                5 -> "GBA"
                6 -> "WS"
                else -> "ROM"
            }
        }
    }
}
