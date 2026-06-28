package io.github.blacksamdev.popcorn.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.blacksamdev.popcorn.bridge.ResumeBridge
import io.github.blacksamdev.popcorn.bridge.SponsorBridge
import io.github.blacksamdev.popcorn.databinding.ActivityPlayerBinding
import io.github.blacksamdev.popcorn.player.BbsPlayer
import io.github.blacksamdev.popcorn.player.CastManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * PlayerActivity — écran de lecture BBS Popcorn Android.
 *
 * - Lecture locale via Media3/ExoPlayer (BbsPlayer)
 * - Reprise de lecture (resume_store via ResumeBridge)
 * - SponsorBlock : UNIQUEMENT si activé dans les réglages (off par défaut —
 *   aucune requête vers sponsor.ajay.app sans activation explicite)
 * - Si une session Chromecast est active : on caste et on ouvre la
 *   télécommande (CastControlActivity) au lieu de lire en local
 * - Bouton/geste retour : arrêt propre de la lecture et retour à l'UI
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SOURCE_URL = "extra_source_url"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: BbsPlayer? = null
    private var castManager: CastManager? = null
    private var sourceUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Garder l'écran allumé pendant la lecture
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Retour (bouton ou geste) : sauvegarde + arrêt propre
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                savePosition()
                player?.stop()
                finish()
            }
        })

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: ""

        if (streamUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Flux invalide", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        castManager = CastManager(this).also { it.register() }

        // Session Chromecast active → on caste et on ouvre la télécommande
        if (castManager?.isConnected == true) {
            castManager?.loadMedia(streamUrl, title)
            val ctrl = Intent(this, CastControlActivity::class.java).apply {
                putExtra(CastControlActivity.EXTRA_STREAM_URL, streamUrl)
                putExtra(CastControlActivity.EXTRA_TITLE, title)
            }
            startActivity(ctrl)
            finish()
            return
        }

        // Lecture locale
        player = BbsPlayer(this, lifecycleScope).also {
            binding.playerView.player = it.exoPlayer
        }

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

            player?.play(streamUrl, segments, startPositionMs = resumeMs)

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

    private fun formatMs(ms: Long): String {
        val totalS = ms / 1000
        val h = totalS / 3600
        val m = (totalS % 3600) / 60
        val s = totalS % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * Sauvegarde la position courante (la logique <10s / >95% est côté Python).
     */
    private fun savePosition() {
        val p = player ?: return
        if (sourceUrl.isEmpty()) return
        val pos = p.currentPositionMs
        val dur = p.durationMs
        if (pos <= 0) return
        // runBlocking court : écriture JSON locale, appelée à la fermeture
        runBlocking {
            ResumeBridge.setMs(sourceUrl, pos, if (dur > 0) dur else 0L)
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        castManager?.unregister()
        castManager = null
    }
}
