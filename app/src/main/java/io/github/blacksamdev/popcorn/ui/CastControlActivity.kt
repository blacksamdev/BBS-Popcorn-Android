package io.github.blacksamdev.popcorn.ui

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.blacksamdev.popcorn.R
import io.github.blacksamdev.popcorn.databinding.ActivityCastControlBinding
import io.github.blacksamdev.popcorn.player.CastManager

/**
 * CastControlActivity — télécommande de lecture pour le cast Chromecast.
 *
 * - Play / pause
 * - Sauts rapides : −30 / −10 / +10 / +30 s (cumulables en cliquant plusieurs fois)
 * - Barre de progression (seek libre) avec minutage qui s'affiche au toucher
 * - Position / durée mises à jour en temps réel via le ProgressListener
 *
 * Les contrôles natifs (notification / écran verrouillé) sont fournis
 * automatiquement par le Cast SDK dès que le seek est supporté.
 */
class CastControlActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var binding: ActivityCastControlBinding
    private var castManager: CastManager? = null

    // true tant que l'utilisateur fait glisser la barre (on n'écrase pas sa valeur)
    private var userSeeking = false
    private var lastDurationMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCastControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        binding.textTitle.text = title

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

        binding.textDevice.text = castManager?.deviceName?.let {
            getString(R.string.cast_on_device, it)
        } ?: ""

        setupControls()
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            castManager?.togglePlayPause()
            updatePlayPauseIcon()
        }

        binding.btnBack30.setOnClickListener { castManager?.seekBy(-30_000) }
        binding.btnBack10.setOnClickListener { castManager?.seekBy(-10_000) }
        binding.btnFwd10.setOnClickListener { castManager?.seekBy(+10_000) }
        binding.btnFwd30.setOnClickListener { castManager?.seekBy(+30_000) }

        binding.btnStop.setOnClickListener {
            castManager?.stop()
            finish()
        }

        binding.seekBar.max = 1000
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && lastDurationMs > 0) {
                    // Affiche le minutage cible pendant le glissement
                    val targetMs = lastDurationMs * progress / 1000
                    binding.textPosition.text = formatMs(targetMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                if (lastDurationMs > 0) {
                    val targetMs = lastDurationMs * sb.progress / 1000
                    castManager?.seekTo(targetMs)
                }
            }
        })
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
