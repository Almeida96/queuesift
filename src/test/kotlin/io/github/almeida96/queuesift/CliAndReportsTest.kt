package io.github.almeida96.queuesift

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class CliAndReportsTest {
    @TempDir lateinit var root: Path
    private fun cli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli.run(arrayOf(*args), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test fun `packaged demo validates outcomes and emits payload free review artifacts`() {
        val directory = root.resolve("demo")
        val result = cli("demo", "--out", directory.toString())
        assertEquals(0, result.first, result.third)
        val summary = Json.read(result.second)
        assertEquals(3, summary.at("/recovery/counts/CANDIDATE").intValue())
        assertEquals(11, summary.at("/recovery/counts/HOLD").intValue())
        assertEquals(2, summary.at("/recovery/counts/EXCLUDE").intValue())
        for (filename in listOf("report.html", "report.md", "plan.json", "replay-candidates.jsonl")) {
            val content = Files.readString(directory.resolve("recovery/$filename"))
            assertFalse(content.contains("PRIVATE_PAYLOAD_CANARY"))
            assertFalse(content.contains("debugToken"))
        }
        val manifest = Files.readAllLines(directory.resolve("recovery/replay-candidates.jsonl")).map(Json::read)
        assertEquals(setOf("m01", "m13", "m14"), manifest.map { it.path("messageId").asText() }.toSet())
        assertTrue(manifest.all { it.path("requiresOperatorReview").booleanValue() })
        assertTrue(manifest.all { it.path("planId") == summary.at("/recovery/planId") })
        val original = Files.readAllBytes(directory.resolve("evidence.db"))
        assertEquals(1, cli("demo", "--out", directory.toString()).first)
        assertContentEquals(original, Files.readAllBytes(directory.resolve("evidence.db")))
    }

    @Test fun `gate exit differs from normal analysis without altering evidence`() {
        val demo = root.resolve("demo")
        assertEquals(0, cli("demo", "--out", demo.toString()).first)
        val db = demo.resolve("evidence.db")
        val before = Files.readAllBytes(db)
        val args = arrayOf("analyze", "--db", db.toString(), "--snapshot", "fulfillment", "--policy", demo.resolve("policy.json").toString(), "--as-of", Demo.AS_OF)
        val normal = cli(*args, "--out", root.resolve("normal").toString())
        val gated = cli(*args, "--out", root.resolve("gated").toString(), "--gate")
        assertEquals(0, normal.first, normal.third)
        assertEquals(2, gated.first, gated.third)
        assertEquals(normal.second, gated.second)
        assertContentEquals(Files.readAllBytes(root.resolve("normal/plan.json")), Files.readAllBytes(root.resolve("gated/plan.json")))
        assertContentEquals(before, Files.readAllBytes(db))
    }

    @Test fun `unknown duplicate and valueless options fail clearly`() {
        for (args in listOf(arrayOf("snapshots", "--wat", "x"), arrayOf("demo", "--out"), arrayOf("demo", "--out", "x", "--out", "y"), arrayOf("no-such-command"))) {
            val result = cli(*args)
            assertEquals(1, result.first)
            assertTrue(result.third.startsWith("queuesift:"))
        }
    }

    @Test fun `HTML escapes untrusted identifiers and has no active external content`() {
        val payload = Json.read("{\"secret\":\"should-not-appear\"}")
        val source = SourceRef("<img src=x onerror=alert(1)>.jsonl", "a".repeat(64), 1, "b".repeat(64))
        val message = Message("<script>alert(1)</script>", "north", "evt", "event", "2026-11-04T10:00:00Z", null, "TIMEOUT", 1, null, null, payload, Json.contentHash("event", payload), source)
        val snapshot = Snapshot(SnapshotMeta("sample", "queue", "2026-11-04T12:00:00Z", "PARTIAL"), "c".repeat(64), listOf(message), emptyList(), emptyList())
        val policy = PolicyReader.parse(Json.read("""{"schemaVersion":1,"consumerIdempotent":true,"resolvedFailureCodes":["TIMEOUT"],"maxAttempts":6,"maxAgeSeconds":86400,"maxSnapshotAgeSeconds":3600,"batchSize":2,"ordering":"UNORDERED","payloadRules":[]}"""))
        val plan = RecoveryEngine.analyze(snapshot, policy, Demo.AS_OF)
        Reports.write(plan, root)
        val html = Files.readString(root.resolve("report.html"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("should-not-appear"))
        assertTrue(html.contains("Content-Security-Policy"))
        assertFailsWith<IllegalArgumentException> { Reports.write(plan, root) }
    }
}
