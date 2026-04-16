package com.proactiveai.extreme.ui.permission

import com.proactiveai.extreme.ui.theme.Gold200
import com.proactiveai.extreme.ui.theme.Gold50
import com.proactiveai.extreme.ui.theme.Gold500
import com.proactiveai.extreme.ui.theme.Slate700
import com.proactiveai.extreme.ui.theme.Slate800
import com.proactiveai.extreme.ui.theme.Slate900
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandCenterUiTest {

    @Test
    fun `hero panel chrome uses dark container with light content`() {
        val hero = panelChrome(PanelTone.Hero)

        assertEquals(Slate900, hero.containerColor)
        assertEquals(Gold50, hero.contentColor)
        assertEquals(Gold200, hero.subtleColor)
        assertEquals(Slate800, hero.insetColor)
        assertEquals(Gold500, hero.borderColor)
        assertEquals(Gold500, hero.eyebrowColor)
    }

    @Test
    fun `dark compact action buttons keep readable contrast`() {
        val darkPalette = compactActionPalette(ActionSurfaceTone.Dark)

        assertEquals(Gold500, darkPalette.filledContainerColor)
        assertEquals(Slate900, darkPalette.filledContentColor)
        assertEquals(Gold200, darkPalette.outlinedBorderColor)
        assertEquals(Gold200, darkPalette.outlinedContentColor)
        assertEquals(Slate700, darkPalette.outlinedDisabledContentColor)
        assertNotEquals(darkPalette.outlinedContentColor, darkPalette.outlinedDisabledContentColor)
    }
}
