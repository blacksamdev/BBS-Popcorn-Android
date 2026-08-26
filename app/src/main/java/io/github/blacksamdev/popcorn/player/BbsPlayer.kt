package io.github.blacksamdev.popcorn.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
 * - Lecture d'un flux combiné OU de deux pistes séparées (vidéo + audio),
 *   synchronisées par MergingMediaSource — même principe que mpv côté desktop.
 *   YouTube ne propose plus de format combiné au-delà de 360p : c'est ainsi
 *   qu'on obtient le 1080p avec le son.
 * - Reprise de lecture (position de départ)
 * - Skip automatique des segments SponsorBlock
 */
@UnstableApi
class BbsPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private var sponsorSegments: List<SponsorBridge.SponsorSegment> = emptyList()
    private var sponsorJob: Job? = null
    var onStatusChange: ((String) -> Unit)? = null

    // ─── Lecture ──────────────────────────────────────────────────────

    /**
     * @param streamUrl piste vidéo (ou flux combiné si audioUrl est vide)
     * @param audioUrl piste audio séparée, vide si l'audio est déjà inclus
     */
    fun play(
        streamUrl: String,
        audioUrl: String = "",
        segments: List<SponsorBridge.SponsorSegment> = emptyList(),
        startPositionMs: Long = 0L,
    ) {
        sponsorSegments = segments

        if (audioUrl.isNotEmpty()) {
            // Pistes séparées : ExoPlayer les synchronise
            val factory = DefaultDataSource.Factory(context)
            val video = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(streamUrl))
            val audio = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(audioUrl))
            exoPlayer.setMediaSource(MergingMediaSource(video, audio))
        } else {
            // Flux unique (combiné ou HLS) : détection automatique
            exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
        }

        exoPlayer.prepare()
        if (startPositionMs > 0) {
            exoPlayer.seekTo(startPositionMs)
        }
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
