package io.github.almeida96.queuesift

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportsMarkdownTest {
    @TempDir lateinit var directory: Path

    @Test fun `markdown renders link and image shaped evidence as literal text`() {
        val messageId = "![probe](https://tracker.example/pixel)"
        val tenant = "[tenant](https://attacker.example/)"
        val eventId = "[event][reference]"
        val snapshotId = "![snapshot](https://tracker.example/snapshot)"
        val queue = "[queue](https://attacker.example/queue)"
        val detail = "Review ![proof](https://tracker.example/proof)"
        val source = SourceRef("messages.jsonl", "a".repeat(64), 1, "b".repeat(64))
        val decision = Decision(
            "decision", messageId, tenant, eventId, "order.created", "c".repeat(64), "TIMEOUT",
            Outcome.CANDIDATE, listOf(Reason("REVIEW_CANDIDATE", detail, null, listOf(source))),
            source, null, null, null,
        )
        val plan = RecoveryPlan(
            snapshot = SnapshotMeta(snapshotId, queue, "2026-11-04T12:00:00Z", "PARTIAL"),
            snapshotDigest = "a".repeat(64), policyDigest = "b".repeat(64),
            asOf = "2026-11-04T12:05:00Z", planId = "c".repeat(64),
            counts = mapOf("CANDIDATE" to 1, "HOLD" to 0, "EXCLUDE" to 0),
            decisions = listOf(decision), batches = listOf(Batch(1, listOf(messageId))),
            groups = listOf(FailureGroup("TIMEOUT", 1, 1, 0, 0)),
            assumptions = listOf("[operator](https://attacker.example/operator) must review"),
        )

        Reports.write(plan, directory)

        val markdown = Files.readString(directory.resolve("report.md"))
        for (untrusted in listOf(messageId, tenant, eventId, snapshotId, queue, detail, plan.assumptions.single())) {
            assertFalse(markdown.contains(untrusted), "Unescaped markdown evidence: $untrusted")
            assertTrue(markdown.contains(untrusted.replace("[", "\\[").replace("]", "\\]")))
        }
        // Escaping is a presentation concern; machine-readable evidence keeps the exact IDs.
        val persisted = Json.readFile(directory.resolve("plan.json"))
        assertEquals(messageId, persisted["decisions"][0]["messageId"].textValue())
        assertEquals(tenant, persisted["decisions"][0]["tenant"].textValue())
        val manifest = Json.read(Files.readString(directory.resolve("replay-candidates.jsonl")).trim())
        assertEquals(messageId, manifest["messageId"].textValue())
    }
}
