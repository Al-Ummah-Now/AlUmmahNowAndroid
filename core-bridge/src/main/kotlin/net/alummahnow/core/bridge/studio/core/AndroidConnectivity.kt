package net.alummahnow.core.bridge.studio.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import net.alummahnow.core.Connectivity
import net.alummahnow.core.NetworkState

/** aun-core's connectivity adapter (Wi-Fi-only uploads and offline waiting). */
class AndroidConnectivity(
    context: Context,
    private val transfersAllowed: () -> Boolean = { true }
) : Connectivity {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun current(): NetworkState {
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        // The native engine checks this before running and before sending each upload chunk.
        val online = transfersAllowed() && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        return NetworkState(online = online, metered = metered)
    }
}
