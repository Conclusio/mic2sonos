package org.crocophant.speech2sonos

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

class TestAudioPlayer {

    companion object {
        private const val TAG = "TestAudioPlayer"
        private const val SAMPLE_RATE = 44100
        private const val FREQUENCY_HZ = 440.0
        private const val AMPLITUDE = 0.8
    }

    private var server: ApplicationEngine? = null
    private var mediaCodec: MediaCodec? = null
    private var serverJob: Job? = null
    private var generatorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _audioDataFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    private val audioDataFlow = _audioDataFlow.asSharedFlow()

    private val isRunning = AtomicBoolean(false)

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (isRunning.get()) {
            Log.w(TAG, "Already running")
            return@withContext false
        }

        try {
            val bufferSize = 4096

            val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            startServer()
            delay(500)

            mediaCodec?.start()

            isRunning.set(true)
            startToneGeneratorLoop(bufferSize)

            Log.i(TAG, "Test audio streaming started on port 8081")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start test audio streaming", e)
            cleanup()
            false
        }
    }

    private fun startServer() {
        server = embeddedServer(CIO, port = 8081) {
            install(StatusPages) {
                exception<ClosedChannelException> { _, _ -> }
                exception<IOException> { _, cause ->
                    Log.d(TAG, "I/O error during streaming: ${cause.message}")
                }
                exception<Throwable> { _, cause ->
                    Log.e(TAG, "Unexpected error in server", cause)
                }
            }
            routing {
                get("/test.aac") {
                    call.respondOutputStream(ContentType("audio", "aac")) {
                        try {
                            audioDataFlow.collect { audioChunk ->
                                write(audioChunk)
                                flush()
                            }
                        } catch (e: IOException) {
                            Log.d(TAG, "Client disconnected")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error streaming audio", e)
                        }
                    }
                }
            }
        }

        serverJob = scope.launch {
            try {
                server?.start(wait = true)
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }
    }

    private fun startToneGeneratorLoop(bufferSize: Int) {
        generatorJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            val samplesPerBuffer = bufferSize / 2
            var sampleIndex = 0L

            while (isActive && isRunning.get()) {
                try {
                    val codec = mediaCodec ?: break

                    val pcmBuffer = ShortArray(samplesPerBuffer)
                    for (i in 0 until samplesPerBuffer) {
                        val sample = (AMPLITUDE * Short.MAX_VALUE * sin(2.0 * Math.PI * FREQUENCY_HZ * sampleIndex / SAMPLE_RATE)).toInt().toShort()
                        pcmBuffer[i] = sample
                        sampleIndex++
                    }

                    val byteBuffer = ByteArray(bufferSize)
                    for (i in 0 until samplesPerBuffer) {
                        byteBuffer[i * 2] = (pcmBuffer[i].toInt() and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = ((pcmBuffer[i].toInt() shr 8) and 0xFF).toByte()
                    }

                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(byteBuffer)
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            bufferSize,
                            System.nanoTime() / 1000,
                            0
                        )
                    }

                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    while (outputBufferIndex >= 0) {
                        if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null) {
                                val encodedData = ByteArray(bufferInfo.size)
                                outputBuffer.get(encodedData)
                                val adtsHeader = createAdtsHeader(bufferInfo.size)
                                _audioDataFlow.tryEmit(adtsHeader + encodedData)
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    }

                    val bufferDurationMs = (samplesPerBuffer * 1000L) / SAMPLE_RATE
                    delay(bufferDurationMs)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Codec in illegal state", e)
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in tone generator loop", e)
                    delay(10)
                }
            }
        }
    }

    private fun createAdtsHeader(length: Int): ByteArray {
        val frameLength = length + 7
        val adtsHeader = ByteArray(7)
        val profile = 2  // AAC LC
        val freqIdx = 4  // 44.1KHz
        val chanCfg = 1  // Mono

        adtsHeader[0] = 0xFF.toByte()
        adtsHeader[1] = 0xF9.toByte()
        adtsHeader[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        adtsHeader[3] = (((chanCfg and 3) shl 6) + (frameLength shr 11)).toByte()
        adtsHeader[4] = ((frameLength and 0x7FF) shr 3).toByte()
        adtsHeader[5] = (((frameLength and 7) shl 5) + 0x1F).toByte()
        adtsHeader[6] = 0xFC.toByte()
        return adtsHeader
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        if (!isRunning.getAndSet(false)) {
            return@withContext
        }

        Log.i(TAG, "Stopping test audio streaming")
        cleanup()
    }

    private fun cleanup() {
        generatorJob?.cancel()
        generatorJob = null

        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaCodec", e)
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec", e)
        }
        mediaCodec = null

        serverJob?.cancel()
        try {
            server?.stop(500, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
        server = null
        serverJob = null
    }
}
