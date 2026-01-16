package org.crocophant.mic2sonos

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

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

    private var eventServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var assignedPort: Int? = null
    private val subscriptions =
        mutableMapOf<String, SubscriptionInfo>() // device IP -> subscription info
    private val deviceMap = mutableMapOf<String, SonosDevice>() // IP address -> full device info
    private var cleanedUp = false
    private val scope =
        CoroutineScope(Dispatchers.IO + Job() + CoroutineExceptionHandler { _, exception ->
            Log.e(
                TAG,
                "Uncaught exception in event subscription scope: ${exception.message}",
                exception
            )
        })
    private val sonosController = SonosController(context)

    data class SubscriptionInfo(
        val sid: String, // Subscription ID
        val deviceIp: String,
        val subscriptionTime: Long,
        val timeout: Int
    )

    fun startEventServer(): Boolean {
        return try {
            // Reset cleanup flag so we can start fresh
            cleanedUp = false
            
            // Ensure any old server is stopped first
            stopEventServer()

            val localIp = getLocalIpAddress() ?: run {
                Log.e(TAG, "Could not determine local IP address")
                return false
            }

            Log.d(TAG, "Starting event server on $localIp with port 0 (dynamic assignment)")

            try {
                // Use port 0 to let OS assign an available port
                eventServer = embeddedServer(ServerCIO, port = 0) {
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
                                    Log.w(
                                        TAG,
                                        "Received notification for unknown subscription: $sid"
                                    )
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

                // Get the actual assigned port from resolvedConnectors
                scope.launch {
                    try {
                        val connectors = eventServer?.engine?.resolvedConnectors() ?: emptyList()
                        if (connectors.isNotEmpty()) {
                            assignedPort = connectors.first().port
                            Log.d(TAG, "Event server assigned port $assignedPort")
                        } else {
                            Log.w(
                                TAG,
                                "Could not determine assigned port, using fallback $EVENT_SERVER_PORT"
                            )
                            assignedPort = EVENT_SERVER_PORT
                        }
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Error getting resolved connectors: ${e.message}, using fallback $EVENT_SERVER_PORT"
                        )
                        assignedPort = EVENT_SERVER_PORT
                    }
                }

                Log.d(TAG, "Event server started successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start event server: ${e.message}")
                eventServer?.stop()
                eventServer = null
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start event server: ${e.message}", e)
            false
        }
    }

    private fun parseAndHandleNotification(deviceIp: String, xmlContent: String) {
        try {
            // Parse the LastChange variable which contains the actual state changes
            val lastChangeMatch =
                xmlContent.substringAfter("<LastChange>").substringBefore("</LastChange>")
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
                decodedLastChange.contains("TransportState")
            ) {

                Log.d(TAG, "Track or state change detected for device $deviceIp")

                // Query the device for current track info
                scope.launch {
                    try {
                        // Get the device from map, or create a placeholder with IP
                        val device = deviceMap[deviceIp] ?: SonosDevice("Unknown", deviceIp, 1400)
                        val newInfo = sonosController.getNowPlaying(device)

                        Log.d(TAG, "Updated track info for ${device.name}: ${newInfo.title}")
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

            val subscriptionUrl =
                "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Event"
            val port = assignedPort ?: EVENT_SERVER_PORT
            val callbackUrl = "http://$localIp:$port/notify"

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

                // Store device info for later event callbacks
                deviceMap[device.ipAddress] = device
                
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
            val subscriptionUrl =
                "http://${device.ipAddress}:${device.port}/MediaRenderer/AVTransport/Event"

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

    fun stopEventServer() {
        try {
            eventServer?.let { server ->
                try {
                    server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
                    Log.d(TAG, "Event server stopped")
                } catch (e: Exception) {
                    Log.w(TAG, "Error during graceful shutdown, forcing stop", e)
                    try {
                        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
                    } catch (e2: Exception) {
                        Log.w(TAG, "Error forcing server stop", e2)
                    }
                }
            }
            eventServer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping event server", e)
        }
    }

    suspend fun unsubscribeAll() {
        try {
            Log.d(TAG, "Unsubscribing from ${subscriptions.size} devices")
            subscriptions.forEach { (deviceIp, subInfo) ->
                try {
                    // Attempt to unsubscribe (send SUBSCRIBE with TIMEOUT: Second-0)
                    val subscriptionUrl = "http://$deviceIp:1400/MediaRenderer/AVTransport/Event"
                    val httpClient = HttpClient(ClientCIO) {
                        engine {
                            requestTimeout = 5000
                        }
                    }

                    httpClient.request(subscriptionUrl) {
                        method = io.ktor.http.HttpMethod.parse("SUBSCRIBE")
                        header("SID", subInfo.sid)
                        header("TIMEOUT", "Second-0")
                    }
                    httpClient.close()
                    Log.d(TAG, "Unsubscribed from device $deviceIp")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unsubscribe from device $deviceIp", e)
                }
            }
            subscriptions.clear()
            deviceMap.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error unsubscribing from all devices", e)
        }
    }

    fun cleanup() {
        if (cleanedUp) {
            Log.d(TAG, "Already cleaned up, skipping duplicate cleanup")
            return
        }
        cleanedUp = true
        
        try {
            Log.d(TAG, "Starting cleanup")
            // Stop server first (graceful shutdown)
            stopEventServer()
            
            // Give server a moment to finish shutdown
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Log.v(TAG, "Interrupted during cleanup delay")
            }
            
            // Cancel scope last (after server is shut down)
            scope.cancel()
            Log.d(TAG, "Cleanup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    private fun getLocalIpAddress(): String? = NetworkUtils.getLocalIpAddress(context)
}
