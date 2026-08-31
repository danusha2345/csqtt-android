// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class TunnelStartupStage {
    WAITING_FOR_CREDENTIALS,
    WAITING_FOR_TURN,
    WAITING_FOR_SERVER_HANDSHAKE,
    READY,
}

internal class TunnelStartupProgress {
    private val credentials = AtomicInteger()
    private val turnReady = AtomicBoolean(false)
    private val peerHandshakeStarted = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    fun credentialReceived(): Int = credentials.incrementAndGet()

    fun turnReady() {
        turnReady.set(true)
    }

    fun peerHandshakeStarted() {
        peerHandshakeStarted.set(true)
    }

    fun streamReady() {
        ready.set(true)
    }

    fun stage(): TunnelStartupStage = when {
        ready.get() -> TunnelStartupStage.READY
        turnReady.get() || peerHandshakeStarted.get() -> TunnelStartupStage.WAITING_FOR_SERVER_HANDSHAKE
        credentials.get() > 0 -> TunnelStartupStage.WAITING_FOR_TURN
        else -> TunnelStartupStage.WAITING_FOR_CREDENTIALS
    }
}
