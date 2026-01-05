package org.crocophant.speech2sonos

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
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

data class NowPlayingInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String = "",
    val isPlaying: Boolean = false
)

class SonosNowPlaying {

    companion object {
        private const val TAG = "SonosNowPlaying"
    }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 10000
        }
    }

    suspend fun getNowPlaying(device: SonosDevice): NowPlayingInfo = withContext(Dispatchers.IO) {
        try {
            // Get position info which contains metadata
            val positionInfo = getPositionInfo(device)
            // Parse the metadata from the response
            parseMetadata(positionInfo, device)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get now playing info for ${device.name}", e)
            NowPlayingInfo()
        }
    }

    private suspend fun getPositionInfo(device: SonosDevice): String {
        val url = "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Control"
        val body =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
        </u:GetPositionInfo>
    </s:Body>
</s:Envelope>"""

        return try {
            val response = client.post(url) {
                header(
                    "SOAPACTION",
                    "\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\""
                )
                contentType(ContentType.Text.Xml)
                setBody(body)
            }
            response.bodyAsText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get position info from ${device.name}", e)
            ""
        }
    }

    private fun parseMetadata(responseBody: String, device: SonosDevice): NowPlayingInfo {
        try {
            // Extract TrackMetaData using regex first
            val metadataMatch =
                Regex("<TrackMetaData>(.*?)</TrackMetaData>", RegexOption.DOT_MATCHES_ALL).find(
                    responseBody
                )
            if (metadataMatch == null) {
                return NowPlayingInfo()
            }

            var metadataXml = metadataMatch.groupValues[1]

            // HTML decode the metadata - it comes XML-escaped in the SOAP response
            metadataXml = decodeHtmlEntities(metadataXml)

            // Parse the DIDL-Lite XML
            val docFactory = DocumentBuilderFactory.newInstance()
            val doc = docFactory.newDocumentBuilder().parse(
                java.io.ByteArrayInputStream(metadataXml.toByteArray(Charsets.UTF_8))
            )

            val itemElement =
                doc.getElementsByTagName("item").item(0) as? Element ?: return NowPlayingInfo()

            val title = itemElement.getElementsByTagName("dc:title").item(0)?.textContent ?: ""
            val artist = itemElement.getElementsByTagName("dc:creator").item(0)?.textContent ?: ""
            val album = itemElement.getElementsByTagName("upnp:album").item(0)?.textContent ?: ""
            var albumArtUri =
                itemElement.getElementsByTagName("upnp:albumArtURI").item(0)?.textContent ?: ""

            // Convert relative URL to absolute if needed
            if (albumArtUri.isNotEmpty() && !albumArtUri.startsWith("http")) {
                albumArtUri = "http://${device.ipAddress}:${device.port}$albumArtUri"
            }

            return NowPlayingInfo(
                title = title,
                artist = artist,
                album = album,
                artworkUrl = albumArtUri,
                isPlaying = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse metadata", e)
            return NowPlayingInfo()
        }
    }

    private fun decodeHtmlEntities(text: String): String {
        var result = text
        // Decode HTML entities in order (handle &amp; last to avoid double-decoding)
        result = result.replace("&lt;", "<")
        result = result.replace("&gt;", ">")
        result = result.replace("&quot;", "\"")
        result = result.replace("&apos;", "'")
        result = result.replace("&amp;", "&")  // Must be last!
        return result
    }
}
