package io.github.blacksamdev.popcorn.bridge

import android.content.Context
import android.webkit.CookieManager
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YtdlpBridge {

    data class VideoInfo(
        val title: String,
        val streamUrl: String,
        val thumbnailUrl: String?,
        val durationS: Long,
    )

    private val COOKIE_DOMAINS = listOf(
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://youtube.com",
        "https://www.google.com",
        "https://googlevideo.com",
    )

    fun init(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        cookiesModule.callAttr("init", context.filesDir.absolutePath)
    }

    private val resolver by lazy { Python.getInstance().getModule("resolver") }
    private val cookiesModule by lazy { Python.getInstance().getModule("cookies") }

    private fun buildCookieFile(): String? {
        return try {
            val cm = CookieManager.getInstance()
            val map = mutableMapOf<String, String>()
            for (domainUrl in COOKIE_DOMAINS) {
                val c = cm.getCookie(domainUrl)
                if (!c.isNullOrBlank()) {
                    val host = domainUrl.removePrefix("https://").removePrefix("www.")
                    map[".$host"] = c
                }
            }
            if (map.isEmpty()) return null
            val pyDict = Python.getInstance().builtins.callAttr("dict")
            for ((k, v) in map) {
                pyDict.callAttr("__setitem__", k, v)
            }
            cookiesModule.callAttr("write_cookies", pyDict)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun prepareUrl(url: String): String = withContext(Dispatchers.IO) {
        try { resolver.callAttr("prepare_url", url).toString() } catch (e: Exception) { url }
    }

    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        try { resolver.callAttr("fetch_title", url)?.toString() } catch (e: Exception) { null }
    }

    suspend fun resolveStreamUrl(url: String, quality: String = "1080"): String? =
        withContext(Dispatchers.IO) {
            try {
                resolver.callAttr("resolve_stream_url", url, quality, buildCookieFile())?.toString()
            } catch (e: Exception) { null }
        }

    suspend fun fetchInfo(url: String, quality: String = "1080"): VideoInfo? =
        withContext(Dispatchers.IO) {
            try {
                val result: PyObject = resolver.callAttr(
                    "fetch_info", url, quality, buildCookieFile()
                ) ?: return@withContext null
                val map = result.asMap()
                val streamUrl = map[PyObject.fromJava("stream_url")]?.toString()
                    ?: return@withContext null
                val title = map[PyObject.fromJava("title")]?.toString() ?: ""
                val thumbnail = map[PyObject.fromJava("thumbnail")]?.toString()
                val duration = map[PyObject.fromJava("duration_s")]?.toLong() ?: 0L
                VideoInfo(title, streamUrl, thumbnail, duration)
            } catch (e: Exception) { null }
        }

    /** DIAGNOSTIC : JSON ok/erreurs des deux tentatives. */
    suspend fun fetchInfoDebug(url: String, quality: String = "1080"): String =
        withContext(Dispatchers.IO) {
            try {
                resolver.callAttr("fetch_info_debug", url, quality, buildCookieFile())
                    ?.toString() ?: """{"ok":false,"err_no_cookie":"bridge null"}"""
            } catch (e: Exception) {
                """{"ok":false,"err_no_cookie":"bridge exc: ${e.message}"}"""
            }
        }
}
