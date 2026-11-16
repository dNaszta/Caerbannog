package dev.caerbannog.core.planner

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** M9-02: the naming style is read from and written to `AppSettings.plannerNamingStyle` (per machine, default kebab-case); changing it renames nothing. */
class PlannerViewModelNamingStyleTest {
    private val rig = PlannerRig()

    @AfterTest
    fun close() = rig.close()

    @Test
    fun `no style was ever chosen means kebab-case`() {
        assertEquals(NamingStyle.KEBAB, rig.state.namingStyle)
        assertEquals(NamingStyle.KEBAB, rig.settings.plannerNamingStyle)
    }

    @Test
    fun `choosing a style writes the setting and a new view model reads it back`() {
        rig.vm.setNamingStyle(NamingStyle.SNAKE)
        assertEquals(NamingStyle.SNAKE, rig.settings.plannerNamingStyle)
        rig.restart()
        assertEquals(NamingStyle.SNAKE, rig.state.namingStyle)
    }

    @Test
    fun `every style round trips through the settings`() {
        NamingStyle.entries.forEach { style ->
            rig.vm.setNamingStyle(style)
            rig.restart()
            assertEquals(style, rig.state.namingStyle, style.label)
        }
    }

    @Test
    fun `the style is per machine, not per plan, so a new plan after Dismiss keeps it`() {
        rig.vm.setNamingStyle(NamingStyle.DOT)
        rig.queue("a")
        rig.vm.dismiss(); rig.vm.confirm()
        assertEquals(NamingStyle.DOT, rig.state.namingStyle)
    }
}
