// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelStartupProgressTest {
    @Test
    fun startupStageIdentifiesTheBlockedLayer() {
        val progress = TunnelStartupProgress()
        assertEquals(TunnelStartupStage.WAITING_FOR_CREDENTIALS, progress.stage())
        assertEquals(1, progress.credentialReceived())
        assertEquals(TunnelStartupStage.WAITING_FOR_TURN, progress.stage())
        progress.turnReady()
        assertEquals(TunnelStartupStage.WAITING_FOR_SERVER_HANDSHAKE, progress.stage())
        progress.streamReady()
        assertEquals(TunnelStartupStage.READY, progress.stage())
    }

    @Test
    fun peerHandshakeIsDistinguishedFromTurnAllocation() {
        val progress = TunnelStartupProgress()

        progress.peerHandshakeStarted()

        assertEquals(TunnelStartupStage.WAITING_FOR_SERVER_HANDSHAKE, progress.stage())
    }

    @Test
    fun concurrentCredentialEventsAreNeverLost() {
        val progress = TunnelStartupProgress()
        val count = 128
        val done = CountDownLatch(count)
        val executor = Executors.newFixedThreadPool(16)
        repeat(count) {
            executor.execute {
                progress.credentialReceived()
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(129, progress.credentialReceived())
    }
}
