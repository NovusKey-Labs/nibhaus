package com.nibhaus.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Shape scale (design-system §3). FAB uses a 20dp rounded square; chips/switches are stadium. */
val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), // chips' inner, small tags
    small = RoundedCornerShape(10.dp), // inline fields
    medium = RoundedCornerShape(14.dp), // thumbnails, secondary cards, buttons
    large = RoundedCornerShape(18.dp), // primary cards, capture canvas
    extraLarge = RoundedCornerShape(28.dp), // bottom sheets, dialogs
)

/** Rounded-square FAB (not circular) — design-system §3. */
val FabShape = RoundedCornerShape(20.dp)
