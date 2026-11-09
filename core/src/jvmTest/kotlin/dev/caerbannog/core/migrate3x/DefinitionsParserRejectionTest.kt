package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefinitionsParserRejectionTest {

    @Test
    fun `empty text, plain text and truncated JSON are not JSON`() {
        listOf("", "not JSON at all", """{"queues":[{"name":"q"""", "{").forEach {
            assertEquals(DefinitionsRejection.NotJson, rejection(it), "input: ${it.take(30)}")
        }
        assertEquals("The file is not valid JSON", DefinitionsRejection.NotJson.message)
    }

    @Test
    fun `an empty object has no definitions`() {
        assertEquals(DefinitionsRejection.NoDefinitions, rejection("{}"))
        assertEquals(DefinitionsRejection.NoDefinitions, rejection("  { }  "))
        assertEquals("The file has no definitions", DefinitionsRejection.NoDefinitions.message)
    }

    @Test
    fun `arrays, scalars and other objects are not a definitions file`() {
        listOf("[]", "\"text\"", "42", "null", "true", """{"version":"001","actions":[]}""", """{"foo":1}""").forEach {
            assertEquals(DefinitionsRejection.NotDefinitionsFile, rejection(it), "input: $it")
        }
        assertEquals("The file is not a definitions file", DefinitionsRejection.NotDefinitionsFile.message)
    }

    @Test
    fun `a known section that is not an array is malformed`() {
        assertEquals(DefinitionsRejection.Malformed("queues"), rejection("""{"queues":"x"}"""))
        assertEquals(DefinitionsRejection.Malformed("exchanges"), rejection("""{"exchanges":{}}"""))
        assertEquals(
            """The file is not a definitions file: the section "queues" is malformed""",
            DefinitionsRejection.Malformed("queues").message,
        )
    }

    @Test
    fun `an entry without a required key is malformed`() {
        assertEquals(DefinitionsRejection.Malformed("queues"), rejection("""{"queues":[{"vhost":"/"}]}"""))
        assertEquals(DefinitionsRejection.Malformed("bindings"), rejection("""{"bindings":[{"source":"a","destination":"b","vhost":"/"}]}"""))
        assertEquals(DefinitionsRejection.Malformed("policies"), rejection("""{"policies":[{"name":"p","vhost":"/"}]}"""))
        assertEquals(DefinitionsRejection.Malformed("users"), rejection("""{"users":[{"tags":[]}]}"""))
        assertEquals(DefinitionsRejection.Malformed("permissions"), rejection("""{"permissions":[{"user":"u","vhost":"/","configure":".*","read":".*"}]}"""))
        assertEquals(DefinitionsRejection.Malformed("topic_permissions"), rejection("""{"topic_permissions":[{"user":"u","vhost":"/","exchange":"e","write":".*"}]}"""))
        assertEquals(DefinitionsRejection.Malformed("vhosts"), rejection("""{"vhosts":[{"description":"d"}]}"""))
    }

    @Test
    fun `a queue entry that is not an object, or with an argument section that is not an object, is malformed`() {
        assertEquals(DefinitionsRejection.Malformed("queues"), rejection("""{"queues":[1]}"""))
        assertEquals(DefinitionsRejection.Malformed("queues"), rejection("""{"queues":[{"name":"q","vhost":"/","arguments":[]}]}"""))
        assertEquals(DefinitionsRejection.Malformed("vhosts"), rejection("""{"vhosts":[{"name":"/","metadata":[]}]}"""))
        assertEquals(DefinitionsRejection.Malformed("policies"), rejection("""{"policies":[{"name":"p","vhost":"/","pattern":".*","definition":"x"}]}"""))
        assertEquals(DefinitionsRejection.Malformed("users"), rejection("""{"users":[{"name":"u","limits":"x"}]}"""))
    }

    @Test
    fun `no rejection message contains input text`() {
        val inputs = listOf(
            """{"users":[{"name":"SENTINEL-HASH-x","password_hash":"SENTINEL-HASH-x"}""", // truncated
            "SENTINEL-HASH-x not json",
            """{"queues":"SENTINEL-HASH-x"}""",
            """{"queues":[{"vhost":"SENTINEL-HASH-x"}]}""",
            """["SENTINEL-HASH-x"]""",
            """{"SENTINEL-HASH-x":1}""",
        )
        inputs.forEach { text ->
            val rejected = DefinitionsParser.parse(text)
            assertTrue(rejected is DefinitionsParseResult.Rejected, "input: $text")
            val reason = (rejected as DefinitionsParseResult.Rejected).reason
            assertFalse(reason.message.contains("SENTINEL"), "message echoes input: ${reason.message}")
            assertFalse(rejected.toString().contains("SENTINEL"), "toString echoes input: $rejected")
        }
    }

    @Test
    fun `the parser never throws for broken input`() {
        val broken = listOf(
            "", " ", "{", "}", "[", "]", "{\"a\":", "\u0000", "﻿{}", "nul", "{\"queues\":[{\"name\":}]}", "{'queues':[]}",
            "{\"queues\":[{\"name\":\"q\",\"vhost\":\"/\",\"arguments\":{\"x\":}}]}", "\"unterminated", "1e", "{\"queues\":[null]}",
        )
        broken.forEach { text ->
            val result = DefinitionsParser.parse(text)
            assertTrue(result is DefinitionsParseResult.Rejected, "input ${text.take(20)} gave $result")
        }
    }

    @Test
    fun `a megabyte of open brackets is not valid JSON and does not blow the stack`() {
        // If kotlinx recurses too deep, the parser must catch and map it (brief: record the fact in the test).
        val text = "[".repeat(1_000_000)
        assertEquals(DefinitionsRejection.NotJson, rejection(text))
    }
}
