package io.github.blacksamdev.popcorn.player

import android.content.Context
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/**
 * CastManager — intégration Chromecast via Cast SDK Google.
 *
 * Gère :
 * - Détection de session Cast active
 * - Envoi du stream URL vers le Chromecast
 * - Callbacks connect/disconnect
 */
class CastManager(context: Context) {

    private val castContext = CastContext.getSharedInstance(context)
    private var castSession: CastSession? = null

    var onCastConnected: (() -> Unit)? = null
    var onCastDisconnected: (() -> Unit)? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            castSession = session
            onCastConnected?.invoke()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            castSession = null
            onCastDisconnected?.invoke()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            castSession = session
            onCastConnected?.invoke()
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    // ─── Lifecycle ────────────────────────────────────────────────────

    fun register() {
        castContext.sessionManager.addSessionManagerListener(
            sessionListener, CastSession::class.java
        )
        castSession = castContext.sessionManager.currentCastSession
    }

    fun unregister() {
        castContext.sessionManager.removeSessionManagerListener(
            sessionListener, CastSession::class.java
        )
    }

    // ─── Cast ─────────────────────────────────────────────────────────

    val isConnected: Boolean get() = castSession?.isConnected == true

    /**
     * Envoie un stream URL vers le Chromecast connecté.
     * streamUrl : URL directe résolue par YtdlpBridge
     * title     : titre de la vidéo (affiché sur la TV)
     */
    fun loadMedia(streamUrl: String, title: String = "") {
        val session = castSession ?: return
        val remoteClient: RemoteMediaClient = session.remoteMediaClient ?: return

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }

        val mediaInfo = MediaInfo.Builder(streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("video/mp4")
            .setMetadata(metadata)
            .build()

        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        remoteClient.load(request)
    }

    fun pause() {
        castSession?.remoteMediaClient?.pause()
    }

    fun play() {
        castSession?.remoteMediaClient?.play()
    }

    fun stop() {
        castSession?.remoteMediaClient?.stop()
    }
}
