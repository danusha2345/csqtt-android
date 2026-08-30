// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun TunnelActionBar(
    linkMode: Boolean,
    secretsMissing: Boolean,
    tunnelRunning: Boolean,
    tunnelStarting: Boolean,
    cooldownActive: Boolean,
    connectEnabled: Boolean,
    onAuthorization: () -> Unit,
    onToggleTunnel: () -> Unit,
    modifier: Modifier = Modifier,
    uptimeSeconds: Long? = null,
) {
    val isStarting = tunnelStarting && !tunnelRunning
    val buttonColor by animateColorAsState(
        targetValue = if (tunnelRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        animationSpec = tween(400),
        label = "tunnel_action_color",
    )
    val buttonContentColor by animateColorAsState(
        targetValue = if (tunnelRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
        animationSpec = tween(400),
        label = "tunnel_action_content_color",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!linkMode) {
                OutlinedButton(
                    onClick = onAuthorization,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (secretsMissing) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                        } else {
                            Color.Transparent
                        },
                        contentColor = if (secretsMissing) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (secretsMissing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        },
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Авторизация", fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

            Button(
                onClick = onToggleTunnel,
                modifier = Modifier.weight(1f).height(52.dp),
                enabled = if (tunnelRunning) connectEnabled else (connectEnabled && !isStarting),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonContentColor,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
            ) {
                Icon(
                    imageVector = if (tunnelRunning) Icons.Default.Stop else Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        tunnelRunning -> "Остановить"
                        isStarting -> "Запуск…"
                        cooldownActive -> "Подождите…"
                        else -> "Подключить"
                    },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }

        if (uptimeSeconds != null) {
            val totalSeconds = uptimeSeconds.coerceAtLeast(0L)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            val formattedTime = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = if (tunnelRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (tunnelRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    letterSpacing = 0.8.sp,
                )
            }
        }
    }
}
