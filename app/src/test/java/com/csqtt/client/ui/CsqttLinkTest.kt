// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client.ui

import com.csqtt.client.ui.utils.parseCsqttLink
import com.csqtt.client.ui.utils.peerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsqttLinkTest {
    @Test
    fun parsesV2WithoutHashes() {
        val link = parseCsqttLink("csqtt://connect?v=2&host=203.0.113.7&peer=46000&password=p%40ss")
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
        assertEquals("p@ss", link.password)
        assertEquals(emptyList<String>(), link.hashes)
    }

    @Test
    fun parsesV2ConcatenatedParameters() {
        val link = parseCsqttLink("csqtt://connect?v=2host=203.0.113.88peer=46000password=dummy_secret_pass")
        requireNotNull(link)
        assertEquals("203.0.113.88", link.host)
        assertEquals(46000, link.port)
        assertEquals("dummy_secret_pass", link.password)
        assertEquals(emptyList<String>(), link.hashes)
    }

    @Test
    fun parsesV2WithSixPlusSeparatedHashes() {
        val hashes = (1..6).map { "abcdefghijklmnop$it" }
        val link = parseCsqttLink(
            "csqtt://connect?v=2&host=vps.example&peer=46000&password=secret&hashes=${hashes.joinToString("+")}",
        )
        requireNotNull(link)
        assertEquals(hashes, link.hashes)
    }

    @Test
    fun preservesEncodedPlusInsideHash() {
        val link = parseCsqttLink(
            "csqtt://connect?v=2&host=vps.example&peer=46000&password=secret&hashes=abcdefghijklmno%2Bp",
        )
        requireNotNull(link)
        assertEquals(listOf("abcdefghijklmno+p"), link.hashes)
    }

    @Test
    fun retainsLegacyLinks() {
        val link = parseCsqttLink("csqtt://secret@203.0.113.7:46000")
        requireNotNull(link)
        assertEquals("203.0.113.7:46000", link.peerAddress())
        assertEquals("secret", link.password)
    }

    @Test
    fun formatsIpv6PeerWithBrackets() {
        val link = parseCsqttLink("csqtt://connect?v=2&host=2001%3Adb8%3A%3A1&peer=46000&password=secret")
        requireNotNull(link)
        assertEquals("[2001:db8::1]:46000", link.peerAddress())
    }

    @Test
    fun rejectsMalformedV2Links() {
        assertNull(parseCsqttLink("csqtt://connect?v=3&host=1.2.3.4&peer=46000&password=secret"))
        assertNull(parseCsqttLink("csqtt://connect?v=2&host=1.2.3.4&peer=0&password=secret"))
        assertNull(parseCsqttLink("csqtt://connect?v=2&host=1.2.3.4&peer=46000&password=secret&hashes="))
        assertNull(parseCsqttLink("csqtt://connect?v=2&host=1.2.3.4&peer=46000&password=secret&hashes=short"))
        assertNull(
            parseCsqttLink(
                "csqtt://connect?v=2&host=1.2.3.4&peer=46000&password=secret&hashes=" +
                    (1..7).joinToString("+") { "abcdefghijklmnop$it" },
            ),
        )
    }
}
