// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionSourceTest {
    @Test
    fun invalidLinkHashesNeverFallBackToRawSavedValue() {
        val invalid = "invalid-link-hash"
        assertNull(
            selectConnectionHashes(
                listOf(invalid),
                "saved-hash-123456",
                mapOf(invalid to VkHashValidationStatus.Invalid),
            ),
        )
    }

    @Test
    fun linkWithoutHashesUsesOnlyValidatedSavedHashes() {
        val invalid = "invalid-saved-hash"
        assertEquals(
            "valid-saved-hash" to false,
            selectConnectionHashes(
                emptyList(),
                "$invalid,valid-saved-hash",
                mapOf(invalid to VkHashValidationStatus.Invalid),
            ),
        )
    }

    @Test
    fun validatedLinkHashesRemainMarkedAsLinkOwned() {
        assertEquals(
            "link-hash-123456" to true,
            selectConnectionHashes(listOf("link-hash-123456"), "saved-hash-123456", emptyMap()),
        )
    }

    @Test
    fun linkWithoutAnyHashesMayProceedForAutoJs() {
        assertEquals("" to false, selectConnectionHashes(emptyList(), "", emptyMap()))
    }
}
