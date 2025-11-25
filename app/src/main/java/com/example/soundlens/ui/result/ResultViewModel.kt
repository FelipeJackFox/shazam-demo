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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

Gracias, actualmente hay varios errores en la forma en la que se graba

class ResultViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableLiveData(ResultUiState())
    val state: LiveData<ResultUiState> = _state

    private val player = AudioPlayer()
    private var ticker: Job? = null

    fun initWithPayload(
        payloadJson: String,
        title: String,
        artist: String,
        year: Int,
        genre: String,
        offsetFrames: Int
    ) {
        val resp = IdentifyParser.parseOrNull(payloadJson)
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

        viewModelScope.launch { downloadAndPrepare(resp, offsetFrames) }
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

    private suspend fun downloadAndPrepare(resp: IdentifyResponse?, offsetFrames: Int) {
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
                val ms = if (offsetFrames > 0) AudioUtils.framesToMs(offsetFrames) else 0
                if (ms > 0) runCatching { player.seekTo(ms) }
                player.start()
                startTicker()
                _state.postValue(_state.value!!.copy(
                    loading = false,
                    audioReady = true,
                    isPlaying = true,
                    localFile = f
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

    override fun onCleared() {
        super.onCleared()
        stopTicker()
        player.release()
    }
}
