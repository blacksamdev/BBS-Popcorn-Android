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
 *
 * Cookies (approche desktop) :
 * - On récupère les cookies WebView des domaines YouTube/Google
 * - cookies.py les écrit dans un fichier Netscape filtré
 * - On passe le CHEMIN de ce fichier à yt-dlp (option cookiefile)
 * - resolver.py tente d'abord SANS cookies, puis AVEC en repli
 *   (vidéos à restriction d'âge). Jamais de header Cookie brut.
 */
object YtdlpBridge {

    data class VideoInfo(
        val title: String,
        val streamUrl: String,
        val thumbnailUrl: String?,
        val durationS: Long,
    )

    // Domaines dont on extrait les cookies (alignés sur cookies.py)
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
        // Init du module cookies avec le dossier de stockage app
        cookiesModule.callAttr("init", context.filesDir.absolutePath)
    }

    private val resolver by lazy {
        Python.getInstance().getModule("resolver")
    }

    private val cookiesModule by lazy {
        Python.getInstance().getModule("cookies")
    }

    /**
     * Construit le fichier cookies.txt filtré depuis la WebView.
     * Retourne le chemin du fichier, ou null si aucun cookie utile.
     */
    private fun buildCookieFile(): String? {
        return try {
            val cm = CookieManager.getInstance()
            val map = mutableMapOf<String, String>()
            for (domainUrl in COOKIE_DOMAINS) {
                val c = cm.getCookie(domainUrl)
                if (!c.isNullOrBlank()) {
                    // clé = host sans schéma (ex: .youtube.com)
                    val host = domainUrl.removePrefix("https://").removePrefix("www.")
                    map[".$host"] = c
                }
            }
            if (map.isEmpty()) return null

            // Convertit la Map Kotlin en dict Python
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
                val cookieFile = buildCookieFile()
                resolver.callAttr("resolve_stream_url", url, quality, cookieFile)
                    ?.toString()
            } catch (e: Exception) {
                null
            }
        }

    /** DIAGNOSTIC : liste brute des formats avec cookiefile. */
    suspend fun listFormatsDebug(url: String): String = withContext(Dispatchers.IO) {
        try {
            resolver.callAttr("list_formats_debug", url, buildCookieFile())
                ?.toString() ?: """{"ok":false,"error":"null"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    suspend fun fetchInfo(url: String, quality: String = "1080"): VideoInfo? =
        withContext(Dispatchers.IO) {
            try {
                val cookieFile = buildCookieFile()  // null si pas connecté
                val result: PyObject = resolver.callAttr(
                    "fetch_info", url, quality, cookieFile
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
