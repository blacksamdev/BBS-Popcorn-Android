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
 * - Suivi de progression en temps réel (position / durée)
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

    // Progress listener du RemoteMediaClient (mise à jour ~toutes les secondes)
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

    // ─── Cast ─────────────────────────────────────────────────────────

    val isConnected: Boolean get() = castSession?.isConnected == true

    val deviceName: String?
        get() = castSession?.castDevice?.friendlyName

    private fun detectContentType(streamUrl: String): String {
        return when {
            streamUrl.contains(".m3u8") ||
            streamUrl.contains("/manifest/hls") ||
            streamUrl.contains("hls_playlist") -> "application/x-mpegurl"
            streamUrl.contains(".webm") -> "video/webm"
            else -> "video/mp4"
        }
    }

    fun loadMedia(streamUrl: String, title: String = "") {
        val session = castSession ?: return
        val remoteClient: RemoteMediaClient = session.remoteMediaClient ?: return

        val contentType = detectContentType(streamUrl)
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }
        val mediaInfo = MediaInfo.Builder(streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
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

    /** Position approximative courante en ms (0 si indisponible). */
    fun currentPositionMs(): Long =
        castSession?.remoteMediaClient?.approximateStreamPosition ?: 0L

    /** Durée du flux en ms (0 si indisponible). */
    fun durationMs(): Long =
        castSession?.remoteMediaClient?.streamDuration ?: 0L

    /** Se déplace à une position absolue (ms). */
    fun seekTo(positionMs: Long) {
        val rmc = castSession?.remoteMediaClient ?: return
        val opts = MediaSeekOptions.Builder()
            .setPosition(positionMs.coerceAtLeast(0L))
            .build()
        rmc.seek(opts)
    }

    /** Se déplace de deltaMs depuis la position courante (±). */
    fun seekBy(deltaMs: Long) {
        val rmc = castSession?.remoteMediaClient ?: return
        val dur = rmc.streamDuration
        val target = (rmc.approximateStreamPosition + deltaMs)
            .coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        seekTo(target)
    }
}
