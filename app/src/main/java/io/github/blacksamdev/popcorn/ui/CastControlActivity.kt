package io.github.blacksamdev.popcorn.ui

import android.os.Bundle
import android.view.KeyEvent
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.blacksamdev.popcorn.R
import io.github.blacksamdev.popcorn.databinding.ActivityCastControlBinding
import io.github.blacksamdev.popcorn.player.CastManager

/**
 * CastControlActivity — télécommande de lecture pour le cast Chromecast.
 * Disposition inspirée de la remote native, habillée aux couleurs pOpcOrn.
 *
 * Deux modes d'ouverture :
 * - Depuis PlayerActivity au lancement d'une vidéo (EXTRA_TITLE fourni)
 * - Depuis l'icône cast quand une session est déjà active (titre lu depuis
 *   la session en cours)
 */
class CastControlActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IS_LIVE = "extra_is_live"
        private const val VOLUME_STEP = 0.05
        // En deçà de ce retard, on considère qu'on est au bord du direct
        private const val LIVE_EDGE_TOLERANCE_MS = 10_000L
    }

    private lateinit var binding: ActivityCastControlBinding
    private var castManager: CastManager? = null

    // Mode direct
    private var liveMode = false
    private var dvrAvailable: Boolean? = null  // null = état non encore appliqué

    private var userSeeking = false
    private var userVoluming = false
    private var lastDurationMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCastControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        castManager = CastManager(this).also { cm ->
            cm.register()
            cm.onProgress = { posMs, durMs -> onProgress(posMs, durMs) }
            cm.onCastDisconnected = {
                runOnUiThread {
                    Toast.makeText(
                        this, getString(R.string.cast_disconnected), Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }

        // Si aucune session active à l'ouverture → rien à contrôler, on ferme
        if (castManager?.isConnected != true) {
            Toast.makeText(this, getString(R.string.cast_disconnected), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Titre : depuis l'intent, sinon depuis la session en cours
        val titleFromIntent = intent.getStringExtra(EXTRA_TITLE)
        val title = if (!titleFromIntent.isNullOrBlank()) {
            titleFromIntent
        } else {
            castManager?.currentMediaTitle() ?: ""
        }
        binding.textTitle.text = title

        val device = castManager?.deviceName ?: getString(R.string.cast_device_fallback)
        binding.textDevice.text = getString(R.string.cast_disconnect_label, device)

        // Mode direct : on conserve play/pause et, si un buffer DVR existe,
        // la timeline et les sauts (revoir une action, passer un temps mort).
        liveMode = intent.getBooleanExtra(EXTRA_IS_LIVE, false) ||
                castManager?.isLiveStream() == true
        if (liveMode) {
            binding.textDuration.visibility = android.view.View.GONE
            binding.btnLive.visibility = android.view.View.VISIBLE
            applyDvrState(castManager?.liveWindow() != null)
        }

        setupControls()
        initVolumeBar()
    }

    private fun setupControls() {
        binding.btnClose.setOnClickListener { finish() }

        // Pilule appareil : tap → confirmation → arrêter la diffusion
        binding.textDevice.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.cast_disconnect_title))
                .setMessage(getString(R.string.cast_disconnect_message))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    castManager?.endSession()
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnPlayPause.setOnClickListener {
            castManager?.togglePlayPause()
            updatePlayPauseIcon()
        }

        // Retour au bord du direct
        binding.btnLive.setOnClickListener {
            castManager?.jumpToLiveEdge()
        }

        binding.btnBack30.setOnClickListener { castManager?.seekBy(-30_000) }
        binding.btnBack10.setOnClickListener { castManager?.seekBy(-10_000) }
        binding.btnFwd10.setOnClickListener { castManager?.seekBy(+10_000) }
        binding.btnFwd30.setOnClickListener { castManager?.seekBy(+30_000) }

        binding.seekBar.max = 1000
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val win = if (liveMode) castManager?.liveWindow() else null
                if (win != null) {
                    val (start, end) = win
                    val target = start + (end - start) * progress / 1000
                    binding.textPosition.text = formatDelay(end - target)
                } else if (lastDurationMs > 0) {
                    binding.textPosition.text = formatMs(lastDurationMs * progress / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                val win = if (liveMode) castManager?.liveWindow() else null
                if (win != null) {
                    // Direct : la timeline couvre la fenêtre DVR
                    val (start, end) = win
                    castManager?.seekTo(start + (end - start) * sb.progress / 1000)
                } else if (lastDurationMs > 0) {
                    castManager?.seekTo(lastDurationMs * sb.progress / 1000)
                }
            }
        })

        binding.volumeBar.max = 100
        binding.volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    castManager?.setVolume(progress / 100.0)
                    binding.textVolume.text = "$progress %"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userVoluming = true }
            override fun onStopTrackingTouch(sb: SeekBar) { userVoluming = false }
        })
    }

    private fun initVolumeBar() {
        val vol = ((castManager?.getVolume() ?: 0.0) * 100).toInt()
        binding.volumeBar.progress = vol
        binding.textVolume.text = "$vol %"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                castManager?.adjustVolume(+VOLUME_STEP); refreshVolumeBar(); true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                castManager?.adjustVolume(-VOLUME_STEP); refreshVolumeBar(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun refreshVolumeBar() {
        if (!userVoluming) {
            val vol = ((castManager?.getVolume() ?: 0.0) * 100).toInt()
            binding.volumeBar.progress = vol
            binding.textVolume.text = "$vol %"
        }
    }

    private fun onProgress(posMs: Long, durMs: Long) {
        runOnUiThread {
            updatePlayPauseIcon()
            refreshVolumeBar()

            if (liveMode) {
                onProgressLive(posMs)
                return@runOnUiThread
            }

            lastDurationMs = durMs
            binding.textDuration.text = formatMs(durMs)
            if (!userSeeking) {
                binding.textPosition.text = formatMs(posMs)
                binding.seekBar.progress =
                    if (durMs > 0) (posMs * 1000 / durMs).toInt() else 0
            }
        }
    }

    /**
     * Progression sur un direct : la timeline couvre la fenêtre DVR et
     * textPosition affiche le retard sur le direct (« ● EN DIRECT » au bord).
     */
    private fun onProgressLive(posMs: Long) {
        val win = castManager?.liveWindow()
        applyDvrState(win != null)
        if (win == null) {
            binding.textPosition.text = getString(R.string.cast_live_badge)
            binding.btnLive.isSelected = true
            return
        }
        val (start, end) = win
        val atEdge = posMs >= end - LIVE_EDGE_TOLERANCE_MS
        binding.btnLive.isSelected = atEdge
        if (!userSeeking) {
            binding.textPosition.text =
                if (atEdge) getString(R.string.cast_live_badge)
                else formatDelay(end - posMs)
            binding.seekBar.progress =
                (((posMs - start).coerceAtLeast(0L) * 1000) / (end - start)).toInt()
        }
    }

    /**
     * Active ou masque les contrôles de navigation selon la présence
     * d'un buffer DVR (certains directs ne permettent aucun retour arrière).
     */
    private fun applyDvrState(available: Boolean) {
        if (dvrAvailable == available) return
        dvrAvailable = available
        val vis = if (available) android.view.View.VISIBLE else android.view.View.GONE
        binding.seekBar.visibility = vis
        binding.btnBack30.visibility = vis
        binding.btnBack10.visibility = vis
        binding.btnFwd10.visibility = vis
        binding.btnFwd30.visibility = vis
        // Le badge direct reste visible même sans DVR (simple indicateur)
        binding.btnLive.visibility = android.view.View.VISIBLE
    }

    /** Formate un retard sur le direct : « -1:23 ». */
    private fun formatDelay(deltaMs: Long): String =
        "-" + formatMs(deltaMs.coerceAtLeast(0L))

    private fun updatePlayPauseIcon() {
        val playing = castManager?.isPlaying == true
        binding.btnPlayPause.setImageResource(
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalS = ms / 1000
        val h = totalS / 3600
        val m = (totalS % 3600) / 60
        val s = totalS % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        castManager?.unregister()
        castManager = null
    }
}
