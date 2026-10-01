package io.github.blacksamdev.popcorn.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * ChunkedDataSource — téléchargement par tranches bornées.
 *
 * Pourquoi : les serveurs de YouTube servent rapidement le début d'un flux
 * puis brident fortement une requête qui reste ouverte. Mesuré sur un flux
 * 1080p : 623 ko/s en requête continue contre 55 Mo/s en requête bornée,
 * soit presque 90 fois plus. ExoPlayer, lui, ouvre par défaut une seule
 * requête sans borne — d'où une lecture qui démarre bien puis se fige.
 *
 * Cette source découpe la lecture en tranches successives : chaque tranche
 * est une requête bornée, qui repart donc à pleine vitesse. C'est la même
 * parade que l'option de découpage de yt-dlp.
 *
 * La découpe est invisible pour le reste du lecteur : la longueur totale
 * est déduite de l'en-tête Content-Range dès la première tranche, et la
 * recherche dans la vidéo continue de fonctionner normalement.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val chunkSize: Long,
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstreamFactory, chunkSize)
    }

    companion object {
        /** 8 Mo : mesuré non bridé, et assez large pour espacer les requêtes. */
        const val DEFAULT_CHUNK_SIZE: Long = 8L * 1024 * 1024
        private val UNSET: Long = C.LENGTH_UNSET.toLong()
        /** Garde-fou contre une boucle si le serveur renvoie des tranches vides. */
        private const val MAX_EMPTY_CHUNKS = 2
    }

    private val listeners = mutableListOf<TransferListener>()

    private var masterSpec: DataSpec? = null
    private var current: DataSource? = null
    private var position: Long = 0
    private var remainingTotal: Long = UNSET
    private var remainingInChunk: Long = 0

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeCurrent()
        masterSpec = dataSpec
        position = dataSpec.position
        remainingTotal = dataSpec.length
        openChunk()
        return remainingTotal
    }

    /**
     * Ouvre la tranche suivante à partir de la position courante.
     * Déduit la longueur totale de Content-Range à la première tranche.
     */
    private fun openChunk() {
        val spec = masterSpec ?: return
        val length = if (remainingTotal == UNSET) {
            chunkSize
        } else {
            minOf(chunkSize, remainingTotal)
        }

        val source = upstreamFactory.createDataSource()
        listeners.forEach { source.addTransferListener(it) }

        val chunkSpec = spec.buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()

        val opened = source.open(chunkSpec)
        current = source
        remainingInChunk = if (opened == UNSET) length else opened

        if (remainingTotal == UNSET) {
            remainingTotal = totalRemainingFromHeaders(source)
        }
    }

    /**
     * Longueur restante déduite de « Content-Range: bytes a-b/total ».
     * Renvoie UNSET si l'en-tête est absent ou illisible — la lecture
     * fonctionne alors quand même, tranche après tranche.
     */
    private fun totalRemainingFromHeaders(source: DataSource): Long {
        val header = source.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull() ?: return UNSET
        val total = header.substringAfter('/', "").trim().toLongOrNull() ?: return UNSET
        return (total - position).coerceAtLeast(0)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remainingTotal == 0L) return C.RESULT_END_OF_INPUT

        var emptyChunks = 0
        while (emptyChunks <= MAX_EMPTY_CHUNKS) {
            val source = current ?: return C.RESULT_END_OF_INPUT

            val maxRead = if (remainingInChunk in 1..Int.MAX_VALUE.toLong()) {
                minOf(length.toLong(), remainingInChunk).toInt()
            } else {
                length
            }

            val read = source.read(buffer, offset, maxRead)
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                if (remainingInChunk > 0) remainingInChunk -= read
                if (remainingTotal > 0) remainingTotal -= read
                return read
            }

            // Tranche épuisée : enchaîner sur la suivante.
            closeCurrent()
            if (remainingTotal == 0L) return C.RESULT_END_OF_INPUT
            openChunk()
            emptyChunks++
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = current?.uri ?: masterSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        current?.responseHeaders ?: emptyMap()

    override fun close() {
        closeCurrent()
        masterSpec = null
        position = 0
        remainingTotal = UNSET
        remainingInChunk = 0
    }

    private fun closeCurrent() {
        val source = current ?: return
        current = null
        remainingInChunk = 0
        try {
            source.close()
        } catch (e: Exception) {
            // Fermeture d'une tranche déjà terminée : sans conséquence.
        }
    }
}
