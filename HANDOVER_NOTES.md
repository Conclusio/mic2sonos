# Handover Notes: Sonos Live Audio Streaming

## Current Status

**✅ WORKING: The app successfully streams live microphone audio to Sonos speakers.**

### What Works
- ✅ Sonos device discovery via mDNS
- ✅ UPnP SOAP commands (SetAVTransportURI, Play, Stop, etc.)
- ✅ HTTP server on phone serving audio to Sonos
- ✅ Real-time WAV streaming with ~1 second latency
- ✅ Audio visualization (waveform) during streaming
- ✅ Continuous playback (up to 10 minutes per session)

---

## Key Discoveries

### 1. Audio Amplification Required (CRITICAL)
Microphone audio from Android's AudioRecord is too quiet for Sonos to play audibly. **10x amplification** is applied to the PCM samples before streaming.

```kotlin
val amp = (signed * 10).coerceIn(-32768, 32767).toShort()
```

### 2. Content-Length Header Required
Sonos requires a `Content-Length` header to play audio. For streaming, we use a "fake" length of 10 minutes:

```kotlin
val fakeDataSize = 44100 * 2 * 60 * 10  // 10 minutes of PCM data
call.response.header("Content-Length", (44 + fakeDataSize).toString())
```

### 3. WAV Format Works Best
- WAV PCM 16-bit, 44.1kHz, mono works reliably
- AAC streaming stays in TRANSITIONING state
- HLS was tested but Sonos didn't play the segments

### 4. Plain HTTP URI (not radio schemes)
For WAV files, use plain `http://` URLs. The `x-rincon-mp3radio://` and `aac://` schemes are for internet radio streams only.

---

## Architecture

### Files
- `AudioStreamer.kt` - Records mic, amplifies PCM, serves WAV via Ktor HTTP server on port 8080
- `SonosController.kt` - Sends UPnP SOAP commands to Sonos devices  
- `SonosDiscovery.kt` - Discovers Sonos via mDNS (_sonos._tcp), uses port 1400 for UPnP
- `MainActivity.kt` - UI with device list, waveform visualizer, streaming controls

### Key Endpoint
- `http://<phone-ip>:8080/stream.wav` - Live WAV stream with amplified mic audio

### Data Flow
```
Phone Mic → AudioRecord (44.1kHz, 16-bit, mono) → PCM Buffer
                                                      ↓
                                              10x Amplification
                                                      ↓
                                              WAV Header + PCM
                                                      ↓
                                         Ktor HTTP Server (:8080)
                                                      ↓
                                              Sonos Speaker
```

---

## Known Limitations

1. **10-minute session limit** - Due to fake Content-Length. User must restart after 10 minutes.
2. **~1 second latency** - Unavoidable due to Sonos internal buffering.
3. **Single stream** - Current implementation streams to one device at a time.

---

## Build & Run

```bash
./gradlew assembleDebug
./gradlew installDebug
```

---

## Test Commands

### Verify server is running
```bash
curl http://<phone-ip>:8080/
# Returns: "Audio Streamer OK"
```

### Test WAV stream with VLC
```bash
vlc http://<phone-ip>:8080/stream.wav
```

---

## Future Improvements

1. **Multi-room support** - Stream to multiple Sonos speakers simultaneously
2. **Longer sessions** - Restart stream automatically before 10-minute limit
3. **Dynamic gain control** - Auto-adjust amplification based on input level
4. **AAC/MP3 encoding** - Reduce bandwidth for better network performance
