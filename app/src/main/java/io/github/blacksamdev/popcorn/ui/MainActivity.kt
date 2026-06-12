package io.github.blacksamdev.popcorn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import io.github.blacksamdev.popcorn.R
import io.github.blacksamdev.popcorn.bridge.HistoryBridge
import io.github.blacksamdev.popcorn.bridge.ResumeBridge
import io.github.blacksamdev.popcorn.bridge.YtdlpBridge
import io.github.blacksamdev.popcorn.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * MainActivity — v0.3 : WebView YouTube + historique + qualité + reprise.
 *
 * L'utilisateur navigue sur m.youtube.com normalement (recherche, abonnements,
 * recommandations). Au clic sur une vidéo, BBS intercepte la navigation,
 * bloque la lecture YouTube (et ses pubs) et lance la lecture propre
 * via yt-dlp → Media3.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val YOUTUBE_HOME = "https://m.youtube.com"
        private const val INTERCEPT_DEBOUNCE_MS = 2000L
        private const val PREFS_NAME = "bbs_popcorn"
        private const val PREF_QUALITY = "quality"
        private const val DEFAULT_QUALITY = "1080"
        private val QUALITIES = arrayOf("480", "720", "1080", "1440", "2160")
    }

    private lateinit var binding: ActivityMainBinding

    // Anti double-déclenchement (les deux hooks peuvent voir la même URL)
    private var lastInterceptedId: String? = null
    private var lastInterceptedAt: Long = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init Chaquopy (une seule fois pour toute l'app)
        YtdlpBridge.init(applicationContext)
        HistoryBridge.init(applicationContext)
        ResumeBridge.init(applicationContext)

        // Init Cast SDK
        CastContext.getSharedInstance(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bouton Cast dans la barre pOpcOrn
        CastButtonFactory.setUpMediaRouteButton(
            applicationContext, binding.mediaRouteButton
        )

        // Bouton historique
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Bouton qualité
        binding.btnQuality.setOnClickListener { showQualityDialog() }

        setupWebView()

        // Retour : navigation arrière dans la WebView avant de quitter l'app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })

        // Partage depuis l'app YouTube officielle (Intent ACTION_SEND)
        if (!handleShareIntent(intent)) {
            binding.webView.loadUrl(YOUTUBE_HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    // ─────────────────────────────
    // Qualité
    // ─────────────────────────────

    private fun currentQuality(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_QUALITY, DEFAULT_QUALITY) ?: DEFAULT_QUALITY
    }

    private fun showQualityDialog() {
        val current = currentQuality()
        val checked = QUALITIES.indexOf(current).coerceAtLeast(0)
        val labels = QUALITIES.map { "${it}p" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.quality_title))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_QUALITY, QUALITIES[which])
                    .apply()
                dialog.dismiss()
                Toast.makeText(
                    this,
                    getString(R.string.quality_set, QUALITIES[which]),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ─────────────────────────────
    // WebView
    // ─────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
        }

        // Cookies persistants : l'utilisateur reste connecté à son compte
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            // Navigations classiques (liens, redirections)
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (isWatchUrl(url)) {
                    interceptVideo(url, navigatedInWebView = false)
                    return true // on bloque le chargement de la page de lecture
                }
                return false
            }

            // Navigations SPA (YouTube change l'URL en JavaScript)
            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String,
                isReload: Boolean
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!isReload && isWatchUrl(url)) {
                    interceptVideo(url, navigatedInWebView = true)
                }
            }
        }
    }

    /**
     * Détecte une URL de lecture vidéo YouTube.
     */
    private fun isWatchUrl(url: String): Boolean {
        return url.contains("/watch?v=") || url.contains("youtu.be/")
    }

    /**
     * Extrait le video_id pour le debounce.
     */
    private fun extractVideoId(url: String): String? {
        val watchMatch = Regex("""[?&]v=([A-Za-z0-9_-]{6,})""").find(url)
        if (watchMatch != null) return watchMatch.groupValues[1]
        val shortMatch = Regex("""youtu\.be/([A-Za-z0-9_-]{6,})""").find(url)
        return shortMatch?.groupValues?.get(1)
    }

    /**
     * Interception : bloque la lecture YouTube et lance la chaîne BBS.
     * navigatedInWebView = true si la SPA a déjà navigué vers la page de
     * lecture → on fait goBack() pour revenir à la liste.
     */
    private fun interceptVideo(url: String, navigatedInWebView: Boolean) {
        val videoId = extractVideoId(url) ?: return

        // Debounce : les deux hooks peuvent intercepter la même navigation
        val now = System.currentTimeMillis()
        if (videoId == lastInterceptedId && now - lastInterceptedAt < INTERCEPT_DEBOUNCE_MS) {
            return
        }
        lastInterceptedId = videoId
        lastInterceptedAt = now

        if (navigatedInWebView) {
            // La SPA a déjà basculé sur la page de lecture : on coupe court
            binding.webView.stopLoading()
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }

        resolveAndPlay("https://www.youtube.com/watch?v=$videoId")
    }

    // ─────────────────────────────
    // Partage depuis l'app YouTube
    // ─────────────────────────────

    private fun handleShareIntent(intent: Intent?): Boolean {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!sharedUrl.isNullOrEmpty() && isWatchUrl(sharedUrl)) {
                resolveAndPlay(sharedUrl)
                return true
            }
        }
        return false
    }

    // ─────────────────────────────
    // Chaîne BBS : yt-dlp → Media3
    // ─────────────────────────────

    private fun resolveAndPlay(rawUrl: String) {
        binding.loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            val cleanUrl = YtdlpBridge.prepareUrl(rawUrl)
            val info = YtdlpBridge.fetchInfo(cleanUrl, quality = currentQuality())

            binding.loadingOverlay.visibility = View.GONE

            if (info == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.main_resolve_error),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            // Historique : enregistrer la lecture
            HistoryBridge.add(cleanUrl, info.title)

            val playerIntent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL, info.streamUrl)
                putExtra(PlayerActivity.EXTRA_TITLE, info.title)
                putExtra(PlayerActivity.EXTRA_SOURCE_URL, cleanUrl)
            }
            startActivity(playerIntent)
        }
    }

    // ─────────────────────────────
    // Lifecycle
    // ─────────────────────────────

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
