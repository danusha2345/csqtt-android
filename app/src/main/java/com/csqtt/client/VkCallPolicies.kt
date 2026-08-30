// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

internal class VkRequestPacer(
    private val intervalMs: Long,
    private val nowMs: () -> Long,
    private val sleepMs: suspend (Long) -> Unit,
) {
    private var nextRequestAtMs = 0L

    suspend fun awaitRequestSlot() {
        val waitMs = nextRequestAtMs - nowMs()
        if (waitMs > 0) sleepMs(waitMs)
        nextRequestAtMs = nowMs() + intervalMs
    }
}

internal suspend fun <T> runVkCallAttempts(
    request: suspend () -> VkApiResult<T>,
): VkApiResult<T> {
    var result: VkApiResult<T> = VkApiResult.Failed("not attempted")
    var attempts = 0
    while (attempts < CsqttConstants.VkAutoHash.AUTO_CALL_MAX_ATTEMPTS) {
        attempts++
        result = request()
        if (result is VkApiResult.Ok<*>) break
        if (result is VkApiResult.ApiError && VkApi.isTokenInvalid(result)) break
    }
    return result
}
