QueueSift 0.1.0 investigates dead-letter exports and prepares evidence-backed recovery reviews.

The Kotlin engine reconciles duplicate deliveries, tenant-scoped event identity, processing receipts, application expiry, policy checks and optional sequence checkpoints. It exports a standalone HTML report, JSON/Markdown and a payload-free candidate manifest. Five read-only MCP tools expose the same decisions to agents.

Extract the ZIP, install Java 21 and run `bin/queuesift demo --out fulfillment-review` (`bin\queuesift.bat` on Windows). Open `fulfillment-review/recovery/report.html`. The included demo uses synthetic data and asserts its expected decisions.

The release includes the distribution, a generated demonstration report and SHA-256 checksums. Linux and Windows CI must pass before publication.

No broker connection or replay executor is included. Candidates require operator review and depend on the supplied evidence and policy declarations. See the README and policy documentation for input limits and decision semantics.
