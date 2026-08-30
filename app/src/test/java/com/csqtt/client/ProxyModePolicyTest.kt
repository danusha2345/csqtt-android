// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyModePolicyTest {
    @Test
    fun socksModeDoesNotRequestAndroidVpnPermission() {
        assertFalse(requiresVpnPermission(CsqttConstants.Proxy.MODE_SOCKS5))
        assertTrue(requiresVpnPermission(CsqttConstants.Proxy.MODE_VPN))
    }

    @Test
    fun nativeRuntimeUsesExactlyOneLocalIoMode() {
        assertEquals(
            listOf("-socks5", "127.0.0.1:1080"),
            proxyRuntimeArgs(CsqttConstants.Proxy.MODE_SOCKS5, 1080),
        )
        assertEquals(
            listOf("-tun-uds", "csqtt_tun_uds"),
            proxyRuntimeArgs(CsqttConstants.Proxy.MODE_VPN, 1080),
        )
    }
}
