package com.aiquotamonitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.aiquotamonitor.data.repository.SyncSettings
import com.aiquotamonitor.ui.components.ActiveHoursSlider
import com.aiquotamonitor.ui.components.GlancePreviewCard
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald
import com.aiquotamonitor.ui.viewmodel.QuotaViewModel

@Composable
fun WidgetsAndSyncScreen(
    viewModel: QuotaViewModel,
    modifier: Modifier = Modifier
) {
    val snapshot by viewModel.activeSnapshot.collectAsState()
    val savedSettings by viewModel.settings.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val isSyncError by viewModel.isSyncError.collectAsState()
    val syncError by viewModel.syncError.collectAsState()

    var tailscaleUrl by remember(savedSettings) { mutableStateOf(savedSettings.tailscaleUrl) }
    var tailscaleToken by remember(savedSettings) { mutableStateOf(savedSettings.tailscaleToken) }
    var gistId by remember(savedSettings) { mutableStateOf(savedSettings.gistId) }
    var gistAesKey by remember(savedSettings) { mutableStateOf(savedSettings.gistAesKey) }
    var githubToken by remember(savedSettings) { mutableStateOf(savedSettings.githubToken) }

    val startHour = try {
        savedSettings.activeHoursStart.split(":").first().toInt()
    } catch (_: Exception) { 8 }

    val endHour = try {
        savedSettings.activeHoursEnd.split(":").first().toInt()
    } catch (_: Exception) { 22 }

    var currentStartHour by remember(startHour) { mutableStateOf(startHour) }
    var currentEndHour by remember(endHour) { mutableStateOf(endHour) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Screen Header
        Column {
            Text(
                text = "WIDGETS & DUAL-TRANSPORT SYNC",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "Glance Hub & Connection Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Live In-App Widget Previews
        GlancePreviewCard(snapshot = snapshot)

        // Active-Hours Wake Slider for Grok Smart Prime
        ActiveHoursSlider(
            startHour = currentStartHour,
            endHour = currentEndHour,
            onHoursChanged = { s, e ->
                currentStartHour = s
                currentEndHour = e
                viewModel.updateActiveHours("%02d:00".format(s), "%02d:00".format(e))
            }
        )

        // Dual-Transport Configuration Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
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

                // 1. Primary Tailscale Path
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
                    placeholder = { Text("e.g. http://100.x.y.z:8080") },
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

                // 2. Fallback Cloud Relay Path
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

                Spacer(modifier = Modifier.height(8.dp))

                // Save & Sync Button
                Button(
                    onClick = {
                        val newSettings = SyncSettings(
                            tailscaleUrl = tailscaleUrl.trim(),
                            tailscaleToken = tailscaleToken.trim(),
                            gistId = gistId.trim(),
                            gistAesKey = gistAesKey.trim(),
                            githubToken = githubToken.trim(),
                            activeHoursStart = "%02d:00".format(currentStartHour),
                            activeHoursEnd = "%02d:00".format(currentEndHour)
                        )
                        viewModel.saveSettings(newSettings)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Save and sync",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Save Settings & Sync Now",
                        fontWeight = FontWeight.Bold
                    )
                }

                if (userMessage != null) {
                    val messageColor = if (isSyncError) QuotaCoral else QuotaEmerald
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(messageColor.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (isSyncError) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = QuotaCoral,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = QuotaEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = userMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = messageColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
