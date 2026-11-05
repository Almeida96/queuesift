package io.github.almeida96.queuesift

import java.time.Duration
import java.time.Instant

object RecoveryEngine {
    private const val VERSION = "0.1.0"
    private data class Identity(val tenant: String, val eventId: String)
    private data class Stream(val tenant: String, val key: String)
    private class Work(val message: Message) {
        var outcome = Outcome.CANDIDATE
        var representative: String? = null
        val reasons = mutableListOf<Reason>()
        var hardHold = false
        fun hold(reason: Reason, hard: Boolean = false) {
            outcome = Outcome.HOLD
            reasons += reason
            hardHold = hardHold || hard
        }
    }

    fun analyze(snapshot: Snapshot, policy: Policy, asOf: String): RecoveryPlan {
        val at = instant(asOf) ?: throw IllegalArgumentException("asOf must be an ISO-8601 instant")
        val captured = instant(snapshot.meta.capturedAt) ?: throw IllegalArgumentException("snapshot capturedAt must be an ISO-8601 instant")
        require(!at.isBefore(captured)) { "asOf must be at or after snapshot capturedAt" }
        require(snapshot.messages.map { it.id }.distinct().size == snapshot.messages.size) { "Snapshot message IDs must be unique" }
        val stale = Duration.between(captured, at) > Duration.ofSeconds(policy.maxSnapshotAgeSeconds)
        val work = snapshot.messages.sortedBy { it.id }.map { Work(it) }
        work.forEach { inspect(it, policy, at, captured, stale) }

        val receiptGroups = snapshot.receipts.groupBy { Identity(it.tenant, it.eventId) }
        val identities = work.filter { !it.message.eventId.isNullOrBlank() && it.message.tenant.isNotBlank() }
            .groupBy { Identity(it.message.tenant, it.message.eventId!!) }
        identities.values.forEach { copies ->
            val first = copies.first().message
            val receipts = receiptGroups[Identity(first.tenant, first.eventId!!)].orEmpty()
            val hashes = copies.map { Json.contentHash(it.message.eventType, it.message.payload) }.toSet()
            val references = copies.map { it.message.source } + receipts.map { it.source }
            if (hashes.size > 1 || receipts.any { it.contentHash !in hashes }) {
                val conflict = reason("IDENTITY_CONTENT_CONFLICT", "The event identity has conflicting content in messages or processing receipts.", null, references)
                copies.forEach { it.hold(conflict, true) }
            }
            val metadata = copies.map { listOf(it.message.occurredAt, it.message.expiresAt, it.message.orderingKey, it.message.sequence) }.toSet()
            if (metadata.size > 1) {
                val conflict = reason("IDENTITY_METADATA_CONFLICT", "Copies of the same event disagree on occurrence, expiry, or ordering metadata.", null, copies.map { copy -> copy.message.source })
                copies.forEach { it.hold(conflict, true) }
            }
            val invalidReceipts = receipts.filter {
                val processed = instant(it.processedAt)
                val occurred = first.occurredAt?.let(::instant)
                processed == null || processed.isAfter(captured) || (occurred != null && processed.isBefore(occurred))
            }
            if (invalidReceipts.isNotEmpty()) {
                val invalid = reason("RECEIPT_TIME_INVALID", "A processing receipt has an invalid time, precedes event occurrence, or is later than snapshot capture.", null,
                    copies.map { copy -> copy.message.source } + invalidReceipts.map { receipt -> receipt.source })
                copies.forEach { it.hold(invalid, true) }
            }
            // A fact conflict on any copy applies to the identity, preventing a clean copy from bypassing it.
            if (copies.any { it.hardHold }) {
                val combined = mergeReasons(copies.flatMap { it.reasons })
                copies.forEach { it.outcome = Outcome.HOLD; it.hardHold = true; it.reasons.clear(); it.reasons += combined }
            } else if (receipts.isNotEmpty()) {
                val processed = reason("RECORDED_PROCESSED", "A matching processing receipt records this event content as processed.", null, references)
                copies.forEach {
                    it.outcome = Outcome.EXCLUDE
                    it.reasons.clear()
                    it.reasons += processed
                }
            } else if (copies.size > 1) {
                val representative = copies.minBy { it.message.id }
                val allChecks = copies.flatMap { it.reasons }
                if (copies.any { it.outcome == Outcome.HOLD }) representative.outcome = Outcome.HOLD
                representative.reasons.clear()
                representative.reasons += allChecks
                copies.filter { it !== representative }.forEach {
                    it.outcome = Outcome.EXCLUDE
                    it.representative = representative.message.id
                    it.reasons.clear()
                    it.reasons += reason("DUPLICATE_OCCURRENCE", "An identical event is represented by message ${representative.message.id}; only its representative can become a candidate.", null,
                        listOf(it.message.source, representative.message.source))
                }
            }
        }

        if (policy.ordering == "PER_KEY_SEQUENCE") enforceOrdering(work, snapshot.checkpoints, policy, at, captured)
        val decisions = work.map { item ->
            val m = item.message
            if (item.outcome == Outcome.CANDIDATE) item.reasons += reason("REVIEW_CANDIDATE", "The declared checks passed; human review is required before any replay.", null, listOf(m.source))
            val reportedReasons = compactReasons(item.reasons)
            // The complete check results have determined outcomes and ordering. Release the larger
            // working lists as the bounded report is built, instead of keeping both until hashing.
            item.reasons.clear()
            Decision(Json.hash(Json.text(listOf("decision", snapshot.digest, m.id))), m.id, m.tenant, m.eventId, m.eventType,
                m.contentHash, m.failureCode, item.outcome, reportedReasons, m.source, item.representative, m.orderingKey, m.sequence)
        }
        val batches = batches(decisions, policy)
        val counts = Outcome.entries.associate { outcome -> outcome.name to decisions.count { it.outcome == outcome } }
        val groups = decisions.groupBy { it.failureCode }.toSortedMap().map { (code, values) ->
            FailureGroup(code, values.size, values.count { it.outcome == Outcome.CANDIDATE }, values.count { it.outcome == Outcome.HOLD }, values.count { it.outcome == Outcome.EXCLUDE })
        }
        val normalizedTime = at.toString()
        // Fixed field order is part of the v1 plan fingerprint. Stream to SHA-256 without creating
        // duplicate JsonNode trees or an additional serialized copy of every reported decision.
        val binding = listOf("QueueSift-plan-v1", VERSION, snapshot.digest, policy.digest, normalizedTime, decisions, batches)
        return RecoveryPlan(snapshot = snapshot.meta, snapshotDigest = snapshot.digest, policyDigest = policy.digest,
            asOf = normalizedTime, planId = Json.hashValue(binding), counts = counts, decisions = decisions,
            batches = batches, groups = groups, assumptions = listOf(
                "This manifest supports explicit human review and does not authorize, send, or execute a replay.",
                "Consumer idempotency and resolved failure codes are declarations in the policy, not independently verified capabilities.",
                "Missing processing receipts never prove that an event was unprocessed; coverage labels are declarations only.",
                "The analysis uses an immutable snapshot at the stated evaluation time; live queue and downstream state may have changed.",
                "Checkpoints describe an ordering prefix and do not prove an individual event's business effect.",
                "Payload rules are limited pointer checks, not a full JSON Schema or a proof of business correctness.",
            ))
    }

