package io.github.blacksamdev.popcorn.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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
 * Lecture d'un flux combiné OU de deux pistes séparées (vidéo + audio),
 * synchronisées par MergingMediaSource — même principe que mpv côté desktop.
 * YouTube ne propose plus de format combiné au-delà de 360p : c'est ainsi
 * qu'on obtient le 1080p avec le son.
 *
 * Tampons : les valeurs par défaut d'ExoPlayer sont trop justes pour deux
 * flux chargés en parallèle, dont une vidéo à fort débit. On les élargit
 * nettement pour éviter les coupures après quelques secondes de lecture.
 */
@UnstableApi
class BbsPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    companion object {
        // Tampon visé avant de considérer le chargement suffisant
        private const val MIN_BUFFER_MS = 50_000
        private const val MAX_BUFFER_MS = 120_000
        // Tampon requis pour (re)démarrer la lecture
        private const val BUFFER_FOR_PLAYBACK_MS = 3_000
        private const val BUFFER_AFTER_REBUFFER_MS = 10_000
        private const val HTTP_TIMEOUT_MS = 15_000
    }

    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_AFTER_REBUFFER_MS,
        )
        // Privilégier la durée de tampon plutôt qu'une limite en octets :
        // un flux 1080p atteindrait le plafond d'octets avant d'avoir
        // accumulé assez de secondes de lecture.
        .setPrioritizeTimeOverSizeThresholds(true)
        .setTargetBufferBytes(C.LENGTH_UNSET)
        .build()

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .build()

    private var sponsorSegments: List<SponsorBridge.SponsorSegment> = emptyList()
    private var sponsorJob: Job? = null
    var onStatusChange: ((String) -> Unit)? = null

    /**
     * Fabrique réseau : timeouts explicites et redirections autorisées.
     * Les serveurs de YouTube redirigent fréquemment ; sans cela, une
     * lecture peut se bloquer sur une réponse intermédiaire.
     */
    private fun dataSourceFactory(): DefaultDataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(HTTP_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_TIMEOUT_MS)
        return DefaultDataSource.Factory(context, http)
    }

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
            val factory = dataSourceFactory()
            val video = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(streamUrl))
            val audio = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(audioUrl))
            exoPlayer.setMediaSource(MergingMediaSource(video, audio))
        } else {
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
