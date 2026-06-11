package io.github.blacksamdev.popcorn.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import io.github.blacksamdev.popcorn.bridge.YtdlpBridge
import io.github.blacksamdev.popcorn.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * MainActivity — écran principal BBS Popcorn Android.
 *
 * v0.1 : champ URL → résolution yt-dlp → PlayerActivity.
 * Le WebView YouTube intégré viendra dans une itération suivante.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            // Init Chaquopy (une seule fois pour toute l'app)
            YtdlpBridge.init(applicationContext)

            // Init Cast SDK
            CastContext.getSharedInstance(applicationContext)

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Bouton Cast dans la toolbar
            CastButtonFactory.setUpMediaRouteButton(
                applicationContext, binding.mediaRouteButton
            )

            binding.btnPlay.setOnClickListener {
                val url = binding.editUrl.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "Colle une URL YouTube", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                resolveAndPlay(url)
            }

            // Gestion du partage depuis l'app YouTube (Intent ACTION_SEND)
            handleShareIntent(intent)
        }

        override fun onNewIntent(intent: Intent) {
            super.onNewIntent(intent)
            handleShareIntent(intent)
        }

        /**
         * Permet de partager une vidéo depuis l'app YouTube vers BBS Popcorn.
         */
        private fun handleShareIntent(intent: Intent?) {
            if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
                val sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                if (!sharedUrl.isNullOrEmpty()) {
                    binding.editUrl.setText(sharedUrl)
                    resolveAndPlay(sharedUrl)
                }
            }
        }

        private fun resolveAndPlay(rawUrl: String) {
            binding.progressBar.visibility = View.VISIBLE
            binding.btnPlay.isEnabled = false

            lifecycleScope.launch {
                val cleanUrl = YtdlpBridge.prepareUrl(rawUrl)
                val info = YtdlpBridge.fetchInfo(cleanUrl)

                binding.progressBar.visibility = View.GONE
                binding.btnPlay.isEnabled = true

                if (info == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Impossible de résoudre cette vidéo",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val playerIntent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, info.streamUrl)
                    putExtra(PlayerActivity.EXTRA_TITLE, info.title)
                    putExtra(PlayerActivity.EXTRA_SOURCE_URL, cleanUrl)
                }
                startActivity(playerIntent)
            }
        }
}
