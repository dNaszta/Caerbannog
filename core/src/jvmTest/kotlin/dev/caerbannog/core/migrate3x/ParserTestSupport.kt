package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import org.junit.jupiter.api.Assertions.fail

internal fun parsedDocument(text: String): DefinitionsDocument =
    when (val result = DefinitionsParser.parse(text)) {
        is DefinitionsParseResult.Parsed -> result.document
        is DefinitionsParseResult.NeedsVirtualHost -> fail("Expected Parsed but got NeedsVirtualHost")
        is DefinitionsParseResult.Rejected -> fail("Expected Parsed but got Rejected(${result.reason::class.simpleName})")
    }

internal fun fullFixture(): DefinitionsDocument = parsedDocument(DefinitionsFixtures.read(DefinitionsFixtures.FULL))

internal fun rejection(text: String): DefinitionsRejection =
    when (val result = DefinitionsParser.parse(text)) {
        is DefinitionsParseResult.Rejected -> result.reason
        else -> fail("Expected Rejected but got ${result::class.simpleName}")
    }

internal fun needsVhost(text: String): UnassignedDefinitions =
    when (val result = DefinitionsParser.parse(text)) {
        is DefinitionsParseResult.NeedsVirtualHost -> result.draft
        else -> fail("Expected NeedsVirtualHost but got ${result::class.simpleName}")
    }

internal fun assigned(draft: UnassignedDefinitions, name: String): DefinitionsDocument =
    when (val result = draft.assignVirtualHost(name)) {
        is VirtualHostAssignment.Assigned -> result.document
        is VirtualHostAssignment.InvalidName -> fail("Expected Assigned but got InvalidName")
    }
