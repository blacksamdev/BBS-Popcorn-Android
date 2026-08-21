package io.github.blacksamdev.popcorn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import io.github.blacksamdev.popcorn.R
import io.github.blacksamdev.popcorn.bridge.HistoryBridge
import io.github.blacksamdev.popcorn.bridge.ResumeBridge
import io.github.blacksamdev.popcorn.bridge.YtdlpBridge
import io.github.blacksamdev.popcorn.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * MainActivity — v0.3 : WebView YouTube + historique + réglages + reprise.
 *
 * L'app ne caste pas : le lecteur générique du Chromecast ne lit pas les flux
 * adaptatifs de YouTube (en-têtes CORS absents), ce qui limitait le cast à du
 * 360p et interdisait les directs. Un bouton renvoie plutôt vers l'application
 * YouTube officielle, qui gère la TV bien mieux que nous.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val YOUTUBE_HOME = "https://m.youtube.com"
        private const val INTERCEPT_DEBOUNCE_MS = 2000L
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        const val PREFS_NAME = "bbs_popcorn"
        const val PREF_QUALITY = "quality"
        const val PREF_SPONSORBLOCK = "sponsorblock_enabled"
        const val DEFAULT_QUALITY = "1080"
        private val QUALITIES = arrayOf("480", "720", "1080", "1440", "2160")
    }

    private lateinit var binding: ActivityMainBinding

    private var lastInterceptedId: String? = null
    // Dernière vidéo ouverte : cible privilégiée du bouton YouTube
    private var lastVideoUrl: String? = null
    private var lastInterceptedAt: Long = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        YtdlpBridge.init(applicationContext)
        HistoryBridge.init(applicationContext)
        ResumeBridge.init(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bouton « Ouvrir dans YouTube » (TV, sous-titres, qualité maximale)
        binding.btnYoutube.setOnClickListener { openInYouTube() }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.btnQuality.setOnClickListener { showSettingsDialog() }

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (!handleShareIntent(intent)) {
            binding.webView.loadUrl(YOUTUBE_HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)  // requis avec launchMode=singleTask
        handleShareIntent(intent)
    }

    // ─────────────────────────────
    // Réglages
    // ─────────────────────────────

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun currentQuality(): String {
        return prefs().getString(PREF_QUALITY, DEFAULT_QUALITY) ?: DEFAULT_QUALITY
    }

    private fun sponsorBlockEnabled(): Boolean {
        return prefs().getBoolean(PREF_SPONSORBLOCK, false)
    }

    private fun showSettingsDialog() {
        val sbState = if (sponsorBlockEnabled())
            getString(R.string.settings_sb_on) else getString(R.string.settings_sb_off)

        val items = arrayOf(
            getString(R.string.quality_title) + " — ${currentQuality()}p",
            getString(R.string.settings_sponsorblock) + " — $sbState",
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showQualityDialog()
                    1 -> toggleSponsorBlock()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showQualityDialog() {
        val current = currentQuality()
        val checked = QUALITIES.indexOf(current).coerceAtLeast(0)
        val labels = QUALITIES.map { "${it}p" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.quality_title))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs().edit().putString(PREF_QUALITY, QUALITIES[which]).apply()
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

    private fun toggleSponsorBlock() {
        val newState = !sponsorBlockEnabled()
        if (newState) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_sponsorblock))
                .setMessage(getString(R.string.settings_sb_consent))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs().edit().putBoolean(PREF_SPONSORBLOCK, true).apply()
                    Toast.makeText(
                        this, getString(R.string.settings_sb_enabled), Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            prefs().edit().putBoolean(PREF_SPONSORBLOCK, false).apply()
            Toast.makeText(
                this, getString(R.string.settings_sb_disabled), Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ─────────────────────────────
    // Renvoi vers l'application YouTube
    // ─────────────────────────────

    /**
     * Ouvre dans l'application YouTube officielle : la dernière vidéo lue si
     * elle existe, sinon la page en cours dans la WebView. Permet de caster
     * sur la TV en pleine qualité ou d'accéder aux sous-titres — deux choses
     * que l'app ne peut pas offrir elle-même.
     */
    private fun openInYouTube() {
        val target = lastVideoUrl ?: binding.webView.url ?: YOUTUBE_HOME
        val uri = Uri.parse(target)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(YOUTUBE_PACKAGE))
            return
        } catch (_: Exception) {
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.youtube_missing), Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────────
    // WebView
    // ─────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

        webView.settings.apply {
            javaScriptEnabled = true          // requis par YouTube
            domStorageEnabled = true          // requis par YouTube
            mediaPlaybackRequiresUserGesture = true
            // Durcissement : la WebView ne doit jamais lire le stockage local
            allowFileAccess = false
            allowContentAccess = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (isWatchUrl(url)) {
                    interceptVideo(url, navigatedInWebView = false)
                    return true
                }
                // Garde-fou : la WebView reste sur les domaines YouTube/Google.
                // Tout lien externe s'ouvre dans le navigateur système.
                if (!isAllowedHost(request.url.host)) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return false
            }

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
     * Domaines autorisés dans la WebView : YouTube et l'écosystème Google
     * nécessaire (connexion au compte, ressources statiques).
     */
    private fun isAllowedHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        val allowed = listOf(
            "youtube.com", "youtu.be", "google.com", "googleusercontent.com",
            "googlevideo.com", "ytimg.com", "ggpht.com", "gstatic.com",
            "googleapis.com", "googletagmanager.com", "accounts.google.fr",
        )
        return allowed.any { h == it || h.endsWith(".$it") }
    }

    private fun isWatchUrl(url: String): Boolean {
        return url.contains("/watch?v=") || url.contains("youtu.be/")
    }

    private fun extractVideoId(url: String): String? {
        val watchMatch = Regex("""[?&]v=([A-Za-z0-9_-]{6,})""").find(url)
        if (watchMatch != null) return watchMatch.groupValues[1]
        val shortMatch = Regex("""youtu\.be/([A-Za-z0-9_-]{6,})""").find(url)
        return shortMatch?.groupValues?.get(1)
    }

    private fun interceptVideo(url: String, navigatedInWebView: Boolean) {
        val videoId = extractVideoId(url) ?: return

        val now = System.currentTimeMillis()
        if (videoId == lastInterceptedId && now - lastInterceptedAt < INTERCEPT_DEBOUNCE_MS) {
            return
        }
        lastInterceptedId = videoId
        lastInterceptedAt = now

        if (navigatedInWebView) {
            binding.webView.stopLoading()
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }

        lastVideoUrl = "https://www.youtube.com/watch?v=$videoId"
        resolveAndPlay(lastVideoUrl!!)
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
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.resolve_fail_title))
                    .setMessage(getString(R.string.resolve_fail_message))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

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
