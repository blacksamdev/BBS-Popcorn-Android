package io.github.blacksamdev.popcorn.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.blacksamdev.popcorn.bridge.SponsorBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BbsPlayer — wrapper Media3/ExoPlayer pour BBS Popcorn Android.
 *
 * Gère :
 * - Lecture d'un stream URL direct (résolu par YtdlpBridge)
 * - Skip automatique des segments SponsorBlock
 * - Callbacks de statut vers le ViewModel
 */
class BbsPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private var sponsorSegments: List<SponsorBridge.SponsorSegment> = emptyList()
    private var sponsorJob: Job? = null
    var onStatusChange: ((String) -> Unit)? = null

    // ─── Lecture ──────────────────────────────────────────────────────

    fun play(streamUrl: String, segments: List<SponsorBridge.SponsorSegment> = emptyList()) {
        sponsorSegments = segments
        val mediaItem = MediaItem.fromUri(streamUrl)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
        startSponsorWatcher()
        onStatusChange?.invoke("Lecture en cours.")
    }

    fun pause() {
        exoPlayer.pause()
        onStatusChange?.invoke("En pause.")
    }

    fun resume() {
        exoPlayer.play()
        onStatusChange?.invoke("Lecture en cours.")
    }

    fun stop() {
        sponsorJob?.cancel()
        exoPlayer.stop()
        onStatusChange?.invoke("Arrêté.")
    }

    fun release() {
        sponsorJob?.cancel()
        exoPlayer.release()
    }

    // ─── SponsorBlock ─────────────────────────────────────────────────

    /**
     * Polling toutes les 500ms — si la position courante tombe dans un segment,
     * on seek à la fin du segment.
     */
    private fun startSponsorWatcher() {
        sponsorJob?.cancel()
        if (sponsorSegments.isEmpty()) return

        sponsorJob = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(500)
                if (!exoPlayer.isPlaying) continue
                val posMs = exoPlayer.currentPosition
                val hit = sponsorSegments.firstOrNull { seg ->
                    posMs >= seg.startMs && posMs < seg.endMs
                }
                if (hit != null) {
                    exoPlayer.seekTo(hit.endMs)
                    onStatusChange?.invoke("Segment '${hit.category}' skippé.")
                }
            }
        }
    }

    // ─── État ─────────────────────────────────────────────────────────

    val isPlaying: Boolean get() = exoPlayer.isPlaying
    val currentPositionMs: Long get() = exoPlayer.currentPosition
    val durationMs: Long get() = exoPlayer.duration

    fun addListener(listener: Player.Listener) {
        exoPlayer.addListener(listener)
    }
}
