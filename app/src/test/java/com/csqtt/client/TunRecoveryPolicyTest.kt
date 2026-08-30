// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunRecoveryPolicyTest {
    @Test
    fun identicalConfigurationReusesLiveInterface() {
        assertTrue(decide())
    }

    @Test
    fun missingInterfaceRequiresRebuild() {
        assertFalse(decide(hasInterface = false))
    }

    @Test
    fun invalidDescriptorRequiresRebuild() {
        assertFalse(decide(descriptorValid = false))
    }

    @Test
    fun destroyedServiceRequiresRebuild() {
        assertFalse(decide(serviceDestroyed = true))
    }

    @Test
    fun forcedReloadRequiresRebuild() {
        assertFalse(decide(forceRebuild = true))
    }

    @Test
    fun changedAddressRequiresRebuild() {
        assertFalse(decide(requestedClientIp = "10.66.66.3"))
    }

    @Test
    fun changedDnsRequiresRebuild() {
        assertFalse(decide(requestedDns = "8.8.8.8"))
    }

    @Test
    fun absentPreviousAddressRequiresRebuild() {
        assertFalse(decide(activeClientIp = null))
    }

    @Test
    fun absentPreviousDnsRequiresRebuild() {
        assertFalse(decide(activeDns = null))
    }

    private fun decide(
        hasInterface: Boolean = true,
        descriptorValid: Boolean = true,
        serviceDestroyed: Boolean = false,
        forceRebuild: Boolean = false,
        activeClientIp: String? = "10.66.66.2",
        activeDns: String? = "1.1.1.1",
        requestedClientIp: String = "10.66.66.2",
        requestedDns: String = "1.1.1.1",
    ): Boolean = TunRecoveryPolicy.shouldReuse(
        hasInterface = hasInterface,
        descriptorValid = descriptorValid,
        serviceDestroyed = serviceDestroyed,
        forceRebuild = forceRebuild,
        activeClientIp = activeClientIp,
        activeDns = activeDns,
        requestedClientIp = requestedClientIp,
        requestedDns = requestedDns,
    )
}
