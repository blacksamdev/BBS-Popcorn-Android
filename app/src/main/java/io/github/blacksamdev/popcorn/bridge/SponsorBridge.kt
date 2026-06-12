package io.github.blacksamdev.popcorn.bridge

import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * SponsorBridge — pont Kotlin → Python (sponsorblock.py via Chaquopy).
 *
 * Python retourne du JSON brut (string), parsé ici avec org.json :
 * frontière de types propre, aucune manipulation de PyObject complexe.
 */
object SponsorBridge {

    data class SponsorSegment(
        val category: String,
        val startMs: Long,   // en millisecondes pour Media3
        val endMs: Long,
    )

    private val module by lazy {
        Python.getInstance().getModule("sponsorblock")
    }

    /**
     * Récupère les segments SponsorBlock pour une URL YouTube normalisée.
     * Retourne une liste vide si aucun segment ou erreur.
     */
    suspend fun getSegments(url: String): List<SponsorSegment> =
        withContext(Dispatchers.IO) {
            try {
                val videoId = module.callAttr("extract_video_id", url)?.toString()
                    ?: return@withContext emptyList()

                val jsonStr = module.callAttr("get_segments_json", videoId)
                    ?.toString() ?: "[]"

                val arr = JSONArray(jsonStr)
                val result = mutableListOf<SponsorSegment>()

                for (i in 0 until arr.length()) {
                    val seg = arr.getJSONObject(i)
                    result.add(
                        SponsorSegment(
                            category = seg.getString("category"),
                            startMs = (seg.getDouble("start") * 1000).toLong(),
                            endMs = (seg.getDouble("end") * 1000).toLong(),
                        )
                    )
                }
                result
            } catch (e: Exception) {
                emptyList()
            }
        }
}
