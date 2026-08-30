// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import com.csqtt.client.ui.utils.parseCsqttLink
import com.csqtt.client.ui.utils.peerAddress
import kotlinx.coroutines.flow.first

data class ConnectionSource(
    val peer: String,
    val password: String,
    val hashes: String,
    val hashesFromLink: Boolean,
    val webPort: Int,
)

internal fun selectConnectionHashes(
    linkHashes: List<String>,
    savedHashes: String,
    invalidHashes: Map<String, VkHashValidationStatus>,
): Pair<String, Boolean>? {
    fun active(raw: List<String>) = VkHashValidationCodec.active(raw, invalidHashes).joinToString(",")
    if (linkHashes.isNotEmpty()) {
        val selected = active(linkHashes)
        return selected.takeIf(String::isNotEmpty)?.let { it to true }
    }
    val selected = active(savedHashes.split(Regex("[,\\s\\n]+")))
    return selected to false
}

suspend fun resolveConnectionSource(store: SettingsStore): ConnectionSource? {
    val invalidHashes = VkHashValidationCodec.decode(store.vkHashCheckResults.first())
    fun activeHashes(raw: String): String = VkHashValidationCodec.active(
        raw.split(Regex("[,\\s\\n]+")),
        invalidHashes,
    ).joinToString(",")

    if (store.csqttLinkMode.first()) {
        val link = parseCsqttLink(store.csqttLink.first()) ?: return null
        val selectedHashes = selectConnectionHashes(link.hashes, store.vkHashes.first(), invalidHashes)
            ?: return null
        return ConnectionSource(
            peer = link.peerAddress(),
            password = link.password,
            hashes = selectedHashes.first,
            hashesFromLink = selectedHashes.second,
            webPort = link.webPort,
        )
    }

    val basePeer = store.peer.first()
    val password = store.connectionPassword.first()
    if (basePeer.isBlank() || password.isBlank()) return null
    val serverPeerPort = if (store.manualPortsEnabled.first()) {
        store.serverPeerPort.first()
    } else {
        CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT
    }
    val peer = if (basePeer.contains(':')) basePeer else "$basePeer:$serverPeerPort"
    return ConnectionSource(
        peer = peer,
        password = password,
        hashes = activeHashes(store.vkHashes.first()),
        hashesFromLink = false,
        webPort = store.serverWebPort.first(),
    )
}
