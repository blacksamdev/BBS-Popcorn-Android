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
import io.github.blacksamdev.popcorn.player.CastManager
import kotlinx.coroutines.launch

/**
 * MainActivity — v0.3 : WebView YouTube + historique + réglages + reprise.
 *
 * Cast : l'icône cast ouvre le dialog natif Google pour CHOISIR un appareil.
 * Mais si une session BBS Popcorn est déjà active avec un média en cours,
 * le clic ouvre NOTRE télécommande (CastControlActivity) au lieu du dialog,
 * comme l'app YouTube officielle.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val YOUTUBE_HOME = "https://m.youtube.com"
        private const val INTERCEPT_DEBOUNCE_MS = 2000L
        const val PREFS_NAME = "bbs_popcorn"
        const val PREF_QUALITY = "quality"
        const val PREF_SPONSORBLOCK = "sponsorblock_enabled"
        const val DEFAULT_QUALITY = "1080"
        private val QUALITIES = arrayOf("480", "720", "1080", "1440", "2160")
    }

    private lateinit var binding: ActivityMainBinding
    private var castManager: CastManager? = null

    private var lastInterceptedId: String? = null
    private var lastInterceptedAt: Long = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        YtdlpBridge.init(applicationContext)
        HistoryBridge.init(applicationContext)
        ResumeBridge.init(applicationContext)

        CastContext.getSharedInstance(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bouton Cast natif
        CastButtonFactory.setUpMediaRouteButton(
            applicationContext, binding.mediaRouteButton
        )

        // CastManager pour détecter une session active et son média
        castManager = CastManager(this).also { it.register() }

        // Interception du clic cast via un overlay transparent au-dessus du bouton.
        // Si média BBS actif → notre télécommande ; sinon → dialog natif (touch relayé).
        binding.castOverlay.setOnClickListener {
            val cm = castManager
            if (cm?.isConnected == true && cm.hasActiveMedia()) {
                val ctrl = Intent(this, CastControlActivity::class.java).apply {
                    putExtra(CastControlActivity.EXTRA_TITLE, cm.currentMediaTitle())
                }
                startActivity(ctrl)
            } else {
                // Pas de média → on déclenche le dialog natif du MediaRouteButton
                binding.mediaRouteButton.performClick()
            }
        }

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

            // Cast actif : il faut un flux progressif (le Chromecast ne peut
            // pas lire le HLS de YouTube, en-têtes CORS absents côté serveur).
            val casting = castManager?.isConnected == true
            val info = YtdlpBridge.fetchInfo(
                cleanUrl,
                quality = currentQuality(),
                progressiveOnly = casting,
            )

            binding.loadingOverlay.visibility = View.GONE

            if (info == null) {
                // En cast, l'absence de progressif signifie presque toujours
                // un direct en cours : YouTube ne le diffuse qu'en HLS.
                if (casting) {
                    // Expliquer précisément pourquoi aucun flux castable
                    val report = YtdlpBridge.progressiveReport(cleanUrl)
                    val detail = try {
                        val o = org.json.JSONObject(report)
                        val arr = o.optJSONArray("muxed")
                        val list = if (arr != null && arr.length() > 0)
                            (0 until arr.length()).joinToString("\n") { arr.optString(it) }
                        else getString(R.string.cast_no_progressive)
                        getString(R.string.cast_formats_seen, o.optInt("n")) + "\n" + list
                    } catch (e: Exception) {
                        ""
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.cast_live_unsupported_title))
                        .setMessage(
                            getString(R.string.cast_live_unsupported_message) +
                                    "\n\n" + detail
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.resolve_fail_title))
                        .setMessage(getString(R.string.resolve_fail_message))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                return@launch
            }

            HistoryBridge.add(cleanUrl, info.title)

            val playerIntent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL, info.streamUrl)
                putExtra(PlayerActivity.EXTRA_TITLE, info.title)
                putExtra(PlayerActivity.EXTRA_SOURCE_URL, cleanUrl)
                putExtra(PlayerActivity.EXTRA_IS_LIVE, info.isLive)
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
        castManager?.unregister()
        castManager = null
        binding.webView.destroy()
        super.onDestroy()
    }
}
