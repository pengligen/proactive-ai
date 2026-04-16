package com.proactiveai.extreme.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {

    @Test
    fun `theme keeps grayscale foundations with semantic accent colors`() {
        val neutralPalette = mapOf(
            "Slate50" to Slate50,
            "Slate100" to Slate100,
            "Slate200" to Slate200,
            "Slate500" to Slate500,
            "Slate600" to Slate600,
            "Slate700" to Slate700,
            "Slate800" to Slate800,
            "Slate900" to Slate900,
            "White" to White,
        )
        val accentPalette = mapOf(
            "Sky50" to Sky50,
            "Sky700" to Sky700,
            "Cyan500" to Cyan500,
            "Emerald50" to Emerald50,
            "Emerald500" to Emerald500,
            "Emerald600" to Emerald600,
            "Amber50" to Amber50,
            "Amber600" to Amber600,
            "Gold50" to Gold50,
            "Gold200" to Gold200,
            "Gold500" to Gold500,
            "Orange500" to Orange500,
            "Rose50" to Rose50,
            "Rose600" to Rose600,
        )

        val nonGrayscaleNeutrals = neutralPalette.filterValues { color -> !color.isGrayscale() }.keys
        val grayscaleAccents = accentPalette.filterValues { color -> color.isGrayscale() }.keys

        assertTrue(
            "Expected neutral palette to stay grayscale, but found: $nonGrayscaleNeutrals",
            nonGrayscaleNeutrals.isEmpty(),
        )
        assertTrue(
            "Expected accent palette to keep semantic color, but found grayscale accents: $grayscaleAccents",
            grayscaleAccents.isEmpty(),
        )
    }

    @Test
    fun `light color scheme uses blue actions on neutral surfaces`() {
        val colorScheme = proactiveLightColorScheme()

        assertEquals(Sky700, colorScheme.primary)
        assertEquals(Slate700, colorScheme.secondary)
        assertEquals(Orange500, colorScheme.tertiary)
        assertEquals(White, colorScheme.background)
        assertEquals(White, colorScheme.surface)
        assertEquals(Slate50, colorScheme.surfaceVariant)
        assertEquals(Slate200, colorScheme.outline)
        assertEquals(White, colorScheme.onPrimary)
        assertEquals(Slate900, colorScheme.onBackground)
        assertEquals(Slate600, colorScheme.onSurfaceVariant)
    }

    private fun Color.isGrayscale(): Boolean {
        val argb = toArgb()
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return red == green && green == blue
    }
}
