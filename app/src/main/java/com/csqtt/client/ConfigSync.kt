// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal data class RemoteClientConfig(
    val active: Boolean,
    val peerPort: Int,
    val webPort: Int,
    val vkHashes: String,
    val expiresAt: Long,
    val revision: String,
)

internal data class ConfigSyncKeys(
    val id: ByteArray,
    val auth: ByteArray,
    val encryption: ByteArray,
)

internal object ConfigSyncClient {
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private val random = SecureRandom()
    private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getUrlDecoder()
    private val envelopeSslSocketFactory by lazy {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), random)
        }.socketFactory
    }

    suspend fun fetch(peer: String, password: String, webPort: Int): Result<RemoteClientConfig> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(password.isNotEmpty()) { "Пароль подключения не задан" }
                require(webPort in 1..65535) { "Некорректный web-порт" }
                val host = peerHost(peer) ?: error("Некорректный адрес сервера")
                val keys = deriveKeys(password)
                val id = keys.id.toHex()
                val path = "/api/client-config/$id"
                val formattedHost = if (host.contains(':')) "[$host]" else host
                val endpoint = URL("https://$formattedHost:$webPort$path")
                val timestamp = (System.currentTimeMillis() / 1000L).toString()
                val requestNonce = ByteArray(16).also(random::nextBytes)
                val nonceText = base64Encoder.encodeToString(requestNonce)
                val signature = requestSignature(keys.auth, path, timestamp, nonceText)
                val connection = (endpoint.openConnection() as HttpsURLConnection).apply {
                    // The server certificate is self-signed by default. This connection is still
                    // authenticated end-to-end by the password-derived HMAC envelope below.
                    sslSocketFactory = envelopeSslSocketFactory
                    hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-store")
                    setRequestProperty("User-Agent", "CSQTTAndroid/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("X-CSQTT-Timestamp", timestamp)
                    setRequestProperty("X-CSQTT-Nonce", nonceText)
                    setRequestProperty("Authorization", "CSQTT-HMAC $signature")
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        error("Сервер не поддерживает безопасное обновление конфигурации")
                    }
                    val body = connection.inputStream.use(::readBounded)
                    decryptResponse(keys, requestNonce, JSONObject(body.toString(Charsets.UTF_8)))
                } finally {
                    connection.disconnect()
                }
            }
        }

    internal fun deriveKeys(password: String): ConfigSyncKeys {
        require(password.isNotEmpty())
        val salt = "CSQTT-CONFIG-SYNC-v1".toByteArray(Charsets.UTF_8)
        val info = "client-config-envelope".toByteArray(Charsets.UTF_8)
        val prk = hmac(salt, password.toByteArray(Charsets.UTF_8))
        val expanded = hkdfExpand(prk, info, 80)
        return ConfigSyncKeys(
            id = expanded.copyOfRange(0, 16),
            auth = expanded.copyOfRange(16, 48),
            encryption = expanded.copyOfRange(48, 80),
        )
    }

    internal fun requestSignature(authKey: ByteArray, path: String, timestamp: String, nonce: String): String {
        val canonical = "GET\n$path\n$timestamp\n$nonce".toByteArray(Charsets.UTF_8)
        return base64Encoder.encodeToString(hmac(authKey, canonical))
    }

    internal fun decryptResponse(
        keys: ConfigSyncKeys,
        requestNonce: ByteArray,
        envelope: JSONObject,
    ): RemoteClientConfig {
        require(envelope.optInt("version") == 1) { "Неподдерживаемая версия config sync" }
        val iv = base64Decoder.decode(envelope.getString("iv"))
        val ciphertext = base64Decoder.decode(envelope.getString("ciphertext"))
        val suppliedMac = base64Decoder.decode(envelope.getString("mac"))
        require(iv.size == 16 && ciphertext.size <= MAX_RESPONSE_BYTES && suppliedMac.size == 32) {
            "Некорректный защищённый ответ"
        }
        val authenticated = ByteArray("CSQTT-CONFIG-RESPONSE-v1\u0000".toByteArray().size + requestNonce.size + iv.size + ciphertext.size)
        var offset = 0
        fun append(bytes: ByteArray) {
            bytes.copyInto(authenticated, offset)
            offset += bytes.size
        }
        append("CSQTT-CONFIG-RESPONSE-v1\u0000".toByteArray(Charsets.UTF_8))
        append(requestNonce)
        append(iv)
        append(ciphertext)
        val expectedMac = hmac(keys.auth, authenticated)
        require(MessageDigest.isEqual(expectedMac, suppliedMac)) { "Подпись конфигурации не совпадает" }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keys.encryption, "AES"),
            IvParameterSpec(iv),
        )
        val payload = JSONObject(cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
        require(payload.optInt("version") == 1) { "Неподдерживаемая конфигурация" }
        val peerPort = payload.getInt("peer_port")
        val webPort = payload.getInt("web_port")
        val hashes = normalizeHashes(payload.optString("vk_hashes"))
        val revision = payload.getString("revision")
        require(peerPort in 1..65535 && webPort in 1..65535) { "Некорректные порты конфигурации" }
        require(revision.length == 64 && revision.all(Char::isLetterOrDigit)) { "Некорректная ревизия" }
        return RemoteClientConfig(
            active = payload.getBoolean("active"),
            peerPort = peerPort,
            webPort = webPort,
            vkHashes = hashes,
            expiresAt = payload.optLong("expires_at"),
            revision = revision,
        )
    }

    internal fun peerHost(peer: String): String? =
        runCatching {
            URI("http://$peer").host
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

    internal fun withPeerPort(peer: String, port: Int): String? {
        if (port !in 1..65535) return null
        val host = peerHost(peer) ?: return null
        return if (host.contains(':')) "[$host]:$port" else "$host:$port"
    }

    private fun normalizeHashes(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val hashes = value.split(',')
        require(hashes.size <= CsqttConstants.Tunnel.MAX_VK_HASHES)
        require(hashes.all { it.length >= 16 && it.none(Char::isWhitespace) })
        return hashes.distinct().joinToString(",")
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, size: Int): ByteArray {
        val output = ByteArray(size)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < size) {
            val input = previous + info + byteArrayOf(counter.toByte())
            previous = hmac(prk, input)
            val count = minOf(previous.size, size - offset)
            previous.copyInto(output, offset, 0, count)
            offset += count
            counter++
        }
        return output
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= MAX_RESPONSE_BYTES) { "Ответ конфигурации слишком большой" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
