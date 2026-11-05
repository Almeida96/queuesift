# Offline Amazon SQS export adapter

`adapt-sqs` converts an already exported, ReceiveMessage-shaped JSON file to QueueSift JSONL. It contains no AWS SDK integration, reads no credentials, requests no AWS permissions, and contacts no queue. Obtaining the export is a separate operational action: an actual SQS receive changes message visibility and receive history, and one response is not a complete queue inventory. [AWS ReceiveMessage documentation](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html).

## Input and explicit mapping

The input is one JSON object containing `Messages`. A body must be a string containing valid JSON; opaque text and binary bodies are unsupported. Example:

```json
{
  "Messages": [
    {
      "MessageId": "delivery-1",
      "ReceiptHandle": "discarded-by-adapter",
      "Body": "{\"tenant\":\"acme\",\"eventId\":\"invoice-42\",\"type\":\"invoice.created\",\"occurredAt\":\"2026-11-04T11:50:00Z\",\"amount\":12}",
      "Attributes": {
        "ApproximateReceiveCount": "3",
        "SentTimestamp": "1793793000000"
      }
    }
  ]
}
```

Run this against a new output directory:

```sh
queuesift adapt-sqs --input receive-export.json --tenant-pointer /tenant --event-id-pointer /eventId --event-type-pointer /type --occurred-at-pointer /occurredAt --failure-code DEPENDENCY_TIMEOUT --out adapted
```

The three identity/type pointers are required and select nonblank strings inside the parsed **Body**. JSON Pointer uses `/` between path segments, `~1` for a literal slash, and `~0` for a literal tilde. A missing/null required value fails conversion. Optional `--occurred-at-pointer` and `--expires-at-pointer` select application timestamps; missing/null optional values remain absent. Malformed supplied values fail. The adapter does not search message attributes for substitute identity fields or guess an event type.

| QueueSift value | Export source |
| --- | --- |
| `id` | Unique `MessageId` within the supplied export. |
| `tenant`, `eventId`, `eventType` | Required explicit JSON Pointers into the parsed Body. |
| `payload` | The entire parsed Body, including mapped fields. |
| `attempts` | `Attributes.ApproximateReceiveCount`, a decimal string from `"1"` through `"999999"`. |
| `failureCode` | Required `--failure-code`, applied to every exported message. |
| `occurredAt`, `expiresAt` | Optional explicit JSON Pointers into the Body. |

`failureCode` is an operator classification: SQS receive metadata does not supply the application's failure cause. Split exports or create normalized JSONL directly when failures differ. Approximate receive count is broker evidence and does not establish how many business effects occurred. AWS describes it as receives across queues before deletion. [AWS ReceiveMessage attributes](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html).

The adapter accepts up to 4 MiB of raw export JSON, 1–1,000 messages, and 64 KiB per normalized record. The upper count permits a preassembled export; a single SQS ReceiveMessage response returns at most ten messages. [AWS ReceiveMessage limits](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html). Duplicate MessageIds reject conversion. Strict JSON/UTF-8 validation applies to the export and parsed bodies. The output directory must not already exist.

## Output and import

The adapter writes `messages.jsonl` and `adaptation.json`. The latter records the export basename and whole-file SHA-256, mapping options, count, version, and limitations. Retain it alongside the original export. The ledger separately hashes the normalized JSONL; it does not automatically import `adaptation.json` as evidence.

```sh
queuesift import --db evidence.db --id sqs-review-1 --queue invoices.dead --captured-at 2026-11-04T12:00:00Z --coverage PARTIAL --messages adapted/messages.jsonl
```

Use the actual evidence capture time. Queue identity and coverage are supplied at import. No downstream processing receipts or ordering checkpoints are manufactured from broker attributes. The adapter does not map FIFO `MessageGroupId` or SQS `SequenceNumber` into an application sequence; use a separately justified normalized export and checkpoints for ordered review.

## Application time and execution boundary

`SentTimestamp` is a broker enqueue timestamp, not application occurrence. QueueSift never substitutes it for `occurredAt` or derives `expiresAt` from it. Broker retention is distinct from application expiry; AWS also documents different DLQ enqueue-time behavior for standard and FIFO queues. [AWS DLQ retention documentation](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html).

Receipt handles, MD5 fields, and unused broker metadata are discarded. The output supplies no live receipt handle, delete operation, send operation, or redrive call. A candidate manifest is a selective review artifact and cannot be passed directly to native SQS redrive: `StartMessageMoveTask` accepts source/destination queue ARNs and a movement rate, with no per-message selection field. [AWS StartMessageMoveTask API](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_StartMessageMoveTask.html).

Any execution requires a separately designed, authorized workflow that rechecks current queue/downstream state and consumer behavior. QueueSift does not implement that workflow or assert that an offline candidate remains executable.
