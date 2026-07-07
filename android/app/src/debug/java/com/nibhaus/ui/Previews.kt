package com.nibhaus.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.nibhaus.export.ThemeMode
import com.nibhaus.health.ConnectionDiagnostic
import com.nibhaus.ui.common.EmptyState
import com.nibhaus.ui.home.DiagnosticStepCard
import com.nibhaus.ui.pagedetail.EditToolbar

/**
 * @Preview wrappers for the key stateless components (UI/UX audit fix #4). Lives in the `debug`
 * source set so none of it ships in a release build. Only components that render from plain
 * sample data are previewed here — [com.nibhaus.ui.home.PenStatusCard] and the library thumbnails
 * ([com.nibhaus.ui.library.PageThumb]/`NotebookThumb`) both require a live, DI-constructed
 * [com.nibhaus.ui.InkViewModel] (StateFlow collection, DB-backed stroke queries) with no
 * lightweight way to fake that data, so they're intentionally skipped.
 */

@Preview(name = "EmptyState · plain", showBackground = true)
@Composable
private fun PreviewEmptyStatePlain() {
    NibhausTheme(ThemeMode.DARK) {
        EmptyState(
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            text = "Write a page and it lands here.",
        )
    }
}

@Preview(name = "EmptyState · rich (first-run)", showBackground = true)
@Composable
private fun PreviewEmptyStateRich() {
    NibhausTheme(ThemeMode.DARK) {
        EmptyState(
            icon = Icons.Outlined.Draw,
            text = "The pen writes its coordinates as it goes, so it only recognizes genuine Ncode dot-pattern paper — not a plain notebook.",
            headline = "Pair a pen to begin",
            primaryActionLabel = "Pair a pen",
            onPrimaryAction = {},
        )
    }
}

@Preview(name = "EditToolbar", showBackground = true)
@Composable
private fun PreviewEditToolbar() {
    NibhausTheme(ThemeMode.DARK) {
        EditToolbar(
            lassoMode = false,
            hasSelection = true,
            currentColor = 0,
            currentWidth = 1.0f,
            onToggleLasso = {},
            onRecolor = {},
            onResize = {},
            onDelete = {},
            onUndo = {},
        )
    }
}

@Preview(name = "DiagnosticStepCard · pass", showBackground = true)
@Composable
private fun PreviewDiagnosticStepCardPass() {
    NibhausTheme(ThemeMode.DARK) {
        DiagnosticStepCard(
            step = ConnectionDiagnostic.Step("Bluetooth on", ConnectionDiagnostic.Status.PASS, "OK"),
            isFirstFail = false,
        )
    }
}

@Preview(name = "DiagnosticStepCard · first failure", showBackground = true)
@Composable
private fun PreviewDiagnosticStepCardFail() {
    NibhausTheme(ThemeMode.DARK) {
        DiagnosticStepCard(
            step = ConnectionDiagnostic.Step(
                "Pen powered & in range",
                ConnectionDiagnostic.Status.FAIL,
                "Press the pen tip to wake it and hold it within a metre of the device.",
            ),
            isFirstFail = true,
        )
    }
}
