// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ConfigSyncTest {
    @Test
    fun keyDerivationAndRequestSignatureMatchServerVector() {
        val keys = ConfigSyncClient.deriveKeys("test-password")
        assertEquals("dff5b709384e2329e97d93e7e562e65c", keys.id.hex())
        assertEquals(
            "6bcad04a5c4e1f9acb50e97283beb55888eb2febdaa4daa54b36ddd246e34b02",
            keys.auth.hex(),
        )
        val path = "/api/client-config/${keys.id.hex()}"
        assertEquals(
            "e0c1S9Tv0si2McIUudh5C6Pvnk5SRouWW1rxUZ4wtdw",
            ConfigSyncClient.requestSignature(keys.auth, path, "1788080000", "AQIDBAUGBwgJCgsMDQ4PEA"),
        )
    }

    @Test
    fun encryptedResponseIsAuthenticatedBeforeParsing() {
        val keys = ConfigSyncClient.deriveKeys("test-password")
        val requestNonce = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16) { (it + 17).toByte() }
        val plaintext = JSONObject()
            .put("version", 1)
            .put("active", true)
            .put("peer_port", 46010)
            .put("web_port", 46002)
            .put("vk_hashes", "abcdefghijklmnop")
            .put("expires_at", 0)
            .put("revision", "a".repeat(64))
            .toString()
            .toByteArray()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)
        val authenticated = "CSQTT-CONFIG-RESPONSE-v1\u0000".toByteArray() + requestNonce + iv + ciphertext
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(keys.auth, "HmacSHA256"))
            doFinal(authenticated)
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val envelope = JSONObject()
            .put("version", 1)
            .put("iv", encoder.encodeToString(iv))
            .put("ciphertext", encoder.encodeToString(ciphertext))
            .put("mac", encoder.encodeToString(mac))

        val decoded = ConfigSyncClient.decryptResponse(keys, requestNonce, envelope)
        assertEquals(46010, decoded.peerPort)
        assertEquals("abcdefghijklmnop", decoded.vkHashes)

        envelope.put("mac", encoder.encodeToString(ByteArray(32)))
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSyncClient.decryptResponse(keys, requestNonce, envelope)
        }
    }

    @Test
    fun peerParsingHandlesIpv4DomainsAndIpv6() {
        assertEquals("example.com", ConfigSyncClient.peerHost("example.com:46010"))
        assertEquals("2001:db8::1", ConfigSyncClient.peerHost("[2001:db8::1]:46010"))
        assertEquals("[2001:db8::1]:46011", ConfigSyncClient.withPeerPort("[2001:db8::1]:46010", 46011))
        assertNull(ConfigSyncClient.peerHost("bad host:46010"))
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
