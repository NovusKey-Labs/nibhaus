package com.nibhaus.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.nibhaus.ui.CalendarSettingsCard
import com.nibhaus.ui.InkViewModel

/** Tab 4 — "Integrations": calendar sync configuration. */
internal fun LazyListScope.integrationsTab(vm: InkViewModel) {
    item { SectionLabel("Calendar") }
    item { SettingsCard { CalendarSettingsCard(vm) } }
}
