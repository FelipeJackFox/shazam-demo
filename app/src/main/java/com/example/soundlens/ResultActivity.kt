package com.example.soundlens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.soundlens.databinding.ActivityResultBinding
import com.example.soundlens.network.Network
import com.example.soundlens.network.PlotRequest
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private var ytUrl: String = ""
    private var ytId: String = ""
    private var requestId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra("title") ?: "—"
        val artist = intent.getStringExtra("artist") ?: "—"
        val year = intent.getIntExtra("year", -1)
        ytId = intent.getStringExtra("youtube_id") ?: ""
        ytUrl = intent.getStringExtra("youtube_url") ?: ""
        requestId = intent.getStringExtra("request_id") ?: ""
        val offset = intent.getIntExtra("offset_frames", -1)
        val bestMatches = intent.getIntExtra("matches_at_best_offset", -1)
        val totalMatches = intent.getIntExtra("matches_for_song", -1)
        val confidence = intent.getDoubleExtra("confidence", 0.0)

        binding.txtSong.text = title
        binding.txtMeta.text = buildString {
            append(artist)
            if (year > 0) append("  -  $year")
        }
        binding.txtOffset.text = "Offset frames: ${if (offset >= 0) offset else "—"}"
        binding.txtBestMatches.text = "Best consequent matches: ${if (bestMatches >= 0) bestMatches else "—"}"
        binding.txtTotalMatches.text = "Total matches: ${if (totalMatches >= 0) totalMatches else "—"}"

        // Link visible (no auto-open). Si se toca, abre YouTube.
        if (ytUrl.isNotBlank()) {
            binding.txtYoutubeLink.text = ytUrl
            binding.txtYoutubeLink.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ytUrl)))
            }
        } else {
            binding.txtYoutubeLink.text = ""
        }

        // YouTube player embebido con manejo de error (no auto redirige)
        lifecycle.addObserver(binding.youtubePlayerView)
        val options = IFramePlayerOptions.Builder().controls(1).build()
        binding.youtubePlayerView.enableAutomaticInitialization = false
        binding.youtubePlayerView.initialize(object : YouTubePlayerListener {
            override fun onReady(player: YouTubePlayer) {
                if (ytId.isNotBlank()) player.loadVideo(ytId, 0f)
            }
            override fun onError(player: YouTubePlayer, error: PlayerConstants.PlayerError) {
                // Si el propietario bloquea embedding (15/150/101), el link ya queda visible arriba.
            }
            override fun onApiChange(player: YouTubePlayer) {}
            override fun onCurrentSecond(player: YouTubePlayer, second: Float) {}
            override fun onPlaybackQualityChange(player: YouTubePlayer, playbackQuality: PlayerConstants.PlaybackQuality) {}
            override fun onPlaybackRateChange(player: YouTubePlayer, playbackRate: PlayerConstants.PlaybackRate) {}
            override fun onStateChange(player: YouTubePlayer, state: PlayerConstants.PlayerState) {}
            override fun onVideoDuration(player: YouTubePlayer, duration: Float) {}
            override fun onVideoId(player: YouTubePlayer, videoId: String) {}
            override fun onVideoLoadedFraction(player: YouTubePlayer, loadedFraction: Float) {}
        }, options)

        // Botón para pedir la gráfica (Plot Lambda) y abrir en horizontal
        binding.btnGraph.setOnClickListener {
            if (requestId.isBlank()) {
                Toast.makeText(this, "No request_id in response", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launchWhenStarted {
                try {
                    binding.btnGraph.isEnabled = false
                    binding.btnGraph.text = "Generating…"

                    val plotApi = Network.plotApi(this@ResultActivity)
                    val resp = plotApi.createPlot(PlotRequest(request_id = requestId))
                    val url = resp.plot_url

                    if (!url.isNullOrBlank()) {
                        startActivity(Intent(this@ResultActivity, GraphActivity::class.java).apply {
                            putExtra("plot_url", url)
                        })
                    } else {
                        Toast.makeText(this@ResultActivity, "No plot_url returned", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@ResultActivity, "Plot error: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    binding.btnGraph.isEnabled = true
                    binding.btnGraph.text = "Create match graph"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.youtubePlayerView.release()
    }
}
