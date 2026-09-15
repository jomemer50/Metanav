package com.metanav.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/** Places content at a fraction of the parent's size (used for overlay labels). */
fun Modifier.offsetFraction(xFraction: Float, yFraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        val x = (constraints.maxWidth * xFraction).roundToInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
        val y = (constraints.maxHeight * yFraction).roundToInt().coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
        placeable.place(x, y)
    }
}
