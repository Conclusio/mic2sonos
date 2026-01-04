package org.crocophant.speech2sonos

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SonosDevice(
    val name: String,
    val ipAddress: String,
    val port: Int,
    val nowPlayingInfo: NowPlayingInfo = NowPlayingInfo()
)

class SonosDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "SonosDiscovery"
        private const val SONOS_UPNP_PORT = 1400
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _devices = MutableStateFlow<List<SonosDevice>>(emptyList())
    val devices: StateFlow<List<SonosDevice>> = _devices

    private var isDiscovering = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Discovery started for $regType")
            isDiscovering = true
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${service.serviceName}")
            if (service.serviceType.contains("_sonos._tcp")) {
                nsdManager.resolveService(service, createResolveListener())
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${service.serviceName}")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped for $serviceType")
            isDiscovering = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Start discovery failed with error code $errorCode")
            isDiscovering = false
            try {
                nsdManager.stopServiceDiscovery(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery after failure", e)
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop discovery failed with error code $errorCode")
            isDiscovering = false
        }
    }
    
    private fun createResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName} with error code $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val hostAddress = serviceInfo.host?.hostAddress ?: return
                val rawName = serviceInfo.serviceName
                val displayName = if (rawName.contains("@")) {
                    rawName.substringAfter("@").trim()
                } else {
                    rawName
                }
                val device = SonosDevice(
                    name = displayName,
                    ipAddress = hostAddress,
                    port = SONOS_UPNP_PORT
                )
                Log.i(TAG, "Resolved device: ${device.name} at ${device.ipAddress}:${device.port} (mDNS port was ${serviceInfo.port})")
                val currentDevices = _devices.value
                if (currentDevices.none { it.ipAddress == device.ipAddress }) {
                    _devices.value = currentDevices + device
                }
            }
        }
    }

    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already in progress")
            return
        }
        try {
            Log.d(TAG, "Starting discovery")
            nsdManager.discoverServices("_sonos._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) {
            Log.d(TAG, "Discovery not in progress")
            return
        }
        try {
            Log.d(TAG, "Stopping discovery")
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop discovery", e)
        }
    }
}
