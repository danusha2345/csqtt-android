package com.csqtt.client.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.CsqttConstants
import com.csqtt.client.SettingsStore
import kotlinx.coroutines.launch

@Composable
internal fun ProxySettingsCard(store: SettingsStore) {
    val mode by store.proxyMode.collectAsStateWithLifecycle(initialValue = CsqttConstants.Proxy.MODE_VPN)
    val port by store.proxyPort.collectAsStateWithLifecycle(initialValue = 1080)
    val lan by store.proxyLanEnabled.collectAsStateWithLifecycle(initialValue = false)
    val username by store.proxyUsername.collectAsStateWithLifecycle(initialValue = "")
    val password by store.proxyPassword.collectAsStateWithLifecycle(initialValue = "")
    var portText by remember(port) { mutableStateOf(port.toString()) }
    val scope = rememberCoroutineScope()
    fun save(nextMode: String = mode, nextPort: Int = port, nextLan: Boolean = lan, nextUser: String = username, nextPassword: String = password) {
        scope.launch { store.saveProxySettings(nextMode, nextPort, nextLan, nextUser, nextPassword) }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Режим подключения", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Локальный SOCKS5", modifier = Modifier.weight(1f))
                Switch(mode == CsqttConstants.Proxy.MODE_SOCKS5, onCheckedChange = { save(nextMode = if (it) CsqttConstants.Proxy.MODE_SOCKS5 else CsqttConstants.Proxy.MODE_VPN) })
            }
            if (mode == CsqttConstants.Proxy.MODE_SOCKS5) {
                Text("Укажите этот прокси в другом приложении. Системный VPN в этом режиме не создаётся.")
                OutlinedTextField(portText, onValueChange = { raw ->
                    if (raw.length <= 5 && raw.all(Char::isDigit)) {
                        portText = raw
                        raw.toIntOrNull()?.takeIf { it in 1..65535 }?.let { save(nextPort = it) }
                    }
                }, label = { Text("Порт") }, singleLine = true)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Доступ из локальной сети", modifier = Modifier.weight(1f))
                    Switch(lan, onCheckedChange = { save(nextLan = it) })
                }
                if (lan) {
                    Text("Другие устройства подключаются к IP этого телефона. Обязательны имя пользователя и пароль.")
                    OutlinedTextField(username, onValueChange = { if (it.toByteArray().size <= 255) save(nextUser = it) }, label = { Text("Пользователь SOCKS5") }, singleLine = true)
                    OutlinedTextField(password, onValueChange = { if (it.toByteArray().size <= 255) save(nextPassword = it) }, label = { Text("Пароль SOCKS5") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            }
        }
    }
}
