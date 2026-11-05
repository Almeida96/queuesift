# Scale smoke check

The importer enforces 10,000 records per source, 65,536 bytes per line, and 32 MiB across the source files in one snapshot. Policies permit at most 64 payload rules. Final decisions expose at most 32 distinct reasons and 16 evidence references per reason. These are implemented input/report limits; they are not a claim that every possible input combination has been benchmarked.

`checks/scale_smoke.py` exercises two explicit failure-heavy workloads using the installed distribution:

1. **Failed rules:** 10,000 distinct event identities, each failing all 64 required-field rules. Every event must remain `HOLD`, with 31 retained reasons and an explicit summary of the other 33.
2. **Identity conflict:** 10,000 occurrences of the same event identity with 10,000 different payload variants. Every occurrence must remain `HOLD`; the conflict reason must retain the total evidence count while exposing only 16 references.

Both runs use a fixed evaluation instant, invoke Java directly with `-Xmx512m`, import into a new SQLite ledger, and generate the full JSON, Markdown, and HTML review artifacts. The script independently recomputes each plan fingerprint while reading one decision at a time. It checks every outcome, reason/reference bound, empty candidate manifest, and absence of a private payload canary in public artifacts.

After `installDist`:

```text
python checks/scale_smoke.py --install-dir build/install/queuesift --out build/scale-smoke --java /path/to/java
```

Choose a new `--out` directory each run. Existing files are not overwritten or deleted. Optional `--case failed-rules` or `--case identity-conflict` selects one workload. The default timeout is 180 seconds per Java process; `--timeout-seconds` changes this execution deadline, which is not a throughput target.

The output directory retains the synthetic inputs, ledgers, reports, command summaries, stderr logs, and `measurements.json`. Measurements include separate wall times for import, analysis plus report writing, and Python verification, as well as input/output byte counts. They include JVM startup and local disk behavior. The 512 MiB setting limits the Java heap; native allocations and the Python verifier are outside that limit. A successful local run demonstrates these two concrete workloads on that machine, rather than a universal memory or performance guarantee.

## Observed local result

Both cases passed on 2026-11-04 on Windows using the Java 21 runtime and the `0.1.0` installed distribution. Each Java invocation had a maximum heap of 512 MiB. These are single observed runs, not averages or benchmark claims.

| Workload | Import | Analyze and write all reports | Python verification | JSON plan | HTML report |
|---|---:|---:|---:|---:|---:|
| 10,000 unique identities × 64 failed rules | 3.776 s | 10.583 s | 4.771 s | 147,106,066 bytes | 98,912,279 bytes |
| 10,000 conflicting occurrences of one identity | 3.710 s | 5.685 s | 1.484 s | 49,530,357 bytes | 24,296,570 bytes |

The verifier checked all 10,000 decisions in each case, both streamed plan fingerprints, all reason/reference limits, empty candidate manifests, and payload-canary exclusion. The full reports can be large even with bounded reasons; successful generation does not establish that a browser will interact smoothly with a roughly 99 MB HTML file. A separate read-only MCP check can query bounded pages without loading the complete HTML report.

## MCP startup at the same size

The read-only MCP process also passed against the 10,000-message failed-rules snapshot with `-Xmx512m`: initialization, summary and one full message evidence response completed in 7.910 seconds, with 26,737 stdout bytes. The evidence retained 32 reasons, omitted the payload, and left the database byte-for-byte unchanged. This is one local measurement, including JVM startup and analysis, rather than a latency target.

```text
python checks/mcp_scale_smoke.py --install-dir build/install/queuesift --db build/scale-smoke/failed-rules/evidence.db --snapshot failed-rules --policy build/scale-smoke/failed-rules/policy.json --as-of 2026-11-04T12:00:00Z --out build/mcp-scale-check
```

This standard-library check bounds captured output and execution time. CI runs it after generating the CLI scale fixture. The official SDK check covers protocol behavior separately. The MCP server keeps a single private full-decision snapshot plus compact list views, avoiding a second copy of every reason and evidence reference.
