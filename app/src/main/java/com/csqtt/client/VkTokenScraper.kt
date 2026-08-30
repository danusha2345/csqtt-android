// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URL

internal object VkTokenScraper {
    private val trustedHosts = setOf(
        "oauth.vk.com",
        "oauth.vk.ru",
        "login.vk.com",
        "login.vk.ru",
        "id.vk.com",
        "id.vk.ru",
    )

    suspend fun scrape(cookies: String, userAgent: String): Result<VkAuthPayload> = withContext(Dispatchers.IO) {
        var endpoint = "https://oauth.vk.com/authorize?client_id=7793118&display=mobile&redirect_uri=https%3A%2F%2Foauth.vk.ru%2Fblank.html&response_type=token&scope=1073737727&v=5.199&revoke=1"
        
        val jsLocationRegex = Regex("location\\.href\\s*=\\s*[\"']([^\"']+)[\"']")
        val oauthGrantRegex = Regex("(https://login\\.vk\\.(?:com|ru)/\\?act=grant_access[^\"'\\s<]+)")

        var hopsRemaining = CsqttConstants.VkAutoHash.MAX_OAUTH_HOPS
        
        try {
            while (hopsRemaining-- > 0) {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("Cookie", cookies)
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val code = connection.responseCode

                if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_SEE_OTHER) {
                    val nextLocation = connection.getHeaderField("Location") ?: break
                    val trustedLocation = resolveTrustedUrl(endpoint, nextLocation)
                        ?: return@withContext Result.failure(IllegalStateException("Untrusted VK OAuth redirect"))
                    val payload = parseFragment(trustedLocation)
                    if (payload != null) return@withContext Result.success(payload)
                    endpoint = trustedLocation
                    continue
                }

                if (code == HttpURLConnection.HTTP_OK) {
                    val htmlContent = connection.inputStream.bufferedReader().use { it.readText() }

                    val matchJs = jsLocationRegex.find(htmlContent)
                    if (matchJs != null) {
                        val scriptTarget = resolveTrustedUrl(endpoint, matchJs.groupValues[1])
                            ?: return@withContext Result.failure(IllegalStateException("Untrusted VK OAuth script redirect"))
                        val payload = parseFragment(scriptTarget)
                        if (payload != null) return@withContext Result.success(payload)
                        endpoint = scriptTarget
                        continue
                    }

                    val matchGrant = oauthGrantRegex.find(htmlContent)
                    if (matchGrant != null) {
                        endpoint = resolveTrustedUrl(endpoint, matchGrant.groupValues[1].replace("&amp;", "&"))
                            ?: return@withContext Result.failure(IllegalStateException("Untrusted VK OAuth grant redirect"))
                        continue
                    }
                    
                    return@withContext Result.failure(IllegalStateException("No redirection found in HTML output"))
                }
                
                break
            }
            Result.failure(IllegalStateException("Too many redirects: max hops exceeded"))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    internal fun resolveTrustedUrl(currentUrl: String, target: String): String? {
        val resolved = runCatching { URI(currentUrl).resolve(target.trim()) }.getOrNull() ?: return null
        val host = resolved.host?.lowercase() ?: return null
        if (!resolved.scheme.equals("https", ignoreCase = true) ||
            resolved.rawUserInfo != null ||
            resolved.port !in setOf(-1, 443) ||
            host !in trustedHosts
        ) {
            return null
        }
        return resolved.toASCIIString()
    }

    internal fun parseFragment(urlStr: String): VkAuthPayload? {
        val uri = runCatching { URI(urlStr) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.port !in setOf(-1, 443) ||
            host !in setOf("oauth.vk.com", "oauth.vk.ru") ||
            uri.path != "/blank.html"
        ) return null
        val fragmentPart = uri.rawFragment ?: return null

        val params = fragmentPart.split("&").associate {
            val parts = it.split("=", limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }

        val tokenVal = params["access_token"]
        if (!tokenVal.isNullOrBlank()) {
            return VkAuthPayload(
                token = tokenVal,
                userId = params["user_id"].orEmpty(),
                expiresIn = params["expires_in"]?.toLongOrNull() ?: 0L
            )
        }
        return null
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
}
