package com.csqtt.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlinx.coroutines.CancellationException

class UpdateRepositoriesTest {
    private val tag = "v2.1.18"
    private val apk = "CSQTT-2.1.18-universal.apk"
    private val bytes = "apk test bytes".toByteArray()
    private val info = JSONObject().put("version", "2.1.18").put("sha256", JSONObject().put(apk, updateSHA256(bytes))).toString().toByteArray()
    private val sums = "${updateSHA256(info)}  BUILDINFO-2.1.18.json\n${updateSHA256(bytes)}  $apk\n".toByteArray()

    private fun release(source: UpdateRepository, value: String = tag): JSONObject {
        val assets = JSONArray(listOf(apk, "BUILDINFO-2.1.18.json", "SHA256SUMS-2.1.18.txt").map { JSONObject().put("name", it) })
        return JSONObject().put("tag_name", value).put("assets", if (source == UpdateRepository.GitLab) JSONObject().put("links", assets) else assets)
    }

    @Test fun fallsBackInOrderAndKeepsMirrorDownloadLocation() {
        for (selected in UpdateRepository.entries) {
            val attempted = mutableListOf<UpdateRepository>()
            val found = checkUpdateRepositories { url, _ ->
                val source = UpdateRepository.entries.find { it.api == url }
                if (source != null) {
                    attempted += source
                    if (source != selected) error("offline")
                    JSONArray().put(release(source)).toString().toByteArray()
                } else when (url) {
                    selected.assetUrl(tag, "SHA256SUMS-2.1.18.txt") -> sums
                    selected.assetUrl(tag, "BUILDINFO-2.1.18.json") -> info
                    else -> error("unexpected URL $url")
                }
            }
            assertEquals(selected, found?.repository)
            assertEquals(selected.releaseUrl(tag), found?.releaseUrl)
            assertEquals(UpdateRepository.entries.take(selected.ordinal + 1), attempted)
        }
    }

    @Test fun ignoresOpenWrtDraftPrereleaseAndIncompleteReleases() {
        for (source in UpdateRepository.entries) {
            assertEquals(tag, stableAndroidTag(release(source), source))
            for (bad in listOf("openwrt-v2.1.18-r2", "v2.1.18-rc1", "v999999999999999.1.0/evil")) {
                assertNull(stableAndroidTag(release(source, bad), source))
            }
            for (flag in listOf("draft", "prerelease", "upcoming_release")) assertNull(stableAndroidTag(release(source).put(flag, true), source))
            assertNull(stableAndroidTag(JSONObject().put("tag_name",tag),source))
        }
    }

    @Test fun corruptMetadataFallsBackAndNoSourceReturnsNull() {
        val seen = mutableListOf<String>()
        assertNull(checkUpdateRepositories { url, _ ->
            seen += url
            val source = UpdateRepository.entries.find { it.api == url }
            if (source != null) JSONArray().put(release(source)).toString().toByteArray()
            else if (url.endsWith(".txt")) sums else "corrupt".toByteArray()
        })
        assertTrue(UpdateRepository.entries.all { it.api in seen })
    }

    @Test fun checksumDuplicatesAndMissingApkAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parseUpdateChecksums(sums + sums) }
        assertThrows(IllegalArgumentException::class.java) { releaseFromRepository(UpdateRepository.GitHub,tag) { url,_ -> if (url.endsWith(".txt")) "${updateSHA256(info)}  BUILDINFO-2.1.18.json\n".toByteArray() else info } }
    }

    @Test fun downloadChecksTruncationHashSizeAndCancellation() {
        val output = ByteArrayOutputStream()
        copyVerifiedApk(bytes.inputStream(),output,updateSHA256(bytes),bytes.size.toLong(),{}, {_,_->})
        assertArrayEquals(bytes,output.toByteArray())
        for ((data,length) in listOf(bytes.copyOf(2) to bytes.size.toLong(), "bad".toByteArray() to 3L, bytes to MAX_UPDATE_APK+1)) {
            assertThrows(IllegalArgumentException::class.java) { copyVerifiedApk(data.inputStream(),ByteArrayOutputStream(),updateSHA256(bytes),length,{}, {_,_->}) }
        }
        assertThrows(CancellationException::class.java) { copyVerifiedApk(bytes.inputStream(),ByteArrayOutputStream(),updateSHA256(bytes),-1,{ throw CancellationException() }, {_,_->}) }
    }

    @Test fun installerRejectsWrongPackageVersionAndSigner() {
        val installed = ApkIdentity("io.github.danusha2345.csqtt","2.1.17",227,setOf("release-signer"))
        val update = installed.copy(versionName="2.1.18",versionCode=228)
        validateApkIdentity(installed,update,"2.1.18")
        for (bad in listOf(update.copy(packageName="csqtt.quic.amurcanov"),update.copy(versionName="2.1.19"),update.copy(versionCode=227),update.copy(signers=setOf("other")),update.copy(signers=emptySet()))) {
            assertThrows(IllegalArgumentException::class.java) { validateApkIdentity(installed,bad,"2.1.18") }
        }
    }

    @Test fun onlyTrustedHttpsLocationsAreAccepted() {
        for (source in UpdateRepository.entries) {
            assertTrue(trustedUpdateUrl(URL(source.api)))
            assertTrue(trustedUpdateUrl(URL(source.assetUrl(tag,apk))))
        }
        for (url in listOf("http://github.com/danusha2345/csqtt-android/releases/download/x", "https://user@github.com/danusha2345/csqtt-android/releases/download/x", "https://gitlab.com/other/uploads/x", "https://github.com.evil/x", "https://git.danik2files.ru:443/attachments/x")) assertFalse(url,trustedUpdateUrl(URL(url)))
    }
}
