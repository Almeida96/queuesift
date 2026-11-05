# Architecture

QueueSift is a local deterministic investigation tool for dead-letter exports. Its core result is a review plan built from a sealed input snapshot, an explicit policy, and a fixed evaluation instant. Reports and MCP expose that same result. Agents consume the result; no model participates in classification.

## Data flow

1. Optionally convert a supplied SQS ReceiveMessage-shaped JSON export into normalized message JSONL using `adapt-sqs`. The adapter never contacts SQS.
2. Import message JSONL and optional processing-receipt/checkpoint JSONL into an append-only SQLite evidence ledger.
3. Load one snapshot through a read-only SQLite connection, then close the connection.
4. Parse the local policy and call `RecoveryEngine.analyze(snapshot, policy, asOf)`.
5. Write a static report/review manifest with `analyze`, or expose a private in-memory copy through the read-only stdio MCP server.
6. An operator reviews candidates, holds, exclusions, evidence, and proposed batches. Sending messages is outside the program.

The CLI requires an explicit `--as-of` for analysis and MCP. Runtime wall-clock time is not an input to classification. Nothing refreshes broker or downstream state automatically.

## Components

| Source | Responsibility |
| --- | --- |
| `Main.kt` | Strict command/option dispatch, read-only analysis startup, output-directory creation, exit codes, and packaged demo orchestration. |
| `Model.kt` | Message, receipt, checkpoint, policy, decision, provenance, batch, and recovery-plan data classes. |
| `Json.kt` | Bounded strict JSON parsing, UTF-8 validation, canonical JSON, and SHA-256 helpers. |
| `Records.kt` | Strict interchange fields, printable identifiers, normalized timestamps, numeric bounds, and computed message content hashes. |
| `Ledger.kt` | Transactional import, source/line fingerprints, sealed snapshots, read-only loading, and ledger schema checks. |
| `PolicyReader.kt` | Closed local policy format and bounded JSON Pointer field rules. |
| `RecoveryEngine.kt` | Identity reconciliation, safety checks, ordering barriers, deterministic decisions and batch proposals. |
| `Reports.kt` | Payload-free JSON, Markdown, HTML review output and review manifest. |
| `SqsAdapter.kt` | Explicit payload-pointer mapping from a supplied SQS export to normalized JSONL and an adaptation provenance sidecar. |
| `McpServer.java` | JSON-RPC transport and five read-only views of an immutable startup plan. |

## Evidence and identity

The transport `id` identifies one exported message occurrence. Event identity is the pair `(tenant, eventId)`. Equal event IDs in different tenants are independent. The content hash covers canonical JSON containing only `eventType` and `payload`; it is computed during import rather than trusted from the message input.

Canonicalization sorts object keys, preserves array order, and normalizes numeric representation. It is the project's versioned comparison convention, not a claim of compliance with an external canonical-JSON standard. The policy digest hashes its canonical JSON. A snapshot digest binds normalized snapshot metadata and whole-file hashes for message, receipt, and checkpoint sources.

Every imported record carries a source basename, whole-file SHA-256, one-based physical line number, and line SHA-256. The whole-file hash includes original delimiters. A line hash excludes LF, or both CR and LF for CRLF. The ledger retains the exact UTF-8 line text and verifies its hash before parsing it on load. It does not retain each original line delimiter, so keep the source exports for whole-file byte comparisons.

The SQLite schema has `snapshots`, `sources`, and `records` tables. Foreign keys are deferred until commit so a complete snapshot can be validated and inserted in one transaction. Update/delete triggers protect imported rows, and insert triggers seal source/record additions after the snapshot row is committed. Reimporting the same snapshot ID with the same normalized metadata and source-byte fingerprints is a no-op. Conflicting metadata or source bytes fail, and failed imports roll back.

Inspection opens an existing ledger with SQLite read-only settings and `query_only=ON`; it does not create a missing database. Application/schema identifiers and record metadata are checked on load. The ledger is a local evidence store, not a signed or independently authenticated record: its hashes support comparison with retained sources, not proof of who supplied the data.

## Decision computation

The engine starts from messages sorted by transport ID and applies the following evidence:

- Snapshot freshness; event occurrence and optional application expiry; positive known delivery attempts; declared resolved failure codes; declared consumer idempotency; and configured payload field rules.
- Matching processing receipts and duplicate occurrences, scoped by tenant/event identity. Conflicting content, inconsistent event metadata, invalid receipt times, and other hard evidence problems propagate across copies of that identity.
- Optional per-key sequence constraints, scoped by tenant/ordering key, using supplied checkpoint facts and visible messages.

