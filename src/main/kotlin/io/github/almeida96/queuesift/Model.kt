package io.github.almeida96.queuesift

import com.fasterxml.jackson.databind.JsonNode

data class SourceRef(val file: String, val sha256: String, val line: Int, val lineSha256: String)
data class SnapshotMeta(val id: String, val queue: String, val capturedAt: String, val coverage: String)
data class Message(
    val id: String, val tenant: String, val eventId: String?, val eventType: String,
    val occurredAt: String?, val expiresAt: String?, val failureCode: String,
    val attempts: Int, val orderingKey: String?, val sequence: Long?,
    val payload: JsonNode, val contentHash: String, val source: SourceRef,
)
data class Receipt(val tenant: String, val eventId: String, val contentHash: String, val processedAt: String, val source: SourceRef)
data class Checkpoint(val tenant: String, val orderingKey: String, val sequence: Long, val asOf: String, val source: SourceRef)
data class Snapshot(val meta: SnapshotMeta, val digest: String, val messages: List<Message>, val receipts: List<Receipt>, val checkpoints: List<Checkpoint>)
data class ImportResult(val snapshot: String, val inserted: Boolean, val digest: String, val messages: Int, val receipts: Int, val checkpoints: Int)
data class FieldRule(val path: String, val type: String, val required: Boolean = true, val allowed: List<JsonNode>? = null, val minimum: java.math.BigDecimal? = null, val maximum: java.math.BigDecimal? = null, val minLength: Int? = null, val maxLength: Int? = null)
data class Policy(
    val schemaVersion: Int = 1, val consumerIdempotent: Boolean,
    val resolvedFailureCodes: List<String>, val maxAttempts: Int, val maxAgeSeconds: Long,
    val maxSnapshotAgeSeconds: Long, val batchSize: Int, val ordering: String,
    val payloadRules: List<FieldRule>, val digest: String,
)
enum class Outcome { CANDIDATE, HOLD, EXCLUDE }
data class Reason(val code: String, val detail: String, val policyPath: String?, val evidence: List<SourceRef> = emptyList())
data class Decision(
    val id: String, val messageId: String, val tenant: String, val eventId: String?, val eventType: String,
    val contentHash: String, val failureCode: String, val outcome: Outcome,
    val reasons: List<Reason>, val source: SourceRef, val representative: String?,
    val orderingKey: String?, val sequence: Long?,
)
data class Batch(val number: Int, val messageIds: List<String>)
data class FailureGroup(val code: String, val total: Int, val candidate: Int, val hold: Int, val exclude: Int)
data class RecoveryPlan(
    val schemaVersion: Int = 1, val toolVersion: String = "0.1.0", val snapshot: SnapshotMeta,
    val snapshotDigest: String, val policyDigest: String, val asOf: String, val planId: String,
    val counts: Map<String, Int>, val decisions: List<Decision>, val batches: List<Batch>,
    val groups: List<FailureGroup>, val assumptions: List<String>,
)
