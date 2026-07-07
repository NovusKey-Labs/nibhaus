package com.nibhaus.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.export.SyncMethod
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.ThemePreviewScreen
import com.nibhaus.ui.riseIn
import com.nibhaus.ui.theme.Palettes

/**
 * Settings (design-system §5/§9): Newsreader title, monoEyebrow section labels, controls grouped in
 * surface cards. Dropdowns render as a value+chevron row (Teal value), each revealing only its
 * contextual field. Every choice persists via DataStore and takes effect on the next export.
 *
 * This is the shell: shared top-level state (the entitlement/sync/theme flows every tab or the
 * cross-tab dialogs need), the header, the tab row, and the LazyColumn that dispatches each tab
 * index to its own tab composable (see [captureAndPenTab], [syncAndOcrTab], [appearanceTab],
 * [privacyTab], [integrationsTab]). The folder-picker launchers stay here (not in the Sync & OCR
 * tab file) so they survive tab switches instead of being torn down when that tab's items leave
 * composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: InkViewModel,
    onBack: () -> Unit,
    onOpenCaptureLab: () -> Unit = {},
    /** Which tab to open on ("Sync & Text" = 1) — lets callers like the Pens-home pending card deep-link
     *  straight to Sync instead of always landing on "Capture & Pen". */
    initialTab: Int = 0,
) {
    val context = LocalContext.current
    val method by vm.syncMethod.collectAsStateWithLifecycle()
    val folderUri by vm.localFolderUri.collectAsStateWithLifecycle()
    val endpoint by vm.tailscaleEndpoint.collectAsStateWithLifecycle()
    val syncToken by vm.syncToken.collectAsStateWithLifecycle()
    val translateEndpoint by vm.translateEndpoint.collectAsStateWithLifecycle()
    val translateModel by vm.translateModel.collectAsStateWithLifecycle()
    val byoOcrEndpoint by vm.byoOcrEndpoint.collectAsStateWithLifecycle()
    val byoOcrToken by vm.byoOcrToken.collectAsStateWithLifecycle()
    val paper by vm.paperTemplate.collectAsStateWithLifecycle()
    val penState by vm.penState.collectAsStateWithLifecycle()
    val premium by vm.isPremium.collectAsStateWithLifecycle()
    val entitled by vm.entitled.collectAsStateWithLifecycle()
    val activePalette by vm.activePalette.collectAsStateWithLifecycle()
    val lightPaper by vm.lightPaper.collectAsStateWithLifecycle()
    var pendingPalette by remember { mutableStateOf<String?>(null) }
    var showThemePreview by remember { mutableStateOf(false) }

    // A locked premium feature (here: native sync) explains itself and points at the Premium card.
    var showUpsell by remember { mutableStateOf(false) }
    if (showUpsell) {
        AlertDialog(
            onDismissRequest = { showUpsell = false },
            title = { Text("A premium feature") },
            text = {
                Text(
                    "Syncing straight to your own server is a planned Premium feature, along " +
                        "with the accurate transcription pass and translation. Premium is not " +
                        "available yet. Your local notebook, instant transcription, full-text " +
                        "search, and folder-based sync stay free.",
                )
            },
            confirmButton = { TextButton(onClick = { showUpsell = false }) { Text("Got it") } },
        )
    }

    // After switching sync target, offer to backfill existing pages to it.
    var reexportPrompt by remember { mutableStateOf<SyncMethod?>(null) }
    reexportPrompt?.let { target ->
        AlertDialog(
            onDismissRequest = { reexportPrompt = null },
            title = { Text("Move existing pages?") },
            text = {
                Text(
                    "New pages now go to ${target.label}. Send your already-captured pages there too? " +
                        "Pages already at the destination are skipped.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.reexportAllPages(); reexportPrompt = null }) { Text("Move them") }
            },
            dismissButton = {
                TextButton(onClick = { reexportPrompt = null }) { Text("Not now") }
            },
        )
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.setLocalFolderUri(uri.toString())
        }
    }

    var restoreMsg by remember { mutableStateOf<String?>(null) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            restoreMsg = "Restoring…"
            vm.restoreFromFolder(uri) { count -> restoreMsg = "Restored $count stroke(s) from backup." }
        }
    }

    val backupFolder by vm.backupFolderUri.collectAsStateWithLifecycle()
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.setBackupFolderUri(uri.toString())
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp).riseIn(0),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack) { Text("Back") }
        }
        // Each category is its own tab instead of one long scroll.
        val tabs = listOf("Capture & Pen", "Sync & Text", "Appearance", "Privacy", "Integrations")
        var tab by rememberSaveable { mutableStateOf(initialTab) }
        var showResetConfirm by remember { mutableStateOf(false) }
        SecondaryScrollableTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        LazyColumn(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
            when (tab) {
                0 -> captureAndPenTab(vm, penState, onOpenCaptureLab)
                1 -> syncAndOcrTab(
                    vm = vm,
                    method = method,
                    folderUri = folderUri,
                    endpoint = endpoint,
                    syncToken = syncToken,
                    translateEndpoint = translateEndpoint,
                    translateModel = translateModel,
                    byoOcrEndpoint = byoOcrEndpoint,
                    byoOcrToken = byoOcrToken,
                    premium = premium,
                    entitled = entitled,
                    restoreMsg = restoreMsg,
                    backupFolder = backupFolder,
                    onPickFolder = { pickFolder.launch(null) },
                    onPickRestoreFolder = { restorePicker.launch(null) },
                    onPickBackupFolder = { backupPicker.launch(null) },
                    onShowUpsell = { showUpsell = true },
                    onReexportPrompt = { picked -> vm.setSyncMethod(picked); reexportPrompt = picked },
                )
                2 -> appearanceTab(
                    vm = vm,
                    activePalette = activePalette,
                    lightPaper = lightPaper,
                    paper = paper,
                    pendingPalette = pendingPalette,
                    onPendingPaletteChange = { pendingPalette = it },
                    onShowThemePreview = { showThemePreview = true },
                )
                3 -> privacyTab(vm)
                4 -> integrationsTab(vm)
            }
            item {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset this tab to defaults") }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("Reset “${tabs[tab]}” to defaults?") },
                text = { Text("Only this tab's settings are restored to their defaults. The other tabs are untouched.") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.resetSettingsTab(tab)
                        if (tab == 2) {
                            // Appearance tab: a tapped-but-not-yet-applied palette (and its preview
                            // dialog) shouldn't survive a reset — it'd otherwise still show as
                            // "pending" over the just-reset (default) active palette.
                            pendingPalette = null
                            showThemePreview = false
                        }
                        showResetConfirm = false
                    }) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
                },
            )
        }
        if (showThemePreview) {
            Dialog(
                onDismissRequest = { showThemePreview = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                ThemePreviewScreen(
                    palette = Palettes.byId(pendingPalette ?: activePalette.id),
                    lightPaper = lightPaper,
                    onClose = { showThemePreview = false },
                )
            }
        }
    }
}
