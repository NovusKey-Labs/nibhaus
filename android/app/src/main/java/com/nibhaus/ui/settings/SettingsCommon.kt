package com.nibhaus.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nibhaus.ui.BandDivider
import com.nibhaus.ui.Eyebrow
import com.nibhaus.ui.steelCard
import com.nibhaus.ui.theme.monoData
import com.nibhaus.ui.theme.monoEyebrow

/** Shared building blocks for the Settings tabs (design-system §5/§9): section headers, the card
 *  surface every group of controls sits in, the dropdown-anchor row pattern, an inline mono
 *  key/value row, a text field that tracks a DataStore-persisted value without fighting the
 *  cursor while the user is typing, and a collapsed-by-default "Advanced" section for
 *  power-user-only config. */

@Composable
internal fun SectionLabel(text: String) {
    BandDivider(Modifier.padding(top = 10.dp))
    Eyebrow(text, Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp))
}

/**
 * A collapsed-by-default "Advanced" section (§18): self-hosted endpoints, tokens, and other
 * power-user-only overrides live here instead of cluttering the main tab. Tap the header to
 * expand/collapse; [content] renders below when expanded. The expanded state is
 * `rememberSaveable` — it survives rotation but resets to collapsed on the next app open or tab
 * switch away and back, since it isn't persisted to DataStore.
 *
 * [label] is the tappable header row's text — it must describe what the caller actually puts
 * inside. The default fits the Sync & Text tab (BYO servers); tabs whose advanced content is
 * something else pass their own.
 */
@Composable
internal fun AdvancedSection(
    label: String = "Self-hosted servers and other power-user settings",
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionLabel("Advanced")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse advanced settings" else "Expand advanced settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().steelCard(radius = 18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) { content() }
    }
}

/** A settings row whose control is a value+chevron dropdown anchor (Teal value), per §9. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> DropdownRow(
    title: String,
    desc: String,
    current: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onPick: (T) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        Row(
            Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = 15.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(current, style = MaterialTheme.typography.labelLarge, color = cs.primary)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = cs.primary)
            }
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onPick(option); expanded = false },
                )
            }
        }
    }
}

/** Inline contextual field: a mono key + mono value, with an optional trailing control (§9). */
@Composable
internal fun InlineField(key: String, value: String, trailing: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(key, style = monoEyebrow, color = cs.onSurfaceVariant)
            Text(
                value,
                style = monoData,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
internal fun PersistedTextField(
    persisted: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    mask: Boolean = false,
) {
    // Local state drives the field while it's focused, so the cursor stays put and fast typing isn't
    // garbled by the async DataStore round-trip. While it's NOT focused we mirror the persisted value,
    // so an external change (Reset to Default, a fresh load) refreshes the field live.
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(persisted) }
    LaunchedEffect(persisted) { if (!focused) text = persisted }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (mask) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    )
}
