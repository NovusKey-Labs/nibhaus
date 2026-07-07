@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nibhaus.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nibhaus.data.NotebookEntity
import com.nibhaus.export.NotebookType

/**
 * One-time prompt shown when you start writing in a notebook the app hasn't set up. Pick its product
 * **type** (so its layout/dimensions and export folder are right) and give this physical copy a
 * **label** (Work, School, …). The type is remembered by Ncode book id — once per product, every copy
 * inherits it — and the label is saved on this notebook. "Skip" just dismisses it (asked once).
 */
@Composable
fun NotebookSetupDialog(
    notebook: NotebookEntity,
    defaultTypeId: String?,
    onSave: (typeId: String, label: String) -> Unit,
    onSkip: () -> Unit,
) {
    var typeId by remember { mutableStateOf(defaultTypeId ?: NotebookType.ALL.first().id) }
    var label by remember { mutableStateOf(notebook.title) }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("New notebook", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text("What kind of notebook is this?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.riseIn(0))
                // FlowRow so type chips wrap as whole chips (a plain Row compressed "2026 Planner" and
                // wrapped its label mid-word); labels stay single-line, chips flow to the next line.
                FlowRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).riseIn(1)) {
                    NotebookType.ALL.forEach { type ->
                        FilterChip(
                            selected = typeId == type.id,
                            onClick = { typeId = type.id },
                            label = { Text(type.displayName, maxLines = 1) },
                            modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Label (e.g. Work, School)") },
                    modifier = Modifier.fillMaxWidth().riseIn(2),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(typeId, label.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}
