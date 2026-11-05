# Evidence format and provenance

QueueSift imports one message file and optional processing-receipt and ordering-checkpoint files into an immutable snapshot. Files are UTF-8 JSON Lines: one JSON object per line, with LF or CRLF endings. A final newline is optional. Empty files are allowed; empty or whitespace-only lines are rejected. Unknown fields, duplicate JSON keys at any depth, invalid UTF-8, unpaired Unicode surrogates, and trailing JSON documents are rejected.

All identifiers and text fields below are case-sensitive, nonblank printable strings of at most 128 Unicode code points. Leading/trailing spaces in a nonblank value are preserved. Control characters, Unicode formatting characters, and line/paragraph separators are forbidden. Optional text may be absent, `null`, or blank; blank optional text becomes `null`. Required fields cannot be `null`.

## Messages

```jsonl
{"id":"delivery-1","tenant":"acme","eventId":"invoice-42","eventType":"invoice.created","occurredAt":"2026-11-04T11:50:00Z","expiresAt":"2026-11-05T00:00:00Z","failureCode":"DEPENDENCY_TIMEOUT","attempts":2,"orderingKey":"account-7","sequence":6,"payload":{"amount":12}}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | Yes | Transport occurrence ID; unique within this snapshot. |
| `tenant` | Yes | Tenant scope for business identity and ordering. |
| `eventId` | No | Business event ID. Identity is the pair `(tenant, eventId)`. |
| `eventType` | Yes | Application event type; contributes to the content hash. |
| `occurredAt` | No | Application occurrence instant used for age checks. |
| `expiresAt` | No | Application expiry instant, independent of broker retention. |
| `failureCode` | Yes | Observed failure classification, matched exactly against policy declarations. |
| `attempts` | Yes | Integer from 0 through 2,147,483,647. Zero is imported but held during analysis because a positive known count is required. |
| `orderingKey` | No | Application stream within a tenant. |
| `sequence` | No | Integer from 0 through 9,007,199,254,740,991, or `null`. |
| `payload` | Yes | Any JSON value, including `null`, arrays, and scalars. |

Integer fields require integer tokens: `1.0`, `"1"`, and booleans are invalid. Duplicate transport IDs reject the import. Distinct transport IDs with the same business identity are preserved for reconciliation; conflicting content is evidence, not an import error. Missing identity or occurrence time remains missing and produces a HOLD during analysis. Ordered analysis also requires ordering metadata.

All supplied timestamps must be ISO-8601 instants with an offset, for example `2026-11-04T08:50:00-03:00`; they normalize to UTC as `2026-11-04T11:50:00Z`. Local dates/times and leap seconds are rejected. Timestamp relationships, such as occurrence after capture or expiry before occurrence, are assessed by the engine.

## Processing receipts

A receipt records a downstream processing assertion for one tenant, event ID, and exact event content. It must come from appropriate application evidence; a broker acknowledgement or receive count does not establish the business effect.

```jsonl
{"tenant":"acme","eventId":"invoice-42","contentHash":"e78837490a85b04a4c21a326e0e26c479e3a90a2270369cc35734954fecc28d0","processedAt":"2026-11-04T11:55:00Z"}
```

These four fields are all required. `contentHash` must be 64 lowercase hexadecimal characters. The example matches event type `invoice.created` and payload `{"amount":12}`. `processedAt` is an instant. Matching receipts can exclude an already processed event; conflicting hashes or impossible receipt times hold the identity. An absent receipt never proves that processing did not occur.

Generate a compatible hash with the CLI, placing the JSON payload alone in `payload.json`:

```sh
queuesift fingerprint --event-type invoice.created --payload payload.json
```

The algorithm hashes the UTF-8 JSON serialization of `{"eventType": TYPE, "payload": PAYLOAD}` after recursively sorting object keys and normalizing decimal numbers with trailing zeros removed. Arrays retain order; Unicode strings are not normalized. Whitespace, object property order, and decimal spellings such as `1` versus `1.00` do not distinguish content. Event type does. This is QueueSift's versioned canonicalization, **not RFC 8785/JCS**; use the CLI or this implementation rather than another canonical-JSON library. Receipt hashes are not raw-body, file, MD5, or transport-ID hashes.

## Ordering checkpoints

```jsonl
{"tenant":"acme","orderingKey":"account-7","sequence":5,"asOf":"2026-11-04T11:45:00Z"}
```

All four fields are required. `sequence` uses the same integer range as messages; `asOf` is an instant. A checkpoint asserts an acknowledged prefix for `(tenant, orderingKey)`. It does not prove the business effect of a particular event. The engine requires fresh, consistent checkpoint evidence and a contiguous sequence after the prefix; gaps and earlier blocked events stop later candidates in that stream.

## Snapshot scope and hashes

```sh
queuesift import --db evidence.db --id incident-42 --queue invoices.dead --captured-at 2026-11-04T12:00:00Z --coverage PARTIAL --messages messages.jsonl --receipts receipts.jsonl --checkpoints checkpoints.jsonl
```

The snapshot ID is unique across a ledger. `queue` is an operator-supplied scope label; receipt and checkpoint files must belong to the same capture scope. `captured-at` is normalized to UTC. Coverage is exactly `PARTIAL` or `DECLARED_COMPLETE`; the latter is an operator assertion and is not independently verified. Multiple queues can share one ledger, but evidence is reconciled only inside the selected snapshot.

Each record receives a `SourceRef` containing the source **basename**, its whole-file SHA-256, its one-based line number, and its line SHA-256. The whole-file hash covers all original bytes, including newlines. A line hash excludes its LF terminator and, for CRLF, the preceding CR. A final line with no terminator is hashed in full. The ledger retains the exact UTF-8 line without its terminator and verifies its line hash on load. Full paths, original files, and their line-ending choices are not retained. Keep original evidence for whole-file byte verification.

The snapshot digest binds schema version 1, normalized metadata (`id`, `queue`, `capturedAt`, `coverage`), and the hashes assigned to the `messages`, `receipts`, and `checkpoints` roles. An omitted optional source is distinct from a supplied empty file. Basenames are excluded: a byte-identical copy under another filename remains the same import, retaining the first import's provenance. Changes to source bytes, including line endings, change the digest.

Reimporting the same ID, metadata, and source bytes returns `inserted: false`. Reusing an ID with changed evidence or metadata fails. An invalid record rolls back every file and row in that import, including rows already inserted in batches. SQLite triggers seal committed evidence against updates, deletions, and extra rows; no CLI command edits a snapshot. These are local integrity controls, not signatures or protection against an administrator replacing the database. Unknown application IDs or schema versions are rejected. Inspection opens an existing database read-only and does not enable WAL.

## Bounds

| Limit | Value |
| --- | --- |
| Records per supplied file | 10,000 |
| Combined raw bytes across all import files | 32 MiB (33,554,432 bytes) |
| Bytes per JSONL line, excluding LF/CRLF | 64 KiB (65,536 bytes) |
| Snapshots per ledger | 1,000 |
| JSON nesting depth | 40, including the record wrapper |
| JSON string length | 65,536 characters, also subject to the line byte limit |
| Numeric token length, decimal precision, absolute decimal scale | 256 each |
| Source basename length | 256 Unicode code points |

Import reads bounded chunks and writes JDBC batches of 500 rows in one transaction. Loading is bounded to the same per-source row counts. Policy files and standalone `fingerprint` payload files have a 64 KiB file limit.