A matching receipt can establish `EXCLUDE` only when hard evidence problems do not block reconciliation. Without a receipt, identical copies select the lexicographically smallest transport ID as representative; the other copies are excluded from batches. Checks from every copy contribute to the representative. Missing receipts never prove that processing did not happen. `PARTIAL` and `DECLARED_COMPLETE` coverage are operator declarations, not verified completeness guarantees.

`CANDIDATE` means the declared checks passed for human review. `HOLD` means review must resolve uncertainty or a blocking condition. `EXCLUDE` means the current plan leaves the occurrence out, such as a reconciled duplicate or recorded processing receipt. No outcome executes an operation.

For `PER_KEY_SEQUENCE`, missing/conflicting/stale checkpoints, unknown positions, reused sequences, gaps, and earlier holds prevent later messages from entering a contiguous review prefix. A checkpoint does not establish an individual message's business effect. Unordered batches sort candidate IDs and chunk them by policy batch size. Ordered batches take at most one next candidate from each tenant/key stream per batch, preserving that stream's sequence order across batches. The batch number is a proposed review order; it is not an execution acknowledgement.

Each decision includes reason codes, policy pointers when applicable, and source references. Reason evidence is sorted, deduplicated, and capped at 16 references with a truncation statement. Decision IDs bind the snapshot digest and transport ID. The plan ID hashes tool version, snapshot digest, policy digest, normalized evaluation time, decisions, and batches, so a policy or evaluation change produces an independently identifiable plan. Re-evaluation of the same persisted snapshot is deterministic. Source basenames appear in decision provenance: importing identical bytes under different basenames into separate ledgers can preserve the snapshot digest while changing the plan ID.

Payload rules support required JSON Pointers, scalar/container types, scalar allowed values, numeric minima/maxima, and Unicode code-point string lengths. They are deliberately smaller than JSON Schema and cannot prove business correctness.

## Output and agent boundary

Analysis writes `plan.json`, `report.md`, `report.html`, and `replay-candidates.jsonl` into a new directory and emits a concise machine-readable summary to stdout. Reports and the review manifest omit raw payloads. HTML escapes evidence strings and has a restrictive Content Security Policy; Markdown escapes evidence markup, including link/image brackets. The JSON plan and manifest preserve the original identifiers. `--gate` returns 2 when any decision is `HOLD`, 0 otherwise; input/execution errors return 1. A zero gate result does not authorize replay.

MCP opens the database and policy only during startup. It takes a private deep copy of plan metadata; raw payloads enter its evidence snapshot only with the operator's `--include-payload` startup flag. Tool calls cannot change the plan, load another path, contact a network service, or perform a queue operation. Payload opt-in affects only `message_evidence`; collection tools remain payload-free. See [mcp.md](mcp.md) for exact tool schemas, response fields, pagination, protocol limits, and the official-SDK smoke command.

The SQS adapter writes payload-bearing normalized input JSONL because that file is intended for import. Its separate provenance sidecar records the source file hash and configured mappings. Receipt handles and unneeded broker attributes are discarded. The observed failure code is an operator assertion, receive count is approximate, and only explicitly mapped payload timestamps contribute event-time evidence. Broker retention is not inferred.

## Resource bounds and runtime

| Boundary | Limit |
| --- | --- |
| One imported source | 10,000 records. |
| One imported JSONL line | 65,536 bytes, excluding its line delimiter. |
| All files in one import | 32 MiB combined. |
| Ledger snapshots | 1,000. |
| Policy file | 65,536 bytes; up to 256 field rules and 256 resolved failure codes. |
| SQS export | 4 MiB; 1–1,000 messages; normalized rows must fit the JSONL line limit. |
| JSON parsing | Nesting depth 40, string length 65,536, numeric token length 256, decimal precision/absolute scale at most 256. |
| Sequence values | Integer range 0 through 9007199254740991. |
| MCP request line | 256 KiB; paged collection results allow 1–50 items. |

The application targets Java 21 and Kotlin 2.4.21. Runtime dependencies are Jackson for JSON, Xerial SQLite JDBC for local storage, and an SLF4J no-op binding. The Gradle 9.2.1 wrapper uses a pinned distribution hash, and dependency versions are locked. The installed launcher defaults to a 512 MiB maximum heap. The application has no model SDK, broker SDK, HTTP server, or replay worker.

Tests cover strict parsing and policy behavior, transactional ledger/provenance rules, decision and ordering behavior, and MCP protocol/privacy isolation. The Python MCP smoke check exercises the installed distribution through the official client SDK. These checks validate the supplied evidence and implementation; they cannot verify actual consumer idempotency or current broker state.