    private fun inspect(w: Work, policy: Policy, at: Instant, captured: Instant, stale: Boolean) {
        val m = w.message
        fun hold(code: String, detail: String, path: String? = null, hard: Boolean = false) = w.hold(reason(code, detail, path, listOf(m.source)), hard)
        if (stale) hold("SNAPSHOT_STALE", "The snapshot exceeds the maximum permitted age at evaluation time.", "/maxSnapshotAgeSeconds", true)
        if (m.eventId.isNullOrBlank() || m.tenant.isBlank()) hold("IDENTITY_MISSING", "A nonblank tenant and event ID are required to reconcile identity.", null, true)
        if (m.contentHash != Json.contentHash(m.eventType, m.payload)) hold("CONTENT_HASH_INVALID", "The declared content hash does not match event type and canonical payload.", null, true)
        val occurred = m.occurredAt?.let(::instant)
        if (occurred == null) hold("OCCURRENCE_TIME_MISSING_OR_INVALID", "A valid occurrence timestamp is required for the event age check.", "/maxAgeSeconds", true)
        else if (occurred.isAfter(captured)) hold("EVENT_TIME_IN_FUTURE", "Event occurrence is later than snapshot capture.", "/maxAgeSeconds", true)
        else if (Duration.between(occurred, at) > Duration.ofSeconds(policy.maxAgeSeconds)) hold("EVENT_TOO_OLD", "The event exceeds the configured maximum age.", "/maxAgeSeconds")
        if (m.expiresAt != null) {
            val expiry = instant(m.expiresAt)
            if (expiry == null || (occurred != null && expiry.isBefore(occurred))) hold("EXPIRY_TIME_INVALID", "The expiry timestamp is invalid or precedes event occurrence.", null, true)
            else if (!expiry.isAfter(at)) hold("EVENT_EXPIRED", "Application expiry has been reached at evaluation time.")
        }
        if (m.attempts <= 0) hold("ATTEMPTS_UNKNOWN_OR_INVALID", "A positive known delivery attempt count is required.", "/maxAttempts")
        else if (m.attempts > policy.maxAttempts) hold("ATTEMPTS_EXCEEDED", "Delivery attempts exceed the configured maximum.", "/maxAttempts")
        if (m.failureCode !in policy.resolvedFailureCodes) hold("FAILURE_UNRESOLVED", "The observed failure code is not explicitly declared resolved.", "/resolvedFailureCodes")
        if (!policy.consumerIdempotent) hold("IDEMPOTENCY_NOT_DECLARED", "The policy does not declare an idempotent consumer.", "/consumerIdempotent")
        policy.payloadRules.forEachIndexed { index, rule ->
            val value = m.payload.at(rule.path)
            val path = "/payloadRules/$index"
            if (value.isMissingNode) {
                if (rule.required) hold("PAYLOAD_REQUIRED", "A required payload field is absent at ${rule.path}.", "$path/required")
            } else if (!PolicyReader.matchesType(value, rule.type)) {
                hold("PAYLOAD_TYPE", "The payload field at ${rule.path} does not have type ${rule.type}.", "$path/type")
            } else {
                if (rule.allowed != null && Json.canonical(value) !in rule.allowed.map(Json::canonical)) hold("PAYLOAD_ENUM", "The payload field at ${rule.path} is outside the allowed scalar values.", "$path/allowed")
                if (rule.minimum != null && value.decimalValue() < rule.minimum) hold("PAYLOAD_MINIMUM", "The numeric payload field at ${rule.path} is below its minimum.", "$path/minimum")
                if (rule.maximum != null && value.decimalValue() > rule.maximum) hold("PAYLOAD_MAXIMUM", "The numeric payload field at ${rule.path} exceeds its maximum.", "$path/maximum")
                if (value.isTextual) {
                    val text = value.textValue()
                    val length = text.codePointCount(0, text.length)
                    if (rule.minLength != null && length < rule.minLength) hold("PAYLOAD_MIN_LENGTH", "The string at ${rule.path} has too few Unicode code points.", "$path/minLength")
                    if (rule.maxLength != null && length > rule.maxLength) hold("PAYLOAD_MAX_LENGTH", "The string at ${rule.path} has too many Unicode code points.", "$path/maxLength")
                }
            }
        }
    }

