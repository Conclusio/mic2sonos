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
    
    // Raw PCM data flow for WAV streaming
    private val _rawPcmFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    private val rawPcmFlow = _rawPcmFlow.asSharedFlow()

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
                // WAV stream endpoint - uncompressed PCM, should be universally compatible
                get("/stream.wav") {
                    Log.i(TAG, "=== STREAM WAV REQUEST RECEIVED ===")
                    Log.i(TAG, "Client: ${call.request.local.remoteHost}")
                    Log.i(TAG, "User-Agent: ${call.request.headers["User-Agent"]}")
                    
                    call.response.header("Cache-Control", "no-cache, no-store")
                    call.response.header("Pragma", "no-cache")
                    call.response.header("Accept-Ranges", "none")
                    call.response.header("icy-name", "Live Microphone")
                    
                    // Stream raw PCM as WAV (no header needed for streaming, or with a fake header)
                    call.respondOutputStream(ContentType("audio", "wav")) {
                        Log.i(TAG, "Starting to stream WAV audio data...")
                        
                        // Write a minimal WAV header for streaming (unknown length)
                        // RIFF header
                        write("RIFF".toByteArray())
                        write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F.toByte())) // Max file size
                        write("WAVE".toByteArray())
                        
                        // fmt chunk
                        write("fmt ".toByteArray())
                        write(byteArrayOf(16, 0, 0, 0)) // Chunk size = 16
                        write(byteArrayOf(1, 0)) // Audio format = 1 (PCM)
                        write(byteArrayOf(1, 0)) // Num channels = 1 (mono)
                        write(byteArrayOf(0x44, 0xAC.toByte(), 0, 0)) // Sample rate = 44100
                        write(byteArrayOf(0x88.toByte(), 0x58, 0x01, 0)) // Byte rate = 88200
                        write(byteArrayOf(2, 0)) // Block align = 2
                        write(byteArrayOf(16, 0)) // Bits per sample = 16
                        
                        // data chunk
                        write("data".toByteArray())
                        write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F.toByte())) // Max data size
                        
                        flush()
                        Log.i(TAG, "WAV header written, now streaming PCM data...")
                        
                        try {
                            var chunkCount = 0
                            var totalBytes = 0L
                            // For WAV, we need raw PCM data, not encoded AAC
                            // We'll need to add a separate flow for raw PCM
                            rawPcmFlow.collect { pcmChunk ->
                                write(pcmChunk)
                                flush()
                                chunkCount++
                                totalBytes += pcmChunk.size
                                if (chunkCount % 50 == 0) {
                                    Log.d(TAG, "Streamed WAV $chunkCount chunks, $totalBytes bytes total")
                                }
                            }
                        } catch (e: IOException) {
                            Log.d(TAG, "WAV client disconnected")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error streaming WAV", e)
                        }
                    }
                }
                head("/test.mp3") {
                    Log.i(TAG, "=== TEST MP3 HEAD REQUEST ===")
                    Log.i(TAG, "Client: ${call.request.local.remoteHost}")
                    call.response.header("Content-Type", "audio/mpeg")
                    call.response.header("Accept-Ranges", "none")
                    call.response.header("Cache-Control", "no-cache")
                    call.respond(io.ktor.http.HttpStatusCode.OK)
                }
                get("/test.mp3") {
                    Log.i(TAG, "=== TEST MP3 GET REQUEST RECEIVED ===")
                    Log.i(TAG, "Client: ${call.request.local.remoteHost}")
                    Log.i(TAG, "User-Agent: ${call.request.headers["User-Agent"]}")
                    Log.i(TAG, "All headers: ${call.request.headers.entries()}")
                    
                    // Generate a simple sine wave tone as MP3-like data
                    // For now, just stream the AAC data we're already generating
                    call.response.header("Cache-Control", "no-cache, no-store")
                    call.response.header("Pragma", "no-cache")
                    call.response.header("Accept-Ranges", "none")
                    call.response.header("icy-name", "Test Stream")
                    call.respondOutputStream(ContentType.Audio.MPEG) {
                        Log.i(TAG, "Starting test.mp3 stream...")
                        try {
                            audioDataFlow.collect { audioChunk ->
                                write(audioChunk)
                                flush()
                            }
                        } catch (e: IOException) {
                            Log.d(TAG, "Test stream client disconnected")
                        }
                    }
                }
                get("/stream.aac") {
                    Log.i(TAG, "=== STREAM AAC REQUEST RECEIVED ===")
                    Log.i(TAG, "Client: ${call.request.local.remoteHost}")
                    Log.i(TAG, "User-Agent: ${call.request.headers["User-Agent"]}")
                    Log.i(TAG, "Request headers: ${call.request.headers.entries().map { "${it.key}: ${it.value}" }}")
                    
                    // Check if client wants ICY metadata
                    val wantsIcyMetadata = call.request.headers["Icy-MetaData"] == "1"
                    Log.i(TAG, "Client wants ICY metadata: $wantsIcyMetadata")
                    
                    call.response.header("Cache-Control", "no-cache, no-store")
                    call.response.header("Pragma", "no-cache")
                    call.response.header("Accept-Ranges", "none")
                    call.response.header("icy-name", "Live Microphone")
                    call.response.header("icy-br", "128")
                    
                    // ICY metadata interval - set to 0 to disable inline metadata
                    // or set to a byte count like 16000 to include metadata
                    if (wantsIcyMetadata) {
                        call.response.header("icy-metaint", "0")
                    }
                    
                    // Use audio/aac content type
                    call.respondOutputStream(ContentType("audio", "aac")) {
                        Log.i(TAG, "Starting to stream AAC audio data...")
                        try {
                            var chunkCount = 0
                            var totalBytes = 0L
                            audioDataFlow.collect { audioChunk ->
                                write(audioChunk)
                                flush()
                                chunkCount++
                                totalBytes += audioChunk.size
                                if (chunkCount % 50 == 0) {
                                    Log.d(TAG, "Streamed $chunkCount chunks, $totalBytes bytes total")
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
                    
                    // Emit raw PCM for WAV streaming
                    // Debug: check if audio data is valid (not all zeros)
                    var maxSample = 0
                    for (i in 0 until bytesRead / 2) {
                        val sample = (pcmBuffer[i * 2].toInt() and 0xFF) or (pcmBuffer[i * 2 + 1].toInt() shl 8)
                        val signedSample = sample.toShort().toInt()
                        if (kotlin.math.abs(signedSample) > maxSample) {
                            maxSample = kotlin.math.abs(signedSample)
                        }
                    }
                    if (maxSample > 100) {
                        // Only log occasionally to avoid spam
                        if (System.currentTimeMillis() % 2000 < 50) {
                            Log.d(TAG, "PCM data max sample: $maxSample (should be > 0 if mic is capturing)")
                        }
                    }
                    _rawPcmFlow.tryEmit(pcmBuffer.copyOf(bytesRead))

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
        val profile = 2  // AAC LC (will be stored as profile-1 = 1)
        val freqIdx = 4  // 44.1KHz
        val chanCfg = 1  // Mono

        // Byte 0: 0xFF - sync word (all 1s)
        adtsHeader[0] = 0xFF.toByte()
        
        // Byte 1: 0xF1 for MPEG-4 AAC (ID=0), or 0xF9 for MPEG-2 AAC (ID=1)
        // Format: 1111 IBPP where I=ID, B=layer(00), P=protection_absent
        // Using 0xF1 = 11110001 = MPEG-4, layer 00, protection_absent=1
        adtsHeader[1] = 0xF1.toByte()
        
        // Byte 2: PPFF FFFC where PP=profile-1, FFFF=freq_idx, C=channel_config high bit
        adtsHeader[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        
        // Byte 3: CCOO OOOO where CC=channel_config low 2 bits, O=originality, home, etc + frame length high 2 bits
        adtsHeader[3] = (((chanCfg and 3) shl 6) + (frameLength shr 11)).toByte()
        
        // Byte 4: frame length middle 8 bits
        adtsHeader[4] = ((frameLength and 0x7FF) shr 3).toByte()
        
        // Byte 5: frame length low 3 bits + buffer fullness high 5 bits
        adtsHeader[5] = (((frameLength and 7) shl 5) + 0x1F).toByte()
        
        // Byte 6: buffer fullness low 6 bits + number of frames - 1
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
