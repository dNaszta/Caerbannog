package dev.caerbannog.core.migrate3x

import kotlin.test.Test
import kotlin.test.assertEquals

/** M8-06b: `savedPath(folder, version)`: folder plus `<version>.json`, joined with the separator the folder string already uses. */
class SavedPathTest {
    @Test
    fun `a slash folder gets a slash`() = assertEquals("/home/me/migrations/0003.json", savedPath("/home/me/migrations", "0003"))

    @Test
    fun `a backslash folder gets a backslash`() = assertEquals("C:\\Users\\me\\migrations\\0003.json", savedPath("C:\\Users\\me\\migrations", "0003"))

    @Test
    fun `a trailing separator is not doubled`() {
        assertEquals("/home/me/migrations/0003.json", savedPath("/home/me/migrations/", "0003"))
        assertEquals("C:\\m\\0003.json", savedPath("C:\\m\\", "0003"))
    }

    @Test
    fun `a Windows path that also holds a slash keeps the backslash it already uses`() =
        assertEquals("C:\\Users\\me/x\\0003.json", savedPath("C:\\Users\\me/x", "0003"))

    @Test
    fun `the root folder`() = assertEquals("/0003.json", savedPath("/", "0003"))

    @Test
    fun `the path is plain text joining - nothing is resolved, trimmed or escaped`() {
        assertEquals("/a b/../c/0003.json", savedPath("/a b/../c", "0003"))
    }
}
