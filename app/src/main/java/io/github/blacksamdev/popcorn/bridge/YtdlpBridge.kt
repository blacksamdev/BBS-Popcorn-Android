package io.github.blacksamdev.popcorn.bridge

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YtdlpBridge — pont Kotlin → Python (resolver.py via Chaquopy).
 * Toutes les opérations sont suspendantes (Dispatchers.IO).
 */
object YtdlpBridge {

    fun init(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    private val resolver by lazy {
        Python.getInstance().getModule("resolver")
    }

    /**
     * Normalise une URL YouTube (supprime tracking, unifie format).
     */
    suspend fun prepareUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            resolver.callAttr("prepare_url", url).toString()
        } catch (e: Exception) {
            url // fallback : retourner l'URL brute
        }
    }

    /**
     * Récupère le titre d'une vidéo via yt-dlp.
     * Retourne null si indisponible.
     */
    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        try {
            resolver.callAttr("fetch_title", url)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Résout l'URL du stream direct (sans pub) via yt-dlp.
     * quality : "2160", "1440", "1080", "720", "480"
     * Retourne null si résolution impossible.
     */
    suspend fun resolveStreamUrl(url: String, quality: String = "1080"): String? =
        withContext(Dispatchers.IO) {
            try {
                resolver.callAttr("resolve_stream_url", url, quality)?.toString()
            } catch (e: Exception) {
                null
            }
        }
}
