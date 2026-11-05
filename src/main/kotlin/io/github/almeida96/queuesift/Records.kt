package io.github.almeida96.queuesift

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Strict normalization for the version-one JSONL interchange format. */
object Records {
    const val MAX_SAFE_SEQUENCE: Long = 9_007_199_254_740_991L
    private val messageFields = setOf("id", "tenant", "eventId", "eventType", "occurredAt", "expiresAt", "failureCode", "attempts", "orderingKey", "sequence", "payload")
    private val receiptFields = setOf("tenant", "eventId", "contentHash", "processedAt")
    private val checkpointFields = setOf("tenant", "orderingKey", "sequence", "asOf")
    private val sha256 = Regex("[0-9a-f]{64}")

    fun parseMessage(node: JsonNode, source: SourceRef): Message {
        fields(node, messageFields)
        val eventType = requiredText(node, "eventType")
        require(node.has("payload")) { "payload is required (JSON null is allowed)" }
        val payload = node.get("payload")
        return Message(
            requiredText(node, "id"), requiredText(node, "tenant"), optionalText(node, "eventId"), eventType,
            optionalInstant(node, "occurredAt"), optionalInstant(node, "expiresAt"), requiredText(node, "failureCode"),
            integer(node, "attempts", Int.MAX_VALUE.toLong()).toInt(), optionalText(node, "orderingKey"),
            if (!node.hasNonNull("sequence")) null else integer(node, "sequence", MAX_SAFE_SEQUENCE),
            payload, Json.contentHash(eventType, payload), source,
        )
    }

    fun parseReceipt(node: JsonNode, source: SourceRef): Receipt {
        fields(node, receiptFields)
        val hash = requiredText(node, "contentHash")
        require(sha256.matches(hash)) { "contentHash must be 64 lowercase hexadecimal characters" }
        return Receipt(requiredText(node, "tenant"), requiredText(node, "eventId"), hash, requiredInstant(node, "processedAt"), source)
    }

    fun parseCheckpoint(node: JsonNode, source: SourceRef): Checkpoint {
        fields(node, checkpointFields)
        return Checkpoint(requiredText(node, "tenant"), requiredText(node, "orderingKey"), integer(node, "sequence", MAX_SAFE_SEQUENCE), requiredInstant(node, "asOf"), source)
    }

    fun normalizeMeta(meta: SnapshotMeta): SnapshotMeta {
        text(meta.id, "snapshot id")
        text(meta.queue, "queue")
        require(meta.coverage in setOf("PARTIAL", "DECLARED_COMPLETE")) { "coverage must be PARTIAL or DECLARED_COMPLETE" }
        return meta.copy(capturedAt = instant(meta.capturedAt, "capturedAt"))
    }

    fun instant(value: String, field: String): String {
        text(value, field, 128)
        // Instant.parse accepts leap seconds and silently normalizes them; refuse that ambiguity.
        try {
            val parsed = DateTimeFormatter.ISO_INSTANT.parse(value)
            require(!parsed.query(DateTimeFormatter.parsedLeapSecond())) { "$field must not contain a leap second" }
            return Instant.from(parsed).toString()
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("$field must be an ISO-8601 instant with an offset", error)
        }
    }

    fun isSha256(value: String): Boolean = sha256.matches(value)

    fun text(value: String, field: String, maxLength: Int = 128): String {
        require(value.isNotBlank()) { "$field must be nonblank" }
        require(value.codePointCount(0, value.length) <= maxLength) { "$field exceeds $maxLength characters" }
        require(value.codePoints().allMatch { point ->
            !Character.isISOControl(point) && Character.getType(point) !in setOf(
                Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt(),
            )
        }) { "$field must contain only printable characters" }
        return value
    }

    private fun fields(node: JsonNode, allowed: Set<String>) {
        require(node.isObject) { "Each record must be a JSON object" }
        val unknown = node.fieldNames().asSequence().filter { it !in allowed }.toList()
        require(unknown.isEmpty()) { "Unknown record field: ${unknown.firstOrNull()}" }
    }

    private fun requiredText(node: JsonNode, key: String): String {
        val value = node.get(key)
        require(value != null && value.isTextual) { "$key must be a string" }
        return text(value.textValue(), key)
    }

    private fun optionalText(node: JsonNode, key: String): String? {
        val value = node.get(key) ?: return null
        if (value.isNull) return null
        require(value.isTextual) { "$key must be a string or null" }
        val result = value.textValue()
        if (result.isBlank()) return null
        return text(result, key)
    }

    private fun requiredInstant(node: JsonNode, key: String): String = instant(requiredText(node, key), key)
    private fun optionalInstant(node: JsonNode, key: String): String? = optionalText(node, key)?.let { instant(it, key) }

    private fun integer(node: JsonNode, key: String, maximum: Long): Long {
        val value = node.get(key)
        require(value != null && value.isIntegralNumber && value.canConvertToLong()) { "$key must be an integer" }
        return value.longValue().also { require(it in 0..maximum) { "$key must be between 0 and $maximum" } }
    }
}
