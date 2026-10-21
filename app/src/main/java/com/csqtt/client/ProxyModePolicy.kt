// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

internal fun requiresVpnPermission(mode: String): Boolean =
    mode != CsqttConstants.Proxy.MODE_SOCKS5

internal fun proxyRuntimeArgs(mode: String, port: Int, lanEnabled: Boolean = false, username: String = ""): List<String> =
    if (mode == CsqttConstants.Proxy.MODE_SOCKS5) {
        require(port in 1..65535)
        listOf("-socks5", "${if (lanEnabled) "0.0.0.0" else "127.0.0.1"}:$port") +
            (if (username.isNotEmpty()) listOf("--socks5-user", username) else emptyList())
    } else {
        listOf("-tun-uds", "csqtt_tun_uds")
    }
