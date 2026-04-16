package com.proactiveai.extreme.ui.permission

import com.proactiveai.extreme.ui.theme.Amber50
import com.proactiveai.extreme.ui.theme.Amber600
import com.proactiveai.extreme.ui.theme.Emerald50
import com.proactiveai.extreme.ui.theme.Emerald500
import com.proactiveai.extreme.ui.theme.Rose50
import com.proactiveai.extreme.ui.theme.Rose600
import com.proactiveai.extreme.ui.theme.Sky50
import com.proactiveai.extreme.ui.theme.Sky700
import com.proactiveai.extreme.ui.theme.Slate100
import com.proactiveai.extreme.ui.theme.Slate700
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPillColorsTest {

    @Test
    fun `signal pills map tones to semantic colors`() {
        assertEquals(SignalPillColors(background = Slate100, content = Slate700), signalPillColors(SignalTone.Neutral))
        assertEquals(SignalPillColors(background = Sky50, content = Sky700), signalPillColors(SignalTone.Info))
        assertEquals(SignalPillColors(background = Emerald50, content = Emerald500), signalPillColors(SignalTone.Success))
        assertEquals(SignalPillColors(background = Amber50, content = Amber600), signalPillColors(SignalTone.Warning))
        assertEquals(SignalPillColors(background = Rose50, content = Rose600), signalPillColors(SignalTone.Danger))
    }

    @Test
    fun `semantic signal tones stay visually distinct from neutral`() {
        val neutral = signalPillColors(SignalTone.Neutral)
        val semanticTones = listOf(
            signalPillColors(SignalTone.Info),
            signalPillColors(SignalTone.Success),
            signalPillColors(SignalTone.Warning),
            signalPillColors(SignalTone.Danger),
        )

        assertTrue(semanticTones.none { it == neutral })
    }
}
