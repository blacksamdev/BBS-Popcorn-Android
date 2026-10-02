package io.github.blacksamdev.popcorn.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import io.github.blacksamdev.popcorn.bridge.ResumeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * PlaybackHolder — propriétaire unique du lecteur.
 *
 * Le lecteur ne peut pas appartenir à l'écran de lecture : celui-ci est
 * détruit dès qu'on quitte l'app ou que le système a besoin de mémoire, ce
 * qui couperait le son. Il vit donc ici, et deux acteurs s'y branchent :
 *
 * - PlaybackService publie une session média autour de lui — c'est ce qui
 *   donne la notification, les contrôles de l'écran verrouillé et les
 *   boutons des écouteurs Bluetooth ;
 * - PlayerActivity n'y branche que sa surface vidéo, quand elle est visible.
 *
 * La destruction passe toujours par le service, jamais par l'activité :
 * la session doit être fermée avant le lecteur, sinon elle pointe un instant
 * vers un lecteur détruit.
 */
@UnstableApi
object PlaybackHolder {

    // Portée du veilleur SponsorBlock : rattachée au lecteur, donc
    // indépendante de toute activité.
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Portée des écritures de position : jamais annulée, pour que la
    // sauvegarde aboutisse même quand tout le reste disparaît.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var instance: BbsPlayer? = null

    /** URL de la page YouTube en cours, nécessaire à la reprise de lecture. */
    var sourceUrl: String = ""
        private set

    val current: BbsPlayer? get() = instance

    /** Le lecteur en vie, ou un nouveau s'il n'y en a pas. */
    fun obtain(context: Context): BbsPlayer {
        instance?.let { return it }
        return BbsPlayer(context.applicationContext, playerScope).also { instance = it }
    }

    fun remember(sourceUrl: String) {
        this.sourceUrl = sourceUrl
    }

    /**
     * Enregistre la position courante (la règle « moins de 10 s / plus de
     * 95 % » est côté Python). Appelée par l'écran de lecture mais aussi par
     * le service : le son peut avoir continué longtemps après la disparition
     * de l'écran, et c'est cette position-là qu'il faut retenir.
     *
     * À appeler depuis le thread principal : la position est lue sur le
     * lecteur, seule l'écriture du fichier part en tâche de fond.
     */
    fun saveResume() {
        val player = instance ?: return
        val url = sourceUrl
        if (url.isEmpty()) return
        val positionMs = player.currentPositionMs
        val durationMs = player.durationMs
        if (positionMs <= 0) return
        saveScope.launch {
            ResumeBridge.setMs(url, positionMs, if (durationMs > 0) durationMs else 0L)
        }
    }

    fun release() {
        saveResume()
        instance?.release()
        instance = null
        sourceUrl = ""
    }
}
