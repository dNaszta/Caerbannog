package dev.caerbannog.core.browser

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Filter criteria for matching stream messages during a scan.
 */
sealed interface MessageFilterCriteria {
    data object None : MessageFilterCriteria
    data class Text(val query: String) : MessageFilterCriteria
    data class Property(val name: String, val value: String) : MessageFilterCriteria
    data class ApplicationProperty(val name: String, val value: String) : MessageFilterCriteria
}

/**
 * Progress snapshot emitted during a stream scan.
 */
data class ScanProgressUpdate(
    val scannedCount: Int,
    val matches: List<StreamMessage>,
    val limitReached: Boolean = false,
    val isRunning: Boolean = false,
    val notice: String? = null,
)

/**
 * Bounded client-side stream scanner executing sequential chunk queries
 * via [StreamMessageReader] up to a maximum scan limit (default 10,000 messages).
 *
 * Safe and read-only: does not publish, acknowledge, delete, or track consumer offsets.
 */
class StreamScanner(
    private val reader: StreamMessageReader,
) {
    companion object {
        const val DEFAULT_SCAN_LIMIT = 10_000
        const val SCAN_CHUNK_SIZE = 100
        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        private fun byteToHex(b: Byte): String {
            val i = b.toInt() and 0xFF
            return "" + HEX_CHARS[i ushr 4] + HEX_CHARS[i and 0x0F]
        }
    }

    /**
     * Executes a bounded scan starting from [startOffset] matching [criteria].
     */
    fun scan(
        vhost: String,
        stream: String,
        startOffset: Long,
        criteria: MessageFilterCriteria,
        scanLimit: Int = DEFAULT_SCAN_LIMIT,
        chunkSize: Int = SCAN_CHUNK_SIZE,
    ): Flow<ScanProgressUpdate> = flow {
        if (criteria is MessageFilterCriteria.None) {
            emit(
                ScanProgressUpdate(
                    scannedCount = 0,
                    matches = emptyList(),
                    limitReached = false,
                    isRunning = false,
                )
            )
            return@flow
        }

        var currentOffset = startOffset
        var totalScanned = 0
        val accumulatedMatches = mutableListOf<StreamMessage>()
        var limitReached = false

        while (totalScanned < scanLimit && currentCoroutineContext().isActive) {
            val toRead = minOf(chunkSize, scanLimit - totalScanned)
            when (val result = reader.readPage(vhost, stream, BrowseStart.Offset(currentOffset), toRead)) {
                is BrowseResult.Success -> {
                    if (result.messages.isEmpty()) {
                        break
                    }
                    val chunkMatches = result.messages.filter { matchesCriteria(it, criteria) }
                    accumulatedMatches.addAll(chunkMatches)
                    totalScanned += result.messages.size
                    currentOffset = result.messages.last().offset + 1L

                    emit(
                        ScanProgressUpdate(
                            scannedCount = totalScanned,
                            matches = accumulatedMatches.toList(),
                            limitReached = false,
                            isRunning = true,
                            notice = "Scanned $totalScanned messages",
                        )
                    )

                    if (result.messages.size < toRead || currentOffset > result.streamLastOffset) {
                        break
                    }
                }
                is BrowseResult.OutsideRange -> {
                    if (result.validRange.first > currentOffset && result.validRange.first <= result.validRange.last) {
                        currentOffset = result.validRange.first
                        continue
                    }
                    break
                }
                is BrowseResult.Empty,
                is BrowseResult.NoMessagesAtOrAfterTime,
                is BrowseResult.NotFound,
                is BrowseResult.AccessDenied,
                is BrowseResult.Failed -> {
                    break
                }
            }
        }

        if (totalScanned >= scanLimit) {
            limitReached = true
        }

        val finalNotice = if (limitReached) {
            "Scan limit of $scanLimit reached; remaining messages were not searched"
        } else {
            "Scanned $totalScanned messages"
        }

        emit(
            ScanProgressUpdate(
                scannedCount = totalScanned,
                matches = accumulatedMatches.toList(),
                limitReached = limitReached,
                isRunning = false,
                notice = finalNotice,
            )
        )
    }

    /**
     * Evaluates whether [message] matches [criteria].
     */
    fun matchesCriteria(message: StreamMessage, criteria: MessageFilterCriteria): Boolean {
        return when (criteria) {
            is MessageFilterCriteria.None -> true
            is MessageFilterCriteria.Text -> {
                val q = criteria.query
                val text = try {
                    message.payload.decodeToString()
                } catch (_: Exception) {
                    ""
                }
                if (text.contains(q, ignoreCase = true)) {
                    true
                } else {
                    val hex = message.payload.joinToString(" ") { byteToHex(it) }
                    hex.contains(q, ignoreCase = true)
                }
            }
            is MessageFilterCriteria.Property -> {
                criteria.name in message.properties && PropertyValueText.of(message.properties[criteria.name]) == criteria.value
            }
            is MessageFilterCriteria.ApplicationProperty -> {
                criteria.name in message.applicationProperties &&
                    PropertyValueText.of(message.applicationProperties[criteria.name]) == criteria.value
            }
        }
    }
}
