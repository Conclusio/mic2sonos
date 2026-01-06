package org.crocophant.mic2sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Controls Sonos device playback via SOAP commands over port 1400.
 * Supports playing audio streams, announcements, and fetching device metadata.
 */
class SonosController(private val context: Context) {

    companion object {
        private const val TAG = "SonosController"
    }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 10000
        }
    }

    private val audioClip = SonosAudioClip()
    private val nowPlaying = SonosNowPlaying()

    suspend fun play(
        device: SonosDevice,
        streamUrl: String,
        title: String = "Live Microphone",
        forceRadio: Boolean = true
    ) {
        val url = "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Control"

        val escapedTitle = title
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // SoCo-style metadata for radio streams
        // Note: This will be XML-escaped when embedded in SOAP body
        val metadataRaw =
            """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item id="R:0/0/0" parentID="R:0/0" restricted="true"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.audioItem.audioBroadcast</upnp:class><desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON65031_</desc></item></DIDL-Lite>"""

        // XML-escape the metadata for embedding in SOAP body
        val metadata = metadataRaw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        // For AAC streams, use aac:// scheme; for MP3 streams use x-rincon-mp3radio://
        // Check if it's an AAC stream based on file extension
        val isAacStream = streamUrl.contains(".aac")
        val actualUri = if (forceRadio && streamUrl.startsWith("http://")) {
            if (isAacStream) {
                "aac://" + streamUrl.removePrefix("http://")
            } else {
                "x-rincon-mp3radio://" + streamUrl.removePrefix("http://")
            }
        } else if (forceRadio && streamUrl.startsWith("https://")) {
            if (isAacStream) {
                "aac://" + streamUrl.removePrefix("https://")
            } else {
                "x-rincon-mp3radio://" + streamUrl.removePrefix("https://")
            }
        } else {
            streamUrl
        }

        Log.d(TAG, "Original URI: $streamUrl")
        Log.d(TAG, "Actual URI sent to Sonos: $actualUri")
        Log.d(TAG, "Metadata: $metadata")

        val setAVTransportURIBody =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
            <CurrentURI>$actualUri</CurrentURI>
            <CurrentURIMetaData>$metadata</CurrentURIMetaData>
        </u:SetAVTransportURI>
    </s:Body>
</s:Envelope>"""

        withContext(Dispatchers.IO) {
            try {
                // First, stop any current playback
                Log.d(TAG, "Stopping current playback on ${device.name}")
                val stopBody =
                    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
        </u:Stop>
    </s:Body>
</s:Envelope>"""
                try {
                    client.post(url) {
                        header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"")
                        contentType(ContentType.Text.Xml)
                        setBody(stopBody)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Stop failed (may be already stopped): ${e.message}")
                }

                Log.d(TAG, "Setting AVTransport URI to $actualUri for device ${device.name}")
                val setUriResponse = client.post(url) {
                    header(
                        "SOAPACTION",
                        "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""
                    )
                    contentType(ContentType.Text.Xml)
                    setBody(setAVTransportURIBody)
                }
                val setUriResponseBody = setUriResponse.bodyAsText()
                Log.i(TAG, "SetAVTransportURI response status: ${setUriResponse.status}")
                Log.i(TAG, "SetAVTransportURI response body: $setUriResponseBody")

                val playBody =
                    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
            <Speed>1</Speed>
        </u:Play>
    </s:Body>
</s:Envelope>"""

                Log.d(TAG, "Sending Play command to device ${device.name}")
                client.post(url) {
                    header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
                    contentType(ContentType.Text.Xml)
                    setBody(playBody)
                }
                Log.i(TAG, "Successfully started playback on ${device.name}")

                // Check transport state to see if Sonos reports any errors
                kotlinx.coroutines.delay(500)
                var transportInfo = getTransportInfo(device)
                Log.i(TAG, "Transport state at 0.5s: $transportInfo")

                // Also check what URI is actually set
                var mediaInfo = getMediaInfo(device)
                Log.i(TAG, "Media URI at 0.5s: $mediaInfo")

                // Check again after more time
                kotlinx.coroutines.delay(2000)
                transportInfo = getTransportInfo(device)
                Log.i(TAG, "Transport state at 2.5s: $transportInfo")
                mediaInfo = getMediaInfo(device)
                Log.i(TAG, "Media URI at 2.5s: $mediaInfo")

                // Check volume
                val volumeInfo = getVolume(device)
                Log.i(TAG, "Current volume: $volumeInfo")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start playback on ${device.name}", e)
                throw e
            }
        }
    }

    suspend fun getVolume(device: SonosDevice): String {
        val url = "http://${device.ipAddress}:${device.port}/MediaRenderer/RenderingControl/Control"
        val body =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:GetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
            <InstanceID>0</InstanceID>
            <Channel>Master</Channel>
        </u:GetVolume>
    </s:Body>
</s:Envelope>"""

        return withContext(Dispatchers.IO) {
            try {
                val response = client.post(url) {
                    header(
                        "SOAPACTION",
                        "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\""
                    )
                    contentType(ContentType.Text.Xml)
                    setBody(body)
                }
                val responseBody = response.bodyAsText()
                val volumeMatch = Regex("<CurrentVolume>(\\d+)</CurrentVolume>").find(responseBody)
                volumeMatch?.groupValues?.get(1) ?: "unknown"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get volume", e)
                "Error: ${e.message}"
            }
        }
    }

    suspend fun getMediaInfo(device: SonosDevice): String {
        val url = "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Control"
        val body =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:GetMediaInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
        </u:GetMediaInfo>
    </s:Body>
</s:Envelope>"""

        return withContext(Dispatchers.IO) {
            try {
                val response = client.post(url) {
                    header(
                        "SOAPACTION",
                        "\"urn:schemas-upnp-org:service:AVTransport:1#GetMediaInfo\""
                    )
                    contentType(ContentType.Text.Xml)
                    setBody(body)
                }
                val responseBody = response.bodyAsText()
                // Extract just the CurrentURI for cleaner logging
                val uriMatch = Regex("<CurrentURI>(.*?)</CurrentURI>").find(responseBody)
                val uri = uriMatch?.groupValues?.get(1) ?: "not found"
                Log.d(TAG, "GetMediaInfo CurrentURI: $uri")
                uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get media info", e)
                "Error: ${e.message}"
            }
        }
    }

    suspend fun getTransportInfo(device: SonosDevice): String {
        val url = "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Control"
        val body =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
        </u:GetTransportInfo>
    </s:Body>
</s:Envelope>"""

        return withContext(Dispatchers.IO) {
            try {
                val response = client.post(url) {
                    header(
                        "SOAPACTION",
                        "\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\""
                    )
                    contentType(ContentType.Text.Xml)
                    setBody(body)
                }
                val responseBody = response.bodyAsText()
                Log.d(TAG, "GetTransportInfo response: $responseBody")
                responseBody
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get transport info", e)
                "Error: ${e.message}"
            }
        }
    }

    /**
     * Play audio as an announcement with automatic ducking.
     * Uses the Sonos audioClip API which ducks current playback
     * and restores it after the clip finishes.
     *
     * @param device The Sonos device to announce on
     * @param streamUrl The URL of the audio stream to play
     * @param volume Optional announcement volume (0-100), doesn't affect music volume
     * @return Result with success/failure info
     */
    suspend fun playAnnouncement(
        device: SonosDevice,
        streamUrl: String,
        volume: Int? = null
    ): Result<AudioClipResponse> {
        Log.i(TAG, "Playing announcement on ${device.name}: $streamUrl (volume: $volume)")
        return audioClip.playAudioClip(device, streamUrl, volume)
    }

    /**
     * Play live microphone stream as an announcement.
     * The currently playing music will be ducked and restored after streaming stops.
     */
    suspend fun playAnnouncementStream(
        device: SonosDevice,
        localIp: String,
        port: Int,
        volume: Int? = null
    ): Result<AudioClipResponse> {
        val streamUrl = "http://$localIp:$port/stream.wav"
        Log.i(TAG, "Starting announcement stream: $streamUrl")
        return playAnnouncement(device, streamUrl, volume)
    }

    /**
     * Get currently playing track information from the device.
     */
    suspend fun getNowPlaying(device: SonosDevice): NowPlayingInfo {
        return nowPlaying.getNowPlaying(device)
    }

    suspend fun stop(device: SonosDevice) {
        val url = "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Control"
        val stopBody =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
        </u:Stop>
    </s:Body>
</s:Envelope>"""

        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Sending Stop command to device ${device.name}")
                client.post(url) {
                    header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"")
                    contentType(ContentType.Text.Xml)
                    setBody(stopBody)
                }
                Log.i(TAG, "Successfully stopped playback on ${device.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop playback on ${device.name}", e)
            }
        }
    }

    fun getDeviceIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        Log.d(TAG, "Found IP address: ${address.hostAddress}")
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address via NetworkInterface", e)
        }

        try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddressStr = wifiInfo.ipAddress.let { ipInt ->
                if (ipInt != 0) {
                    String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                } else null
            }
            if (ipAddressStr != null) {
                Log.d(TAG, "Found IP address via WifiManager: $ipAddressStr")
                return ipAddressStr
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address via WifiManager", e)
        }

        Log.w(TAG, "Could not determine device IP address")
        return null
    }
}
