package com.csqtt.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class UpdateRepository(val label: String, val api: String, val web: String) {
    GitHub("GitHub", "https://api.github.com/repos/danusha2345/csqtt-android/releases?per_page=30", "https://github.com/danusha2345/csqtt-android/releases"),
    GitLab("GitLab", "https://gitlab.com/api/v4/projects/pipecpriam%2Fcsqtt-android/releases?per_page=30", "https://gitlab.com/pipecpriam/csqtt-android/-/releases"),
    Forgejo("Forgejo", "https://git.danik2files.ru/api/v1/repos/danik/csqtt-android-server/releases?limit=30", "https://git.danik2files.ru/danik/csqtt-android-server/releases");

    fun releaseUrl(tag: String) = if (this == GitLab) "$web/$tag" else "$web/tag/$tag"
    fun assetUrl(tag: String, name: String) = if (this == GitLab) "$web/$tag/downloads/$name" else "$web/download/$tag/$name"
}

private val stableTag = Regex("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
internal val updateHashPattern = Regex("[0-9a-fA-F]{64}")

internal fun stableAndroidTag(json: JSONObject, repository: UpdateRepository): String? {
    val tag = json.optString("tag_name")
    if (!stableTag.matches(tag) || tag.drop(1).split('.').any { it.toIntOrNull() == null } || json.optBoolean("draft") || json.optBoolean("prerelease") || json.optBoolean("upcoming_release")) return null
    val assets = if (repository == UpdateRepository.GitLab) json.optJSONObject("assets")?.optJSONArray("links") else json.optJSONArray("assets")
    assets ?: return null
    val names = (0 until assets.length()).mapNotNull { assets.optJSONObject(it)?.optString("name") }
    val version = tag.removePrefix("v")
    val required = listOf("CSQTT-$version-universal.apk", "BUILDINFO-$version.json", "SHA256SUMS-$version.txt")
    return tag.takeIf { required.all { name -> names.count { it == name } == 1 } }
}

internal fun parseUpdateChecksums(bytes: ByteArray): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (line in bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }) {
        val fields = line.trim().split(Regex("\\s+"), limit = 2)
        require(fields.size == 2 && updateHashPattern.matches(fields[0])) { "Некорректный SHA256SUMS" }
        val name = fields[1].removePrefix("*")
        require(result.put(name, fields[0].lowercase()) == null) { "Повтор имени в SHA256SUMS" }
    }
    return result
}

internal fun updateSHA256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun releaseFromRepository(repository: UpdateRepository, tag: String, read: (String, Int) -> ByteArray): AppReleaseInfo {
    require(stableTag.matches(tag))
    val version = tag.removePrefix("v")
    val checksums = parseUpdateChecksums(read(repository.assetUrl(tag, "SHA256SUMS-$version.txt"), 64 * 1024))
    val infoName = "BUILDINFO-$version.json"
    val info = read(repository.assetUrl(tag, infoName), 1024 * 1024)
    require(updateSHA256(info) == checksums[infoName]) { "SHA256 BUILDINFO не совпадает" }
    val metadata = JSONObject(info.toString(Charsets.UTF_8))
    val apk = "CSQTT-$version-universal.apk"
    val hash = checksums[apk]
    require(metadata.optString("version") == version && hash != null && metadata.optJSONObject("sha256")?.optString(apk) == hash) { "BUILDINFO не подтверждает APK" }
    return AppReleaseInfo(tag, repository.releaseUrl(tag), repository, hash)
}

internal fun checkUpdateRepositories(read: (String, Int) -> ByteArray): AppReleaseInfo? {
    for (repository in UpdateRepository.entries) {
        try {
            val releases = JSONArray(read(repository.api, 2 * 1024 * 1024).toString(Charsets.UTF_8))
            var best: String? = null
            for (i in 0 until releases.length()) {
                val tag = releases.optJSONObject(i)?.let { stableAndroidTag(it, repository) } ?: continue
                if (best == null || isNewerVersion(best, tag)) best = tag
            }
            if (best != null) return releaseFromRepository(repository, best, read)
        } catch (_: Exception) {
            // Недоступный или неполный релиз: следующий доверенный репозиторий.
        }
    }
    return null
}

internal fun trustedUpdateUrl(url: URL): Boolean {
    if (url.protocol != "https" || url.userInfo != null || url.port != -1 || url.ref != null) return false
    val path = url.path
    return when (url.host) {
        "api.github.com" -> path.startsWith("/repos/danusha2345/csqtt-android/releases")
        "github.com" -> path.startsWith("/danusha2345/csqtt-android/releases/download/")
        "release-assets.githubusercontent.com" -> true
        "gitlab.com" -> path.startsWith("/api/v4/projects/pipecpriam%2Fcsqtt-android/releases") ||
            path.startsWith("/pipecpriam/csqtt-android/-/releases/") || path.startsWith("/-/project/86211930/uploads/") ||
            path.startsWith("/pipecpriam/csqtt-android/uploads/")
        "git.danik2files.ru" -> path.startsWith("/api/v1/repos/danik/csqtt-android-server/releases") ||
            path.startsWith("/danik/csqtt-android-server/releases/download/") || path.startsWith("/attachments/")
        else -> false
    }
}

internal fun openUpdateConnection(raw: String): HttpURLConnection {
    var url = URL(raw)
    repeat(6) {
        require(trustedUpdateUrl(url)) { "Недопустимый источник обновления" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/json,application/octet-stream,*/*")
            setRequestProperty("User-Agent", "CSQTTAndroid/${BuildConfig.VERSION_NAME}")
        }
        try {
            when (connection.responseCode) {
                200 -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location") ?: error("Нет адреса перенаправления")
                    url = URL(url, location)
                }
                else -> error("Источник обновления: HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
        connection.disconnect()
    }
    error("Слишком много перенаправлений")
}

internal fun readUpdateBytes(url: String, limit: Int): ByteArray {
    val connection = openUpdateConnection(url)
    return try {
        connection.inputStream.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                require(output.size() + n <= limit) { "Слишком большой ответ обновления" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    } finally { connection.disconnect() }
}
