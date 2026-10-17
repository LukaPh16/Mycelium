package app.mycelium.engine.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** Pure decision: online only if a non-VPN network has INTERNET and VALIDATED. */
internal object NetRules {
    data class Net(val internet: Boolean, val validated: Boolean, val vpn: Boolean)

    fun online(nets: Collection<Net>) = nets.any { it.internet && it.validated && !it.vpn }
}

/** Tracks non-VPN networks via callback (no polling) and reports online <-> sanctuary changes. */
class NetGate(context: Context, private val onChange: (online: Boolean) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val caps = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val links = ConcurrentHashMap<Network, LinkProperties>()

    @Volatile var online = false
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            cm.getNetworkCapabilities(n)?.let { caps[n] = it }
            cm.getLinkProperties(n)?.let { links[n] = it }
            evaluate()
        }
        override fun onLost(n: Network) { caps.remove(n); links.remove(n); evaluate() }
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { caps[n] = c; evaluate() }
        override fun onLinkPropertiesChanged(n: Network, l: LinkProperties) { links[n] = l; evaluate() }
    }

    fun start() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(req, callback)
    }

    /** First DNS server (IPv4 preferred) of a validated non-VPN network. */
    fun upstreamDns(): InetAddress? {
        val net = caps.entries.firstOrNull { NetRules.online(listOf(it.value.toNet())) }?.key ?: return null
        val servers = links[net]?.dnsServers.orEmpty()
        return servers.firstOrNull { it is Inet4Address } ?: servers.firstOrNull()
    }

    @Synchronized private fun evaluate() {
        val now = NetRules.online(caps.values.map { it.toNet() })
        if (now != online) {
            online = now
            onChange(now)
        }
    }

    private fun NetworkCapabilities.toNet() = NetRules.Net(
        internet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        vpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
    )
}
