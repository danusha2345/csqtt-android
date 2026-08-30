// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client.ui.utils

data class CsqttLink(
    val host: String,
    val port: Int,
    val password: String,
    val hashes: List<String> = emptyList(),
    val webPort: Int = com.csqtt.client.CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT,
)

fun parseCsqttLink(raw: String): CsqttLink? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("csqtt://", ignoreCase = true)) return null
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    if (!uri.scheme.equals("csqtt", ignoreCase = true) || uri.rawFragment != null) return null
    if (uri.host.equals("connect", ignoreCase = true)) {
        return parseCsqttV2(uri)
    }
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val port = uri.port
    val password = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
    if (port !in 1..65535) return null
    return CsqttLink(host, port, password)
}

fun CsqttLink.peerAddress(): String {
    val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
    return "$formattedHost:$port"
}

private fun parseCsqttV2(uri: java.net.URI): CsqttLink? {
    if (uri.rawUserInfo != null || uri.port != -1 || uri.rawPath.orEmpty().isNotEmpty()) return null
    val parameters = parseRawQuery(uri.rawQuery ?: return null) ?: return null
    if (parameters["v"]?.let(::decodeQueryComponent) != "2") return null
    val host = parameters["host"]?.let(::decodeQueryComponent)?.takeIf { it.isNotBlank() } ?: return null
    val port = parameters["peer"]?.let(::decodeQueryComponent)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val password = parameters["password"]?.let(::decodeQueryComponent)?.takeIf { it.isNotBlank() } ?: return null
    if (host.any(Char::isWhitespace) || password.any(Char::isWhitespace)) return null
    val hashes = parameters["hashes"]?.let(::parseLinkHashes) ?: emptyList()
    if (parameters.containsKey("hashes") && hashes.isEmpty()) return null
    val webPort = parameters["web"]
        ?.let(::decodeQueryComponent)
        ?.toIntOrNull()
        ?.takeIf { it in 1..65535 }
        ?: com.csqtt.client.CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT
    return CsqttLink(host, port, password, hashes, webPort)
}

private fun parseRawQuery(rawQuery: String): Map<String, String>? {
    if (rawQuery.isEmpty()) return null
    val sanitized = rawQuery.replace("&amp;", "&")
    val result = linkedMapOf<String, String>()
    if (sanitized.contains('&') || sanitized.contains(';')) {
        for (part in sanitized.split(Regex("[&;]"))) {
            if (part.isBlank()) continue
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            val key = decodeQueryComponent(part.substring(0, separator)) ?: continue
            val value = part.substring(separator + 1)
            if (key.isNotBlank()) {
                result[key] = value
            }
        }
        if (result.isNotEmpty() && result.containsKey("v") && result.containsKey("host") && result.containsKey("peer") && result.containsKey("password")) {
            return result
        }
    }

    val pattern = Regex("(?:^|[&;]|(?<=[a-zA-Z0-9_]))(v|host|peer|web|password|hashes)=([^&?]*?)(?=(?:v|host|peer|web|password|hashes)=|$)", RegexOption.IGNORE_CASE)
    val matches = pattern.findAll(sanitized).toList()
    if (matches.isNotEmpty()) {
        val extracted = linkedMapOf<String, String>()
        for (match in matches) {
            val key = decodeQueryComponent(match.groupValues[1].lowercase()) ?: continue
            val value = match.groupValues[2]
            extracted[key] = value
        }
        if (extracted.isNotEmpty() && extracted.containsKey("v") && extracted.containsKey("host") && extracted.containsKey("peer") && extracted.containsKey("password")) {
            return extracted
        }
    }

    return if (result.isNotEmpty()) result else null
}

private fun parseLinkHashes(rawHashes: String): List<String> {
    val parts = rawHashes.split('+')
    if (parts.size !in 1..6 || parts.any { it.isEmpty() }) return emptyList()
    val hashes = parts.map { encoded ->
        decodeQueryComponent(encoded)?.let(::stripVkUrlStatic).orEmpty()
    }
    if (hashes.any { it.length < 16 || it.any(Char::isWhitespace) }) return emptyList()
    return hashes.distinct().takeIf { it.size == hashes.size }.orEmpty()
}

private fun decodeQueryComponent(value: String): String? {
    return runCatching {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrNull()
}

fun stripVkUrlStatic(input: String): String {
    var s = input.trim()
    val lower = s.lowercase()
    val prefixes = listOf(
        "https://vk.com/call/join/",
        "http://vk.com/call/join/",
        "https://m.vk.com/call/join/",
        "http://m.vk.com/call/join/",
        "m.vk.com/call/join/",
        "vk.com/call/join/",
        "https://vk.ru/call/join/",
        "http://vk.ru/call/join/",
        "https://m.vk.ru/call/join/",
        "http://m.vk.ru/call/join/",
        "m.vk.ru/call/join/",
        "vk.ru/call/join/"
    )
    for (prefix in prefixes) {
        if (lower.startsWith(prefix)) {
            s = s.substring(prefix.length)
            break
        }
    }
    val qIdx = s.indexOf('?')
    if (qIdx != -1) s = s.substring(0, qIdx)
    val hIdx = s.indexOf('#')
    if (hIdx != -1) s = s.substring(0, hIdx)
    return s.trimEnd('/')
}
