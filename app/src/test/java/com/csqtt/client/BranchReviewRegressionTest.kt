package com.csqtt.client

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*
import java.nio.file.Files

class BranchReviewRegressionTest {
    @Test fun applicationWhitelistIsIndependentOfDomainBlacklist() {
        val rule = TunnelManager.parseServerRoutingRuleJson("""{"mode":"whitelist","packages":["org.example.browser"],"domains_mode":"blacklist","domains_is_whitelist":false,"domains":["example.org"],"ip_mode":"blacklist","ips":[]}""")!!
        assertTrue("Application whitelist must not be replaced by domain blacklist",rule.isWhite)
        assertFalse(rule.domainsIsWhite)
    }
    @Test fun applicationBlacklistIsIndependentOfDomainWhitelist() {
        val rule = TunnelManager.parseServerRoutingRuleJson("""{"mode":"blacklist","packages":["org.example.browser"],"domains_mode":"whitelist","domains_is_whitelist":true,"domains":["example.org"],"ip_mode":"blacklist","ips":[]}""")!!
        assertFalse("Application blacklist must not be replaced by domain whitelist",rule.isWhite)
        assertTrue(rule.domainsIsWhite)
    }
    @Test fun proxyConfigurationSurvivesExportImport() = runBlocking {
        val directory = Files.createTempDirectory("csqtt-review").toFile()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope=scope) { directory.resolve("test.preferences_pb") }
        val context=mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val secrets=mock(SecureStringStore::class.java)
        `when`(secrets.encrypt(anyString())).thenAnswer { "cipher:"+it.getArgument<String>(0) }
        `when`(secrets.decrypt(anyString())).thenAnswer { it.getArgument<String>(0).removePrefix("cipher:") }
        try {
            val store=SettingsStore(context,data,secrets)
            store.saveProxySettings("socks5",1088,true,"review-user","review-password")
            val invalidExport=store.exportConfigJson(true,true,true,true,true)
            assertTrue("Default export must import", store.importConfigJson(invalidExport,true,true,true,true))
            store.saveActiveProfile(9)
            assertTrue(store.importConfigJson(invalidExport,true,true,true,true))
            store.savePorts(46010,46002,"22")
            val exported=store.exportConfigJson(true,true,true,true,true)
            store.saveProxySettings("vpn",1080,false,"","")
            assertTrue(store.importConfigJson(exported,true,true,true,true))
            assertEquals("SOCKS5 mode was not restored", "socks5",store.proxyMode.first())
            assertEquals(1088,store.proxyPort.first())
            assertEquals("review-user",store.proxyUsername.first())
            assertEquals("review-password",store.proxyPassword.first())
            assertTrue(store.proxyLanEnabled.first())
            val publicExport = store.exportConfigJson(true,true,true,true,false)
            assertFalse(publicExport.contains("review-password"))
            assertFalse(org.json.JSONObject(publicExport).getJSONObject("tunnel").has("proxy_password"))
            assertFalse(org.json.JSONObject(publicExport).getJSONObject("deploy").getBoolean("docker_install"))
            assertTrue(store.importConfigJson("""{"version":2,"exceptions":{"domain_routing_enabled":false,"domain_is_whitelist":true,"direct_domains_white":"example.com","ip_routing_enabled":false,"direct_ips_black":"198.18.0.0/24"}}""",false,false,true,false))
            val disabled = store.localRoutingSettings.first()
            assertFalse(disabled.enabled)
            assertTrue(disabled.isWhitelist)
            assertFalse(disabled.ipRoutingEnabled)
            assertEquals("", disabled.osExcludedIps)
            assertEquals("198.18.0.0/24", disabled.directIps)
            assertTrue(store.importConfigJson("""{"version":2,"exceptions":{"domain_routing_enabled":true,"ip_routing_enabled":true}}""",false,false,true,false))
            val enabled = store.localRoutingSettings.first()
            assertTrue(enabled.enabled)
            assertTrue(enabled.isWhitelist)
            assertTrue(enabled.ipRoutingEnabled)
            assertFalse(enabled.ipIsWhitelist)
            assertEquals("198.18.0.0/24", enabled.osExcludedIps)
            assertFalse(store.importConfigJson("""{"version":2,"tunnel":{"proxy_mode":"vpn","proxy_port":0}}""",true,false,false,false))
            assertEquals("socks5", store.proxyMode.first())
            // Pending UI writes are addressed to the edited profile, not the newly selected one.
            store.saveActiveProfile(0)
            store.saveProxySettings("vpn",1081,false,"other","",targetProfile=9)
            assertEquals("socks5",store.proxyMode.first())
            store.saveActiveProfile(9)
            assertEquals(1081,store.proxyPort.first())
        } finally { scope.cancel();directory.deleteRecursively() }
    }
}