    private fun enforceOrdering(work: List<Work>, checkpoints: List<Checkpoint>, policy: Policy, at: Instant, captured: Instant) {
        val active = work.filter { it.representative == null }
        active.filter { it.message.orderingKey.isNullOrBlank() || it.message.sequence == null || it.message.sequence < 0 }.forEach {
            if (it.outcome != Outcome.EXCLUDE) it.hold(reason("ORDERING_METADATA_MISSING", "Ordered review requires an ordering key and nonnegative sequence.", "/ordering", listOf(it.message.source)))
        }
        val checkpointGroups = checkpoints.groupBy { Stream(it.tenant, it.orderingKey) }
        val unknownPositions = active.filter { it.outcome != Outcome.EXCLUDE && !it.message.orderingKey.isNullOrBlank() && (it.message.sequence == null || it.message.sequence < 0) }
            .groupBy { Stream(it.message.tenant, it.message.orderingKey!!) }
        val streams = active.filter { !it.message.orderingKey.isNullOrBlank() && it.message.sequence != null && it.message.sequence >= 0 }
            .groupBy { Stream(it.message.tenant, it.message.orderingKey!!) }
        streams.forEach { (key, events) ->
            val facts = checkpointGroups[key].orEmpty()
            val factRefs = facts.map { it.source }
            fun blockAll(code: String, detail: String) {
                val shared = reason(code, detail, "/ordering", factRefs)
                events.filter { it.outcome != Outcome.EXCLUDE }.forEach { it.hold(shared) }
            }
            val unknown = unknownPositions[key].orEmpty()
            if (unknown.isNotEmpty()) {
                val shared = reason("ORDERING_POSITION_UNKNOWN", "Another blocked event in this ordering key has no valid sequence, so its position cannot be established.", "/ordering", unknown.map { it.message.source })
                events.filter { it.outcome != Outcome.EXCLUDE }.forEach { it.hold(shared) }
                return@forEach
            }
            if (facts.isEmpty()) { blockAll("CHECKPOINT_MISSING", "No ordering checkpoint is available for this tenant and ordering key."); return@forEach }
            if (facts.map { it.sequence }.distinct().size > 1) { blockAll("CHECKPOINT_CONFLICT", "Checkpoint facts disagree on the acknowledged sequence."); return@forEach }
            if (facts.any { fact -> val time = instant(fact.asOf); fact.sequence < 0 || time == null || time.isAfter(captured) }) {
                blockAll("CHECKPOINT_INVALID", "A checkpoint has an invalid sequence/time or is later than snapshot capture."); return@forEach
            }
            val newest = facts.maxOf { instant(it.asOf)!! }
            if (Duration.between(newest, at) > Duration.ofSeconds(policy.maxSnapshotAgeSeconds)) {
                blockAll("CHECKPOINT_STALE", "The checkpoint exceeds the maximum snapshot age at evaluation time."); return@forEach
            }
            val checkpoint = facts.first().sequence
            val bySequence = events.groupBy { it.message.sequence!! }.toSortedMap()
            bySequence.filterValues { entries -> entries.map {
                val eventId = it.message.eventId?.takeIf(String::isNotBlank)
                // A missing identity is a separate typed alternative, never a reserved user-string prefix.
                eventId to if (eventId == null) it.message.id else null
            }.distinct().size > 1 }
                .values.forEach { reused ->
                    val shared = reason("SEQUENCE_REUSED", "Distinct event identities occupy the same sequence in this ordering key.", "/ordering", reused.map { row -> row.message.source })
                    reused.forEach { it.hold(shared) }
                }
            val prefixReason = reason("CHECKPOINT_PREFIX_UNVERIFIED", "The sequence is at or below the checkpoint, which does not prove this event's business effect.", "/ordering", factRefs)
            bySequence.filterKeys { it <= checkpoint }.values.flatten().filter { it.outcome != Outcome.EXCLUDE }.forEach {
                it.hold(prefixReason)
            }
            var expected = if (checkpoint == Long.MAX_VALUE) null else checkpoint + 1
            var barrier: Reason? = null
            fun blocked(refs: List<SourceRef>) = reason("ORDERING_BLOCKED", "An earlier event in this ordering key is blocked or a sequence gap remains.", "/ordering", refs)
            bySequence.filterKeys { it > checkpoint }.forEach { (sequence, rows) ->
                if (barrier != null || expected == null || sequence != expected) {
                    val refs = if (barrier == null) rows.map { it.message.source } + factRefs else emptyList()
                    val shared = barrier ?: reason("SEQUENCE_GAP", "The next required sequence $expected is absent; later events cannot enter the review batches.", "/ordering", refs)
                    rows.filter { it.outcome != Outcome.EXCLUDE }.forEach { it.hold(shared) }
                    if (barrier == null) barrier = blocked(refs)
                } else if (rows.any { it.outcome == Outcome.HOLD }) {
                    val blockedRefs = rows.filter { it.outcome == Outcome.HOLD }.map { it.message.source }
                    val shared = reason("ORDERING_BLOCKED", "An event at this required sequence is blocked; no unprocessed occurrence at the same position can be a candidate.", "/ordering", blockedRefs)
                    rows.filter { it.outcome != Outcome.EXCLUDE }.forEach { it.hold(shared) }
                    barrier = blocked(blockedRefs)
                } else {
                    expected = if (sequence == Long.MAX_VALUE) null else sequence + 1
                }
            }
        }
    }

