package dev.nymph.lyrics.app.status

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A change of the device's default network. */
sealed interface ConnectivityChange {
    data object Available : ConnectivityChange

    /** No default network left. */
    data object Lost : ConnectivityChange
}

/**
 * Default-network changes while collected. A switch between networks (Wi-Fi to mobile) is
 * [ConnectivityChange.Available] for the new one; [ConnectivityChange.Lost] only when nothing is
 * left. Registering also reports the current network once.
 */
fun connectivityChanges(context: Context): Flow<ConnectivityChange> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(ConnectivityChange.Available)
        }

        override fun onLost(network: Network) {
            if (manager.activeNetwork == null) trySend(ConnectivityChange.Lost)
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}
