package org.crocophant.speech2sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

class SonosController(private val context: Context) {

    companion object {
        private const val TAG = "SonosController"
    }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 10000
        }
    }

    suspend fun play(device: SonosDevice, streamUrl: String, title: String = "Live Microphone") {
        val url = "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Control"
        
        val escapedTitle = title
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        
        val metadata = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item id="R:0/0/0" parentID="R:0/0" restricted="true"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.audioItem.audioBroadcast</upnp:class><desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON65031_</desc></item></DIDL-Lite>"""
        
        Log.d(TAG, "Playing URI: $streamUrl")
        Log.d(TAG, "Metadata: $metadata")
        
        val setAVTransportURIBody = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
            <CurrentURI>$streamUrl</CurrentURI>
            <CurrentURIMetaData>$metadata</CurrentURIMetaData>
        </u:SetAVTransportURI>
    </s:Body>
</s:Envelope>"""

        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Setting AVTransport URI to $streamUrl for device ${device.name}")
                client.post(url) {
                    header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
                    contentType(ContentType.Text.Xml)
                    setBody(setAVTransportURIBody)
                }

                val playBody = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start playback on ${device.name}", e)
                throw e
            }
        }
    }

    suspend fun playTestAudio(device: SonosDevice) {
        val testUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
        play(device, testUrl)
    }

    suspend fun stop(device: SonosDevice) {
        val url = "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Control"
        val stopBody = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
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
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            if (ipAddress != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
                Log.d(TAG, "Found IP address via WifiManager: $ip")
                return ip
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address via WifiManager", e)
        }
        
        Log.w(TAG, "Could not determine device IP address")
        return null
    }
}
