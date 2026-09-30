// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

internal fun vkProbeRetryDelayMs(failures: Int): Long = when (failures.coerceAtLeast(1)) {
    in 1..15 -> 1_000L
    16 -> 2_000L
    17 -> 3_000L
    18 -> 5_000L
    19 -> 8_000L
    else -> 15_000L
}

internal fun isVkProbeHttpResponse(code: Int): Boolean = code in 100..599

// Retry only a failed request, and only while the target remains the default network.
internal fun probeVkWithFallback(
    bound: () -> Boolean?,
    isCurrentNetwork: () -> Boolean,
    unbound: () -> Boolean?,
): Boolean {
    val verdict = bound()
    if (verdict != null) return verdict
    if (!isCurrentNetwork()) return false
    val fallback = unbound() ?: false
    return isCurrentNetwork() && fallback
}
