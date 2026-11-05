# Recovery policy and decision semantics

QueueSift evaluates an imported evidence snapshot against a small deterministic policy. It does not implement JSON Schema, connect to a broker, infer application correctness, or execute a replay. A `CANDIDATE` is an event that passed the declared checks and can enter an operator's review; it is not a permission to send it.

## Policy document

Every root field below is required. Unknown fields, duplicate JSON object keys, unsupported values, and missing required fields are errors. Policy files are UTF-8 JSON, limited to 65,536 bytes. The shared JSON reader limits nesting to 40, strings to 65,536 characters, number token length to 256, and decimal precision/absolute scale to 256.

```json
{
  "schemaVersion": 1,
  "consumerIdempotent": true,
  "resolvedFailureCodes": ["SCHEMA_FIXED", "DEPENDENCY_RECOVERED"],
  "maxAttempts": 10,
  "maxAgeSeconds": 86400,
  "maxSnapshotAgeSeconds": 1800,
  "batchSize": 20,
  "ordering": "UNORDERED",
  "payloadRules": [
    {"path": "/orderId", "type": "string", "minLength": 1},
    {"path": "/quantity", "type": "integer", "minimum": 1, "maximum": 100},
    {"path": "/status", "type": "string", "allowed": ["pending"]},
    {"path": "/priority", "type": "boolean", "required": false}
  ]
}
```

| Field | Accepted value and meaning |
|---|---|
| `schemaVersion` | JSON integer `1`. |
| `consumerIdempotent` | Boolean. `true` is an operator assertion about the consumer, not a verified capability. `false` holds otherwise eligible events. |
| `resolvedFailureCodes` | Array of at most 256 distinct nonblank strings, each at most 256 UTF-16 code units. Empty is permitted and resolves no failures. A message's exact, case-sensitive failure code must occur here. |
| `maxAttempts` | JSON integer from 1 through 2,147,483,647. Attempts greater than the maximum hold the event. A message with zero attempts has no known positive delivery count and is also held. |
| `maxAgeSeconds` | JSON integer from 0 through 9,223,372,036,854,775,807. The event's age at evaluation must not exceed this duration. Missing occurrence time holds the event. |
| `maxSnapshotAgeSeconds` | Same integer range as `maxAgeSeconds`. A snapshot older than this duration holds every event. In ordered mode, the newest consistent checkpoint for each key must also satisfy this limit. |
| `batchSize` | JSON integer from 1 through 1,000. Maximum candidates per suggested review batch. |
| `ordering` | Exactly `UNORDERED` or `PER_KEY_SEQUENCE`. |
| `payloadRules` | Array of at most 64 rules with unique JSON Pointer paths. Empty applies no payload checks. |

There are no root-field defaults. JSON values such as `1.0` are not accepted for the integer configuration fields, even when mathematically integral.

## Payload pointer checks

Each rule requires `path` and `type`. Only the fields below are supported. For example, `pattern`, `$ref`, `properties`, `format`, `oneOf`, coercions, remote references, and defaults are unsupported and rejected.

| Rule field | Meaning |
|---|---|
| `path` | JSON Pointer, at most 1,024 UTF-16 code units. The empty string addresses the complete payload; other paths start with `/`. Only `~0` and `~1` escapes are valid. `/a~1b/~0name` addresses `{"a/b":{"~name":...}}`. |
| `type` | One of `string`, `integer`, `number`, `boolean`, `object`, `array`. Type checks do not coerce strings to numbers or booleans. |
| `required` | Optional boolean; defaults to `true`. `false` allows an absent field. An explicitly present JSON `null` still fails every supported type. |
| `allowed` | Optional nonempty array of at most 256 distinct scalar values matching the declared type. Supported only for `string`, `integer`, `number`, and `boolean`. Object, array, and null enum values are rejected. |
| `minimum`, `maximum` | Optional inclusive numeric bounds; only valid with `integer` or `number`. `minimum` cannot exceed `maximum`. Comparisons use decimal values rather than floating-point approximations. |
| `minLength`, `maxLength` | Optional JSON integers from 0 through 2,147,483,647; only valid with `string`. Count Unicode code points, so a supplementary character such as `😀` counts as one. `minLength` cannot exceed `maxLength`. These do not count grapheme clusters. |

For payload values, `integer` means a numeric value with no fractional component, so `2.0` satisfies it. Numeric enum comparison uses canonical decimals: `1` and `1.0` are equivalent and cannot both appear in the same enum. Object member order is irrelevant to content fingerprints; array order and string content remain significant. No Unicode normalization is applied to string values or identity fields.

## Identity, receipts, duplicates, and precedence

Event identity is the exact pair `(tenant, eventId)`. A business key inside a payload, such as `orderId`, is not an event identity; multiple different events can legitimately describe the same order. Content fingerprints bind the event type and canonical payload, not delivery attempt counts or failure codes.

The decision sequence is:

