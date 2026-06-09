package io.github.blacksamdev.popcorn.bridge

import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SponsorBridge — pont Kotlin → Python (sponsorblock.py via Chaquopy).
 *
 * Retourne les segments sous forme de liste de SponsorSegment,
 * prêts à être injectés dans Media3 (ClippingMediaSource ou MediaItem).
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
                // Extraire le video_id depuis l'URL
                val videoId = module.callAttr("extract_video_id", url)?.toString()
                    ?: return@withContext emptyList()

                val pyList = module.callAttr("get_segments", videoId)
                val result = mutableListOf<SponsorSegment>()

                for (i in 0 until pyList.asList().size) {
                    val seg = pyList.asList()[i].asMap()
                    val category = seg[Python.getInstance()
                        .builtins.callAttr("str", "category")]?.toString() ?: continue
                    val start = seg[Python.getInstance()
                        .builtins.callAttr("str", "start")]?.toDouble() ?: continue
                    val end = seg[Python.getInstance()
                        .builtins.callAttr("str", "end")]?.toDouble() ?: continue

                    result.add(SponsorSegment(
                        category = category,
                        startMs = (start * 1000).toLong(),
                        endMs = (end * 1000).toLong(),
                    ))
                }
                result
            } catch (e: Exception) {
                emptyList()
            }
        }
}
