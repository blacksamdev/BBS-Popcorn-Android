package io.github.blacksamdev.popcorn.ui

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.blacksamdev.popcorn.bridge.SponsorBridge
import io.github.blacksamdev.popcorn.databinding.ActivityPlayerBinding
import io.github.blacksamdev.popcorn.player.BbsPlayer
import io.github.blacksamdev.popcorn.player.CastManager
import kotlinx.coroutines.launch

/**
 * PlayerActivity — écran de lecture BBS Popcorn Android.
 *
 * - Lecture locale via Media3/ExoPlayer (BbsPlayer)
 * - Skip automatique des segments SponsorBlock
 * - Si une session Chromecast est active : envoi du flux vers la TV
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

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    binding = ActivityPlayerBinding.inflate(layoutInflater)
                    setContentView(binding.root)

                    // Garder l'écran allumé pendant la lecture
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    hideSystemBars()

                    val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                    val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: ""

                    if (streamUrl.isNullOrEmpty()) {
                        Toast.makeText(this, "Flux invalide", Toast.LENGTH_SHORT).show()
                        finish()
                        return
                    }

                    castManager = CastManager(this).also { it.register() }

                    // Session Chromecast active → on caste, on ne lit pas en local
                    if (castManager?.isConnected == true) {
                        castManager?.loadMedia(streamUrl, title)
                        Toast.makeText(this, "Lecture sur Chromecast", Toast.LENGTH_SHORT).show()
                        finish()
                        return
                    }

                    // Lecture locale
                    player = BbsPlayer(this, lifecycleScope).also {
                        binding.playerView.player = it.exoPlayer
                    }

                    // SponsorBlock : récupérer les segments puis lancer la lecture
                    lifecycleScope.launch {
                        val segments = if (sourceUrl.isNotEmpty()) {
                            SponsorBridge.getSegments(sourceUrl)
                        } else {
                            emptyList()
                        }
                        player?.play(streamUrl, segments)
                        if (segments.isNotEmpty()) {
                            Toast.makeText(
                                this@PlayerActivity,
                                "SponsorBlock : ${segments.size} segment(s) à skipper",
                                           Toast.LENGTH_SHORT
                            ).show()
                        }
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
