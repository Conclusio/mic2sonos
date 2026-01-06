# Mic2Sonos

Stream microphone audio to Sonos speakers on your local network.

## Features

- **Device Discovery**: Find Sonos speakers via mDNS with manual refresh button
- **Audio Streaming**: Stream live microphone audio in WAV format or HLS
- **Track Metadata**: Display currently playing track with artwork (via UPnP event subscriptions)
- **Lyrics Search**: Quick link to search for lyrics of currently playing tracks
- **Amplification Control**: Adjust gain with a slider in Settings
- **Announcement Mode**: Play audio over currently playing music (announcement feature)
- **Multiple Device Support**: Select and stream to multiple speakers simultaneously

## Tech Stack

- **Android**: Compose UI, ViewModels, Kotlin coroutines
- **Device Discovery**: Android's NsdManager (mDNS)
- **Audio**: Android MediaRecorder, MediaCodec for WAV/AAC encoding
- **Networking**: Ktor client/server for HTTP streaming and UPnP event subscriptions
- **Metadata**: UPnP AVTransport service queries and event subscriptions

## Usage

1. Grant microphone permission when prompted
2. Tap **Refresh** to discover Sonos speakers
3. Select one or more devices
4. Tap the **microphone icon** to start streaming
5. Use **Settings** (gear icon) to adjust amplification and select audio mode

### Audio Modes

**Announcement Mode (Recommended)**
- Ducks the volume of currently playing music and plays your microphone stream on top at the specified volume level
- Preserves your current queue and song playback
- Recommended for most use cases

**Streaming Mode**
- Sends raw microphone audio as a direct stream to the device
- Clears the current queue as Sonos must stop playback to play the stream
- Requires significantly higher amplification (typically 20-40x) due to the audio level limitations of raw streaming

## Configuration

- **Min SDK**: Android 9 (for HTTP cleartext traffic to local devices)
- **Sonos Ports**: 1400 (UPnP), 8888 (event subscriptions)
- **Local Server Ports**: 8080 (audio streaming)

## Notes

Created with the help of [Sourcegraph's AmpCode](https://ampcode.com/).
