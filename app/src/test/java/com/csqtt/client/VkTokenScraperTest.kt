// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VkTokenScraperTest {
    @Test
    fun trustedRelativeRedirectIsResolved() {
        assertEquals(
            "https://oauth.vk.ru/blank.html#access_token=token&user_id=42",
            VkTokenScraper.resolveTrustedUrl(
                "https://oauth.vk.ru/authorize",
                "/blank.html#access_token=token&user_id=42",
            ),
        )
    }

    @Test
    fun crossOriginAndNonHttpsRedirectsAreRejected() {
        assertNull(VkTokenScraper.resolveTrustedUrl("https://oauth.vk.ru/authorize", "https://example.com/steal"))
        assertNull(VkTokenScraper.resolveTrustedUrl("https://oauth.vk.ru/authorize", "http://oauth.vk.ru/blank.html"))
        assertNull(VkTokenScraper.resolveTrustedUrl("https://oauth.vk.ru/authorize", "https://oauth.vk.ru:444/blank.html"))
        assertNull(VkTokenScraper.resolveTrustedUrl("https://oauth.vk.ru/authorize", "https://oauth.vk.ru@example.com/steal"))
    }

    @Test
    fun tokenIsAcceptedOnlyFromConfiguredOauthCallback() {
        val payload = VkTokenScraper.parseFragment(
            "https://oauth.vk.ru/blank.html#access_token=token%2Bvalue&user_id=42&expires_in=3600",
        )
        assertEquals("token+value", payload?.token)
        assertEquals("42", payload?.userId)
        assertEquals(3600L, payload?.expiresIn)
        assertNull(VkTokenScraper.parseFragment("https://login.vk.ru/#access_token=stolen"))
        assertNull(VkTokenScraper.parseFragment("https://oauth.vk.ru/other#access_token=stolen"))
        assertNull(VkTokenScraper.parseFragment("https://example.com/blank.html#access_token=stolen"))
    }
}
