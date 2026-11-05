package io.github.almeida96.queuesift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RecoveryEngineTests {
    private val captured = "2026-11-04T12:00:00Z"

    private fun policy(vararg changes: Pair<String, Any>): Policy {
        val node = Json.read("""{"schemaVersion":1,"consumerIdempotent":true,"resolvedFailureCodes":["FIXED"],"maxAttempts":5,"maxAgeSeconds":86400,"maxSnapshotAgeSeconds":3600,"batchSize":2,"ordering":"UNORDERED","payloadRules":[]}""").deepCopy<ObjectNode>()
        changes.forEach { (key, value) -> node.set<JsonNode>(key, if (value is JsonNode) value else Json.mapper.valueToTree(value)) }
        return PolicyReader.parse(node)
    }

    private fun ref(id: String) = SourceRef("events.jsonl", Json.hash("file"), (id.hashCode() and Int.MAX_VALUE) % 100000 + 1, Json.hash(id))
    private fun message(
        id: String, event: String? = id, tenant: String = "tenant", payload: String = """{"orderId":"same-order","amount":12}""",
        type: String = "order.created", code: String = "FIXED", attempts: Int = 3,
        occurred: String? = "2026-11-04T11:00:00Z", expires: String? = null, key: String? = null, sequence: Long? = null,
    ): Message {
        val body = Json.read(payload)
        return Message(id, tenant, event, type, occurred, expires, code, attempts, key, sequence, body, Json.contentHash(type, body), ref(id))
    }

    private fun receipt(m: Message, hash: String = m.contentHash, time: String = "2026-11-04T11:30:00Z") =
        Receipt(m.tenant, m.eventId!!, hash, time, ref("receipt-${m.id}-$hash-$time"))
    private fun checkpoint(key: String = "orders", sequence: Long = 10, time: String = captured, tenant: String = "tenant") =
        Checkpoint(tenant, key, sequence, time, ref("checkpoint-$tenant-$key-$sequence-$time"))
    private fun snapshot(messages: List<Message>, receipts: List<Receipt> = emptyList(), checkpoints: List<Checkpoint> = emptyList()) =
        Snapshot(SnapshotMeta("incident", "orders-dlq", captured, "PARTIAL"), Json.hash("snapshot"), messages, receipts, checkpoints)
    private fun plan(messages: List<Message>, p: Policy = policy(), receipts: List<Receipt> = emptyList(), checkpoints: List<Checkpoint> = emptyList(), time: String = captured) =
        RecoveryEngine.analyze(snapshot(messages, receipts, checkpoints), p, time)
    private fun RecoveryPlan.row(id: String) = decisions.single { it.messageId == id }
    private fun RecoveryPlan.has(id: String, code: String) = row(id).reasons.any { it.code == code }

    @Test fun `identity uses tenant and event ID rather than business key`() {
        val result = plan(listOf(message("a", event = "e1"), message("b", event = "e2"), message("c", event = "e1", tenant = "other")))
        assertEquals(3, result.counts["CANDIDATE"])
        assertTrue(result.decisions.all { it.representative == null })
    }

    @Test fun `same event identity with changed payload or event type holds every copy`() {
        for (other in listOf(message("b", event = "e", payload = """{"amount":13}"""), message("b", event = "e", type = "order.cancelled"))) {
            val result = plan(listOf(message("a", event = "e"), other))
            assertTrue(result.decisions.all { it.outcome == Outcome.HOLD })
            assertTrue(result.decisions.all { it.reasons.any { reason -> reason.code == "IDENTITY_CONTENT_CONFLICT" } })
            assertTrue(result.batches.isEmpty())
        }
    }

    @Test fun `matching receipt excludes and a contradictory receipt takes precedence`() {
        val m = message("a")
        assertEquals(Outcome.EXCLUDE, plan(listOf(m), receipts = listOf(receipt(m))).row("a").outcome)
        val result = plan(listOf(m), receipts = listOf(receipt(m), receipt(m, hash = Json.hash("different"))))
        assertEquals(Outcome.HOLD, result.row("a").outcome)
        assertTrue(result.has("a", "IDENTITY_CONTENT_CONFLICT"))
        val otherTenant = receipt(m).copy(tenant = "other")
        assertEquals(Outcome.CANDIDATE, plan(listOf(m), receipts = listOf(otherTenant)).row("a").outcome)
    }

    @Test fun `canonical identical copies collapse despite delivery fact differences`() {
        val a = message("a", event = "event", payload = """{"amount":1,"label":"ok"}""", attempts = 3)
        val b = message("b", event = "event", payload = """{"label":"ok","amount":1.0}""", attempts = 4, code = "ALSO_FIXED")
        val result = plan(listOf(b, a), policy("resolvedFailureCodes" to listOf("FIXED", "ALSO_FIXED")))
        assertEquals(Outcome.CANDIDATE, result.row("a").outcome)
        assertEquals(Outcome.EXCLUDE, result.row("b").outcome)
        assertEquals("a", result.row("b").representative)
        assertEquals(listOf("a"), result.batches.flatMap { it.messageIds })
    }

    @Test fun `unresolved or excessive attempts on a copy block its representative`() {
        for (copy in listOf(message("b", event = "event", code = "UNRESOLVED"), message("b", event = "event", attempts = 6))) {
            val result = plan(listOf(message("a", event = "event"), copy))
            assertEquals(Outcome.HOLD, result.row("a").outcome)
            assertEquals(Outcome.EXCLUDE, result.row("b").outcome)
            assertTrue(result.row("a").reasons.any { copy.source in it.evidence })
            assertTrue(result.batches.isEmpty())
        }
    }

    @Test fun `semantic metadata differences hold the identity instead of choosing a clean copy`() {
        val a = message("a", event = "event", key = "orders", sequence = 11)
        for (b in listOf(a.copy(id = "b", source = ref("b"), sequence = 12), a.copy(id = "b", source = ref("b"), expiresAt = captured))) {
            val result = plan(listOf(a, b))
            assertTrue(result.decisions.all { it.outcome == Outcome.HOLD })
            assertTrue(result.decisions.all { it.representative == null })
            assertTrue(result.has("a", "IDENTITY_METADATA_CONFLICT"))
        }
    }

    @Test fun `future event and receipt facts hold only their affected identity`() {
        val a = message("a")
        val result = plan(listOf(a, message("b"), message("c", occurred = "2026-11-04T12:00:01Z")), receipts = listOf(receipt(a, time = "2026-11-04T12:00:01Z")))
        assertEquals(Outcome.HOLD, result.row("a").outcome)
        assertEquals(Outcome.CANDIDATE, result.row("b").outcome)
        assertEquals(Outcome.HOLD, result.row("c").outcome)
        assertTrue(result.has("a", "RECEIPT_TIME_INVALID"))
        assertTrue(result.has("c", "EVENT_TIME_IN_FUTURE"))
    }

    @Test fun `expiry and age compare exact instants and attempts require positive knowledge`() {
        val result = plan(listOf(
            message("age-boundary", attempts = 5),
            message("too-old", occurred = "2026-11-04T10:59:59.999999999Z"),
            message("expired", expires = captured),
            message("unknown-attempts", attempts = 0),
            message("invalid-expiry", expires = "2026-11-04T10:00:00Z"),
        ), policy("maxAgeSeconds" to 3600))
        assertEquals(Outcome.CANDIDATE, result.row("age-boundary").outcome)
        assertTrue(result.has("too-old", "EVENT_TOO_OLD"))
        assertTrue(result.has("expired", "EVENT_EXPIRED"))
        assertTrue(result.has("unknown-attempts", "ATTEMPTS_UNKNOWN_OR_INVALID"))
        assertTrue(result.has("invalid-expiry", "EXPIRY_TIME_INVALID"))
    }

    @Test fun `undeclared idempotency blocks candidates while recorded processed events are excluded`() {
        val a = message("a")
        val result = plan(listOf(a, message("b")), policy("consumerIdempotent" to false), receipts = listOf(receipt(a)))
        assertEquals(Outcome.EXCLUDE, result.row("a").outcome)
        assertEquals(Outcome.HOLD, result.row("b").outcome)
        assertTrue(result.has("b", "IDEMPOTENCY_NOT_DECLARED"))
        assertTrue(result.assumptions.any { "never prove" in it })
        assertTrue(result.assumptions.any { "does not authorize" in it })
    }

    @Test fun `missing identities and invalid hashes cannot become candidates`() {
        val a = message("a", event = null)
        val b = message("b").copy(contentHash = Json.hash("tampered"))
        val result = plan(listOf(a, b))
        assertTrue(result.decisions.all { it.outcome == Outcome.HOLD })
        assertTrue(result.has("a", "IDENTITY_MISSING"))
        assertTrue(result.has("b", "CONTENT_HASH_INVALID"))
    }

    @Test fun `stale snapshots hold all events and evaluation cannot precede capture`() {
        val a = message("a")
        val result = plan(listOf(a, message("b")), receipts = listOf(receipt(a)), time = "2026-11-04T13:00:00.000000001Z")
        assertTrue(result.decisions.all { it.outcome == Outcome.HOLD })
        assertTrue(result.decisions.all { it.reasons.any { reason -> reason.code == "SNAPSHOT_STALE" } })
        assertFailsWith<IllegalArgumentException> { plan(listOf(a), time = "2026-11-04T11:59:59Z") }
        assertFailsWith<IllegalArgumentException> { plan(listOf(a), time = "2026-11-04T12:59:60Z") }
    }

    @Test fun `payload rules handle pointers mathematical integers enums and Unicode lengths`() {
        val rules = Json.read("""[
          {"path":"/a~1b/~0name","type":"string","minLength":1,"maxLength":1,"allowed":["😀"]},
          {"path":"/count","type":"integer","minimum":1,"maximum":2,"allowed":[2]},
          {"path":"/optional","type":"boolean","required":false},
          {"path":"/items","type":"array"},{"path":"/metadata","type":"object"}
        ]""")
        val body = """{"a/b":{"~name":"😀"},"count":2.0,"items":[],"metadata":{}}"""
        assertEquals(Outcome.CANDIDATE, plan(listOf(message("ok", payload = body)), policy("payloadRules" to rules)).row("ok").outcome)
        val nullOptional = message("null", payload = """{"a/b":{"~name":"😀"},"count":2,"items":[],"metadata":{},"optional":null}""")
        assertTrue(plan(listOf(nullOptional), policy("payloadRules" to rules)).has("null", "PAYLOAD_TYPE"))
    }

    @Test fun `payload violations cite policy paths without exposing actual payload values`() {
        val rules = Json.read("""[{"path":"/secret","type":"string","maxLength":2,"allowed":["ok"]},{"path":"/n","type":"number","minimum":1},{"path":"/required","type":"boolean"}]""")
        val result = plan(listOf(message("a", payload = """{"secret":"PRIVATE_PAYLOAD_CANARY","n":0}""")), policy("payloadRules" to rules))
        assertTrue(result.has("a", "PAYLOAD_ENUM"))
        assertTrue(result.has("a", "PAYLOAD_MAX_LENGTH"))
        assertTrue(result.has("a", "PAYLOAD_MINIMUM"))
        assertTrue(result.has("a", "PAYLOAD_REQUIRED"))
        assertTrue(result.row("a").reasons.filter { it.code.startsWith("PAYLOAD_") }.all { it.policyPath?.startsWith("/payloadRules/") == true })
        assertFalse(Json.text(result).contains("PRIVATE_PAYLOAD_CANARY"))
    }

    @Test fun `ordered holds form a barrier to later candidates`() {
        val messages = listOf(message("a", key = "orders", sequence = 11), message("b", key = "orders", sequence = 12, code = "OPEN"), message("c", key = "orders", sequence = 13))
        val result = plan(messages, policy("ordering" to "PER_KEY_SEQUENCE"), checkpoints = listOf(checkpoint()))
        assertEquals(Outcome.CANDIDATE, result.row("a").outcome)
        assertEquals(Outcome.HOLD, result.row("b").outcome)
        assertTrue(result.has("c", "ORDERING_BLOCKED"))
        assertEquals(listOf("a"), result.batches.flatMap { it.messageIds })
    }

    @Test fun `ordered gaps never jump over absent sequence numbers`() {
        val result = plan(listOf(message("a", key = "orders", sequence = 11), message("b", key = "orders", sequence = 13), message("c", key = "orders", sequence = 14)),
            policy("ordering" to "PER_KEY_SEQUENCE"), checkpoints = listOf(checkpoint()))
        assertEquals(Outcome.CANDIDATE, result.row("a").outcome)
        assertTrue(result.has("b", "SEQUENCE_GAP"))
        assertTrue(result.has("c", "ORDERING_BLOCKED"))
    }

    @Test fun `matching processed receipt bridges a known sequence but is not batched`() {
        val b = message("b", key = "orders", sequence = 12)
        val result = plan(listOf(message("a", key = "orders", sequence = 11), b, message("c", key = "orders", sequence = 13)),
            policy("ordering" to "PER_KEY_SEQUENCE"), receipts = listOf(receipt(b)), checkpoints = listOf(checkpoint()))
        assertEquals(Outcome.EXCLUDE, result.row("b").outcome)
        assertEquals(listOf(listOf("a"), listOf("c")), result.batches.map { it.messageIds })
    }

    @Test fun `checkpoint prefix alone does not prove event processing`() {
        val a = message("a", key = "orders", sequence = 10)
        val p = policy("ordering" to "PER_KEY_SEQUENCE")
        val result = plan(listOf(a, message("b", key = "orders", sequence = 11)), p, checkpoints = listOf(checkpoint()))
        assertTrue(result.has("a", "CHECKPOINT_PREFIX_UNVERIFIED"))
        assertEquals(Outcome.CANDIDATE, result.row("b").outcome)
        assertEquals(Outcome.EXCLUDE, plan(listOf(a), p, receipts = listOf(receipt(a)), checkpoints = listOf(checkpoint())).row("a").outcome)
    }

    @Test fun `distinct identities sharing a sequence hold even with a matching receipt`() {
        val a = message("a", key = "orders", sequence = 11)
        val result = plan(listOf(a, message("b", key = "orders", sequence = 11), message("c", key = "orders", sequence = 12)),
            policy("ordering" to "PER_KEY_SEQUENCE"), receipts = listOf(receipt(a)), checkpoints = listOf(checkpoint()))
        assertTrue(result.has("a", "SEQUENCE_REUSED"))
        assertEquals(Outcome.HOLD, result.row("a").outcome)
        assertTrue(result.has("b", "SEQUENCE_REUSED"))
        assertTrue(result.has("c", "ORDERING_BLOCKED"))
    }

    @Test fun `missing identity cannot collide with a literal event ID at the same sequence`() {
        val result = plan(listOf(message("m1", event = null, key = "orders", sequence = 11),
            message("m2", event = "missing:m1", key = "orders", sequence = 11), message("later", key = "orders", sequence = 12)),
            policy("ordering" to "PER_KEY_SEQUENCE"), checkpoints = listOf(checkpoint()))
        assertTrue(result.decisions.all { it.outcome == Outcome.HOLD })
        assertTrue(result.has("m2", "SEQUENCE_REUSED"))
        assertTrue(result.has("later", "ORDERING_BLOCKED"))
        assertTrue(result.batches.isEmpty())
    }

    @Test fun `missing stale future and conflicting checkpoints hold their stream`() {
        val m = message("a", key = "orders", sequence = 11)
        val p = policy("ordering" to "PER_KEY_SEQUENCE")
        val cases = listOf(
            emptyList<Checkpoint>() to "CHECKPOINT_MISSING",
            listOf(checkpoint(time = "2026-11-04T10:00:00Z")) to "CHECKPOINT_STALE",
            listOf(checkpoint(time = "2026-11-04T12:00:01Z")) to "CHECKPOINT_INVALID",
            listOf(checkpoint(), checkpoint(sequence = 9)) to "CHECKPOINT_CONFLICT",
        )
        cases.forEach { (facts, reason) -> assertTrue(plan(listOf(m), p, checkpoints = facts).has("a", reason), reason) }
        val isolated = plan(listOf(m, message("b", key = "other", sequence = 11)), p,
            checkpoints = listOf(checkpoint(time = "2026-11-04T12:00:01Z"), checkpoint(key = "other")))
        assertEquals(Outcome.CANDIDATE, isolated.row("b").outcome)
    }

    @Test fun `unknown position blocks other candidates in its known ordering key`() {
        val result = plan(listOf(message("a", key = "orders"), message("b", key = "orders", sequence = 11)),
            policy("ordering" to "PER_KEY_SEQUENCE"), checkpoints = listOf(checkpoint()))
        assertTrue(result.has("a", "ORDERING_METADATA_MISSING"))
        assertTrue(result.has("b", "ORDERING_POSITION_UNKNOWN"))
        assertTrue(result.batches.isEmpty())
    }

    @Test fun `ordered batches retain prefixes and include at most one event per tenant key`() {
        val messages = listOf(message("a11", key = "a", sequence = 11), message("a12", key = "a", sequence = 12),
            message("b11", key = "b", sequence = 11), message("b12", key = "b", sequence = 12))
        val result = plan(messages.reversed(), policy("ordering" to "PER_KEY_SEQUENCE"), checkpoints = listOf(checkpoint(key = "a"), checkpoint(key = "b")))
        assertEquals(listOf(listOf("a11", "b11"), listOf("a12", "b12")), result.batches.map { it.messageIds })
        assertTrue(result.batches.all { it.messageIds.size <= 2 })
    }

    @Test fun `plans are deterministic and bind policy time snapshot and review decisions`() {
        val messages = listOf(message("a"), message("b"), message("c"))
        val p = policy()
        val first = plan(messages, p)
        assertEquals(Json.hashValue(listOf("QueueSift-plan-v1", first.toolVersion, first.snapshotDigest, first.policyDigest,
            first.asOf, first.decisions, first.batches)), first.planId)
        assertEquals(Json.text(first), Json.text(plan(messages.reversed(), p)))
        assertNotEquals(first.planId, plan(messages, policy("batchSize" to 3)).planId)
        assertNotEquals(first.planId, plan(messages, p, time = "2026-11-04T12:00:01Z").planId)
        assertNotEquals(first.planId, RecoveryEngine.analyze(snapshot(messages).copy(digest = Json.hash("other snapshot")), p, captured).planId)
        assertEquals(3, first.groups.single().candidate)
    }

    @Test fun `large identity conflict evidence is bounded and records the original count`() {
        val messages = (1..200).map { message("m%03d".format(it), event = "event", payload = """{"amount":$it}""") }
        val result = plan(messages)
        assertTrue(result.decisions.all { it.outcome == Outcome.HOLD })
        assertTrue(result.decisions.flatMap { it.reasons }.all { it.evidence.size <= 16 })
        assertTrue(result.decisions.all { row -> row.reasons.any { it.code == "IDENTITY_CONTENT_CONFLICT" && "200" in it.detail } })
    }

    @Test fun `reason compaction retains held status and explicitly counts omitted reasons`() {
        val rules = Json.mapper.createArrayNode().also { array ->
            repeat(64) { array.add(Json.mapper.createObjectNode().put("path", "/field$it").put("type", "string")) }
        }
        val result = plan(listOf(message("a", payload = "{}")), policy("payloadRules" to rules))
        assertEquals(Outcome.HOLD, result.row("a").outcome)
        assertEquals(32, result.row("a").reasons.size)
        val summary = result.row("a").reasons.single { it.code == "ADDITIONAL_REASONS_OMITTED" }
        assertTrue(summary.detail.startsWith("33 additional"))
        assertTrue("64 reasons were evaluated" in summary.detail)
        assertEquals(1, result.counts["HOLD"])
        assertTrue(result.batches.isEmpty())
    }
}
