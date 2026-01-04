package org.crocophant.speech2sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Manages UPnP event subscriptions for Sonos devices.
 * Subscribes to AVTransport service events to get notified of track changes
 * instead of polling every 3 seconds.
 */
class SonosEventSubscription(
    private val context: Context,
    private val onTrackChanged: (device: SonosDevice, newInfo: NowPlayingInfo) -> Unit
) {
    companion object {
        private const val TAG = "SonosEventSubscription"
        private const val SUBSCRIBE_TIMEOUT = 3600 // seconds (1 hour)
        private const val EVENT_SERVER_PORT = 8888
    }

    private var eventServer: ApplicationEngine? = null
    private val subscriptions = mutableMapOf<String, SubscriptionInfo>() // device IP -> subscription info
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val sonosController = SonosController(context)

    data class SubscriptionInfo(
        val sid: String, // Subscription ID
        val deviceIp: String,
        val subscriptionTime: Long,
        val timeout: Int
    )

    fun startEventServer(): Boolean {
        return try {
            val localIp = getLocalIpAddress() ?: run {
                Log.e(TAG, "Could not determine local IP address")
                return false
            }

            Log.d(TAG, "Starting event server on $localIp:$EVENT_SERVER_PORT")

            eventServer = embeddedServer(ServerCIO, port = EVENT_SERVER_PORT) {
                routing {
                    // Catch all requests - handle NOTIFY and any other method
                    route("/notify") {
                        handle {
                            Log.d(TAG, "*** Received ${call.request.httpMethod} on /notify ***")
                            val sid = call.request.header("SID")

                            if (sid == null) {
                                Log.w(TAG, "Request missing SID header")
                                call.response.status(HttpStatusCode.BadRequest)
                                call.respondText("Missing SID")
                                return@handle
                            }

                            Log.d(TAG, "Received request for SID: $sid")

                            // Find subscription by SID
                            val subscriptionInfo = subscriptions.values.find { it.sid == sid }
                            if (subscriptionInfo == null) {
                                Log.w(TAG, "Received notification for unknown subscription: $sid")
                                call.response.status(HttpStatusCode.BadRequest)
                                call.respondText("Unknown SID")
                                return@handle
                            }

                            // Get request body content
                            val content = call.receiveText()
                            Log.d(TAG, "Request body length: ${content.length}")
                            if (content.isNotEmpty()) {
                                parseAndHandleNotification(subscriptionInfo.deviceIp, content)
                            }

                            // Respond with 200 OK
                            call.response.status(HttpStatusCode.OK)
                            call.respondText("OK")
                        }
                    }
                }
            }
            eventServer?.start()

            Log.d(TAG, "Event server started successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start event server", e)
            false
        }
    }

    private fun parseAndHandleNotification(deviceIp: String, xmlContent: String) {
        try {
            // Parse the LastChange variable which contains the actual state changes
            val lastChangeMatch = xmlContent.substringAfter("<LastChange>").substringBefore("</LastChange>")
            if (lastChangeMatch.isEmpty()) {
                Log.v(TAG, "No LastChange in notification")
                return
            }

            // LastChange contains XML-encoded instance data
            val decodedLastChange = lastChangeMatch
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")

            // Check if CurrentTrackMetaData or TransportState changed
            if (decodedLastChange.contains("CurrentTrackMetaData") ||
                decodedLastChange.contains("TransportState")) {

                Log.d(TAG, "Track or state change detected for device $deviceIp")

                // Query the device for current track info
                scope.launch {
                    try {
                        val device = SonosDevice("Unknown", deviceIp, 1400)
                        val newInfo = sonosController.getNowPlaying(device)

                        Log.d(TAG, "Updated track info for $deviceIp: ${newInfo.title}")
                        onTrackChanged(device, newInfo)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get updated track info for $deviceIp", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing notification", e)
        }
    }

    suspend fun subscribeToDevice(device: SonosDevice): Boolean {
        return try {
            val localIp = getLocalIpAddress() ?: run {
                Log.e(TAG, "Could not determine local IP address")
                return false
            }

            val subscriptionUrl = "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Event"
            val callbackUrl = "http://$localIp:$EVENT_SERVER_PORT/notify"

            Log.d(TAG, "Subscribing to events on ${device.name} at $subscriptionUrl")
            Log.d(TAG, "Callback URL: $callbackUrl")

            val httpClient = HttpClient(ClientCIO) {
                engine {
                    requestTimeout = 5000
                }
            }

            val response = httpClient.request(subscriptionUrl) {
                method = io.ktor.http.HttpMethod.parse("SUBSCRIBE")
                header("CALLBACK", "<$callbackUrl>")
                header("NT", "upnp:event")
                header("TIMEOUT", "Second-$SUBSCRIBE_TIMEOUT")
            }

            httpClient.close()

            if (response.status.value == 200) {
                val sid = response.headers["SID"] ?: run {
                    Log.e(TAG, "No SID in subscription response")
                    return false
                }

                subscriptions[device.ipAddress] = SubscriptionInfo(
                    sid = sid,
                    deviceIp = device.ipAddress,
                    subscriptionTime = System.currentTimeMillis(),
                    timeout = SUBSCRIBE_TIMEOUT
                )

                Log.d(TAG, "Successfully subscribed to ${device.name}: SID=$sid")

                // Start auto-renewal before timeout expires
                startAutoRenewal(device)

                true
            } else {
                Log.e(TAG, "Subscription failed with status ${response.status}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to ${device.name}", e)
            false
        }
    }

    private fun startAutoRenewal(device: SonosDevice) {
        scope.launch {
            // Renew at 85% of timeout
            val renewalDelay = (SUBSCRIBE_TIMEOUT * 0.85).toLong() * 1000

            while (subscriptions.containsKey(device.ipAddress)) {
                delay(renewalDelay)
                renewSubscription(device)
            }
        }
    }

    private suspend fun renewSubscription(device: SonosDevice) {
        try {
            val subInfo = subscriptions[device.ipAddress] ?: return
            val subscriptionUrl = "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Event"

            Log.d(TAG, "Renewing subscription to ${device.name}")

            val httpClient = HttpClient(ClientCIO) {
                engine {
                    requestTimeout = 5000
                }
            }

            val response = httpClient.request(subscriptionUrl) {
                method = io.ktor.http.HttpMethod.parse("SUBSCRIBE")
                header("SID", subInfo.sid)
                header("TIMEOUT", "Second-$SUBSCRIBE_TIMEOUT")
            }

            httpClient.close()

            if (response.status.value == 200) {
                subscriptions[device.ipAddress] = subInfo.copy(
                    subscriptionTime = System.currentTimeMillis()
                )
                Log.d(TAG, "Successfully renewed subscription for ${device.name}")
            } else {
                Log.e(TAG, "Subscription renewal failed for ${device.name}: ${response.status}")
                subscriptions.remove(device.ipAddress)
                subscribeToDevice(device)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to renew subscription for ${device.name}", e)
            subscriptions.remove(device.ipAddress)
        }
    }

    suspend fun unsubscribeFromDevice(device: SonosDevice) {
        try {
            val subInfo = subscriptions[device.ipAddress] ?: return
            val subscriptionUrl = "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Event"

            Log.d(TAG, "Unsubscribing from ${device.name}")

            val httpClient = HttpClient(ClientCIO) {
                engine {
                    requestTimeout = 5000
                }
            }

            httpClient.request(subscriptionUrl) {
                method = io.ktor.http.HttpMethod.parse("UNSUBSCRIBE")
                header("SID", subInfo.sid)
            }

            httpClient.close()
            subscriptions.remove(device.ipAddress)

            Log.d(TAG, "Successfully unsubscribed from ${device.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unsubscribe from ${device.name}", e)
            subscriptions.remove(device.ipAddress)
        }
    }

    fun stopEventServer() {
        try {
            eventServer?.stop()
            Log.d(TAG, "Event server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping event server", e)
        }
    }

    fun cleanup() {
        scope.cancel()
        stopEventServer()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            val ipAddress = connectionInfo.ipAddress

            return if (ipAddress == 0) {
                // WiFi not connected, try other interfaces
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .map { it.hostAddress }
                    .firstOrNull()
            } else {
                // WiFi is connected
                val bytes = byteArrayOf(
                    (ipAddress and 0xff).toByte(),
                    ((ipAddress shr 8) and 0xff).toByte(),
                    ((ipAddress shr 16) and 0xff).toByte(),
                    ((ipAddress shr 24) and 0xff).toByte()
                )
                "%d.%d.%d.%d".format(
                    bytes[0].toInt() and 0xff,
                    bytes[1].toInt() and 0xff,
                    bytes[2].toInt() and 0xff,
                    bytes[3].toInt() and 0xff
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP address", e)
            return null
        }
    }
}
