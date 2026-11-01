package com.aiquotamonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.data.repository.SyncSettings
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald
import com.aiquotamonitor.ui.viewmodel.QuotaViewModel

private enum class FormAction {
    TEST,
    SAVE
}

/**
 * Reusable configuration form for Dual-Transport collector endpoints:
 * 1. Primary Tailscale Live Daemon (URL on default port 8741 + optional Bearer token)
 * 2. Fallback Encrypted Cloud Relay (Secret Gist ID / Raw URL, AES-256-GCM Key, optional GitHub Token)
 *
 * Includes Save Settings action and Test Connection action with live feedback.
 */
@Composable
fun ConnectionSettingsForm(
    viewModel: QuotaViewModel,
    modifier: Modifier = Modifier,
    onSaved: (() -> Unit)? = null
) {
    val settings by viewModel.settings.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val isSyncError by viewModel.isSyncError.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    ConnectionSettingsForm(
        settings = settings,
        userMessage = userMessage,
        isSyncError = isSyncError,
        isSyncing = connectionState == ConnectionState.SYNCING,
        onSaveSettings = { newSettings ->
            viewModel.saveSettings(newSettings)
            onSaved?.invoke()
        },
        onTestConnection = { newSettings ->
            viewModel.testConnection(newSettings)
        },
        modifier = modifier
    )
}

@Composable
fun ConnectionSettingsForm(
    settings: SyncSettings,
    userMessage: String?,
    isSyncError: Boolean,
    onSaveSettings: (SyncSettings) -> Unit,
    onTestConnection: (SyncSettings) -> Unit,
    modifier: Modifier = Modifier,
    isSyncing: Boolean = false
) {
    var tailscaleUrl by remember(settings.tailscaleUrl) { mutableStateOf(settings.tailscaleUrl) }
    var tailscaleToken by remember(settings.tailscaleToken) { mutableStateOf(settings.tailscaleToken) }
    var gistId by remember(settings.gistId) { mutableStateOf(settings.gistId) }
    var gistAesKey by remember(settings.gistAesKey) { mutableStateOf(settings.gistAesKey) }
    var githubToken by remember(settings.githubToken) { mutableStateOf(settings.githubToken) }

    var activeAction by remember { mutableStateOf<FormAction?>(null) }

    LaunchedEffect(isSyncing) {
        if (!isSyncing) {
            activeAction = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(24.dp)
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "DUAL TRANSPORT ENDPOINTS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )

            Text(
                text = "1. Primary: Tailscale Live Daemon",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            OutlinedTextField(
                value = tailscaleUrl,
                onValueChange = { tailscaleUrl = it },
                label = { Text("Tailscale Daemon Base URL") },
                placeholder = { Text("e.g. http://100.x.y.z:8741") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = QuotaCyan,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            OutlinedTextField(
                value = tailscaleToken,
                onValueChange = { tailscaleToken = it },
                label = { Text("Bearer Token (Optional)") },
                placeholder = { Text("Auth bearer token") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = QuotaCyan,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "2. Fallback: Encrypted Cloud Relay (Secret Gist)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            OutlinedTextField(
                value = gistId,
                onValueChange = { gistId = it },
                label = { Text("GitHub Secret Gist ID or Raw URL") },
                placeholder = { Text("Gist ID or raw URL (gist.githubusercontent.com/...)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = QuotaCyan,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            OutlinedTextField(
                value = gistAesKey,
                onValueChange = { gistAesKey = it },
                label = { Text("Relay AES-256-GCM Key (Hex / Base64)") },
                placeholder = { Text("64-char Hex or 32-byte Base64 key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = QuotaCyan,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            OutlinedTextField(
                value = githubToken,
                onValueChange = { githubToken = it },
                label = { Text("GitHub Read Token (Optional)") },
                placeholder = { Text("Fine-grained read token (Bearer)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = QuotaCyan,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            Text(
                text = "Tip: For private/secret Gists via api.github.com, provide an optional GitHub Read Token. Alternatively, paste a direct Raw Gist URL (gist.githubusercontent.com/...) without a token.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            val currentSettings = {
                SyncSettings(
                    tailscaleUrl = tailscaleUrl.trim(),
                    tailscaleToken = tailscaleToken.trim(),
                    gistId = gistId.trim(),
                    gistAesKey = gistAesKey.trim(),
                    githubToken = githubToken.trim(),
                    activeHoursStart = settings.activeHoursStart,
                    activeHoursEnd = settings.activeHoursEnd
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isTesting = isSyncing && activeAction == FormAction.TEST
                val isSaving = isSyncing && activeAction == FormAction.SAVE

                OutlinedButton(
                    onClick = {
                        activeAction = FormAction.TEST
                        onTestConnection(currentSettings())
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isSyncing
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Testing...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Test connection",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Test Connection",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                Button(
                    onClick = {
                        activeAction = FormAction.SAVE
                        onSaveSettings(currentSettings())
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isSyncing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Saving...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save settings",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Save Settings & Sync Now",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (userMessage != null) {
                val isRealSuccess = !isSyncError && (
                    userMessage.contains("succeeded", ignoreCase = true) ||
                    userMessage.contains("successfully", ignoreCase = true)
                )
                val messageColor = if (isRealSuccess) QuotaEmerald else QuotaCoral
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(messageColor.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isRealSuccess) Icons.Default.Check else Icons.Default.Warning,
                        contentDescription = null,
                        tint = messageColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = userMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = messageColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
