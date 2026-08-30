// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

internal fun requiresVpnPermission(mode: String): Boolean =
    mode != CsqttConstants.Proxy.MODE_SOCKS5

internal fun proxyRuntimeArgs(mode: String, port: Int): List<String> =
    if (mode == CsqttConstants.Proxy.MODE_SOCKS5) {
        require(port in 1..65535)
        listOf("-socks5", "127.0.0.1:$port")
    } else {
        listOf("-tun-uds", "csqtt_tun_uds")
    }
