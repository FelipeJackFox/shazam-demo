package com.example.soundlens.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class WavRecorder(private val outputFile: File) {

        private val sampleRate = 44100
        private val channelConfig = AudioFormat.CHANNEL_IN_MONO
        private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        private val bufferSize =
                AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        private var recorder: AudioRecord? = null
        private var isRecording = false

        fun startRecording() {
                recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                )
                recorder?.startRecording()
                isRecording = true

                thread {
                        val pcmBuffer = ByteArray(bufferSize)
                        FileOutputStream(outputFile).use { fos ->
                                // header provisional
                                writeWavHeader(fos, sampleRate, 1, 16, 0)
                                var totalAudioLen = 0L

                                while (isRecording && recorder != null) {
                                        val read = recorder!!.read(pcmBuffer, 0, bufferSize)
                                        if (read > 0) {
                                                fos.write(pcmBuffer, 0, read)
                                                totalAudioLen += read
                                        }
                                }

                                // actualizar header con los tamaños reales
                                updateWavHeader(outputFile, totalAudioLen)
                        }
                }
        }

        fun stopRecording() {
                isRecording = false
                recorder?.apply {
                        stop()
                        release()
                }
                recorder = null
        }

        /** escribe un header WAV de 44 bytes */
        private fun writeWavHeader(
                out: FileOutputStream,
                sampleRate: Int,
                channels: Int,
                bitsPerSample: Int,
                totalAudioLen: Long
        ) {
                val byteRate = sampleRate * channels * bitsPerSample / 8
                val totalDataLen = totalAudioLen + 36
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray(Charsets.US_ASCII))
                header.putInt(totalDataLen.toInt())
                header.put("WAVE".toByteArray(Charsets.US_ASCII))
                header.put("fmt ".toByteArray(Charsets.US_ASCII))
                header.putInt(16)
                header.putShort(1) // PCM
                header.putShort(channels.toShort())
                header.putInt(sampleRate)
                header.putInt(byteRate)
                header.putShort((channels * bitsPerSample / 8).toShort())
                header.putShort(bitsPerSample.toShort())
                header.put("data".toByteArray(Charsets.US_ASCII))
                header.putInt(totalAudioLen.toInt())
                out.write(header.array(), 0, 44)
        }

        /** reabre el archivo y corrige los dos tamaños del header */
        private fun updateWavHeader(file: File, totalAudioLen: Long) {
                val totalDataLen = totalAudioLen + 36
                val raf = RandomAccessFile(file, "rw")
                // chunk size (4 bytes) en offset 4
                raf.seek(4)
                raf.write(intToLE(totalDataLen.toInt()))
                // subchunk2 size (data) en offset 40
                raf.seek(40)
                raf.write(intToLE(totalAudioLen.toInt()))
                raf.close()
        }

        private fun intToLE(value: Int): ByteArray =
                ByteBuffer.allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(value)
                        .array()
}
