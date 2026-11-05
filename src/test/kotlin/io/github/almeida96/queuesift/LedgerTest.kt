package io.github.almeida96.queuesift

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerTest {
    @TempDir lateinit var directory: Path
    private val meta = SnapshotMeta("sample", "billing-dead-letter", "2026-01-01T00:00:00Z", "PARTIAL")
    private fun database() = directory.resolve("ledger.sqlite")
    private fun message(id: String = "m1") = """{"id":"$id","tenant":"t","eventId":"event-1","eventType":"invoice.created","failureCode":"TIMEOUT","attempts":1,"payload":{"amount":12}}"""
    private fun file(name: String, content: String): Path = directory.resolve(name).also { Files.writeString(it, content) }
    private fun sql(statement: String) = DriverManager.getConnection("jdbc:sqlite:${database().toUri()}").use { connection -> connection.createStatement().use { it.execute(statement) } }
    private fun count(table: String): Int = DriverManager.getConnection("jdbc:sqlite:${database().toUri()}").use { connection -> connection.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM $table").use { it.next(); it.getInt(1) } } }

    @Test fun `import is idempotent and retains exact file and line provenance`() {
        val first = message("m1")
        val second = message("m2")
        val raw = "$first\r\n$second\r\n"
        val source = file("messages.jsonl", raw)
        Ledger(database(), true).use { ledger ->
            val imported = ledger.importSnapshot(meta, source)
            assertTrue(imported.inserted)
            assertEquals(2, imported.messages)
            val again = ledger.importSnapshot(meta, source)
            assertFalse(again.inserted)
            assertEquals(imported.digest, again.digest)
            val copied = file("copy.jsonl", raw)
            assertFalse(ledger.importSnapshot(meta, copied).inserted)
            val rows = ledger.load(meta.id).messages
            assertEquals(listOf(1, 2), rows.map { it.source.line })
            assertEquals(Json.hash(raw), rows[0].source.sha256)
            assertEquals(Json.hash(first), rows[0].source.lineSha256)
            assertEquals(Json.hash(second), rows[1].source.lineSha256)
            assertEquals("messages.jsonl", rows[0].source.file)
            // Two deliveries of the same business identity remain valid evidence.
            assertEquals(rows[0].eventId, rows[1].eventId)
        }
    }

    @Test fun `reusing id with different bytes metadata or optional sources fails`() {
        val source = file("messages.jsonl", message() + "\n")
        Ledger(database(), true).use { ledger ->
            val first = ledger.importSnapshot(meta, source)
            assertFailsWith<IllegalArgumentException> { ledger.importSnapshot(meta.copy(queue = "other"), source) }
            val empty = file("receipts.jsonl", "")
            assertFailsWith<IllegalArgumentException> { ledger.importSnapshot(meta, source, empty) }
            Files.writeString(source, message() + "\r\n")
            assertFailsWith<IllegalArgumentException> { ledger.importSnapshot(meta, source) }
            assertEquals(first.digest, ledger.load(meta.id).digest)
            assertEquals(1, ledger.snapshots().size)
        }
    }

    @Test fun `failure after a flushed JDBC batch rolls back every source and record`() {
        val source = file("messages.jsonl", (1..501).joinToString("\n") { message("m$it") })
        val receipts = file("receipts.jsonl", "{\"tenant\":\"missing-required-fields\"}\n")
        Ledger(database(), true).use { ledger ->
            assertFailsWith<IllegalArgumentException> { ledger.importSnapshot(meta, source, receipts) }
            assertEquals(0, ledger.snapshots().size)
            assertEquals(0, count("records"))
            assertEquals(0, count("sources"))
            assertTrue(ledger.importSnapshot(meta, source).inserted)
            assertEquals(501, ledger.load(meta.id).messages.size)
        }
    }

    @Test fun `duplicate transport id is rejected without partial state`() {
        val source = file("messages.jsonl", message() + "\n" + message())
        Ledger(database(), true).use { ledger ->
            assertFailsWith<IllegalArgumentException> { ledger.importSnapshot(meta, source) }
            assertEquals(emptyList(), ledger.snapshots())
        }
    }

    @Test fun `strict JSON UTF8 and blank lines cannot enter the ledger`() {
        val source = file("messages.jsonl", "")
        val badInputs = listOf(
            (message() + "\n\n").toByteArray(),
            "   \n".toByteArray(),
            message().replace("\"attempts\":1", "\"attempts\":1,\"attempts\":2").toByteArray(),
            (message() + " {}").toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28),
            ("{\"x\":" + "[".repeat(41) + "0" + "]".repeat(41) + "}").toByteArray(),
        )
        Ledger(database(), true).use { ledger ->
            for (bad in badInputs) {
                Files.write(source, bad)
                assertFails { ledger.importSnapshot(meta, source) }
                assertTrue(ledger.snapshots().isEmpty())
            }
        }
    }

    @Test fun `line row and aggregate byte limits are enforced atomically`() {
        val source = file("messages.jsonl", "x".repeat(Ledger.MAX_LINE_BYTES + 1))
        Ledger(database(), true).use { ledger ->
            assertFailsWith<IllegalArgumentException> { ledger.importSnapshot(meta, source) }
            Files.newBufferedWriter(source).use { out -> repeat(Ledger.MAX_ROWS + 1) { out.append(message("m$it")).append('\n') } }
            assertFailsWith<IllegalArgumentException> { ledger.importSnapshot(meta, source) }
            assertEquals(0, count("records"))
            val payload = "x".repeat(60_000)
            Files.newBufferedWriter(source).use { out -> repeat(560) { out.append(message("large$it").replace("{\"amount\":12}", "\"$payload\"")).append('\n') } }
            assertTrue(Files.size(source) > Ledger.MAX_IMPORT_BYTES)
            assertFailsWith<IllegalArgumentException> { ledger.importSnapshot(meta, source) }
            assertEquals(0, count("records"))
            assertTrue(ledger.snapshots().isEmpty())
        }
    }

    @Test fun `readonly access requires an existing ledger and leaves its bytes unchanged`() {
        assertFailsWith<IllegalArgumentException> { Ledger(database()).close() }
        assertFalse(Files.exists(database()))
        val source = file("messages.jsonl", message())
        Ledger(database(), true).use { it.importSnapshot(meta, source) }
        val before = Files.readAllBytes(database())
        Ledger(database()).use { ledger ->
            assertEquals(meta, ledger.load(meta.id).meta)
            assertEquals(1, ledger.snapshots().size)
            assertFailsWith<IllegalStateException> { ledger.importSnapshot(meta, source) }
        }
        assertTrue(before.contentEquals(Files.readAllBytes(database())))
        assertFalse(Files.exists(directory.resolve("ledger.sqlite-wal")))
        assertFalse(Files.exists(directory.resolve("ledger.sqlite-shm")))
    }

    @Test fun `foreign databases and unknown schema versions are never modified`() {
        sql("CREATE TABLE unrelated(value TEXT)")
        val before = Files.readAllBytes(database())
        assertFailsWith<IllegalArgumentException> { Ledger(database(), true).close() }
        assertTrue(before.contentEquals(Files.readAllBytes(database())))
        Files.delete(database())
        Ledger(database(), true).close()
        sql("PRAGMA user_version=999")
        val future = Files.readAllBytes(database())
        assertFailsWith<IllegalArgumentException> { Ledger(database()).close() }
        assertFailsWith<IllegalArgumentException> { Ledger(database(), true).close() }
        assertTrue(future.contentEquals(Files.readAllBytes(database())))
    }

    @Test fun `snapshots isolate queues and their processing evidence`() {
        val source = file("messages.jsonl", message())
        val receipts = file("receipts.jsonl", """{"tenant":"t","eventId":"event-1","contentHash":"${Json.contentHash("invoice.created", Json.read("{\"amount\":12}"))}","processedAt":"2026-01-01T00:00:00Z"}""")
        val checkpoints = file("checkpoints.jsonl", """{"tenant":"t","orderingKey":"invoice","sequence":5,"asOf":"2026-01-01T00:00:00Z"}""")
        Ledger(database(), true).use { ledger ->
            ledger.importSnapshot(meta, source, receipts, checkpoints)
            ledger.importSnapshot(meta.copy(id = "other", queue = "other-queue"), source)
            assertEquals(1, ledger.load(meta.id).receipts.size)
            assertEquals(1, ledger.load(meta.id).checkpoints.size)
            assertTrue(ledger.load("other").receipts.isEmpty())
            assertTrue(ledger.load("other").checkpoints.isEmpty())
            assertEquals(setOf("billing-dead-letter", "other-queue"), ledger.snapshots().map { it["queue"] }.toSet())
        }
    }

    @Test fun `committed evidence is sealed against SQL updates deletes and appends`() {
        val source = file("messages.jsonl", message())
        Ledger(database(), true).use { it.importSnapshot(meta, source) }
        for (statement in listOf(
            "UPDATE snapshots SET queue='other'",
            "DELETE FROM records",
            "UPDATE sources SET file='changed.jsonl'",
            "INSERT INTO records(snapshot_id,kind,line,line_sha256,transport_id,record_json) VALUES('sample','messages',2,'${"a".repeat(64)}','m2','{}')",
        )) assertFailsWith<SQLException> { sql(statement) }
        Ledger(database()).use { assertEquals(1, it.load(meta.id).messages.size) }
    }

    @Test fun `load verifies stored raw line bytes against their provenance hash`() {
        val source = file("messages.jsonl", "  " + message().replace("12", "12.00") + "  \r\n")
        Ledger(database(), true).use {
            it.importSnapshot(meta, source)
            assertEquals(1, it.load(meta.id).messages.size)
        }
        // Simulate external corruption after deliberately bypassing local immutability controls.
        sql("DROP TRIGGER immutable_records_update")
        sql("UPDATE records SET record_json=replace(record_json,'12.00','13.00')")
        Ledger(database()).use { ledger ->
            val error = assertFailsWith<IllegalArgumentException> { ledger.load(meta.id) }
            assertTrue(error.message.orEmpty().contains("source line digest"))
        }
    }
}
