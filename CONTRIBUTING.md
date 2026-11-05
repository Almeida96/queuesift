# Contributing

Use JDK 21 and the checked-in Gradle wrapper. Run `./gradlew test installDist` before submitting a change, then run the CLI and MCP checks listed in the README. Use synthetic fixtures; never attach real message bodies, credentials or receipt handles to issues.

For a decision-engine bug, include the smallest normalized messages, receipts, checkpoints, policy and fixed `--as-of` time that reproduce it. State the expected decision and the evidence that supports it. A candidate must not become eligible by discarding conflicting evidence.

Keep the core deterministic and independent of model providers. Add meaningful regression tests for changes to precedence, import boundaries, hashing, transport or output behavior. If a data contract or decision changes, update the associated documentation and changelog. Do not add automatic dependency-update bots.

The CLI, JSONL contract, report schema and MCP tools are versioned together. Changes that alter content hashes or stored evidence require an explicit compatibility plan; do not silently reinterpret existing ledgers.

For performance work, record the workload, Java version, heap limit and measured results. Avoid claiming production throughput from the small worked example.
