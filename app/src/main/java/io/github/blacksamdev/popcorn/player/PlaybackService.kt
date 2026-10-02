package io.github.blacksamdev.popcorn.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.blacksamdev.popcorn.ui.PlayerActivity

/**
 * PlaybackService — service au premier plan qui porte la session média.
 *
 * Son rôle n'est pas de lire : le lecteur vit dans PlaybackHolder. Ce service
 * existe pour deux raisons :
 *
 * - Android n'autorise le son à continuer écran éteint qu'au profit d'un
 *   service au premier plan ;
 * - la session média qu'il publie fournit la notification, les contrôles de
 *   l'écran verrouillé et la réponse aux boutons des écouteurs Bluetooth.
 *
 * C'est aussi le seul endroit qui démonte la lecture, pour garantir l'ordre :
 * la session se ferme avant le lecteur.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlaybackHolder.obtain(this).exoPlayer

        // Toucher la notification ramène à l'écran de lecture. Sans flux dans
        // l'intent, PlayerActivity se rebranche sur la lecture en cours.
        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayer)
            .build()
            .also {
                // Enregistrement explicite : sans cela la session n'est connue
                // du service qu'au moment où un contrôleur s'y connecte. Or
                // ici personne ne s'y connecte — l'écran de lecture se branche
                // directement sur le lecteur. Sans cet appel, pas de
                // notification média, donc pas de passage au premier plan, et
                // Android finirait par couper le son en arrière-plan.
                addSession(it)
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App balayée hors des récentes : l'utilisateur l'a fermée. On arrête
        // tout. Sans cela la notification média survit à une application qui
        // n'existe plus, et il faut la rouvrir pour s'en débarrasser.
        // La position est relevée avant l'arrêt : le son a pu continuer
        // longtemps après la disparition de l'écran de lecture.
        PlaybackHolder.saveResume()
        PlaybackHolder.current?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        // Ordre imposé : la session d'abord, le lecteur ensuite.
        session?.release()
        session = null
        PlaybackHolder.release()
        super.onDestroy()
    }
}
