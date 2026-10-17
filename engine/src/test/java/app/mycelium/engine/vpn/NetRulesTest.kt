package app.mycelium.engine.vpn

import app.mycelium.engine.vpn.NetRules.Net
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetRulesTest {
    private val wifiValidated = Net(internet = true, validated = true, vpn = false)
    private val wifiNoInternet = Net(internet = true, validated = false, vpn = false)   // also captive portal
    private val cellValidated = Net(internet = true, validated = true, vpn = false)
    private val vpnValidated = Net(internet = true, validated = true, vpn = true)

    @Test fun wifiValidatedIsOnline() = assertTrue(NetRules.online(listOf(wifiValidated)))
    @Test fun wifiNotValidatedIsSanctuary() = assertFalse(NetRules.online(listOf(wifiNoInternet)))
    @Test fun noNetworkIsSanctuary() = assertFalse(NetRules.online(emptyList()))
    @Test fun vpnOnlyIsSanctuary() = assertFalse(NetRules.online(listOf(vpnValidated)))
    @Test fun anyValidatedNonVpnNetworkWins() = assertTrue(NetRules.online(listOf(wifiNoInternet, vpnValidated, cellValidated)))
    @Test fun validatedWithoutInternetIsSanctuary() =
        assertFalse(NetRules.online(listOf(Net(internet = false, validated = true, vpn = false))))
}