1. Check snapshot freshness, event identity, content-hash consistency, timestamps, application expiry, age, attempts, failure resolution, declared idempotency, and payload rules.
2. Reconcile every occurrence of an event identity with every processing receipt for that identity. Different content hashes, inconsistent occurrence/expiry/ordering metadata, or invalid/future receipt facts hold the entire identity. A clean copy cannot bypass a conflicting one.
3. A matching valid processing receipt excludes the event as `RECORDED_PROCESSED`, even if ordinary replay checks such as unresolved failure, age, expiry, or payload rules fail. No replay is needed. Contradictory facts and a stale snapshot take precedence and remain `HOLD`.
4. If there is no receipt and identical copies agree on semantic metadata, choose the lexicographically smallest message ID as representative. Failure codes and attempts may vary by delivery. The representative inherits the policy failures of **all** copies. Other copies are `EXCLUDE` with a reference to that representative, even when the representative is held; no duplicate is put in a batch.
5. Apply ordering constraints and create batches only from final candidates.

Blank or missing event identity holds the occurrence. A missing or invalid occurrence timestamp, a mismatched content hash, and invalid timestamp relationships also hold rather than being overridden by a matching receipt. A valid receipt must not precede event occurrence or postdate snapshot capture. Receipts do not expire merely because their recorded processing time is old.

Absent receipts never prove that an event was unprocessed. `PARTIAL` and `DECLARED_COMPLETE` are coverage declarations, not independently verified completeness guarantees. Either coverage label can yield candidates when all applicable checks pass under the policy's idempotency assertion.

## Time and application expiry

Evaluation requires an explicit ISO-8601 instant `asOf` at or after snapshot capture; an earlier evaluation time is an error. Offsets are normalized to UTC, and leap-second strings are rejected instead of silently normalized. No wall-clock value is used implicitly.

Event occurrence and receipt/checkpoint observation times must not postdate snapshot capture, even when the evaluation time is later. Future or invalid event/receipt facts affect the corresponding identity; future or invalid checkpoint facts affect their ordering key. They do not automatically hold unrelated events. A stale snapshot affects the entire analysis.

Age limits are inclusive and retain subsecond precision: an age equal to its maximum passes, while an age even one nanosecond greater fails. Application expiry is exclusive: `expiresAt <= asOf` holds the event. Expiry before event occurrence is an invalid fact. Broker retention and SQS enqueue times are separate from application expiry; QueueSift does not derive one from the other.

## Ordered review

`PER_KEY_SEQUENCE` uses `(tenant, orderingKey)` as the ordering stream. Imported sequence and checkpoint numbers are integers between 0 and 9,007,199,254,740,991. The engine requires a nonblank ordering key and a nonnegative sequence for each event that needs replay review.

Each stream needs a checkpoint with an acknowledged prefix sequence and observation time. Conflicting checkpoint sequence values hold the stream. Every checkpoint fact must have a valid, nonfuture time; when multiple facts agree on the sequence, the newest time determines freshness. A checkpoint alone does not prove an individual event's business effect: events at or below it are held unless matching receipts justify exclusion.

Candidates must form a contiguous prefix starting at `checkpoint + 1`. A missing sequence holds later events. A held event blocks later candidates. A matching processed receipt for a known event at the next required sequence can advance the prefix without entering a batch. An event with a known ordering key but unknown/invalid sequence holds other review events for that key because its relative position is unknown.

Distinct event identities at the same sequence are a contradiction and are held, including occurrences initially excluded by receipts. A blocked event below an established checkpoint does not by itself block a contiguous new prefix after the checkpoint. Events already excluded by matching receipts need no replay and do not require additional ordering metadata merely to remain excluded.

Ordered batches contain at most one candidate per tenant/key and maintain sequence order across batches. Streams are visited in tenant/key lexical order; a batch has at most `batchSize` entries and can contain fewer. Unordered batches contain candidate message IDs in lexical order, partitioned by `batchSize`. Neither form is an executable broker command or a guarantee about future queue ordering.

## Reproducibility and evidence

The policy digest is SHA-256 of the exact canonical JSON policy: object keys sorted recursively, array order preserved, and numbers normalized as decimals. The digest binds explicitly supplied fields. Omitting `required` and writing `"required": true` have the same check behavior but different policy digests. Reordering root object members does not change the digest; reordering arrays can.

The plan ID is SHA-256 of the UTF-8 JSON serialization of a fixed ordered list: `["QueueSift-plan-v1", toolVersion, snapshotDigest, policyDigest, normalizedAsOf, decisions, batches]`. Version-one model field order and the stable ordering of decisions, reasons, references, and batches form part of this fingerprint format. Serialization streams directly into the digest instead of constructing a second JSON tree or serialized copy of the complete plan. The plan ID binds the tool version, snapshot digest, policy digest, normalized evaluation time, final decisions, and batches.

Each decision has stable reason codes, applicable policy pointers, and source file/line/hash references. Every reason exposes at most 16 references; when a larger evidence group is summarized, its total count appears in the detail. Each decision exposes at most 32 distinct reasons. When needed, the first 31 in stable code/policy-path/detail order are followed by `ADDITIONAL_REASONS_OMITTED`, which gives the number omitted and total evaluated. Compaction happens after every check and ordering decision: omitted reasons still affect the outcome, summary counts, and batch eligibility. Payload values are omitted from decisions, reasons, and batch manifests.

Every plan explicitly states its review requirements and assumptions. A `HOLD` means required checks or evidence prevent candidacy. An `EXCLUDE` means the occurrence is not included in this plan's batches; it does not instruct deletion, acknowledgement, or any other broker mutation.
