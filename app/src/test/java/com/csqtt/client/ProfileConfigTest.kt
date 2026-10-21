package com.csqtt.client

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*
import java.nio.file.Files

class ProfileConfigTest {
    private fun checkStore(block: suspend (SettingsStore) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("csqtt-profiles").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("settings.preferences_pb") }
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val secret = mock(SecureStringStore::class.java)
        `when`(secret.encrypt(anyString())).thenAnswer { "cipher:" + it.getArgument<String>(0) }
        `when`(secret.decrypt(anyString())).thenAnswer { it.getArgument<String>(0).removePrefix("cipher:") }
        try { block(SettingsStore(context, data, secret)) }
        finally { scope.cancel(); directory.deleteRecursively() }
    }
    @Test fun exportAndImportUseTheSelectedProfileAndDoNotOverwriteAnotherOne() = checkStore { store ->
        store.saveActiveProfile(0)
        store.save("first.example:46010", "", "", 18, "udp", 0)
        store.saveActiveProfile(9)
        store.save("tenth.example:46010", "", "", 18, "udp", 0)
        store.saveConnectionPassword("private-password")
        val exported = JSONObject(store.exportConfigJson(true, false, false, false))
        assertEquals("tenth.example:46010", exported.getJSONObject("tunnel").getString("peer"))
        assertFalse(exported.getJSONObject("tunnel").has("connection_password"))
        val withSecrets = JSONObject(store.exportConfigJson(true, false, false, false, true))
        assertEquals("private-password", withSecrets.getJSONObject("tunnel").getString("connection_password"))
        exported.getJSONObject("tunnel").put("peer", "imported.example:46010")
        assertTrue(store.importConfigJson(exported.toString(), true, false, false, false))
        assertEquals("imported.example:46010", store.peer.first())
        store.saveActiveProfile(0)
        assertEquals("first.example:46010", store.peer.first())
    }
    @Test fun invalidImportDoesNotApplyPartOfTheConfiguration() = checkStore { store ->
        store.saveActiveProfile(2)
        store.save("original.example:46010", "", "", 18, "udp", 0)
        assertFalse(store.importConfigJson("""{"version":2,"tunnel":{"peer":"bad.example:1","server_peer_port":-1}}""", true, false, false, false))
        assertEquals("original.example:46010", store.peer.first())
    }
    @Test fun whiteAndBlackListsRemainIndependentInEachProfile() = checkStore { store ->
        store.saveActiveProfile(9)
        store.saveExcludedApps("org.example.direct")
        store.saveWhitelistApps("org.example.tunnel")
        assertEquals("org.example.direct",store.excludedApps.first())
        assertEquals("org.example.tunnel",store.whitelistApps.first())
        store.saveActiveProfile(0)
        assertEquals("",store.excludedApps.first())
        assertEquals("",store.whitelistApps.first())
    }
    @Test fun participantProfileChoicePreservesRoleAndKeepsLinksSeparate() = checkStore { store ->
        store.saveCsqttParticipantLink("csqtt://first-secret")
        store.saveActiveProfile(9)
        assertTrue(store.csqttLinkMode.first())
        assertEquals("", store.csqttLink.first())
        store.saveCsqttParticipantLink("csqtt://tenth-secret")
        store.saveActiveProfile(0)
        assertTrue(store.csqttLinkMode.first())
        assertEquals("csqtt://first-secret", store.csqttLink.first())
        val safeExport = JSONObject(store.exportConfigJson(false, true, false, false))
        assertFalse(safeExport.toString().contains("first-secret"))
        store.saveCsqttLinkMode(false)
        store.saveActiveProfile(9)
        assertFalse(store.csqttLinkMode.first())
        assertEquals("csqtt://tenth-secret", store.csqttLink.first())
    }

    @Test fun invalidParticipantLinkCannotSilentlyUseOwnerCall() = checkStore { store ->
        store.save("example.com:46010", "saved-hash-123456", "", 18, "udp", 0)
        val invalid = "invalid-link-hash"
        val link = com.csqtt.client.ui.utils.formatCsqttLink("example.com",46010,"password",listOf(invalid))
        store.saveCsqttParticipantLink(link)
        store.invalidateVkHash(invalid)
        assertNull(resolveConnectionSource(store))
    }

    @Test fun secretPanelPathIsStableAndImportIsProfileScoped() = checkStore { store ->
        val first = store.ensureDeploySecretPathUuid(0)
        store.saveActiveProfile(9)
        val ninth = store.ensureDeploySecretPathUuid(9)
        assertNotEquals(first,ninth)
        assertEquals(ninth,store.ensureDeploySecretPathUuid(9))
        val safe = JSONObject(store.exportConfigJson(false,true,false,false))
        assertFalse(safe.getJSONObject("deploy").has("secret_path_uuid"))
        val imported = """{"version":2,"deploy":{"secret_path_uuid":"imported-path"}}"""
        assertTrue(store.importConfigJson(imported,false,true,false,false))
        assertEquals("imported-path", store.ensureDeploySecretPathUuid(9))
        assertEquals(first, store.ensureDeploySecretPathUuid(0))
    }

}
