package io.github.blacksamdev.popcorn.bridge

import android.content.Context
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ResumeBridge — pont Kotlin → Python (resume_store.py via Chaquopy).
 * Positions en secondes côté Python, millisecondes côté Media3.
 */
object ResumeBridge {

    private val module by lazy {
        Python.getInstance().getModule("resume_store")
    }

    fun init(context: Context) {
        module.callAttr("init", context.filesDir.absolutePath)
    }

    /**
     * Position de reprise en millisecondes, 0 si aucune.
     */
    suspend fun getMs(url: String): Long = withContext(Dispatchers.IO) {
        try {
            val seconds = module.callAttr("get", url)?.toDouble() ?: 0.0
            (seconds * 1000).toLong()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Enregistre la position (ms). La logique <10s / >95% est côté Python.
     */
    suspend fun setMs(url: String, positionMs: Long, durationMs: Long) =
        withContext(Dispatchers.IO) {
            try {
                module.callAttr(
                    "set_position",
                    url,
                    positionMs / 1000.0,
                    if (durationMs > 0) durationMs / 1000.0 else 0.0,
                )
            } catch (_: Exception) {
            }
        }
}
