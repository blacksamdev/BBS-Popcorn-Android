package io.github.blacksamdev.popcorn.bridge

import android.content.Context
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * HistoryBridge — pont Kotlin → Python (history_store.py via Chaquopy).
 * Frontière JSON brut, comme SponsorBridge.
 */
object HistoryBridge {

    data class HistoryEntry(
        val url: String,
        val title: String,
        val timestampS: Long,
    )

    private val module by lazy {
        Python.getInstance().getModule("history_store")
    }

    /**
     * À appeler une fois au démarrage (après YtdlpBridge.init).
     */
    fun init(context: Context) {
        module.callAttr("init", context.filesDir.absolutePath)
    }

    suspend fun add(url: String, title: String) = withContext(Dispatchers.IO) {
        try {
            module.callAttr("add", url, title)
        } catch (_: Exception) {
        }
    }

    suspend fun entries(): List<HistoryEntry> = withContext(Dispatchers.IO) {
        try {
            val jsonStr = module.callAttr("entries_json")?.toString() ?: "[]"
            val arr = JSONArray(jsonStr)
            val result = mutableListOf<HistoryEntry>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                result.add(
                    HistoryEntry(
                        url = e.getString("url"),
                        title = e.optString("title", e.getString("url")),
                        timestampS = e.optLong("ts", 0L),
                    )
                )
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            module.callAttr("clear")
        } catch (_: Exception) {
        }
    }
}
