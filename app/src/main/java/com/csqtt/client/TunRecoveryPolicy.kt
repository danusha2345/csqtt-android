// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

internal object TunRecoveryPolicy {
    fun shouldReuse(
        hasInterface: Boolean,
        descriptorValid: Boolean,
        serviceDestroyed: Boolean,
        forceRebuild: Boolean,
        activeClientIp: String?,
        activeDns: String?,
        requestedClientIp: String,
        requestedDns: String,
    ): Boolean =
        hasInterface &&
            descriptorValid &&
            !serviceDestroyed &&
            !forceRebuild &&
            activeClientIp == requestedClientIp &&
            activeDns == requestedDns
}
