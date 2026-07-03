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
        private const val VOLUME_STEP = 0.05
    }

    private lateinit var binding: ActivityCastControlBinding
    private var castManager: CastManager? = null

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

        binding.textDevice.text = castManager?.deviceName ?: getString(R.string.cast_device_fallback)

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

        binding.btnBack30.setOnClickListener { castManager?.seekBy(-30_000) }
        binding.btnBack10.setOnClickListener { castManager?.seekBy(-10_000) }
        binding.btnFwd10.setOnClickListener { castManager?.seekBy(+10_000) }
        binding.btnFwd30.setOnClickListener { castManager?.seekBy(+30_000) }

        binding.seekBar.max = 1000
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && lastDurationMs > 0) {
                    val targetMs = lastDurationMs * progress / 1000
                    binding.textPosition.text = formatMs(targetMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                if (lastDurationMs > 0) {
                    val targetMs = lastDurationMs * sb.progress / 1000
                    castManager?.seekTo(targetMs)
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
            lastDurationMs = durMs
            binding.textDuration.text = formatMs(durMs)
            updatePlayPauseIcon()
            if (!userSeeking) {
                binding.textPosition.text = formatMs(posMs)
                binding.seekBar.progress =
                    if (durMs > 0) (posMs * 1000 / durMs).toInt() else 0
            }
            refreshVolumeBar()
        }
    }

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
