package com.example.soundlens.ui.result

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.soundlens.audio.AudioPlayer
import com.example.soundlens.audio.AudioUtils
import com.example.soundlens.aws.AwsConfig
import com.example.soundlens.aws.Presigner
import com.example.soundlens.data.models.IdentifyResponse
import com.example.soundlens.parsing.IdentifyParser
import com.example.soundlens.uiutils.Formatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File


class ResultViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableLiveData(ResultUiState())
    val state: LiveData<ResultUiState> = _state

    private val player = AudioPlayer()
    private var ticker: Job? = null
    private var startHighlightMs: Int = 0

    fun initWithPayload(
        payloadJson: String,
        title: String,
        artist: String,
        year: Int,
        genre: String,
        offsetFrames: Int
    ) {
        val resp = IdentifyParser.parseOrNull(payloadJson)
        startHighlightMs = when {
            (resp?.highlight_sec ?: 0) > 0 -> (resp?.highlight_sec ?: 0) * 1000
            offsetFrames > 0 -> AudioUtils.framesToMs(offsetFrames)
            else -> 0
        }
        val subtitle = buildString {
            append(artist)
            if (year > 0) append(" • $year")
            if (genre.isNotBlank()) append(" • $genre")
        }
        _state.value = _state.value!!.copy(
            prettyJson = IdentifyParser.pretty(payloadJson),
            metaTitle = title,
            metaSubtitle = subtitle,
            response = resp,
            showRadar = (resp?.clipFeatures != null && resp.idealFeatures != null),
            error = null
        )

        viewModelScope.launch { downloadAndPrepare(resp) }
    }

    fun togglePlay() {
        if (_state.value?.audioReady != true) return
        if (player.isPlaying()) {
            player.pause()
            stopTicker()
            _state.value = _state.value!!.copy(isPlaying = false)
        } else {
            player.start()
            startTicker()
            _state.value = _state.value!!.copy(isPlaying = true)
        }
    }

    fun seekTo(ms: Int) {
        if (_state.value?.audioReady != true) return
        player.seekTo(ms)
        _state.value = _state.value!!.copy(
            elapsedMs = ms,
            remainingMs = (player.duration() - ms).coerceAtLeast(0)
        )
    }

    private suspend fun downloadAndPrepare(resp: IdentifyResponse?) {
        try {
            _state.postValue(_state.value!!.copy(loading = true, error = null))

            val playable = withContext(Dispatchers.IO) { getPlayableUrl(resp) }
            if (playable == null) {
                _state.postValue(_state.value!!.copy(loading = false, error = "No audio URL (s3_url/s3_key)"))
                return
            }

            val bytes = httpGetBytes(playable)
            val ctx = getApplication<Application>()
            val f = File.createTempFile("song_", AudioUtils.guessExt(playable), ctx.cacheDir)
            f.outputStream().use { it.write(bytes) }

            player.setOnPrepared {
                if (startHighlightMs > 0) runCatching { player.seekTo(startHighlightMs) }
                player.start()
                startTicker()
                _state.postValue(_state.value!!.copy(
                    loading = false,
                    audioReady = true,
                    isPlaying = true,
                    localFile = f
                ))
            }
            player.setOnCompletion {
                stopTicker()
                _state.postValue(_state.value!!.copy(
                    isPlaying = false,
                    elapsedMs = player.duration(),
                    remainingMs = 0
                ))
            }
            player.prepare(f.absolutePath)
        } catch (e: Exception) {
            _state.postValue(_state.value!!.copy(loading = false, error = "${e::class.java.simpleName}: ${e.message}"))
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                val dur = player.duration().coerceAtLeast(0)
                val pos = player.currentPosition().coerceAtLeast(0)
                _state.value = _state.value!!.copy(
                    elapsedMs = pos,
                    remainingMs = (dur - pos).coerceAtLeast(0),
                    isPlaying = player.isPlaying()
                )
                delay(500)
            }
        }
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    private fun isPresigned(url: String): Boolean =
        url.contains("X-Amz-Algorithm=", true) || url.contains("X-Amz-Signature=", true)

    private fun extractKeyFromS3Url(url: String, bucket: String): String? =
        try {
            val u = Uri.parse(url)
            val host = u.host ?: return null
            val path = u.encodedPath?.removePrefix("/") ?: return null
            if (host.startsWith("$bucket.s3")) path else null
        } catch (_: Exception) { null }

    private fun getPlayableUrl(resp: IdentifyResponse?): String? {
        if (resp == null) return null
        val keyFromRoot = resp.s3_key?.takeIf { !it.isNullOrBlank() }
        val keyFromTop  = resp.top_matches?.firstOrNull()?.s3_key?.takeIf { !it.isNullOrBlank() }
        val key = keyFromRoot ?: keyFromTop
        if (key != null) {
            val clean = key.trim().removePrefix("/").replace("\\", "/")
            val finalKey = if (clean.contains("/")) clean
            else "songs/${Presigner.mapGenreToFolder(resp.genre)}/$clean"
            return Presigner.presign(AwsConfig.BUCKET, finalKey)
        }

        resp.s3_url?.let { raw ->
            if (isPresigned(raw) || !raw.contains(".s3.", true)) return raw
            extractKeyFromS3Url(raw, AwsConfig.BUCKET)?.let { k ->
                return Presigner.presign(AwsConfig.BUCKET, k)
            }
            return raw
        }
        return null
    }

    private suspend fun httpGetBytes(url: String): ByteArray =
        withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} al descargar audio")
                resp.body?.bytes() ?: throw IllegalStateException("Cuerpo vacío")
            }
        }
    fun formatOffset(): String {
        val resp = state.value?.response
        val highlight = resp?.highlight_sec ?: 0
        val offset = resp?.offset_frames ?: 0
        return when {
            highlight > 0 -> "Highlight: ${Formatter.fmtMs(highlight * 1000)}"
            offset > 0 -> "Highlight: ${Formatter.fmtMs(AudioUtils.framesToMs(offset))}"
            else -> "Highlight: —"
        }
    }

    fun formatBestMatches(): String {
        val v = state.value?.response?.bestMatches
        return "Matches at best offset: ${v ?: "—"}"
    }

    fun formatTotalMatches(): String {
        val v = state.value?.response?.totalMatches
        return "Total matches: ${v ?: "—"}"
    }

    fun formatPredictedGenre(): String {
        val r = state.value?.response
        val confidence = r?.confidence?.let { " • conf ${"%.3f".format(it)}" } ?: ""
        return "Predicted: ${r?.predictedGenre ?: "—"}$confidence"
    }

    private fun fmtFeatureRow(label: String, value: Double?): String {
        return "%s: %s".format(label, value?.let { "%.4f".format(it) } ?: "—")
    }

    fun formatFeatures(): String {
        val clip = state.value?.response?.clipFeatures
        val ideal = state.value?.response?.idealFeatures
        if (clip == null && ideal == null) return "Features: —"
        val clipText = listOf(
            fmtFeatureRow("rms", clip?.rms),
            fmtFeatureRow("zcr", clip?.zcr),
            fmtFeatureRow("sc_hz", clip?.sc_hz)
        ).joinToString("  ")
        val idealText = listOf(
            fmtFeatureRow("rms", ideal?.rms),
            fmtFeatureRow("zcr", ideal?.zcr),
            fmtFeatureRow("sc_hz", ideal?.sc_hz)
        ).joinToString("  ")
        return "Clip →  $clipText\nIdeal → $idealText"
    }

    fun formatDistances(): String {
        val d = state.value?.response?.genreDistances ?: return "Distances: —"
        val sorted = d.toList().sortedBy { it.second }
        val rows = sorted.joinToString("\n") { (g, v) ->
            "%s: %.4f".format(g, v)
        }
        return "Distances:\n$rows"
    }

    override fun onCleared() {
        super.onCleared()
        stopTicker()
        player.release()
    }
}
