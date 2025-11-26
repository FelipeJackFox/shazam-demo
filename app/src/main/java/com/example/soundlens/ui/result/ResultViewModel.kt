package com.example.soundlens.ui.result

import android.app.Application
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File


class ResultViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "ResultVM" }

    private val _state = MutableLiveData(ResultUiState())
    val state: LiveData<ResultUiState> = _state

    private val player = AudioPlayer()
    private var ticker: Job? = null
    private var startHighlightMs: Int = 0

    init {
        player.setLogger { msg -> Log.d(TAG, msg) }
    }
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
        Log.d(TAG, "Init payload with highlightMs=$startHighlightMs title=$title artist=$artist year=$year genre=$genre")
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
            error = null,
            highlightMs = startHighlightMs
        )

        viewModelScope.launch { downloadAndPrepare(resp) }
    }

    fun togglePlay() {
        if (_state.value?.audioReady != true) return
        Log.d(TAG, "togglePlay() from isPlaying=${player.isPlaying()}")
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
        val target = ms.coerceIn(0, totalDuration())
        Log.d(TAG, "seekTo($target)")
        player.seekTo(target)
        _state.value = _state.value!!.copy(
            elapsedMs = target,
            remainingMs = (totalDuration() - target).coerceAtLeast(0),
            isPlaying = player.isPlaying()
        )
    }

    fun seekBy(deltaMs: Int) {
        if (_state.value?.audioReady != true) return
        val next = (player.currentPosition() + deltaMs).coerceIn(0, totalDuration())
        seekTo(next)
    }

    fun restartFromHighlight() {
        if (_state.value?.audioReady != true) return
        val target = startHighlightMs.coerceIn(0, totalDuration())
        Log.d(TAG, "restartFromHighlight() -> $target")
        seekTo(target)
        if (!player.isPlaying()) {
            player.start()
            startTicker()
            _state.value = _state.value!!.copy(isPlaying = true)
        }
    }

    private suspend fun downloadAndPrepare(resp: IdentifyResponse?) {
        try {
            _state.postValue(_state.value!!.copy(loading = true, error = null))

            val playable = withContext(Dispatchers.IO) { getPlayableUrl(resp) }
            if (playable == null) {
                _state.postValue(_state.value!!.copy(loading = false, error = "No audio URL (s3_url/s3_key)"))
                return
            }

            Log.d(TAG, "Downloading audio from $playable")

            val bytes = httpGetBytes(playable)
            Log.d(TAG, "Downloaded ${bytes.size} bytes from S3")
            val ctx = getApplication<Application>()
            val f = File.createTempFile("song_", AudioUtils.guessExt(playable), ctx.cacheDir)
            f.outputStream().use { it.write(bytes) }
            Log.d(TAG, "Audio cached at ${f.absolutePath}")

            player.setOnPrepared { mp ->
                val duration = mp.duration.coerceAtLeast(0)
                val current = mp.currentPosition.coerceAtLeast(0)
                val playing = mp.isPlaying
                Log.d(TAG, "player prepared (duration=$duration, pos=$current, playing=$playing), updating UI")
                if (playing) startTicker() else stopTicker()
                _state.postValue(_state.value!!.copy(
                    loading = false,
                    audioReady = true,
                    isPlaying = playing,
                    elapsedMs = current,
                    remainingMs = (duration - current).coerceAtLeast(0),
                    durationMs = duration,
                    highlightMs = startHighlightMs,
                    localFile = f
                ))
            }
            player.setOnCompletion {
                Log.d(TAG, "player completed")
                stopTicker()
                _state.postValue(_state.value!!.copy(
                    isPlaying = false,
                    elapsedMs = player.duration(),
                    remainingMs = 0,
                    durationMs = player.duration()
                ))
            }
            player.setOnError { what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                stopTicker()
                _state.postValue(_state.value!!.copy(
                    loading = false,
                    isPlaying = false,
                    audioReady = false,
                    error = "Playback error ($what/$extra)"
                ))
            }
            player.prepare(f.absolutePath, startHighlightMs, playWhenReady = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error preparando audio", e)
            _state.postValue(_state.value!!.copy(loading = false, error = "${e::class.java.simpleName}: ${e.message}"))
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                val dur = totalDuration().coerceAtLeast(0)
                val pos = player.currentPosition().coerceIn(0, dur)
                Log.d(TAG, "ticker dur=$dur pos=$pos playing=${player.isPlaying()}")
                _state.value = _state.value!!.copy(
                    elapsedMs = pos,
                    remainingMs = (dur - pos).coerceAtLeast(0),
                    durationMs = dur,
                    isPlaying = player.isPlaying()
                )
                delay(500)
            }
        }
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    fun hasHighlight(): Boolean = startHighlightMs > 0

    private fun totalDuration(): Int {
        val fromState = _state.value?.durationMs ?: 0
        return if (fromState > 0) fromState else player.duration()
    }

    private fun isPresigned(url: String): Boolean =
        url.contains("X-Amz-Algorithm=", true) || url.contains("X-Amz-Signature=", true)

    private fun extractKeyFromS3Url(url: String, bucket: String): String? =
        try {
            val u = Uri.parse(url)
            val host = u.host ?: return null
            val path = u.encodedPath?.removePrefix("/") ?: return null
            if (host.startsWith("$bucket.s3")) path else null
        } catch (_: Exception) { null }

    private fun buildKeyFromPath(rawPath: String?, genre: String?): String? {
        if (rawPath.isNullOrBlank()) return null
        val clean = rawPath.replace("\\", "/").removePrefix("/")
        if (clean.startsWith("songs/")) return clean
        if (clean.startsWith("audio_corpus/", true)) {
            val stripped = clean.removePrefix("audio_corpus/")
            val parts = stripped.split('/')
            val fileName = parts.lastOrNull() ?: return null
            val folder = parts.dropLast(1).lastOrNull()
            val mappedFolder = folder ?: Presigner.mapGenreToFolder(genre)
            return "songs/$mappedFolder/$fileName"
        }
        val mappedFolder = Presigner.mapGenreToFolder(genre)
        return "songs/$mappedFolder/${clean.substringAfterLast('/')}"
    }
    private fun getPlayableUrl(resp: IdentifyResponse?): String? {
        if (resp == null) return null
        val keyFromRoot = resp.s3_key?.takeIf { !it.isNullOrBlank() }
        val keyFromTop  = resp.top_matches?.firstOrNull()?.s3_key?.takeIf { !it.isNullOrBlank() }
        val keyFromPath = buildKeyFromPath(resp.path, resp.genre)
        val key = keyFromRoot ?: keyFromTop ?: keyFromPath
        if (key != null) {
            val clean = key.trim().removePrefix("/").replace("\\", "/")
            val finalKey = if (clean.contains("/")) clean
            else "songs/${Presigner.mapGenreToFolder(resp.genre)}/$clean"
            Log.d(TAG, "Using S3 key=$finalKey (raw=${resp.s3_key}, top=${resp.top_matches?.firstOrNull()?.s3_key}, path=$keyFromPath)")
            return Presigner.presign(AwsConfig.BUCKET, finalKey)
        }

        resp.s3_url?.let { raw ->
            Log.d(TAG, "Using provided s3_url=$raw")
            if (isPresigned(raw) || !raw.contains(".s3.", true)) return raw
            extractKeyFromS3Url(raw, AwsConfig.BUCKET)?.let { k ->
                Log.d(TAG, "Presigning extracted key from s3_url: $k")
                return Presigner.presign(AwsConfig.BUCKET, k)
            }
            return raw
        }
        Log.e(TAG, "No playable URL found (s3_key, top_match key, path, s3_url all missing)")
        return null
    }

    private suspend fun httpGetBytes(url: String): ByteArray =
        withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val req = Request.Builder().url(url).build()
            Log.d(TAG, "HTTP GET $url")
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} al descargar audio")
                Log.d(TAG, "HTTP ${resp.code} received for audio")
                resp.body?.bytes() ?: throw IllegalStateException("Cuerpo vacío")
            }
        }
    fun formatOffset(): String {
        if (startHighlightMs > 0) return "Highlight: ${Formatter.fmtMs(startHighlightMs)}"
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
