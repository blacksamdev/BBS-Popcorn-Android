package io.github.blacksamdev.popcorn.player

import android.content.Context
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/**
 * CastManager — intégration Chromecast via Cast SDK Google.
 *
 * Gère :
 * - Détection de session Cast active
 * - Envoi du stream URL vers le Chromecast
 * - Détection du type de flux (HLS vs MP4)
 * - Contrôles de lecture : play/pause/stop, seek absolu, seek relatif (±N s)
 * - Contrôle du volume (barre + boutons physiques)
 * - Suivi de progression en temps réel (position / durée)
 * - Lecture du titre du média en cours (pour rouvrir la télécommande)
 * - Callbacks connect/disconnect
 */
class CastManager(context: Context) {

    private val castContext = CastContext.getSharedInstance(context)
    private var castSession: CastSession? = null

    var onCastConnected: (() -> Unit)? = null
    var onCastDisconnected: (() -> Unit)? = null

    /** Callback de progression : (positionMs, durationMs). */
    var onProgress: ((Long, Long) -> Unit)? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            castSession = session
            attachProgressListener()
            onCastConnected?.invoke()
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            detachProgressListener()
            castSession = null
            onCastDisconnected?.invoke()
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            castSession = session
            attachProgressListener()
            onCastConnected?.invoke()
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private val progressListener =
        RemoteMediaClient.ProgressListener { positionMs, durationMs ->
            onProgress?.invoke(positionMs, durationMs)
        }

    private fun attachProgressListener() {
        castSession?.remoteMediaClient?.addProgressListener(progressListener, 1000L)
    }

    private fun detachProgressListener() {
        castSession?.remoteMediaClient?.removeProgressListener(progressListener)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────

    fun register() {
        castContext.sessionManager.addSessionManagerListener(
            sessionListener, CastSession::class.java
        )
        castSession = castContext.sessionManager.currentCastSession
        if (castSession != null) attachProgressListener()
    }

    fun unregister() {
        detachProgressListener()
        castContext.sessionManager.removeSessionManagerListener(
            sessionListener, CastSession::class.java
        )
    }

    // ─── État session ─────────────────────────────────────────────────

    val isConnected: Boolean get() = castSession?.isConnected == true

    val deviceName: String?
        get() = castSession?.castDevice?.friendlyName

    /** Vrai si une session est active ET un média est chargé. */
    fun hasActiveMedia(): Boolean {
        val rmc = castSession?.remoteMediaClient ?: return false
        return rmc.hasMediaSession()
    }

    /** Vrai si le média en cours sur le Chromecast est un direct. */
    fun isLiveStream(): Boolean {
        val info = castSession?.remoteMediaClient?.mediaInfo ?: return false
        return info.streamType == MediaInfo.STREAM_TYPE_LIVE
    }

    /** Titre du média en cours sur le Chromecast (vide si indisponible). */
    fun currentMediaTitle(): String {
        val rmc = castSession?.remoteMediaClient ?: return ""
        val info = rmc.mediaInfo ?: return ""
        return info.metadata?.getString(MediaMetadata.KEY_TITLE) ?: ""
    }

    // ─── Cast ─────────────────────────────────────────────────────────

    private fun detectContentType(streamUrl: String): String {
        return when {
            streamUrl.contains(".m3u8") ||
            streamUrl.contains("/manifest/hls") ||
            streamUrl.contains("hls_playlist") -> "application/x-mpegurl"
            streamUrl.contains(".webm") -> "video/webm"
            else -> "video/mp4"
        }
    }

    fun loadMedia(streamUrl: String, title: String = "", isLive: Boolean = false) {
        val session = castSession ?: return
        val remoteClient: RemoteMediaClient = session.remoteMediaClient ?: return

        val contentType = detectContentType(streamUrl)
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }
        // Un direct doit être déclaré LIVE : en BUFFERED, le receiver cherche
        // une durée inexistante et reste muet.
        val streamType = if (isLive) MediaInfo.STREAM_TYPE_LIVE
                         else MediaInfo.STREAM_TYPE_BUFFERED
        val mediaInfo = MediaInfo.Builder(streamUrl)
            .setStreamType(streamType)
            .setContentType(contentType)
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()
        remoteClient.load(request)
    }

    // ─── Contrôles de lecture ─────────────────────────────────────────

    fun pause() { castSession?.remoteMediaClient?.pause() }
    fun play() { castSession?.remoteMediaClient?.play() }
    fun stop() { castSession?.remoteMediaClient?.stop() }

    val isPlaying: Boolean
        get() = castSession?.remoteMediaClient?.isPlaying == true

    fun togglePlayPause() {
        val rmc = castSession?.remoteMediaClient ?: return
        if (rmc.isPlaying) rmc.pause() else rmc.play()
    }

    fun currentPositionMs(): Long =
        castSession?.remoteMediaClient?.approximateStreamPosition ?: 0L

    fun durationMs(): Long =
        castSession?.remoteMediaClient?.streamDuration ?: 0L

    fun seekTo(positionMs: Long) {
        val rmc = castSession?.remoteMediaClient ?: return
        val opts = MediaSeekOptions.Builder()
            .setPosition(positionMs.coerceAtLeast(0L))
            .build()
        rmc.seek(opts)
    }

    fun seekBy(deltaMs: Long) {
        val rmc = castSession?.remoteMediaClient ?: return
        val current = rmc.approximateStreamPosition
        val win = liveWindow()
        val target = if (win != null) {
            // Direct avec DVR : on reste dans la fenêtre disponible
            (current + deltaMs).coerceIn(win.first, win.second)
        } else {
            val dur = rmc.streamDuration
            (current + deltaMs).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        }
        seekTo(target)
    }

    // ─── Direct (DVR) ─────────────────────────────────────────────────

    /**
     * Fenêtre de navigation d'un direct : Pair(débutMs, finMs).
     * null si le flux n'est pas un direct ou si aucun buffer DVR n'est
     * disponible (on ne peut alors que suivre le direct en temps réel).
     */
    fun liveWindow(): Pair<Long, Long>? {
        return try {
            val status = castSession?.remoteMediaClient?.mediaStatus ?: return null
            val range = status.liveSeekableRange ?: return null
            val start = range.startTime
            val end = range.endTime
            if (end > start) Pair(start, end) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Saute au bord du direct (reprend la diffusion en temps réel). */
    fun jumpToLiveEdge() {
        val rmc = castSession?.remoteMediaClient ?: return
        try {
            rmc.seek(
                MediaSeekOptions.Builder()
                    .setIsSeekToInfinite(true)
                    .build()
            )
        } catch (e: Exception) {
        }
    }

    // ─── Volume ───────────────────────────────────────────────────────

    fun getVolume(): Double =
        try { castSession?.volume ?: 0.0 } catch (e: Exception) { 0.0 }

    fun setVolume(level: Double) {
        try {
            castSession?.volume = level.coerceIn(0.0, 1.0)
        } catch (e: Exception) {
        }
    }

    fun adjustVolume(delta: Double) {
        setVolume(getVolume() + delta)
    }

    // ─── Déconnexion ──────────────────────────────────────────────────

    /**
     * Termine la session cast ("Arrêter la diffusion") :
     * arrête la lecture sur le Chromecast et déconnecte l'app.
     */
    fun endSession() {
        try {
            castContext.sessionManager.endCurrentSession(true)
        } catch (e: Exception) {
        }
    }
}
