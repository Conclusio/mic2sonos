package org.crocophant.speech2sonos

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamer {

    companion object {
        private const val TAG = "AudioStreamer"
        private const val WAVEFORM_SAMPLES = 100
    }

    private var audioRecord: AudioRecord? = null
    private var server: ApplicationEngine? = null
    private var mediaCodec: MediaCodec? = null
    private var serverJob: Job? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _audioDataFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    private val audioDataFlow = _audioDataFlow.asSharedFlow()

    private val _amplitudeFlow = MutableStateFlow(0f)
    val amplitudeFlow: StateFlow<Float> = _amplitudeFlow.asStateFlow()

    private val _waveformData = MutableStateFlow<List<Float>>(emptyList())
    val waveformData: StateFlow<List<Float>> = _waveformData.asStateFlow()

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val isRunning = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (isRunning.get()) {
            Log.w(TAG, "Already running")
            return@withContext false
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size: $bufferSize")
                return@withContext false
            }

            val actualBufferSize = bufferSize * 4

            val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, actualBufferSize)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                actualBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                cleanup()
                return@withContext false
            }

            startServer()
            delay(500)

            audioRecord?.startRecording()
            mediaCodec?.start()

            isRunning.set(true)
            startRecordingLoop(actualBufferSize)

            Log.i(TAG, "Audio streaming started successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio streaming", e)
            cleanup()
            false
        }
    }

    private fun startServer() {
        server = embeddedServer(CIO, host = "0.0.0.0", port = 8080) {
            install(StatusPages) {
                exception<ClosedChannelException> { _, _ ->
                    Log.d(TAG, "Client closed connection")
                }
                exception<IOException> { _, cause ->
                    Log.d(TAG, "I/O error during streaming: ${cause.message}")
                }
                exception<Throwable> { _, cause ->
                    Log.e(TAG, "Unexpected error in server", cause)
                }
            }
            routing {
                get("/") {
                    Log.i(TAG, "Root request from ${call.request.local.remoteHost}")
                    call.respondText("Audio Streamer OK", ContentType.Text.Plain)
                }
                head("/stream.aac") {
                    Log.i(TAG, "HEAD request received from ${call.request.local.remoteHost}")
                    call.response.header("Cache-Control", "no-cache, no-store")
                    call.response.header("Pragma", "no-cache")
                    call.response.header("Accept-Ranges", "none")
                    call.response.header("icy-name", "Live Microphone")
                    call.respond(io.ktor.http.HttpStatusCode.OK)
                }
                get("/stream.aac") {
                    Log.i(TAG, "=== STREAM REQUEST RECEIVED ===")
                    Log.i(TAG, "Client: ${call.request.local.remoteHost}")
                    Log.i(TAG, "User-Agent: ${call.request.headers["User-Agent"]}")
                    call.response.header("Cache-Control", "no-cache, no-store")
                    call.response.header("Pragma", "no-cache")
                    call.response.header("Accept-Ranges", "none")
                    call.response.header("icy-name", "Live Microphone")
                    call.respondOutputStream(ContentType("audio", "aac")) {
                        Log.i(TAG, "Starting to stream audio data...")
                        try {
                            var chunkCount = 0
                            audioDataFlow.collect { audioChunk ->
                                write(audioChunk)
                                flush()
                                chunkCount++
                                if (chunkCount % 100 == 0) {
                                    Log.d(TAG, "Streamed $chunkCount chunks")
                                }
                            }
                        } catch (e: IOException) {
                            Log.d(TAG, "Client disconnected after streaming")
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

    private fun startRecordingLoop(bufferSize: Int) {
        recordingJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBuffer = ByteArray(bufferSize)
            val waveformHistory = ArrayDeque<Float>(WAVEFORM_SAMPLES)

            while (isActive && isRunning.get()) {
                try {
                    val codec = mediaCodec ?: break
                    val recorder = audioRecord ?: break

                    val bytesRead = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (bytesRead <= 0) {
                        delay(10)
                        continue
                    }

                    val amplitude = calculateAmplitude(pcmBuffer, bytesRead)
                    _amplitudeFlow.value = amplitude
                    
                    waveformHistory.addLast(amplitude)
                    if (waveformHistory.size > WAVEFORM_SAMPLES) {
                        waveformHistory.removeFirst()
                    }
                    _waveformData.value = waveformHistory.toList()

                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(pcmBuffer, 0, bytesRead)
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            bytesRead,
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
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Codec in illegal state", e)
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in recording loop", e)
                    delay(10)
                }
            }
            
            _amplitudeFlow.value = 0f
            _waveformData.value = emptyList()
        }
    }

    private fun calculateAmplitude(buffer: ByteArray, bytesRead: Int): Float {
        var sumOfSquares = 0.0
        val numSamples = bytesRead / 2
        var maxSample = 0
        
        for (i in 0 until numSamples) {
            val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
            val signedSample = sample.toShort().toInt()
            sumOfSquares += signedSample.toDouble() * signedSample.toDouble()
            maxSample = maxOf(maxSample, abs(signedSample))
        }
        
        val rms = if (numSamples > 0) sqrt(sumOfSquares / numSamples) else 0.0
        val normalizedRms = (rms / Short.MAX_VALUE).toFloat()
        val amplified = (normalizedRms * 4f).coerceIn(0f, 1f)
        return amplified
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

        Log.i(TAG, "Stopping audio streaming")
        cleanup()
    }

    private fun cleanup() {
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

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