    private fun batches(decisions: List<Decision>, policy: Policy): List<Batch> {
        val candidates = decisions.filter { it.outcome == Outcome.CANDIDATE }
        if (policy.ordering == "UNORDERED") return candidates.sortedBy { it.messageId }.chunked(policy.batchSize).mapIndexed { index, rows -> Batch(index + 1, rows.map { it.messageId }) }
        val streams = candidates.groupBy { Stream(it.tenant, it.orderingKey!!) }.entries
            .sortedWith(compareBy({ it.key.tenant }, { it.key.key }))
            .map { ArrayDeque(it.value.sortedWith(compareBy({ it.sequence }, { it.messageId }))) }
        val result = mutableListOf<Batch>()
        while (streams.any { it.isNotEmpty() }) {
            val ids = streams.filter { it.isNotEmpty() }.take(policy.batchSize).map { it.removeFirst().messageId }
            result += Batch(result.size + 1, ids)
        }
        return result
    }

    private fun instant(text: String): Instant? = try {
        Instant.parse(Records.instant(text, "timestamp"))
    } catch (_: IllegalArgumentException) { null } catch (_: java.time.DateTimeException) { null }
    private fun reason(code: String, detail: String, path: String?, refs: List<SourceRef>): Reason {
        val evidence = refs.distinct().sortedWith(compareBy({ it.file }, { it.sha256 }, { it.line }, { it.lineSha256 }))
        return Reason(code, detail + if (evidence.size > 16) " Evidence references: ${evidence.size}; first 16 shown." else "", path, evidence.take(16))
    }
    private fun mergeReasons(reasons: List<Reason>): List<Reason> = reasons.groupBy { Triple(it.code, it.policyPath, it.detail) }.map { (key, matching) ->
        val evidence = matching.flatMap { it.evidence }.distinct().sortedWith(compareBy({ it.file }, { it.sha256 }, { it.line }, { it.lineSha256 }))
        Reason(key.first, key.third + if (evidence.size > 16) " Evidence references: ${evidence.size}; first 16 shown." else "", key.second, evidence.take(16))
    }.sortedWith(compareBy({ it.code }, { it.policyPath ?: "" }, { it.detail }))

    /** Reporting compaction occurs only after all checks and ordering decisions have completed. */
    private fun compactReasons(reasons: List<Reason>): List<Reason> {
        val distinct = mergeReasons(reasons)
        if (distinct.size <= 32) return distinct
        return distinct.take(31) + Reason("ADDITIONAL_REASONS_OMITTED",
            "${distinct.size - 31} additional distinct reasons omitted from this bounded report; ${distinct.size} reasons were evaluated. Outcome and batch eligibility include every check.", null)
    }
}
