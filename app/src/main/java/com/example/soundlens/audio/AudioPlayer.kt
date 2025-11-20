package com.example.soundlens.audio

import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Wrapper sencillo de MediaPlayer para aislar la UI.
 */
class AudioPlayer {

    private var mp: MediaPlayer? = null
    private var onPreparedCb: ((MediaPlayer) -> Unit)? = null

    fun setOnPrepared(block: (MediaPlayer) -> Unit) {
        onPreparedCb = block
    }

    fun prepare(path: String) {
        release()
        mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener { onPreparedCb?.invoke(it) }
            setOnCompletionListener { /* la UI observa isPlaying y timers */ }
            setOnErrorListener { _, _, _ -> true }
            setDataSource(path)
            prepareAsync()
        }
    }

    fun start() { mp?.start() }
    fun pause() { mp?.pause() }
    fun isPlaying(): Boolean = mp?.isPlaying == true
    fun seekTo(ms: Int) { runCatching { mp?.seekTo(ms) } }
    fun duration(): Int = mp?.duration ?: 0
    fun currentPosition(): Int = mp?.currentPosition ?: 0

    fun release() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        onPreparedCb = null
    }
}
