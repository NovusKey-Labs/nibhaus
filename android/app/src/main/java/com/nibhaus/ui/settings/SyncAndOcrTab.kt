package com.nibhaus.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.export.SyncMethod
import com.nibhaus.export.TranscriptionQuality
import com.nibhaus.premiumapi.VlmDownloadState
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.NibToggle

/** Tab 1 — "Sync & Text": entitlement status, where exports are delivered, backup/restore, and the
 *  transcription + translation model configuration (on-device tiers or a self-hosted BYO server).
 *  The launchers behind [onPickFolder]/[onPickRestoreFolder]/[onPickBackupFolder] and the
 *  premium-upsell / re-export prompts live in the shell (SettingsScreen) so they survive tab
 *  switches — this tab only triggers them. */
internal fun LazyListScope.syncAndOcrTab(
    vm: InkViewModel,
    method: SyncMethod,
    folderUri: String,
    endpoint: String,
    syncToken: String,
    translateEndpoint: String,
    translateModel: String,
    byoOcrEndpoint: String,
    byoOcrToken: String,
    premium: Boolean,
    entitled: Boolean,
    restoreMsg: String?,
    backupFolder: String,
    onPickFolder: () -> Unit,
    onPickRestoreFolder: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onShowUpsell: () -> Unit,
    onReexportPrompt: (SyncMethod) -> Unit,
) {
    item { SectionLabel("Premium") }
    item { PremiumCard(premium = premium, onDevUnlock = vm::setPremiumUnlocked) }
    item { SectionLabel("Sync") }
    item {
        SettingsCard {
            DropdownRow(
                title = "Sync method",
                desc = "Where your pages go after you write them",
                current = method.label,
                options = SyncMethod.entries,
                optionLabel = { it.label },
                onPick = { picked ->
                    if (picked == SyncMethod.TAILSCALE_PUSH && !entitled) onShowUpsell()
                    else if (picked != method) onReexportPrompt(picked)
                },
            )
        }
    }
    // Contextual field for the selected method only.
    item {
        val cs = MaterialTheme.colorScheme
        when (method) {
            SyncMethod.LOCAL_FOLDER -> InlineField("FOLDER", folderUri.ifEmpty { "not chosen" }) {
                Button(onClick = onPickFolder) { Text("Choose…") }
            }
            SyncMethod.TAILSCALE_PUSH -> Column(Modifier.padding(top = 4.dp)) {
                // Endpoint split into scheme (toggle) + host + port so the required port is obvious.
                // Local state drives the fields (cursor-stable); they compose into the stored URL.
                // Mirror the stored endpoint into the fields while neither is focused, so an
                // external change (Reset to Default) refreshes them live; typing (focused) is
                // never clobbered by the DataStore round-trip.
                var hostFocused by remember { mutableStateOf(false) }
                var portFocused by remember { mutableStateOf(false) }
                var scheme by remember {
                    mutableStateOf(if (endpoint.startsWith("http://")) "http://" else "https://")
                }
                var host by remember { mutableStateOf("") }
                var port by remember { mutableStateOf("") }
                LaunchedEffect(endpoint) {
                    if (!hostFocused && !portFocused) {
                        scheme = if (endpoint.startsWith("http://")) "http://" else "https://"
                        val rest = endpoint.substringAfter("://", endpoint)
                        host = rest.substringBeforeLast(":", rest)
                        port = if (rest.contains(":")) rest.substringAfterLast(":") else ""
                    }
                }
                fun fullEndpoint() =
                    scheme + host.trim() + if (port.isNotBlank()) ":${port.trim()}" else ""
                fun compose() { vm.setTailscaleEndpoint(fullEndpoint()) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("https://", "http://").forEach { sch ->
                        FilterChip(
                            selected = scheme == sch,
                            onClick = { scheme = sch; compose() },
                            label = { Text(sch) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it; compose() },
                        label = { Text("Server address") },
                        placeholder = { Text("yourserver.ts.net") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).onFocusChanged { hostFocused = it.isFocused },
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }; compose() },
                        label = { Text("Port") },
                        placeholder = { Text("8090") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(104.dp).onFocusChanged { portFocused = it.isFocused },
                    )
                }
                Spacer(Modifier.height(8.dp))
                PersistedTextField(
                    persisted = syncToken,
                    onChange = vm::setSyncToken,
                    label = "Access key (optional)",
                    placeholder = "the key your server requires, if any",
                    mask = true,
                )
                Spacer(Modifier.height(8.dp))
                val testState by vm.syncTest.collectAsStateWithLifecycle()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.testSyncConnection(fullEndpoint(), syncToken) },
                        enabled = testState !is InkViewModel.SyncTest.Testing && host.isNotBlank(),
                    ) {
                        Text(if (testState is InkViewModel.SyncTest.Testing) "Testing…" else "Test connection")
                    }
                    (testState as? InkViewModel.SyncTest.Result)?.let { r ->
                        Text(
                            if (r.ok) "✓ Connected" else "✗ ${r.message}",
                            color = if (r.ok) cs.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            SyncMethod.LOCAL_ONLY -> Text(
                "Exports stay on this device. Nothing leaves your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }

    item { SectionLabel("Backup & restore") }
    item {
        SettingsCard {
            Column(Modifier.padding(14.dp)) {
                Text("Restore from backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Rebuild your notebooks from a backup folder (made by sync or local-folder export). " +
                        "Safe to run more than once. It never creates duplicates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onPickRestoreFolder, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Choose backup folder")
                }
                restoreMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    item {
        SettingsCard {
            Column(Modifier.padding(14.dp)) {
                Text("Crash backup folder", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Saves an editable backup of each page as you write, so your notes survive even if " +
                        "the app's data is cleared or the device is reset. This is independent of your sync " +
                        "method. Pick a folder that won't get deleted (for example, one under Documents).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (backupFolder.isEmpty()) "Not set" else "Folder chosen",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(onClick = onPickBackupFolder, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (backupFolder.isEmpty()) "Choose folder" else "Change folder")
                }
            }
        }
    }
    item { SectionLabel("Convert handwriting to text") }
    item { OcrSettingsCard(vm) }

    // Power-user-only overrides (§18): self-hosted OCR/translation servers and the on-device VLM
    // overrides. Collapsed by default so the common path (on-device tiers, no BYO server) isn't
    // cluttered by fields most people never touch. all three are
    // premium surfaces (native/BYO transcription, translation, VLM tuning), and a not-entitled user
    // gets one honest cleared-register line per capability instead of editable fields that promise
    // server-backed behavior the app won't actually run for them.
    item {
        AdvancedSection {
            if (entitled) {
                if (vm.onDeviceOcrAvailable) OcrAdvancedOverrides(vm)
                OcrServerCard(vm, byoOcrEndpoint, byoOcrToken)
                TranslationCard(vm, translateEndpoint, translateModel)
            } else {
                PlannedPremiumCard("Handwriting recognition tuning")
                PlannedPremiumCard("Adding your own transcription server")
                PlannedPremiumCard("Translation")
            }
        }
    }
}

/** Exact cleared-register sentence for a premium capability that
 *  isn't available to a not-entitled user yet. Pure so every call site uses identical copy. */
internal fun plannedPremiumFeatureLine(capability: String): String =
    "$capability is a planned Premium feature and is not available yet."

/** One-line stand-in for a gated Advanced-section field: no editable control, just the honest
 *  cleared-register line (see [plannedPremiumFeatureLine]). */
@Composable
private fun PlannedPremiumCard(capability: String) {
    SettingsCard {
        Text(
            plannedPremiumFeatureLine(capability),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 14.dp),
        )
    }
}

/** Self-hosted transcription server: overrides the on-device tiers when set, otherwise ignored. */
@Composable
private fun OcrServerCard(vm: InkViewModel, byoOcrEndpoint: String, byoOcrToken: String) {
    val cs = MaterialTheme.colorScheme
    SettingsCard {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text("Your own transcription server", style = MaterialTheme.typography.titleMedium)
            Text(
                "Highest-quality handwriting-to-text conversion runs on a server you host yourself. " +
                    "Leave blank to use the on-device tiers instead.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            PersistedTextField(
                persisted = byoOcrEndpoint,
                onChange = vm::setByoOcrEndpoint,
                label = "Server address",
                placeholder = "https://yourserver.ts.net",
            )
            Spacer(Modifier.height(8.dp))
            PersistedTextField(
                persisted = byoOcrToken,
                onChange = vm::setByoOcrToken,
                label = "Access key (optional)",
                placeholder = "the key your server requires, if any",
                mask = true,
            )
        }
    }
}

/** Self-hosted translation server: overrides the on-device offline translator when set. */
@Composable
private fun TranslationCard(vm: InkViewModel, translateEndpoint: String, translateModel: String) {
    val cs = MaterialTheme.colorScheme
    SettingsCard {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text("Your own translation server", style = MaterialTheme.typography.titleMedium)
            Text(
                "Best-quality translation runs on a server you host yourself: one model, every " +
                    "language, and nothing leaves your network. Leave this blank to use the on-device " +
                    "offline translator.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            PersistedTextField(
                persisted = translateEndpoint,
                onChange = vm::setTranslateEndpoint,
                label = "Server address",
                placeholder = "https://yourserver.ts.net",
            )
            Spacer(Modifier.height(8.dp))
            PersistedTextField(
                persisted = translateModel,
                onChange = vm::setTranslateModel,
                label = "Model name",
                placeholder = "eurollm  (or tower-plus, etc.)",
            )
        }
    }
}

/**
 * Premium entitlement card: states what the one-time unlock includes (both tiers stay ad-free) and,
 * in debug builds, a dev switch to flip it for testing. Release builds show the (billing-pending)
 * purchase entry instead — the real paywall lands with Play Billing / StoreKit.
 */
@Composable
private fun PremiumCard(premium: Boolean, onDevUnlock: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    SettingsCard {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text(if (premium) "Premium (active)" else "Premium", style = MaterialTheme.typography.titleMedium)
            Text(
                "The planned Premium tier will add the accurate transcription pass, translation, " +
                    "and syncing straight to your own server. It is not available yet. The " +
                    "complete local-first notebook (capture, instant transcription, full-text " +
                    "search, editing, export, folder sync) is free and stays free.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (com.nibhaus.BuildConfig.DEBUG) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Dev: unlock premium (debug only)", style = MaterialTheme.typography.bodySmall)
                    NibToggle(checked = premium, onCheckedChange = onDevUnlock)
                }
            } else if (!premium) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = {}, enabled = false) { Text("Purchase (coming soon)") }
            }
        }
    }
}

