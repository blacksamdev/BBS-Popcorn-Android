package io.github.blacksamdev.popcorn.bridge

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YtdlpBridge — pont Kotlin → Python (resolver.py via Chaquopy).
 * Toutes les opérations sont suspendantes (Dispatchers.IO).
 */
object YtdlpBridge {

    /**
     * Résultat complet d'une résolution yt-dlp.
     */
    data class VideoInfo(
        val title: String,
        val streamUrl: String,
        val thumbnailUrl: String?,
        val durationS: Long,
    )

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

    /**
     * Récupère titre + stream + miniature + durée en UN SEUL appel yt-dlp.
     * À privilégier sur fetchTitle() + resolveStreamUrl() séparés.
     * Retourne null si résolution impossible.
     */
    suspend fun fetchInfo(url: String, quality: String = "1080"): VideoInfo? =
    withContext(Dispatchers.IO) {
        try {
            val result: PyObject = resolver.callAttr("fetch_info", url, quality)
            ?: return@withContext null

            val map = result.asMap()

            val streamUrl = map[PyObject.fromJava("stream_url")]?.toString()
            ?: return@withContext null
            val title = map[PyObject.fromJava("title")]?.toString() ?: ""
            val thumbnail = map[PyObject.fromJava("thumbnail")]?.toString()
            val duration = map[PyObject.fromJava("duration_s")]?.toLong() ?: 0L

            VideoInfo(
                title = title,
                streamUrl = streamUrl,
                thumbnailUrl = thumbnail,
                durationS = duration,
            )
        } catch (e: Exception) {
            null
        }
    }
}
