package com.csqtt.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val MAX_UPDATE_APK = 256L * 1024 * 1024

internal data class ApkIdentity(val packageName: String, val versionName: String?, val versionCode: Long, val signers: Set<String>)

internal fun validateApkIdentity(installed: ApkIdentity, update: ApkIdentity, version: String) {
    require(update.packageName == installed.packageName) { "APK предназначен для другого приложения" }
    require(update.versionName == version) { "Версия APK не совпадает с релизом" }
    require(update.versionCode > installed.versionCode) { "APK не новее установленной версии" }
    require(installed.signers.isNotEmpty() && installed.signers == update.signers) { "Подпись APK отличается от установленного приложения" }
}

internal fun copyVerifiedApk(input: InputStream, output: OutputStream, hash: String, length: Long,
                             checkCancelled: () -> Unit, progress: (Long, Long) -> Unit) {
    require(updateHashPattern.matches(hash) && length <= MAX_UPDATE_APK) { "Некорректные параметры APK" }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    var lastProgress = 0L
    while (true) {
        checkCancelled()
        val n = input.read(buffer)
        if (n < 0) break
        count += n
        require(count <= MAX_UPDATE_APK) { "APK превышает допустимый размер" }
        output.write(buffer, 0, n)
        digest.update(buffer, 0, n)
        val now = System.nanoTime()
        if (now - lastProgress >= 200_000_000) {
            progress(count, length)
            lastProgress = now
        }
    }
    require(count > 0 && (length < 0 || count == length)) { "APK загружен не полностью" }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    require(actual == hash.lowercase()) { "SHA256 APK не совпадает" }
    progress(count, count)
}

@Suppress("DEPRECATION")
private fun packageSignatures(info: PackageInfo): Set<String> =
    (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
        ?.map { updateSHA256(it.toByteArray()) }?.toSet().orEmpty()

@Suppress("DEPRECATION")
internal fun validateUpdateApk(context: Context, file: File, release: AppReleaseInfo) {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val manager = context.packageManager
    val installed = manager.getPackageInfo(context.packageName, flags)
    val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("APK не распознан Android")
    val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
    val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    validateApkIdentity(ApkIdentity(context.packageName, installed.versionName, oldCode, packageSignatures(installed)),
        ApkIdentity(archive.packageName, archive.versionName, newCode, packageSignatures(archive)), release.versionTag.removePrefix("v"))
}

suspend fun downloadAppUpdate(context: Context, release: AppReleaseInfo, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "updates").apply { mkdirs() }
    val partial = File.createTempFile("download-", ".apk", directory)
    val coroutine = currentCoroutineContext()
    var lastError: Exception? = null
    try {
        val sources = listOf(release.repository) + UpdateRepository.entries.filter { it != release.repository }
        for (source in sources) {
            coroutine.ensureActive()
            try {
                // После потери основного источника разрешена только та же версия и SHA256.
                if (source != release.repository) {
                    val mirror = releaseFromRepository(source, release.versionTag, ::readUpdateBytes)
                    require(mirror.apkSHA256 == release.apkSHA256) { "На зеркале другой APK" }
                }
                progress(0, -1)
                val connection = openUpdateConnection(source.assetUrl(release.versionTag, release.apkName))
                try {
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            copyVerifiedApk(input, output, release.apkSHA256, connection.contentLengthLong,
                                { coroutine.ensureActive() }, progress)
                            output.fd.sync()
                        }
                    }
                } finally { connection.disconnect() }
                coroutine.ensureActive()
                validateUpdateApk(context, partial, release)
                val apk = File(directory, "update.apk")
                require(!apk.exists() || apk.delete()) { "Не удалось заменить старый APK" }
                require(partial.renameTo(apk)) { "Не удалось сохранить APK" }
                return@withContext apk
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("Не удалось скачать проверенный APK с GitHub, GitLab или Forgejo: ${lastError?.message}", lastError)
    } finally { partial.delete() }
}

fun startAppUpdateInstaller(context: Context, file: File, release: AppReleaseInfo) {
    // Повторяем проверку после возврата с экрана разрешения установки.
    validateUpdateApk(context, file, release)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