/**
 * Handwriting-to-text controls. Your own server is the default path (pages export there); on-device
 * conversion is an opt-in convenience gated by a first-use disclosure. This centralizes the privacy
 * controls: a master switch to keep it server-only, accuracy disclaimer, quality tier, and model
 * download status. The mobile-data/force-enable overrides live in [OcrAdvancedOverrides], under the
 * tab's collapsed Advanced section.
 */
@Composable
private fun OcrSettingsCard(vm: InkViewModel) {
    val cs = MaterialTheme.colorScheme
    val enabled by vm.onDeviceOcrEnabled.collectAsStateWithLifecycle()
    val quality by vm.transcriptionQuality.collectAsStateWithLifecycle()
    val modelState by vm.vlmModelState.collectAsStateWithLifecycle()

    SettingsCard {
        Column(Modifier.padding(vertical = 14.dp)) {
            // Canonical accuracy disclaimer copy (spec §3.1).
            Text(
                "Converting your handwriting to text makes your notes searchable and editable. Your " +
                    "original ink is always kept and never changed. Accuracy depends on your handwriting; " +
                    "messy or stylized writing may contain errors. For the highest accuracy, connect your " +
                    "own server.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // On-device master switch.
            if (vm.onDeviceOcrAvailable) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Convert handwriting to text on this device", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Lets you convert any page to text right from the page, without waiting on a server. Off: conversion still happens through your own server, if you've set one up below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    NibToggle(checked = enabled, onCheckedChange = vm::setOnDeviceOcrEnabled)
                }
                TextButton(onClick = { vm.resetOnDeviceOcrDisclosure() }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Re-show the first-use notice")
                }

                // Conversion quality tier: controls whether an accurate pass is auto-chained
                // after the instant Tier-0 transcript (without the user tapping ✨ Improve).
                Spacer(Modifier.height(4.dp))
                DropdownRow(
                    title = "Conversion quality",
                    desc = when (quality) {
                        TranscriptionQuality.INSTANT  -> "Fast, done entirely on this device. Tap Improve on a page for better quality later."
                        TranscriptionQuality.ACCURATE -> "Automatically follows up with a more accurate conversion"
                        TranscriptionQuality.AUTO     -> "Uses the accurate version on Wi-Fi; the faster version on mobile data"
                    },
                    current = quality.label,
                    options = TranscriptionQuality.entries,
                    optionLabel = { it.label },
                    onPick = vm::setTranscriptionQuality,
                )

                // On-device model download status.
                when (val s = modelState) {
                    is VlmDownloadState.Downloading -> {
                        val pctText = if (s.pct == -1) "Downloading model…" else "Downloading model… ${s.pct}%"
                        Text(pctText, style = MaterialTheme.typography.bodySmall, color = cs.primary,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    is VlmDownloadState.Ready ->
                        Text("Ready to convert handwriting on this device.", style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    is VlmDownloadState.Failed ->
                        Text("Couldn't download the on-device model: ${s.reason}", style = MaterialTheme.typography.bodySmall,
                            color = cs.error, modifier = Modifier.padding(top = 8.dp))
                    else -> {} // Idle or null — no status shown
                }
            } else {
                Text(
                    "On-device conversion isn't available in this version of the app. Pages still convert to text on your own server, if you've set one up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * On-device conversion overrides for the rare cases where the automatic Wi-Fi/device checks get
 * it wrong. Lives under the tab's collapsed Advanced section (§18) — most people never need these.
 */
@Composable
private fun OcrAdvancedOverrides(vm: InkViewModel) {
    val cs = MaterialTheme.colorScheme
    val allowMetered by vm.vlmAllowMetered.collectAsStateWithLifecycle()
    val forceOnDevice by vm.vlmForceOnDevice.collectAsStateWithLifecycle()
    SettingsCard {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text("On-device conversion overrides", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Allow downloading over mobile data", style = MaterialTheme.typography.titleSmall)
                    Text("Off: keeps the on-device model download off your mobile data plan. Wi-Fi only.", style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant)
                }
                NibToggle(checked = allowMetered, onCheckedChange = vm::setVlmAllowMetered)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Try on-device conversion on this device anyway", style = MaterialTheme.typography.titleSmall)
                    Text("May be slow or fail on this device. Use if the automatic check disabled it incorrectly.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                NibToggle(checked = forceOnDevice, onCheckedChange = vm::setVlmForceOnDevice)
            }
        }
    }
}
