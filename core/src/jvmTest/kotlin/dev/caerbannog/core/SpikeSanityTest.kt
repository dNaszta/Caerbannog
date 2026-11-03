package dev.caerbannog.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * M1-01 skeleton spike, Acceptance item 3: "One trivial unit test passes in `core` via the chosen
 * framework and the chosen build tool's test task."
 *
 * This proves nothing about Caerbannog's domain logic - `core` stays empty of real behaviour until
 * M1-02. It only has to prove that JUnit 5 tests in `core`'s jvm target are discovered and run by
 * the chosen build tool.
 *
 * Expected to fail to compile right now: `core/build.gradle.kts` does not declare a JUnit 5
 * dependency yet (Kotest vs JUnit 5 is an open engineering call per the task brief). The senior
 * developer makes this pass by adding the test framework dependency and wiring
 * `useJUnitPlatform()` on the jvm test task.
 */
class SpikeSanityTest {

    @Test
    fun `build tool and test framework can run a trivial jvm test in core`() {
        assertEquals(4, 2 + 2)
    }
}
