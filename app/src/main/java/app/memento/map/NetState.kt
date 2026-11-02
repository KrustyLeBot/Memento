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
    private val _cellular = MutableStateFlow(false)
    /** The connection is mobile data (4G/5G), said before a large download. */
    val cellular: StateFlow<Boolean> = _cellular
    @Volatile private var registered = false

    @Synchronized
    fun init(context: Context) {
        if (registered) return
        registered = true
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        _online.value = caps?.let { usable(it) } == true
        _cellular.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = usable(caps)
                _cellular.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }

            override fun onLost(network: Network) {
                _online.value = false
                _cellular.value = false
            }
        })
    }

    // VALIDATED: a hotel portal or a dead Wi-Fi is "connected" but serves no tile
    private fun usable(c: NetworkCapabilities) =
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
