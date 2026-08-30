// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.csqtt.client.SettingsStore
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import com.csqtt.client.ui.design.CsqttShapes
import com.csqtt.client.ui.design.CsqttSizes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploySecretsDialog(
    settingsStore: SettingsStore,
    initialMainPass: String,
    initialSshLogin: String,
    initialSshPass: String,
    initialWebLogin: String,
    initialWebPass: String,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var passInput by rememberSaveable { mutableStateOf(initialMainPass) }
    var webLoginInput by rememberSaveable { mutableStateOf(initialWebLogin) }
    var webPassInput by rememberSaveable { mutableStateOf(initialWebPass) }
    var passwordsVisible by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = CsqttShapes.Dialog,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Авторизация", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Придумайте свои данные — латиница и цифры. Они будут созданы на сервере при установке, искать ничего не нужно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                val allowedChars = Regex("^[a-zA-Z0-9]+$")
                val invalidHint: @Composable () -> Unit = {
                    Text("Разрешена «Латиница» и «Цифры»", color = MaterialTheme.colorScheme.error)
                }
                val isPasswordValid = passInput.isNotEmpty() && passInput.matches(allowedChars)
                val isWebLoginValid = webLoginInput.isNotEmpty() && webLoginInput.matches(allowedChars)
                val isWebPassValid = webPassInput.isNotEmpty() && webPassInput.matches(allowedChars)

                Text("Подключение", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passInput,
                    onValueChange = { 
                        passInput = it.filter { c -> !c.isWhitespace() }
                    },
                    label = { Text("Пароль туннеля (придумайте)", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text("придумайте пароль", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CsqttShapes.Control,
                    isError = !isPasswordValid,
                    supportingText = if (passInput.isNotEmpty() && !isPasswordValid) invalidHint else null,
                    visualTransformation = if (passwordsVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { passwordsVisible = !passwordsVisible }) {
                            Text(if (passwordsVisible) "Скрыть" else "Показать")
                        }
                    },
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Данные доступа к web панели", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = webLoginInput,
                    onValueChange = { 
                        webLoginInput = it.filter { c -> !c.isWhitespace() }
                    },
                    label = { Text("Логин WEB (придумайте)", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text("придумайте логин", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CsqttShapes.Control,
                    isError = !isWebLoginValid,
                    supportingText = if (webLoginInput.isNotEmpty() && !isWebLoginValid) invalidHint else null
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = webPassInput,
                    onValueChange = { 
                        webPassInput = it.filter { c -> !c.isWhitespace() }
                    },
                    label = { Text("Пароль WEB (придумайте)", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text("придумайте пароль", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CsqttShapes.Control,
                    isError = !isWebPassValid,
                    supportingText = if (webPassInput.isNotEmpty() && !isWebPassValid) invalidHint else null,
                    visualTransformation = if (passwordsVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = {
                        scope.launch {
                            settingsStore.saveDeploySecrets(passInput, initialSshLogin, initialSshPass, webLoginInput, webPassInput)
                            onSaved()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = CsqttSizes.ControlHeight),
                    shape = CsqttShapes.Control,
                    enabled = isPasswordValid && isWebLoginValid && isWebPassValid,
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Text("Сохранить", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
