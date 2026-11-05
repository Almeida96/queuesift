package io.github.almeida96.queuesift

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RecordsTest {
    private val ref = SourceRef("messages.jsonl", "a".repeat(64), 1, "b".repeat(64))
    private fun message(extra: String = "", payload: String = "null") = Json.read("""{"id":"transport-1","tenant":"tenant-a","eventType":"invoice.created","failureCode":"TIMEOUT","attempts":2,"payload":$payload$extra}""")

    @Test fun `missing identity and time remain absent for policy assessment`() {
        val row = Records.parseMessage(message(), ref)
        assertNull(row.eventId)
        assertNull(row.occurredAt)
        assertNull(row.expiresAt)
        assertNull(row.orderingKey)
        assertNull(row.sequence)
        assertEquals(Json.contentHash("invoice.created", Json.read("null")), row.contentHash)
    }

    @Test fun `blank optional identities normalize to null and offsets normalize to UTC`() {
        val row = Records.parseMessage(message(",\"eventId\":\"  \",\"orderingKey\":null,\"occurredAt\":\"2026-01-02T03:00:00-03:00\",\"expiresAt\":\"2026-01-02T08:00:00+02:00\""), ref)
        assertNull(row.eventId)
        assertNull(row.orderingKey)
        assertEquals("2026-01-02T06:00:00Z", row.occurredAt)
        assertEquals("2026-01-02T06:00:00Z", row.expiresAt)
    }

    @Test fun `missing payload differs from explicit JSON null`() {
        val missing = message().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { remove("payload") }
        assertFailsWith<IllegalArgumentException> { Records.parseMessage(missing, ref) }
        Records.parseMessage(message(payload = "null"), ref)
        Records.parseMessage(message(payload = "[true,2,\"x\"]"), ref)
    }

    @Test fun `unknown fields malformed optional fields and nonprintable identifiers fail`() {
        for (extra in listOf(",\"typo\":1", ",\"eventId\":42", ",\"orderingKey\":false", ",\"occurredAt\":17", ",\"occurredAt\":\"2026-01-02\"", ",\"occurredAt\":\"2016-12-31T23:59:60Z\"")) {
            assertFailsWith<IllegalArgumentException>(extra) { Records.parseMessage(message(extra), ref) }
        }
        for (value in listOf("", " ", "x\ny", "x\u0000y", "x\u202ey", "x".repeat(129))) {
            val row = message().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("id", value)
            assertFailsWith<IllegalArgumentException> { Records.parseMessage(row, ref) }
        }
    }

    @Test fun `attempts and sequence require bounded nonnegative integer tokens`() {
        for (value in listOf("-1", "1.0", "9007199254740992", "9223372036854775808", "\"1\"", "false")) {
            assertFailsWith<IllegalArgumentException>(value) { Records.parseMessage(message(",\"sequence\":$value"), ref) }
        }
        assertEquals(Records.MAX_SAFE_SEQUENCE, Records.parseMessage(message(",\"sequence\":9007199254740991"), ref).sequence)
        for (value in listOf("-1", "1.0", "2147483648", "null", "\"2\"")) {
            val row = message().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().set<com.fasterxml.jackson.databind.JsonNode>("attempts", Json.read(value))
            assertFailsWith<IllegalArgumentException>(value) { Records.parseMessage(row, ref) }
        }
    }

    @Test fun `content hash is canonical and event type contributes`() {
        val first = Records.parseMessage(message(payload = "{\"b\":2,\"a\":1.00}"), ref)
        val same = Records.parseMessage(message(payload = "{\"a\":1,\"b\":2.0}"), ref)
        assertEquals(first.contentHash, same.contentHash)
        val changed = message(payload = "{\"a\":1,\"b\":2}").deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("eventType", "invoice.updated")
        assertNotEquals(first.contentHash, Records.parseMessage(changed, ref).contentHash)
    }

    @Test fun `receipt and checkpoint have strict independent schemas`() {
        val receipt = Json.read("""{"tenant":"a","eventId":"e","contentHash":"${"a".repeat(64)}","processedAt":"2026-01-01T01:00:00+01:00"}""")
        assertEquals("2026-01-01T00:00:00Z", Records.parseReceipt(receipt, ref).processedAt)
        val invalidHash = receipt.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("contentHash", "A".repeat(64))
        assertFailsWith<IllegalArgumentException> { Records.parseReceipt(invalidHash, ref) }
        val checkpoint = Json.read("""{"tenant":"a","orderingKey":"k","sequence":0,"asOf":"2026-01-01T00:00:00Z"}""")
        assertEquals(0L, Records.parseCheckpoint(checkpoint, ref).sequence)
        val wrongSchema = checkpoint.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("eventId", "unexpected")
        assertFailsWith<IllegalArgumentException> { Records.parseCheckpoint(wrongSchema, ref) }
    }

    @Test fun `metadata validates scope coverage and canonical timestamp`() {
        val meta = Records.normalizeMeta(SnapshotMeta("s", "queue-a", "2026-01-01T01:00:00+01:00", "DECLARED_COMPLETE"))
        assertEquals("2026-01-01T00:00:00Z", meta.capturedAt)
        assertFailsWith<IllegalArgumentException> { Records.normalizeMeta(meta.copy(coverage = "COMPLETE")) }
        assertFailsWith<IllegalArgumentException> { Records.normalizeMeta(meta.copy(queue = "")) }
    }
}
