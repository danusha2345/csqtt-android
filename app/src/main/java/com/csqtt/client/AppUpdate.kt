// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val UPDATE_CHECK_NEVER = CsqttConstants.Update.CHECK_NEVER
const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = CsqttConstants.Update.DEFAULT_CHECK_INTERVAL_HOURS
const val UPDATE_DIALOG_ACTION_POSTPONED = CsqttConstants.Update.ACTION_POSTPONED
const val UPDATE_DIALOG_ACTION_UPDATE = CsqttConstants.Update.ACTION_UPDATE

private val VERSION_NUMBER_REGEX = CsqttConstants.Patterns.VERSION_NUMBER

fun updateIntervalHoursToMillis(hours: Int): Long? = when {
    hours <= 0 -> null
    else -> hours * 60L * 60L * 1000L
}

data class AppReleaseInfo(
    val versionTag: String,
    val releaseUrl: String,
    val repository: UpdateRepository,
    val apkSHA256: String,
) {
    val apkName get() = "CSQTT-${versionTag.removePrefix("v")}-universal.apk"
}

@Suppress("UNUSED_PARAMETER")
suspend fun fetchLatestReleaseInfo(localVersion: String? = null): AppReleaseInfo? = withContext(Dispatchers.IO) {
    checkUpdateRepositories(::readUpdateBytes)
}

fun isNewerVersion(local: String, remote: String): Boolean {
    val localParts = versionParts(local)
    val remoteParts = versionParts(remote)
    if (remoteParts.isEmpty()) return false

    val maxLen = maxOf(localParts.size, remoteParts.size)
    for (i in 0 until maxLen) {
        val localPart = localParts.getOrElse(i) { 0 }
        val remotePart = remoteParts.getOrElse(i) { 0 }
        if (remotePart > localPart) return true
        if (remotePart < localPart) return false
    }
    return false
}

private fun versionParts(version: String): List<Int> {
    val normalized = VERSION_NUMBER_REGEX.find(version.trim())?.value ?: return emptyList()
    return normalized.split(".").mapNotNull { it.toIntOrNull() }
}

internal fun normalizeVersionTag(version: String): String {
    val trimmed = version.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("v", ignoreCase = true)) trimmed else "v$trimmed"
}
