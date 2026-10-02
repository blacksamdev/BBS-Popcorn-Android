package io.github.blacksamdev.popcorn.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import io.github.blacksamdev.popcorn.bridge.ResumeBridge
import io.github.blacksamdev.popcorn.bridge.SponsorBridge
import io.github.blacksamdev.popcorn.databinding.ActivityPlayerBinding
import io.github.blacksamdev.popcorn.player.PlaybackHolder
import io.github.blacksamdev.popcorn.player.PlaybackService
import kotlinx.coroutines.launch

/**
 * PlayerActivity — écran de lecture BBS Popcorn Android.
 *
 * Cet écran ne possède pas le lecteur : celui-ci vit dans PlaybackHolder,
 * publié par PlaybackService, afin que le son continue quand l'écran s'éteint
 * ou qu'on quitte l'app. L'activité ne fait que brancher sa surface vidéo
 * dessus quand elle est visible, et la détacher quand elle ne l'est plus.
 *
 * - Reprise de lecture (resume_store via ResumeBridge)
 * - SponsorBlock : UNIQUEMENT si activé dans les réglages (off par défaut —
 *   aucune requête vers sponsor.ajay.app sans activation explicite)
 * - Bouton/geste retour : sortie explicite, donc arrêt de la lecture
 *   (à la différence du passage en arrière-plan, où le son continue)
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_AUDIO_URL = "extra_audio_url"
        const val EXTRA_HEADERS = "extra_headers"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_THUMBNAIL = "extra_thumbnail"
        const val EXTRA_SOURCE_URL = "extra_source_url"

        private const val REQ_NOTIFICATIONS = 1
    }

    private lateinit var binding: ActivityPlayerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Garder l'écran allumé pendant la lecture
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Retour (bouton ou geste) : sortie volontaire, on arrête la lecture.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                PlaybackHolder.saveResume()
                PlaybackHolder.current?.stop()
                finish()
            }
        })

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        if (streamUrl.isNullOrEmpty()) {
            // Ouverture depuis la notification média : l'intent ne porte pas
            // de flux, on se rebranche sur la lecture en cours (onStart).
            if (PlaybackHolder.current == null) {
                Toast.makeText(this, "Flux invalide", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }

        ensureNotificationPermission()

        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL) ?: ""
        val headers = parseHeaders(intent.getStringExtra(EXTRA_HEADERS))
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val thumbnail = intent.getStringExtra(EXTRA_THUMBNAIL)
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: ""

        // Le service doit tourner avant la lecture : c'est lui qui publie la
        // session média, donc la notification et les contrôles Bluetooth.
        startService(Intent(this, PlaybackService::class.java))

        val player = PlaybackHolder.obtain(this)
        PlaybackHolder.remember(sourceUrl)

        val sponsorBlockEnabled = getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE
        ).getBoolean(MainActivity.PREF_SPONSORBLOCK, false)

        // Reprise + SponsorBlock (si activé) : récupérer puis lancer la lecture
        lifecycleScope.launch {
            val resumeMs = if (sourceUrl.isNotEmpty()) {
                ResumeBridge.getMs(sourceUrl)
            } else {
                0L
            }
            val segments = if (sponsorBlockEnabled && sourceUrl.isNotEmpty()) {
                SponsorBridge.getSegments(sourceUrl)
            } else {
                emptyList()
            }

            player.play(
                streamUrl,
                audioUrl,
                segments,
                startPositionMs = resumeMs,
                headers = headers,
                title = title,
                artworkUrl = thumbnail,
            )

            if (resumeMs > 0) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Reprise à ${formatMs(resumeMs)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            if (segments.isNotEmpty()) {
                Toast.makeText(
                    this@PlayerActivity,
                    "SponsorBlock : ${segments.size} segment(s) à skipper",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Android 13+ : sans cette autorisation, la notification média n'apparaît
     * pas. La lecture fonctionne quand même, simplement sans contrôles sur
     * l'écran verrouillé — on demande donc, sans bloquer si c'est refusé.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS
        )
    }

    /**
     * En-têtes fournis par yt-dlp, transmis en JSON.
     * Ils doivent être rejoués tels quels sur les URLs de flux, sans quoi
     * les serveurs de YouTube refusent la lecture (403).
     */
    private fun parseHeaders(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            buildMap {
                obj.keys().forEach { key -> put(key, obj.optString(key)) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun formatMs(ms: Long): String {
        val totalS = ms / 1000
        val h = totalS / 3600
        val m = (totalS % 3600) / 60
        val s = totalS % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ─── Surface vidéo ────────────────────────────────────────────────
    //
    // Branchée seulement quand l'écran est visible. En la détachant, le
    // rendu vidéo s'arrête mais le lecteur continue : c'est exactement le
    // comportement « son en arrière-plan » attendu.

    override fun onStart() {
        super.onStart()
        binding.playerView.player = PlaybackHolder.current?.exoPlayer
    }

    override fun onStop() {
        super.onStop()
        PlaybackHolder.saveResume()
        binding.playerView.player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.playerView.player = null
        if (isFinishing) {
            // Le service ferme la session puis libère le lecteur, dans cet
            // ordre. L'activité ne libère rien elle-même.
            stopService(Intent(this, PlaybackService::class.java))
        }
    }
}
