// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

internal object TunnelServicePolicy {
    const val STARTUP_GRACE_MS = 60_000L

    fun shouldStop(
        wasEverRunning: Boolean,
        running: Boolean,
        elapsedMs: Long,
    ): Boolean = !running && (wasEverRunning || elapsedMs >= STARTUP_GRACE_MS)
}
