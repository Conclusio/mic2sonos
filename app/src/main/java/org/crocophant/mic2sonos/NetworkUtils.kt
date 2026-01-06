package org.crocophant.mic2sonos

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getLocalIpAddress(context: Context): String? {
        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                if (linkProperties != null) {
                    for (address in linkProperties.linkAddresses) {
                        val ipAddress = address.address
                        if (ipAddress is Inet4Address && !ipAddress.isLoopbackAddress) {
                            Log.d(TAG, "Found IP address via ConnectivityManager: ${ipAddress.hostAddress}")
                            return ipAddress.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get IP via ConnectivityManager: ${e.message}")
        }

        // Fallback to NetworkInterface enumeration
        try {
            return NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress }
                .firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP address", e)
            return null
        }
    }
}
