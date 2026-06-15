package io.github.blacksamdev.popcorn.bridge

import android.content.Context
import android.webkit.CookieManager
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YtdlpBridge — pont Kotlin → Python (resolver.py via Chaquopy).
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

    private fun webViewCookies(): String? {
        return try {
            CookieManager.getInstance().getCookie("https://m.youtube.com")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
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
                resolver.callAttr(
                    "resolve_stream_url", url, quality, webViewCookies()
                )?.toString()
            } catch (e: Exception) {
                null
            }
        }

    suspend fun fetchInfo(url: String, quality: String = "1080"): VideoInfo? =
        withContext(Dispatchers.IO) {
            try {
                val result: PyObject = resolver.callAttr(
                    "fetch_info", url, quality, webViewCookies()
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

    /**
     * DIAGNOSTIC : retourne le JSON brut (ok/erreur) de fetch_info_debug.
     * Permet d'afficher la vraie cause yt-dlp dans le toast.
     */
    suspend fun fetchInfoDebug(url: String, quality: String = "1080"): String =
        withContext(Dispatchers.IO) {
            try {
                resolver.callAttr(
                    "fetch_info_debug", url, quality, webViewCookies()
                )?.toString() ?: """{"ok":false,"error":"bridge null"}"""
            } catch (e: Exception) {
                """{"ok":false,"error":"bridge exception: ${e.message}"}"""
            }
        }
}
