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

    @Test fun `the stacked list is 240 dp on a tall page`() = assertEquals(240.dp, stackedListMaxHeight(700.dp))
    @Test fun `the stacked list is 35 percent of a mid page`() = assertEquals(210.dp, stackedListMaxHeight(600.dp))
    @Test fun `the stacked list is at least 160 dp on a short page`() = assertEquals(160.dp, stackedListMaxHeight(300.dp))
}
