package org.crocophant.speech2sonos

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Sonos AudioClip API client for announcement mode.
 * 
 * Uses WebSocket connection to port 1443 with the audioClip:1 namespace
 * to play audio clips with automatic volume ducking of current playback.
 * 
 * ## Implementation Sources:
 * 
 * - **sonos-websocket Python library** (Home Assistant):
 *   https://github.com/jjlawren/sonos-websocket/blob/main/sonos_websocket/websocket.py
 *   - API key, protocol header, and message format
 *   - Flow: empty command → get householdId → getGroups → loadAudioClip
 * 
 * - **Sonos Control API Documentation**:
 *   https://docs.sonos.com/reference/audioclip-loadaudioclip-playerid
 *   - audioClip namespace and loadAudioClip command
 *   - AUDIO_CLIP capability check
 * 
 * - **Home Assistant Sonos Integration**:
 *   https://github.com/home-assistant/core/blob/dev/homeassistant/components/sonos/media_player.py
 *   - announce parameter usage with volume
 * 
 * - **Sonos Groups API**:
 *   https://docs.sonos.com/reference/groups-objects
 *   - Player object structure with websocketUrl and capabilities
 */
class SonosAudioClip {

    companion object {
        private const val TAG = "SonosAudioClip"
        
        // API key from sonos-websocket library
        // Source: https://github.com/jjlawren/sonos-websocket/blob/main/sonos_websocket/websocket.py#L14
        private const val SONOS_API_KEY = "123e4567-e89b-12d3-a456-426655440000"
        
        // WebSocket port for local Sonos API (mentioned in Home Assistant docs)
        // Source: https://www.home-assistant.io/integrations/sonos/#network-requirements
        private const val WEBSOCKET_PORT = 1443
        
        // Protocol header from sonos-websocket library
        // Source: https://github.com/jjlawren/sonos-websocket/blob/main/sonos_websocket/websocket.py#L15
        private const val WEBSOCKET_PROTOCOL = "v1.api.smartspeaker.audio"
    }

    private val client = HttpClient(CIO) {
        install(WebSockets)
        engine {
            https {
                trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            }
        }
    }

