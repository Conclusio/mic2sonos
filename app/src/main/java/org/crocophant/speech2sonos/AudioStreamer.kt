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
        private const val HLS_SEGMENT_DURATION_MS = 2000L // 2 second segments
        private const val HLS_MAX_SEGMENTS = 5 // Keep last 5 segments
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
    
    // HLS segment storage
    private data class HlsSegment(val id: Int, val data: ByteArray, val durationMs: Long)
    private val hlsSegments = mutableListOf<HlsSegment>()
    private var hlsSegmentCounter = 0
    private var currentSegmentBuffer = java.io.ByteArrayOutputStream()
    private var currentSegmentStartTime = 0L
    private val hlsLock = Any()

    private val _amplitudeFlow = MutableStateFlow(0f)
    val amplitudeFlow: StateFlow<Float> = _amplitudeFlow.asStateFlow()

    private val _waveformData = MutableStateFlow<List<Float>>(emptyList())
    val waveformData: StateFlow<List<Float>> = _waveformData.asStateFlow()
    
    // Configurable amplification (1-20x range)
    private val _amplificationFactor = MutableStateFlow(10)
    val amplificationFactor: StateFlow<Int> = _amplificationFactor.asStateFlow()
    
    fun setAmplification(factor: Int) {
        _amplificationFactor.value = factor.coerceIn(1, 20)
    }

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

            val actualBufferSize = bufferSize * 2  // Reduced for lower latency

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
            delay(200)  // Reduced startup delay

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
                exception<kotlinx.coroutines.CancellationException> { _, _ ->
                    // Normal shutdown, ignore
                }
                exception<Throwable> { _, cause ->
                    if (isRunning.get()) {
                        Log.e(TAG, "Unexpected error in server", cause)
                    }
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
                // HEAD handler for WAV - Sonos may check headers first
                head("/stream.wav") {
                    Log.i(TAG, "=== STREAM WAV HEAD REQUEST ===")
                    val fakeDataSize = 44100 * 2 * 60 * 10
                    val totalHttpSize = 44 + fakeDataSize
                    call.response.header("Content-Length", totalHttpSize.toString())
                    call.response.header("Content-Type", "audio/wav")
                    call.response.header("Accept-Ranges", "bytes")
                    call.respond(io.ktor.http.HttpStatusCode.OK)
                }
                // Test: 5-second tone using buildWavFile function
                get("/tone.wav") {
                    Log.i(TAG, "=== TONE WAV REQUEST (using buildWavFile) ===")
                    
                    val durationSeconds = 5
                    val frequency = 440.0
                    val sampleRate = 44100
                    val numSamples = sampleRate * durationSeconds
                    val pcmData = ByteArray(numSamples * 2)
                    
                    for (i in 0 until numSamples) {
                        val t = i.toDouble() / sampleRate
                        val sample = (Math.sin(2 * Math.PI * frequency * t) * 32767).toInt().toShort()
                        pcmData[i * 2] = (sample.toInt() and 0xFF).toByte()
                        pcmData[i * 2 + 1] = (sample.toInt() shr 8).toByte()
                    }
                    
                    val wavData = buildWavFile(pcmData)
                    Log.i(TAG, "Serving tone WAV: ${wavData.size} bytes")
                    
                    call.response.header("Content-Length", wavData.size.toString())
                    call.respondBytes(wavData, ContentType("audio", "wav"))
                }
                // Test: delayed tone to check if Sonos times out
                get("/delay-tone.wav") {
                    Log.i(TAG, "=== DELAYED TONE REQUEST - waiting 3 seconds ===")
                    delay(3000)  // Simulate the buffering delay
                    
                    val durationSeconds = 5
                    val frequency = 440.0
                    val sampleRate = 44100
                    val numSamples = sampleRate * durationSeconds
                    val pcmData = ByteArray(numSamples * 2)
                    
                    for (i in 0 until numSamples) {
                        val t = i.toDouble() / sampleRate
                        val sample = (Math.sin(2 * Math.PI * frequency * t) * 32767).toInt().toShort()
                        pcmData[i * 2] = (sample.toInt() and 0xFF).toByte()
                        pcmData[i * 2 + 1] = (sample.toInt() shr 8).toByte()
                    }
                    
                    val wavData = buildWavFile(pcmData)
                    Log.i(TAG, "Serving delayed tone WAV: ${wavData.size} bytes")
                    
                    call.response.header("Content-Length", wavData.size.toString())
                    call.respondBytes(wavData, ContentType("audio", "wav"))
                }
                // Mic buffer test: captures 3 seconds of mic audio, amplifies, serves as WAV
                get("/mic.wav") {
                    Log.i(TAG, "=== MIC WAV REQUEST ===")
                    
                    val bufferDurationMs = 3000
                    val bytesNeeded = (44100 * 2 * bufferDurationMs) / 1000
                    val pcmBuffer = java.io.ByteArrayOutputStream()
                    
                    Log.i(TAG, "Buffering $bufferDurationMs ms of mic audio...")
                    
                    val startTime = System.currentTimeMillis()
                    var maxSample = 0
                    try {
                        rawPcmFlow.collect { chunk ->
                            pcmBuffer.write(chunk)
                            for (i in 0 until chunk.size / 2) {
                                val sample = (chunk[i * 2].toInt() and 0xFF) or (chunk[i * 2 + 1].toInt() shl 8)
                                val signed = sample.toShort().toInt()
                                if (kotlin.math.abs(signed) > maxSample) {
                                    maxSample = kotlin.math.abs(signed)
                                }
                            }
                            if (pcmBuffer.size() >= bytesNeeded) {
                                throw kotlinx.coroutines.CancellationException("Buffer full")
                            }
                            if (System.currentTimeMillis() - startTime > bufferDurationMs + 1000) {
                                throw kotlinx.coroutines.CancellationException("Timeout")
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.i(TAG, "Buffered ${pcmBuffer.size()} bytes, max sample: $maxSample")
                    }
                    
                    // Amplify mic audio by 10x to check if it's a volume issue
                    val rawPcm = pcmBuffer.toByteArray()
                    val amplifiedPcm = ByteArray(rawPcm.size)
                    for (i in 0 until rawPcm.size / 2) {
                        val sample = (rawPcm[i * 2].toInt() and 0xFF) or (rawPcm[i * 2 + 1].toInt() shl 8)
                        val signed = sample.toShort().toInt()
                        val amplified = (signed * 10).coerceIn(-32768, 32767).toShort()
                        amplifiedPcm[i * 2] = (amplified.toInt() and 0xFF).toByte()
                        amplifiedPcm[i * 2 + 1] = (amplified.toInt() shr 8).toByte()
                    }
                    
                    val wavData = buildWavFile(amplifiedPcm)
                    Log.i(TAG, "Serving amplified mic WAV: ${wavData.size} bytes")
                    
                    call.response.header("Content-Length", wavData.size.toString())
                    call.respondBytes(wavData, ContentType("audio", "wav"))
                }
                // WAV stream endpoint - with fake Content-Length for Sonos compatibility
                get("/stream.wav") {
                    Log.i(TAG, "=== STREAM WAV REQUEST RECEIVED ===")
                    Log.i(TAG, "Client: ${call.request.local.remoteHost}")
                    
                    // Max WAV size: ~3 hours (limited by 32-bit integer, we use ~2GB to be safe)
                    // 44100 Hz * 2 bytes * 60 sec * 180 min = 952,560,000 bytes (~3 hours)
                    val fakeDataSize = 44100 * 2 * 60 * 180
                    val fakeFileSize = 36 + fakeDataSize
                    val totalHttpSize = 44 + fakeDataSize
                    
                    call.response.header("Content-Length", totalHttpSize.toString())
                    call.response.header("Cache-Control", "no-cache, no-store")
                    call.response.header("Accept-Ranges", "none")
                    
                    call.respondOutputStream(ContentType("audio", "wav")) {
                        Log.i(TAG, "Starting WAV stream with Content-Length: $totalHttpSize")
                        
                        // WAV header with fake but valid file size
                        write("RIFF".toByteArray())
                        write(byteArrayOf(
                            (fakeFileSize and 0xFF).toByte(),
                            ((fakeFileSize shr 8) and 0xFF).toByte(),
                            ((fakeFileSize shr 16) and 0xFF).toByte(),
                            ((fakeFileSize shr 24) and 0xFF).toByte()
                        ))
                        write("WAVE".toByteArray())
                        
                        // fmt chunk
                        write("fmt ".toByteArray())
                        write(byteArrayOf(16, 0, 0, 0))
                        write(byteArrayOf(1, 0)) // PCM
                        write(byteArrayOf(1, 0)) // Mono
                        write(byteArrayOf(0x44, 0xAC.toByte(), 0, 0)) // 44100 Hz
                        write(byteArrayOf(0x88.toByte(), 0x58, 0x01, 0)) // 88200 bytes/sec
                        write(byteArrayOf(2, 0)) // Block align
                        write(byteArrayOf(16, 0)) // 16-bit
                        
                        // data chunk
                        write("data".toByteArray())
                        write(byteArrayOf(
                            (fakeDataSize and 0xFF).toByte(),
                            ((fakeDataSize shr 8) and 0xFF).toByte(),
                            ((fakeDataSize shr 16) and 0xFF).toByte(),
                            ((fakeDataSize shr 24) and 0xFF).toByte()
                        ))
                        flush()
                        Log.i(TAG, "WAV header written, streaming PCM...")
                        
                        try {
                            var chunkCount = 0
                            var totalBytes = 0L
                            rawPcmFlow.collect { pcmChunk ->
                                val gain = _amplificationFactor.value
                                val amplified = ByteArray(pcmChunk.size)
                                for (i in 0 until pcmChunk.size / 2) {
                                    val sample = (pcmChunk[i * 2].toInt() and 0xFF) or (pcmChunk[i * 2 + 1].toInt() shl 8)
                                    val signed = sample.toShort().toInt()
                                    val amp = (signed * gain).coerceIn(-32768, 32767).toShort()
                                    amplified[i * 2] = (amp.toInt() and 0xFF).toByte()
                                    amplified[i * 2 + 1] = (amp.toInt() shr 8).toByte()
                                }
                                write(amplified)
                                flush()
                                chunkCount++
                                totalBytes += amplified.size
                                if (chunkCount % 100 == 0) {
                                    Log.d(TAG, "Streamed $chunkCount chunks, ${totalBytes/1024}KB, gain=$gain")
                                }
                            }
                        } catch (e: IOException) {
                            Log.d(TAG, "WAV client disconnected: ${e.message}")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            Log.d(TAG, "WAV streaming cancelled")
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
                // Static test WAV - generates a 5-second 440Hz sine wave
                // If this plays on Sonos, the issue is with streaming format, not network path
                get("/test-static.wav") {
                    Log.i(TAG, "=== STATIC WAV TEST REQUEST ===")
                    Log.i(TAG, "Client: ${call.request.local.remoteHost}")
                    
                    // Generate a 5-second 440Hz sine wave
                    val durationSeconds = 5
                    val frequency = 440.0
                    val sampleRate = 44100
                    val numSamples = sampleRate * durationSeconds
                    val pcmData = ByteArray(numSamples * 2) // 16-bit samples
                    
                    for (i in 0 until numSamples) {
                        val t = i.toDouble() / sampleRate
                        val sample = (Math.sin(2 * Math.PI * frequency * t) * 32767).toInt().toShort()
                        pcmData[i * 2] = (sample.toInt() and 0xFF).toByte()
                        pcmData[i * 2 + 1] = (sample.toInt() shr 8).toByte()
                    }
                    
                    // Build complete WAV file with correct sizes
                    val dataSize = pcmData.size
                    val fileSize = 36 + dataSize
                    
                    val wavFile = java.io.ByteArrayOutputStream()
                    // RIFF header
                    wavFile.write("RIFF".toByteArray())
                    wavFile.write(byteArrayOf(
                        (fileSize and 0xFF).toByte(),
                        ((fileSize shr 8) and 0xFF).toByte(),
                        ((fileSize shr 16) and 0xFF).toByte(),
                        ((fileSize shr 24) and 0xFF).toByte()
                    ))
                    wavFile.write("WAVE".toByteArray())
                    
                    // fmt chunk
                    wavFile.write("fmt ".toByteArray())
                    wavFile.write(byteArrayOf(16, 0, 0, 0)) // Chunk size = 16
                    wavFile.write(byteArrayOf(1, 0)) // Audio format = 1 (PCM)
                    wavFile.write(byteArrayOf(1, 0)) // Num channels = 1 (mono)
                    wavFile.write(byteArrayOf(0x44, 0xAC.toByte(), 0, 0)) // Sample rate = 44100
                    wavFile.write(byteArrayOf(0x88.toByte(), 0x58, 0x01, 0)) // Byte rate = 88200
                    wavFile.write(byteArrayOf(2, 0)) // Block align = 2
                    wavFile.write(byteArrayOf(16, 0)) // Bits per sample = 16
                    
                    // data chunk
                    wavFile.write("data".toByteArray())
                    wavFile.write(byteArrayOf(
                        (dataSize and 0xFF).toByte(),
                        ((dataSize shr 8) and 0xFF).toByte(),
                        ((dataSize shr 16) and 0xFF).toByte(),
                        ((dataSize shr 24) and 0xFF).toByte()
                    ))
                    wavFile.write(pcmData)
                    
                    val wavBytes = wavFile.toByteArray()
                    Log.i(TAG, "Generated static WAV: ${wavBytes.size} bytes, $durationSeconds seconds")
                    
                    call.response.header("Content-Length", wavBytes.size.toString())
                    call.respondBytes(wavBytes, ContentType("audio", "wav"))
                }
                // HLS playlist endpoint
                get("/live.m3u8") {
                    Log.i(TAG, "=== HLS PLAYLIST REQUEST ===")
                    Log.i(TAG, "Client: ${call.request.local.remoteHost}")
                    
                    val playlist = synchronized(hlsLock) {
                        if (hlsSegments.isEmpty()) {
                            // Return empty playlist if no segments yet
                            """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:3
#EXT-X-MEDIA-SEQUENCE:0
"""
                        } else {
                            val firstSegment = hlsSegments.first()
                            val sb = StringBuilder()
                            sb.append("#EXTM3U\n")
                            sb.append("#EXT-X-VERSION:3\n")
                            sb.append("#EXT-X-TARGETDURATION:3\n")
                            sb.append("#EXT-X-MEDIA-SEQUENCE:${firstSegment.id}\n")
                            
                            for (segment in hlsSegments) {
                                val durationSec = segment.durationMs / 1000.0
                                sb.append("#EXTINF:${"%.3f".format(durationSec)},\n")
                                sb.append("segment-${segment.id}.wav\n")
                            }
                            sb.toString()
                        }
                    }
                    
                    Log.d(TAG, "Serving playlist:\n$playlist")
                    call.response.header("Cache-Control", "no-cache, no-store")
                    call.respondText(playlist, ContentType("application", "vnd.apple.mpegurl"))
                }
                
                // HLS segment endpoint
                get("/segment-{id}.wav") {
                    val segmentId = call.parameters["id"]?.toIntOrNull()
                    Log.i(TAG, "=== HLS SEGMENT REQUEST: $segmentId ===")
                    
                    if (segmentId == null) {
                        call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Invalid segment ID")
                        return@get
                    }
                    
                    val segment = synchronized(hlsLock) {
                        hlsSegments.find { it.id == segmentId }
                    }
                    
                    if (segment == null) {
                        Log.w(TAG, "Segment $segmentId not found")
                        call.respond(io.ktor.http.HttpStatusCode.NotFound, "Segment not found")
                        return@get
                    }
                    
                    // Build WAV with proper header
                    val wavData = buildWavFile(segment.data)
                    Log.d(TAG, "Serving segment $segmentId: ${wavData.size} bytes")
                    
                    call.response.header("Content-Length", wavData.size.toString())
                    call.response.header("Cache-Control", "no-cache")
                    call.respondBytes(wavData, ContentType("audio", "wav"))
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Server stopped")
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Server error", e)
                }
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
                    val pcmCopy = pcmBuffer.copyOf(bytesRead)
                    _rawPcmFlow.tryEmit(pcmCopy)
                    
                    // Add to HLS segment buffer
                    addPcmToHlsSegment(pcmCopy)

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
                    if (isRunning.get()) {
                        Log.e(TAG, "Codec in illegal state", e)
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error in recording loop", e)
                    }
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

    private fun buildWavFile(pcmData: ByteArray): ByteArray {
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize
        
        val wavFile = java.io.ByteArrayOutputStream()
        // RIFF header
        wavFile.write("RIFF".toByteArray())
        wavFile.write(byteArrayOf(
            (fileSize and 0xFF).toByte(),
            ((fileSize shr 8) and 0xFF).toByte(),
            ((fileSize shr 16) and 0xFF).toByte(),
            ((fileSize shr 24) and 0xFF).toByte()
        ))
        wavFile.write("WAVE".toByteArray())
        
        // fmt chunk
        wavFile.write("fmt ".toByteArray())
        wavFile.write(byteArrayOf(16, 0, 0, 0)) // Chunk size = 16
        wavFile.write(byteArrayOf(1, 0)) // Audio format = 1 (PCM)
        wavFile.write(byteArrayOf(1, 0)) // Num channels = 1 (mono)
        wavFile.write(byteArrayOf(0x44, 0xAC.toByte(), 0, 0)) // Sample rate = 44100
        wavFile.write(byteArrayOf(0x88.toByte(), 0x58, 0x01, 0)) // Byte rate = 88200
        wavFile.write(byteArrayOf(2, 0)) // Block align = 2
        wavFile.write(byteArrayOf(16, 0)) // Bits per sample = 16
        
        // data chunk
        wavFile.write("data".toByteArray())
        wavFile.write(byteArrayOf(
            (dataSize and 0xFF).toByte(),
            ((dataSize shr 8) and 0xFF).toByte(),
            ((dataSize shr 16) and 0xFF).toByte(),
            ((dataSize shr 24) and 0xFF).toByte()
        ))
        wavFile.write(pcmData)
        
        return wavFile.toByteArray()
    }
    
    private fun addPcmToHlsSegment(pcmChunk: ByteArray) {
        synchronized(hlsLock) {
            if (currentSegmentStartTime == 0L) {
                currentSegmentStartTime = System.currentTimeMillis()
            }
            
            currentSegmentBuffer.write(pcmChunk)
            
            val elapsed = System.currentTimeMillis() - currentSegmentStartTime
            if (elapsed >= HLS_SEGMENT_DURATION_MS) {
                // Finalize current segment
                val segmentData = currentSegmentBuffer.toByteArray()
                val segment = HlsSegment(hlsSegmentCounter++, segmentData, elapsed)
                hlsSegments.add(segment)
                Log.d(TAG, "Created HLS segment ${segment.id}: ${segmentData.size} bytes, ${elapsed}ms")
                
                // Keep only last N segments
                while (hlsSegments.size > HLS_MAX_SEGMENTS) {
                    val removed = hlsSegments.removeAt(0)
                    Log.d(TAG, "Removed old HLS segment ${removed.id}")
                }
                
                // Reset for next segment
                currentSegmentBuffer = java.io.ByteArrayOutputStream()
                currentSegmentStartTime = System.currentTimeMillis()
            }
        }
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
        
        // Clear HLS state
        synchronized(hlsLock) {
            hlsSegments.clear()
            hlsSegmentCounter = 0
            currentSegmentBuffer = java.io.ByteArrayOutputStream()
            currentSegmentStartTime = 0L
        }

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
