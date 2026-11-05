# Changelog

## 0.1.0 — 2026-11-04

- Import bounded JSONL message, processing receipt and checkpoint evidence into immutable SQLite snapshots.
- Reconcile event identity, content, duplicate deliveries, recorded effects and recovery policy checks.
- Plan review batches with optional contiguous per-key sequence constraints.
- Export payload-free JSON, Markdown, HTML and candidate manifests with source provenance.
- Serve five read-only MCP tools over stdio with explicit payload opt-in.
- Normalize saved SQS ReceiveMessage exports with explicit application field mappings.
- Include reproducible unordered and ordered examples, unit tests, CLI checks and official MCP SDK integration checks.
