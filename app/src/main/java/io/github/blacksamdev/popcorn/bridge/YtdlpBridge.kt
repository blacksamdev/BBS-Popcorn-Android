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
 *
 * Optimisation : le fichier cookies n'est réécrit que si les cookies
 * ont changé (hash comparé), au lieu d'une écriture à chaque résolution.
 */
object YtdlpBridge {

    data class VideoInfo(
        val title: String,
        val streamUrl: String,
        val thumbnailUrl: String?,
        val durationS: Long,
        val isLive: Boolean = false,
    )

    private val COOKIE_DOMAINS = listOf(
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://youtube.com",
        "https://www.google.com",
        "https://googlevideo.com",
    )

    // Cache cookiefile : évite l'I/O si les cookies n'ont pas changé
    private var lastCookieHash: Int = 0
    private var lastCookieFilePath: String? = null

    fun init(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        cookiesModule.callAttr("init", context.filesDir.absolutePath)
    }

    private val resolver by lazy { Python.getInstance().getModule("resolver") }
    private val cookiesModule by lazy { Python.getInstance().getModule("cookies") }

    /**
     * Construit (ou réutilise) le fichier cookies.txt filtré depuis la WebView.
     * Réécrit le fichier uniquement si les cookies ont changé depuis le
     * dernier appel. Retourne le chemin, ou null si aucun cookie utile.
     */
    @Synchronized
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
            if (map.isEmpty()) {
                // Plus aucun cookie (déconnexion YouTube) : purger le fichier
                // sur disque pour ne pas laisser traîner d'anciens jetons.
                if (lastCookieFilePath != null || lastCookieHash != 0) {
                    try { cookiesModule.callAttr("clear_cookies") } catch (_: Exception) {}
                }
                lastCookieHash = 0
                lastCookieFilePath = null
                return null
            }

            // Cookies inchangés depuis le dernier appel → réutiliser le fichier
            val hash = map.hashCode()
            if (hash == lastCookieHash && lastCookieFilePath != null) {
                return lastCookieFilePath
            }

            val pyDict = Python.getInstance().builtins.callAttr("dict")
            for ((k, v) in map) {
                pyDict.callAttr("__setitem__", k, v)
            }
            val path = cookiesModule.callAttr("write_cookies", pyDict)?.toString()
            lastCookieHash = hash
            lastCookieFilePath = path
            path
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Normalise une URL YouTube (supprime tracking, unifie format).
     */
    suspend fun prepareUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            resolver.callAttr("prepare_url", url).toString()
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Récupère titre + stream + miniature + durée en UN SEUL appel yt-dlp.
     * Repli cookiefile automatique pour les vidéos restreintes.
     * Retourne null si résolution impossible.
     */
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
                val isLive = map[PyObject.fromJava("is_live")]?.toBoolean() ?: false

                VideoInfo(title, streamUrl, thumbnail, duration, isLive)
            } catch (e: Exception) {
                null
            }
        }
}
