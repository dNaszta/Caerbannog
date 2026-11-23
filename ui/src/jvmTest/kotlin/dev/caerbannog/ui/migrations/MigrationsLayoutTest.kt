package dev.caerbannog.ui.migrations

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

@Timeout(60)
class MigrationsLayoutTest {
    @Test fun `the threshold is 672 dp`() = assertEquals(672.dp, MIGRATIONS_STACK_BELOW)
    @Test fun `671 dp stacks`() = assertEquals(MigrationsLayout.Stacked, migrationsLayoutFor(671.dp))
    @Test fun `672 dp is side by side`() = assertEquals(MigrationsLayout.SideBySide, migrationsLayoutFor(672.dp))
    @Test fun `496 dp stacks`() = assertEquals(MigrationsLayout.Stacked, migrationsLayoutFor(496.dp))
    @Test fun `696 dp is side by side`() = assertEquals(MigrationsLayout.SideBySide, migrationsLayoutFor(696.dp))
    @Test fun `976 dp is side by side`() = assertEquals(MigrationsLayout.SideBySide, migrationsLayoutFor(976.dp))

    @Test fun `751 dp stacks at font scale 1_5`() = assertEquals(MigrationsLayout.Stacked, migrationsLayoutFor(751.dp, 1.5f))
    @Test fun `752 dp is side by side at font scale 1_5`() = assertEquals(MigrationsLayout.SideBySide, migrationsLayoutFor(752.dp, 1.5f))
    @Test fun `671 and 672 dp at font scale 1_0`() {
        assertEquals(MigrationsLayout.Stacked, migrationsLayoutFor(671.dp, 1.0f))
        assertEquals(MigrationsLayout.SideBySide, migrationsLayoutFor(672.dp, 1.0f))
    }
    @Test fun `the threshold at font scale 1_2 is 704 dp`() {
        assertEquals(MigrationsLayout.Stacked, migrationsLayoutFor(703.dp, 1.2f))
        assertEquals(MigrationsLayout.SideBySide, migrationsLayoutFor(704.dp, 1.2f))
    }
    @Test fun `a font scale under 1 does not lower the threshold`() = assertEquals(672.dp, migrationsStackBelow(0.8f))

    @Test fun `the stacked list is 240 dp on a tall page`() = assertEquals(240.dp, stackedListMaxHeight(700.dp))
    @Test fun `the stacked list is 35 percent of a mid page`() = assertEquals(210.dp, stackedListMaxHeight(600.dp))
    @Test fun `the stacked list is at least 160 dp on a short page`() = assertEquals(160.dp, stackedListMaxHeight(300.dp))
}
