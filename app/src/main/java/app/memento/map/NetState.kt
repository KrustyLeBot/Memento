package app.memento.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the phone has a working internet connection, kept up to date by the system's default-network callback. */
object NetState {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    @Volatile private var registered = false

    @Synchronized
    fun init(context: Context) {
        if (registered) return
        registered = true
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        _online.value = cm.getNetworkCapabilities(cm.activeNetwork)?.let { usable(it) } == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = usable(caps)
            }

            override fun onLost(network: Network) {
                _online.value = false
            }
        })
    }

    // VALIDATED: a hotel portal or a dead Wi-Fi is "connected" but serves no tile
    private fun usable(c: NetworkCapabilities) =
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
