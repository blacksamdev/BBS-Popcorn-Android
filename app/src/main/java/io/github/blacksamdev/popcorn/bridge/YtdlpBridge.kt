package io.github.blacksamdev.popcorn.bridge

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YtdlpBridge — pont Kotlin → Python (resolver.py via Chaquopy).
 *
 * NOTE cookies : on NE transmet PLUS les cookies de session à yt-dlp.
 * Ils cassent la résolution sur les comptes connectés (YouTube renvoie une
 * réponse sans formats exploitables). yt-dlp résout les vidéos publiques
 * sans cookies. Le paramètre Python cookie_header reste présent pour
 * compatibilité mais on passe null.
 */
object YtdlpBridge {

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

    suspend fun prepareUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            resolver.callAttr("prepare_url", url).toString()
        } catch (e: Exception) {
            url
        }
    }

    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        try {
            resolver.callAttr("fetch_title", url)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun resolveStreamUrl(url: String, quality: String = "1080"): String? =
        withContext(Dispatchers.IO) {
            try {
                resolver.callAttr("resolve_stream_url", url, quality, null)?.toString()
            } catch (e: Exception) {
                null
            }
        }

    suspend fun fetchInfo(url: String, quality: String = "1080"): VideoInfo? =
        withContext(Dispatchers.IO) {
            try {
                val result: PyObject = resolver.callAttr(
                    "fetch_info", url, quality, null
                ) ?: return@withContext null

                val map = result.asMap()
                val streamUrl = map[PyObject.fromJava("stream_url")]?.toString()
                    ?: return@withContext null
                val title = map[PyObject.fromJava("title")]?.toString() ?: ""
                val thumbnail = map[PyObject.fromJava("thumbnail")]?.toString()
                val duration = map[PyObject.fromJava("duration_s")]?.toLong() ?: 0L

                VideoInfo(title, streamUrl, thumbnail, duration)
            } catch (e: Exception) {
                null
            }
        }
}