    /**
     * Play an audio clip on the Sonos device with automatic ducking.
     * 
     * @param device The Sonos device to play the clip on
     * @param streamUrl The URL of the audio to play
     * @param volume Optional volume level (0-100) for the announcement
     * @param appId Application identifier for the clip
     * @param name Human-readable name for the clip source
     * @return Result containing success status and any error message
     */
    suspend fun playAudioClip(
        device: SonosDevice,
        streamUrl: String,
        volume: Int? = null,
        appId: String = "org.crocophant.speech2sonos",
        name: String = "Speech2Sonos"
    ): Result<AudioClipResponse> = withContext(Dispatchers.IO) {
        try {
            val wsUrl = "wss://${device.ipAddress}:$WEBSOCKET_PORT/websocket/api"
            Log.d(TAG, "Connecting to $wsUrl")

            var clipResponse: AudioClipResponse? = null
            var errorMessage: String? = null

            val completed = withTimeoutOrNull(10000L) {
                try {
                    client.webSocket(
                        urlString = wsUrl,
                        request = {
                            headers.append("X-Sonos-Api-Key", SONOS_API_KEY)
                            headers.append("Sec-WebSocket-Protocol", WEBSOCKET_PROTOCOL)
                        }
                    ) {
                        // Step 1: Get householdId by sending empty command
                        val householdId = getHouseholdId()
                        if (householdId == null) {
                            errorMessage = "Failed to get household ID"
                            return@webSocket
                        }
                        Log.d(TAG, "Got household ID: $householdId")

                        // Step 2: Get player ID for this device via getGroups
                        val playerId = getPlayerId(device.ipAddress, householdId)
                        if (playerId == null) {
                            errorMessage = "Failed to get player ID (device may not support AUDIO_CLIP)"
                            return@webSocket
                        }
                        Log.d(TAG, "Got player ID: $playerId")

                        // Step 3: Send loadAudioClip command
                        clipResponse = loadAudioClip(playerId, streamUrl, volume, appId, name)
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket error", e)
                    errorMessage = e.message
                    false
                }
            }

            when {
                completed == null -> Result.failure(Exception("WebSocket connection timeout"))
                errorMessage != null -> Result.failure(Exception(errorMessage))
                clipResponse != null -> Result.success(clipResponse!!)
                else -> Result.failure(Exception("Unknown error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio clip", e)
            Result.failure(e)
        }
    }

    /**
     * Get household ID by sending an empty command.
     * The Sonos device returns the householdId in the response even for invalid commands.
     * 
     * Source: sonos-websocket get_household_id()
     * https://github.com/jjlawren/sonos-websocket/blob/main/sonos_websocket/websocket.py
     * "Note: This is an invalid command but returns the household ID anyway."
     */
    private suspend fun DefaultClientWebSocketSession.getHouseholdId(): String? {
        // Send empty command - Sonos returns householdId in error response
        // This is the same technique used by sonos-websocket library
        val command = JSONArray().apply {
            put(JSONObject()) // Empty command object
            put(JSONObject()) // Empty options
        }

        send(Frame.Text(command.toString()))
        Log.d(TAG, "Sent empty command to get householdId")

        for (frame in incoming) {
            if (frame is Frame.Text) {
                val responseText = frame.readText()
                Log.d(TAG, "Received: $responseText")
                
                val response = JSONArray(responseText)
                if (response.length() >= 1) {
                    val header = response.getJSONObject(0)
                    // The householdId is returned in the header even for failed commands
                    val householdId = header.optString("householdId", "")
                    if (householdId.isNotEmpty()) {
                        return householdId
                    }
                }
                break
            }
        }
        return null
    }

    /**
     * Get player ID by calling getGroups and matching by IP address.
     * 
     * Source: sonos-websocket get_player_id() and get_groups()
     * https://github.com/jjlawren/sonos-websocket/blob/main/sonos_websocket/websocket.py
     * 
     * The player's websocketUrl contains its IP, which we match against.
     * We also verify the device has AUDIO_CLIP capability.
     * 
     * API Reference: https://docs.sonos.com/reference/groups-objects
     */
    private suspend fun DefaultClientWebSocketSession.getPlayerId(deviceIp: String, householdId: String): String? {
        // Command format from sonos-websocket: namespace + command + householdId
        val command = JSONArray().apply {
            put(JSONObject().apply {
                put("namespace", "groups:1")
                put("command", "getGroups")
                put("householdId", householdId)
            })
            put(JSONObject()) // Empty options
        }

        send(Frame.Text(command.toString()))
        Log.d(TAG, "Sent getGroups command with householdId: $householdId")

        for (frame in incoming) {
            if (frame is Frame.Text) {
                val responseText = frame.readText()
                Log.d(TAG, "Received groups: $responseText")
                
                val response = JSONArray(responseText)
                if (response.length() >= 2) {
                    val header = response.getJSONObject(0)
                    val data = response.getJSONObject(1)
                    
                    if (header.optBoolean("success", false)) {
                        val players = data.optJSONArray("players")
                        if (players != null) {
                            for (i in 0 until players.length()) {
                                val player = players.getJSONObject(i)
                                val wsUrl = player.optString("websocketUrl", "")
                                // Match by IP in the websocket URL
                                if (wsUrl.contains(deviceIp)) {
                                    // Check for AUDIO_CLIP capability
                                    val capabilities = player.optJSONArray("capabilities")
                                    if (capabilities != null) {
                                        for (j in 0 until capabilities.length()) {
                                            if (capabilities.getString(j) == "AUDIO_CLIP") {
                                                return player.optString("id")
                                            }
                                        }
                                    }
                                    Log.w(TAG, "Device does not support AUDIO_CLIP capability")
                                    return null
                                }
                            }
                        }
                    } else {
                        Log.e(TAG, "getGroups failed: ${header.optString("type", "unknown")}")
                    }
                }
                break
            }
        }
        return null
    }

    /**
     * Send loadAudioClip command to play audio with automatic ducking.
     * 
     * Source: sonos-websocket play_clip()
     * https://github.com/jjlawren/sonos-websocket/blob/main/sonos_websocket/websocket.py
     * 
     * API Reference: https://docs.sonos.com/reference/audioclip-loadaudioclip-playerid
     * 
     * The audioClip:1 namespace provides:
     * - Automatic volume ducking of current playback
     * - Priority system for multiple clips
     * - Automatic restoration of previous volume
     */
    private suspend fun DefaultClientWebSocketSession.loadAudioClip(
        playerId: String,
        streamUrl: String,
        volume: Int?,
        appId: String,
        name: String
    ): AudioClipResponse {
        // Options format from sonos-websocket play_clip()
        val options = JSONObject().apply {
            put("name", name)
            put("appId", appId)
            put("streamUrl", streamUrl)
            if (volume != null) {
                put("volume", volume)
            }
        }

        // Command format: [header, options] as JSON array
        val command = JSONArray().apply {
            put(JSONObject().apply {
                put("namespace", "audioClip:1")
                put("command", "loadAudioClip")
                put("playerId", playerId)
            })
            put(options)
        }

        send(Frame.Text(command.toString()))
        Log.d(TAG, "Sent loadAudioClip command: $command")

        for (frame in incoming) {
            if (frame is Frame.Text) {
                val responseText = frame.readText()
                Log.d(TAG, "AudioClip response: $responseText")
                
                val response = JSONArray(responseText)
                if (response.length() >= 2) {
                    val header = response.getJSONObject(0)
                    val data = response.getJSONObject(1)
                    
                    val success = header.optBoolean("success", false)
                    val clipId = data.optString("id", "")
                    
                    return AudioClipResponse(
                        success = success,
                        clipId = clipId,
                        error = if (!success) header.optString("type", "Unknown error") else null
                    )
                }
                break
            }
        }
        
        return AudioClipResponse(success = false, error = "No response received")
    }

    /**
     * Check if a device supports the audioClip feature.
     */
    suspend fun supportsAudioClip(device: SonosDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val wsUrl = "wss://${device.ipAddress}:$WEBSOCKET_PORT/websocket/api"
            
            var supported = false
            
            val completed = withTimeoutOrNull(5000L) {
                try {
                    client.webSocket(
                        urlString = wsUrl,
                        request = {
                            headers.append("X-Sonos-Api-Key", SONOS_API_KEY)
                            headers.append("Sec-WebSocket-Protocol", WEBSOCKET_PROTOCOL)
                        }
                    ) {
                        val householdId = getHouseholdId()
                        if (householdId != null) {
                            val playerId = getPlayerId(device.ipAddress, householdId)
                            supported = playerId != null
                        }
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket error checking support", e)
                    false
                }
            }
            
            completed == true && supported
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check audioClip support", e)
            false
        }
        }
        }

        data class AudioClipResponse(
    val success: Boolean,
    val clipId: String? = null,
    val error: String? = null
)
