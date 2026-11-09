package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrate3x.DefinitionsFileLoader
import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.MAX_DEFINITIONS_FILE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Reads a definitions file as UTF-8 text (a leading byte order mark is stripped). The size is checked before the
 * file is read. Never writes. An unreadable result never carries file content.
 */
class JvmDefinitionsFileLoader(private val maxBytes: Long = MAX_DEFINITIONS_FILE_BYTES) : DefinitionsFileLoader {

    override suspend fun load(path: String): FileLoadResult = withContext(Dispatchers.IO) { loadBlocking(path) }

    private fun loadBlocking(path: String): FileLoadResult = try {
        val file = Path.of(path)
        if (!Files.isRegularFile(file)) {
            unreadable("The path is not a file")
        } else {
            val size = Files.size(file)
            if (size > maxBytes) FileLoadResult.TooLarge(size, maxBytes) else decode(Files.readAllBytes(file))
        }
    } catch (_: InvalidPathException) {
        unreadable("The path is not valid")
    } catch (_: IOException) {
        unreadable("The file could not be read")
    } catch (_: SecurityException) {
        unreadable("The file could not be read")
    }

    private fun decode(bytes: ByteArray): FileLoadResult = try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        FileLoadResult.Loaded(decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix(BYTE_ORDER_MARK))
    } catch (_: CharacterCodingException) {
        unreadable("The file is not valid UTF-8 text")
    }

    private fun unreadable(reason: String) = FileLoadResult.Unreadable(reason)

    private companion object {
        const val BYTE_ORDER_MARK = "﻿"
    }
}
