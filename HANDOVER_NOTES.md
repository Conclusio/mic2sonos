# Handover Notes: Sonos Live Audio Streaming Issue

## Current Status

**The app successfully connects to Sonos and streams audio data, but NO SOUND is produced despite Sonos reporting PLAYING state.**

### What Works
- ✅ Sonos device discovery via mDNS
- ✅ UPnP SOAP commands (SetAVTransportURI, Play, Stop, GetTransportInfo, etc.)
- ✅ HTTP server on phone is reachable from Sonos
- ✅ Sonos connects and receives streaming data (1.4MB+ streamed successfully)
- ✅ Sonos reports `CurrentTransportState: PLAYING` and `CurrentTransportStatus: OK`
- ✅ Sonos app shows "Live Microphone" playing with visualization moving
- ✅ PCM data from microphone contains actual audio (verified max sample values > 2000)
- ✅ Volume is not muted (verified via GetVolume API)

### What Doesn't Work
- ❌ No actual sound comes from the Sonos speaker
- ❌ Despite all indicators showing success, audio is silent

---

## Architecture Overview

### Files
- `AudioStreamer.kt` - Records mic, encodes AAC with ADTS headers, serves WAV/AAC via Ktor HTTP server on port 8080
- `SonosController.kt` - Sends UPnP SOAP commands to Sonos devices
- `SonosDiscovery.kt` - Discovers Sonos via mDNS (_sonos._tcp), uses port 1400 for UPnP
- `MainActivity.kt` - UI with device list, waveform visualizer, record button, test buttons

### Available Endpoints
- `http://<phone-ip>:8080/` - Health check ("Audio Streamer OK")
- `http://<phone-ip>:8080/stream.wav` - WAV stream (PCM 16-bit, 44.1kHz, mono)
- `http://<phone-ip>:8080/stream.aac` - AAC stream (ADTS wrapped AAC-LC, 128kbps)
- `http://<phone-ip>:8080/test.mp3` - Test endpoint (currently streams AAC data)
- `http://<phone-ip>:8080/test-static.wav` - **NEW** Static 5-second 440Hz sine wave with Content-Length header (for debugging)

### Data Flow
```
Phone Mic → AudioRecord (44.1kHz, 16-bit, mono) → PCM Buffer
                                                      ↓
                                         ┌────────────┴────────────┐
                                         ↓                         ↓
                                   MediaCodec (AAC)           Raw PCM
                                         ↓                         ↓
                                   ADTS Headers              WAV Header
                                         ↓                         ↓
                              SharedFlow (_audioDataFlow)   SharedFlow (_rawPcmFlow)
                                         ↓                         ↓
                              /stream.aac endpoint       /stream.wav endpoint
                                         ↓                         ↓
                                         └──────────┬──────────────┘
                                                    ↓
                                            Ktor HTTP Server
                                                    ↓
                                              Sonos Speaker
```

---

## Key Fixes Applied This Session

### 1. SOAP Metadata XML Escaping (Critical Fix)
**Problem:** Error 402 (Invalid Args) from SetAVTransportURI
**Solution:** The DIDL-Lite metadata XML must be escaped before embedding in SOAP envelope
```kotlin
val metadata = metadataRaw
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
```

### 2. URI Scheme for Radio Streams
For AAC streams, use `aac://` scheme. For MP3, use `x-rincon-mp3radio://`:
```kotlin
val isAacStream = streamUrl.contains(".aac")
val actualUri = if (forceRadio && isAacStream) {
    "aac://" + streamUrl.removePrefix("http://")
} else if (forceRadio) {
    "x-rincon-mp3radio://" + streamUrl.removePrefix("http://")
} else {
    streamUrl  // Plain HTTP for WAV
}
```

### 3. Added Debugging APIs
- `getTransportInfo()` - Returns current transport state
- `getMediaInfo()` - Returns currently set URI
- `getVolume()` - Returns current volume level

---

## Formats Tested

| Format | Scheme | Content-Type | Result |
|--------|--------|--------------|--------|
| AAC ADTS | `aac://` | `audio/aac` | Streaming works, TRANSITIONING state, no sound |
| AAC ADTS | `x-rincon-mp3radio://` | `audio/aac` | Streaming works, TRANSITIONING state, no sound |
| WAV PCM | `http://` | `audio/wav` | Streaming works, **PLAYING state**, no sound |

**Note:** WAV gets to PLAYING state (better than AAC which stays TRANSITIONING), but still no sound.

---

## Sonos Supported Formats (from docs.sonos.com)

