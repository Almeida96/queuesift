package io.github.almeida96.queuesift

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class SqsAdapterTest {
    @TempDir lateinit var root: Path
    private fun options() = mapOf("tenant-pointer" to "/tenant", "event-id-pointer" to "/eventId", "event-type-pointer" to "/eventType", "occurred-at-pointer" to "/occurredAt", "expires-at-pointer" to "/expiresAt", "failure-code" to "DEPENDENCY_TIMEOUT")
    private fun fixture(): Path = root.resolve("sqs.json").also { path -> SqsAdapterTest::class.java.getResourceAsStream("/fulfillment/sqs-export.json").use { Files.copy(requireNotNull(it), path) } }

    @Test fun `saved SQS response normalizes into importable evidence without receipt handles`() {
        val input = fixture()
        val output = root.resolve("normalized")
        val result = SqsAdapter.convert(input, options(), output)
        assertEquals(Json.hash(Files.readAllBytes(input)), result["sourceSha256"])
        val normalized = Files.readString(output.resolve("messages.jsonl"))
        assertFalse(normalized.contains("ReceiptHandle"))
        assertFalse(normalized.contains("SentTimestamp"))
        Ledger(root.resolve("evidence.db"), true).use {
            val imported = it.importSnapshot(SnapshotMeta("sqs", "orders.dead", "2026-11-04T12:00:00Z", "PARTIAL"), output.resolve("messages.jsonl"))
            assertEquals(1, imported.messages)
            val message = it.load("sqs").messages.single()
            assertEquals("sqs-event-1", message.eventId)
            assertEquals(3, message.attempts)
            assertEquals("2026-11-04T10:00:00Z", message.occurredAt)
        }
    }

    @Test fun `invalid pointer and leap second cannot silently change input meaning`() {
        val input = fixture()
        assertFailsWith<IllegalArgumentException> { SqsAdapter.convert(input, options() + ("tenant-pointer" to "/bad~2pointer"), root.resolve("bad-pointer")) }
        assertFalse(Files.exists(root.resolve("bad-pointer")))
        Files.writeString(input, Files.readString(input).replace("2026-11-04T10:00:00Z", "2026-11-04T10:00:60Z"))
        assertFailsWith<IllegalArgumentException> { SqsAdapter.convert(input, options(), root.resolve("leap")) }
        assertFalse(Files.exists(root.resolve("leap")))
    }

    @Test fun `malformed bodies and absent receive count fail before any output`() {
        val input = fixture()
        val source = Json.readFile(input)
        (source.path("Messages").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("Body", "not-json")
        Files.writeString(input, Json.text(source))
        assertFails { SqsAdapter.convert(input, options(), root.resolve("bad")) }
        assertFalse(Files.exists(root.resolve("bad")))
        (source.path("Messages").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("Body", "{}")
        (source.path("Messages").get(0).path("Attributes") as com.fasterxml.jackson.databind.node.ObjectNode).remove("ApproximateReceiveCount")
        Files.writeString(input, Json.text(source))
        assertFailsWith<IllegalArgumentException> { SqsAdapter.convert(input, options(), root.resolve("no-count")) }
    }

    @Test fun `adapter and importer reject the same invisible identifier characters`() {
        val input = fixture()
        Files.writeString(input, Files.readString(input).replace("north", "north\u202E"))
        assertFailsWith<IllegalArgumentException> { SqsAdapter.convert(input, options(), root.resolve("bad-identifier")) }
        assertFalse(Files.exists(root.resolve("bad-identifier")))
    }
}
