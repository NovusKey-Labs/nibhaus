package com.nibhaus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nibhaus.translate.Languages

/**
 * The "typed text" face of a page: the OCR transcript in a clean printed font, selectable (copyable /
 * searchable / screen-reader friendly), with an optional translation bar. Non-destructive — the ink
 * stays the source of truth; this is a parallel view (the model the research found users actually
 * keep: Nebo), not the fragile in-place ink replacement that flattens layout.
 *
 * Translation is quality-first: the user's GPU-box LLM when configured, ML Kit on-device otherwise
 * (honestly labelled "basic"). Default target is the device language; source auto-detects.
 */
@Composable
fun PageTextView(
    transcript: String?,
    onTranscribe: () -> Unit,
    canTranscribe: Boolean,
    defaultTarget: String,
    translationText: String?,
    translationOnDevice: Boolean,
    translating: Boolean,
    translateError: String?,
    onTranslate: (source: String?, target: String) -> Unit,
    onShowOriginal: () -> Unit,
    /** persist a manual correction to the transcript — routed through the caller into the
     *  FTS-indexing funnel, so the edit is both stored and made searchable. */
    onSaveTranscript: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var source by remember { mutableStateOf<String?>(null) }      // null = auto-detect
    var target by remember(defaultTarget) { mutableStateOf(defaultTarget) }
    // editing the ORIGINAL transcript only — a translation is a derived, unsaved view.
    var editing by remember { mutableStateOf(false) }
    var draft by remember(transcript, editing) { mutableStateOf(transcript.orEmpty()) }

    Column(modifier.fillMaxSize()) {
        if (!transcript.isNullOrBlank() && !editing) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).riseIn(0),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LanguagePicker("From", source, allowAuto = true, modifier = Modifier.weight(1f)) { source = it }
                LanguagePicker("To", target, allowAuto = false, modifier = Modifier.weight(1f)) { target = it ?: target }
                if (translationText != null) {
                    TextButton(onClick = onShowOriginal) { Text("Original") }
                } else {
                    Button(onClick = { onTranslate(source, target) }, enabled = !translating) {
                        Text(if (translating) "…" else "Translate")
                    }
                }
            }
            if (translationText != null && translationOnDevice) {
                Text(
                    "Offline translation (basic quality). For better results, add a translation server in Settings.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (translateError != null) {
                Text(
                    translateError, style = MaterialTheme.typography.labelSmall, color = cs.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        val body = translationText ?: transcript
        if (editing) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Transcript") },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { editing = false }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSaveTranscript(draft); editing = false }) { Text("Save") }
                }
            }
        } else if (body.isNullOrBlank()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(28.dp).riseIn(1),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No typed text yet. Transcribe this page to read it in a printed font.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (canTranscribe) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onTranscribe) { Text("Transcribe on device") }
                }
                if (translationText == null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { editing = true }) { Text("Add text manually") }
                }
            }
        } else {
            Box(Modifier.weight(1f).riseIn(1)) {
                SelectionContainer(Modifier.fillMaxSize()) {
                    Text(
                        body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    )
                }
                // Editing a translation isn't supported — it's a derived, unsaved view of the original.
                if (translationText == null) {
                    IconButton(onClick = { editing = true }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit transcript")
                    }
                }
            }
            Text(
                "This is a rough first pass. Tap the pencil to fix it.",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).riseIn(2),
            )
        }
    }
}

/** A compact language dropdown over ML Kit's supported set (+ optional Auto-detect). */
@Composable
private fun LanguagePicker(
    label: String,
    code: String?,
    allowAuto: Boolean,
    modifier: Modifier = Modifier,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${Languages.nameOf(code)}", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowAuto) {
                DropdownMenuItem(text = { Text("Auto-detect") }, onClick = { onPick(null); open = false })
            }
            Languages.all.forEach { (c, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(c); open = false })
            }
        }
    }
}