| Codec | Formats | Media Types | Sample Rates |
|-------|---------|-------------|--------------|
| AAC-LC, HE-AAC | .m4a, .mp4, .aac | audio/mp4, audio/aac | up to 48kHz |
| MP3 | .mp3 | audio/mp3, audio/mpeg | up to 48kHz |
| WAV | .wav | audio/wav | up to 48kHz, 16-bit max |
| FLAC | .flac | audio/flac | up to 48kHz |

**Key insight from docs:** Sonos supports streaming but documentation focuses on:
- HLS (HTTP Live Streaming) with m3u8 playlists
- File-based streaming with Content-Length headers
- ICY metadata protocol for radio streams

---

## Likely Root Causes (Ranked by Probability)

### 1. WAV Header Format Issue (HIGH)
The WAV header uses max file size (0x7FFFFFFF) which might confuse Sonos's streaming decoder. Sonos might need specific header values for streaming.

### 2. Streaming Without Content-Length (HIGH)
Sonos docs emphasize the need for Content-Length headers. Our chunked transfer encoding might cause the decoder to fail silently.

### 3. Silent Decoder Failure (MEDIUM)
Sonos might be attempting to decode but producing silence. The "visualization" in the Sonos app could just be fake activity based on PLAYING state.

### 4. Sample Rate/Format Mismatch (LOW)
Header says 44100Hz, data is 44100Hz - should match. But worth verifying.

### 5. Ktor Chunked Transfer Encoding (MEDIUM)
How Ktor's `respondOutputStream` sends data might not be compatible with Sonos's streaming parser.

---

## Recommended Next Steps

### Step 1: Test with Static WAV File
Add an endpoint that serves a known-good WAV file from the phone's assets or downloads. If Sonos plays this, the issue is with our streaming format, not the network path.

```kotlin
get("/test.wav") {
    // Serve a pre-recorded WAV file
    call.respondFile(File("/path/to/test.wav"))
}
```

### Step 2: Capture and Verify Stream
Save the streamed WAV data to a file on the phone and play it with a media player to verify it's valid.

### Step 3: Try HLS Format
Sonos has excellent HLS support. Consider generating an m3u8 playlist with segmented audio files.

### Step 4: Try MP3 Encoding
Use a native MP3 encoder library (like LAME via JNI) since `x-rincon-mp3radio://` is well-tested with Sonos.

### Step 5: Packet Capture
Use Wireshark to capture the actual bytes being sent and compare with a working radio stream.

---

## Code Locations

### WAV Streaming Endpoint
`AudioStreamer.kt` lines ~150-210 - `/stream.wav` handler with WAV header generation

### AAC Streaming Endpoint  
`AudioStreamer.kt` lines ~246-290 - `/stream.aac` handler with ICY metadata support

### SOAP Request with Metadata
`SonosController.kt` lines ~27-90 - `play()` method with XML escaping and URI scheme handling

### PCM Recording Loop
`AudioStreamer.kt` lines ~302-380 - `startRecordingLoop()` with PCM and AAC encoding

### Raw PCM Flow for WAV
`AudioStreamer.kt` line ~56 - `_rawPcmFlow` SharedFlow for WAV streaming

---

## Test Commands

### Verify server is running
```bash
curl http://192.168.0.30:8080/
# Should return: "Audio Streamer OK"
```

### Check WAV stream headers
```bash
curl -I http://192.168.0.30:8080/stream.wav
```

### Query Sonos device info
```bash
curl http://192.168.0.16:1400/status/info
```

### Test with VLC (from another device)
```bash
vlc http://192.168.0.30:8080/stream.wav
```
If VLC plays the audio, the stream is valid and issue is Sonos-specific.

---

## External References

1. **Sonos Audio Formats**: https://docs.sonos.com/docs/supported-audio-formats
2. **Sonos Streaming Basics**: https://docs.sonos.com/docs/streaming-basics
3. **Sonos HLS Support**: https://docs.sonos.com/docs/http-live-streaming-hls
4. **SoCo Python Library**: https://github.com/SoCo/SoCo (reference implementation)
5. **Sonos UPnP Services**: https://sonos.svrooij.io/services/

---

## Build & Run

Ask the user for build/run commands. The project uses Gradle with Kotlin.

---

## Session Log Highlights

```
SetAVTransportURI response status: 200 OK
Transport state at 0.5s: PLAYING
Transport state at 2.5s: PLAYING
Media URI at 2.5s: http://192.168.0.30:8080/stream.wav
Current volume: 10
PCM data max sample: 2046 (should be > 0 if mic is capturing)
Streamed WAV 100 chunks, 1433600 bytes total
```

Everything indicates success except there's no actual audio output from the speaker.
