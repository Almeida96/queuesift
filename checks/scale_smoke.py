#!/usr/bin/env python3
"""Exercise two 10,000-message failure workloads with the packaged CLI at -Xmx512m.

Uses only the Python standard library. Outputs and measured timings are retained in
a new directory; no broker, model, network service, or pre-existing data is used.

Example:
  python checks/scale_smoke.py --install-dir build/install/queuesift \
      --out build/scale-smoke --java /path/to/java

This is a reproducible scale smoke check, not a throughput benchmark. The 512 MiB
setting limits the Java heap, not total process RSS or the Python verifier.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Iterator

COUNT = 10_000
CAPTURED = "2026-11-04T12:00:00Z"
CANARY = b"QUEUESIFT_SCALE_PRIVATE_PAYLOAD_CANARY"


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def compact(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--install-dir", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path, help="New directory for inputs, reports, and measurements")
    parser.add_argument("--java", help="Java 21+ executable, otherwise JAVA_HOME/bin/java or PATH")
    parser.add_argument("--case", choices=("all", "failed-rules", "identity-conflict"), default="all")
    parser.add_argument("--timeout-seconds", type=float, default=180, help="Timeout per CLI process, not a speed assertion")
    return parser.parse_args()


def json_decisions(path: Path) -> Iterator[tuple[str, dict[str, Any]]]:
    """Read the known plan format one decision at a time, without loading a large report."""
    decoder = json.JSONDecoder()
    with path.open(encoding="utf-8") as stream:
        buffer = ""
        while True:
            block = stream.read(65536)
            check(bool(block), "Report has no decisions array")
            buffer += block
            match = re.search(r'"decisions"\s*:\s*\[', buffer)
            if match:
                yield "header", json.loads(buffer[:match.start()] + '"decisions":[]}')
                buffer = buffer[match.end():]
                break
            check(len(buffer) <= 131072, "Unexpectedly large report header")
        while True:
            buffer = buffer.lstrip()
            if buffer.startswith("]"):
                return
            if buffer.startswith(","):
                buffer = buffer[1:].lstrip()
            try:
                value, end = decoder.raw_decode(buffer)
            except json.JSONDecodeError:
                block = stream.read(65536)
                check(bool(block), "Truncated or invalid decision JSON")
                buffer += block
                continue
            check(isinstance(value, dict), "Decision must be a JSON object")
            yield "decision", value
            buffer = buffer[end:]


def assert_no_payload(value: Any) -> None:
    if isinstance(value, dict):
        check("payload" not in value, "Payload appeared in the public review plan")
        for child in value.values():
            assert_no_payload(child)
    elif isinstance(value, list):
        for child in value:
            assert_no_payload(child)


def assert_no_canary(path: Path) -> None:
    tail = b""
    with path.open("rb") as stream:
        while block := stream.read(65536):
            check(CANARY not in tail + block, f"Payload canary leaked into {path.name}")
            tail = block[-len(CANARY):]


def verify_report(directory: Path, mode: str, expected_plan_id: str) -> dict[str, Any]:
    total = 0
    header: dict[str, Any] | None = None
    digest = hashlib.sha256()
    for kind, value in json_decisions(directory / "plan.json"):
        assert_no_payload(value)
        if kind == "header":
            header = value
            check(value["counts"] == {"CANDIDATE": 0, "HOLD": COUNT, "EXCLUDE": 0}, "Incorrect plan counts")
            check(value["planId"] == expected_plan_id, "CLI and report plan IDs differ")
            prefix = ["QueueSift-plan-v1", value["toolVersion"], value["snapshotDigest"], value["policyDigest"], value["asOf"]]
            digest.update((compact(prefix)[:-1] + ",[").encode("utf-8"))
            continue
        check(header is not None, "Decision appeared before the report header")
        check(value["messageId"] == f"m{total:05d}", "Missing, repeated, or unstable message order")
        check(value["outcome"] == "HOLD", "A stress-fixture event became a candidate or exclusion")
        check(value["representative"] is None, "Conflicting facts must not collapse as harmless duplicates")
        check(0 < len(value["reasons"]) <= 32, "Per-decision reason bound failed")
        check(all(len(reason["evidence"]) <= 16 for reason in value["reasons"]), "Evidence bound failed")
        if mode == "failed-rules":
            check(len(value["reasons"]) == 32, "Expected 31 retained checks plus one compaction summary")
            omitted = [reason for reason in value["reasons"] if reason["code"] == "ADDITIONAL_REASONS_OMITTED"]
            check(len(omitted) == 1 and omitted[0]["detail"].startswith("33 additional"), "Incorrect omitted reason count")
            check("64 reasons were evaluated" in omitted[0]["detail"], "Full check count was lost")
        else:
            conflicts = [reason for reason in value["reasons"] if reason["code"] == "IDENTITY_CONTENT_CONFLICT"]
            check(len(conflicts) == 1 and "10000" in conflicts[0]["detail"], "Conflict evidence total was lost")
            check(len(conflicts[0]["evidence"]) == 16, "Expected a bounded 16-reference conflict sample")
        if total:
            digest.update(b",")
        digest.update(compact(value).encode("utf-8"))
        total += 1
    check(total == COUNT, f"Expected {COUNT} decisions, found {total}")
    digest.update(b"],[]]")
    check(digest.hexdigest() == expected_plan_id, "Independent streamed plan fingerprint verification failed")
    check((directory / "replay-candidates.jsonl").stat().st_size == 0, "Held workload generated replay candidates")
    sizes: dict[str, int] = {}
    for name in ("plan.json", "report.md", "report.html", "replay-candidates.jsonl"):
        path = directory / name
        assert_no_canary(path)
        sizes[name] = path.stat().st_size
    return {"verified_decisions": total, "independent_plan_hash_verified": True, "output_bytes": sizes}


def invoke(command: list[str], args: list[str], directory: Path, label: str, timeout: float) -> tuple[dict[str, Any], float]:
    started = time.perf_counter()
    result = subprocess.run(command + args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    elapsed = time.perf_counter() - started
    (directory / f"{label}.stderr.log").write_text(result.stderr, encoding="utf-8")
    check(result.returncode == 0, f"{label} exited {result.returncode}: {result.stderr[-4000:]}")
    value = json.loads(result.stdout)
    (directory / f"{label}.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    return value, elapsed


def run_case(command: list[str], root: Path, mode: str, timeout: float) -> dict[str, Any]:
    directory = root / mode
    directory.mkdir()
    rules = [{"path": f"/required{i:02d}", "type": "string"} for i in range(64)] if mode == "failed-rules" else []
    policy = {
        "schemaVersion": 1, "consumerIdempotent": True, "resolvedFailureCodes": ["FIXED"], "maxAttempts": 5,
        "maxAgeSeconds": 86400, "maxSnapshotAgeSeconds": 3600, "batchSize": 1000, "ordering": "UNORDERED", "payloadRules": rules,
    }
    policy_file = directory / "policy.json"
    policy_file.write_text(compact(policy) + "\n", encoding="utf-8")
    messages = directory / "messages.jsonl"
    with messages.open("w", encoding="utf-8", newline="\n") as stream:
        for index in range(COUNT):
            payload: dict[str, Any] = {"private": CANARY.decode("ascii")}
            if mode == "identity-conflict":
                payload["variant"] = index
            record = {
                "id": f"m{index:05d}", "tenant": "scale", "eventId": f"e{index:05d}" if mode == "failed-rules" else "one-conflicting-event",
                "eventType": "fulfillment.created", "occurredAt": "2026-11-04T11:00:00Z", "failureCode": "FIXED", "attempts": 3, "payload": payload,
            }
            stream.write(compact(record) + "\n")
    database = directory / "evidence.db"
    imported, import_seconds = invoke(command, ["import", "--db", str(database), "--id", mode, "--queue", "synthetic.scale.dead",
        "--captured-at", CAPTURED, "--coverage", "PARTIAL", "--messages", str(messages)], directory, "import", timeout)
    check(imported["inserted"] is True and imported["messages"] == COUNT, "Import did not retain every record")
    report_dir = directory / "review"
    analyzed, analyze_seconds = invoke(command, ["analyze", "--db", str(database), "--snapshot", mode, "--policy", str(policy_file),
        "--as-of", CAPTURED, "--out", str(report_dir)], directory, "analyze", timeout)
    check(analyzed["counts"] == {"CANDIDATE": 0, "HOLD": COUNT, "EXCLUDE": 0}, "Unexpected CLI analysis counts")
    check(analyzed["batches"] == 0, "Held workload generated a review batch")
    verification_started = time.perf_counter()
    verified = verify_report(report_dir, mode, analyzed["planId"])
    return {
        "case": mode, "messages": COUNT, "payload_rules": len(rules), "input_bytes": messages.stat().st_size,
        "java_heap_limit_mib": 512, "import_seconds": round(import_seconds, 3), "analyze_and_write_seconds": round(analyze_seconds, 3),
        "python_verification_seconds": round(time.perf_counter() - verification_started, 3), "plan_id": analyzed["planId"], **verified,
    }


def main() -> int:
    options = arguments()
    output = options.out.resolve()
    owns_output = False
    results: dict[str, Any] = {"check": "queuesift-scale-smoke-v1", "status": "RUNNING", "cases": [],
        "measurement_scope": "Single local runs including JVM startup; maximum Java heap is 512 MiB, not a total RSS limit or a throughput benchmark."}
    try:
        check(options.timeout_seconds > 0, "Timeout must be positive")
        distribution = options.install_dir.resolve(strict=True)
        check(any((distribution / "lib").glob("*.jar")), "Installed JARs not found; run installDist first")
        java_arg = options.java or (str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java")
        java = str(Path(java_arg).resolve()) if Path(java_arg).is_file() else shutil.which(java_arg)
        check(java is not None, "Java executable not found; provide --java")
        check(not output.exists(), "Output already exists; choose a new directory")
        output.mkdir(parents=True)
        owns_output = True
        command = [java, "-Xmx512m", "-XX:+ExitOnOutOfMemoryError", "-cp", str(distribution / "lib" / "*"), "io.github.almeida96.queuesift.MainKt"]
        results["java"] = java
        results["distribution"] = str(distribution)
        for mode in (("failed-rules", "identity-conflict") if options.case == "all" else (options.case,)):
            measured = run_case(command, output, mode, options.timeout_seconds)
            results["cases"].append(measured)
            print(json.dumps(measured, ensure_ascii=False), flush=True)
        results["status"] = "PASS"
    except Exception as error:
        results["status"] = "FAIL"
        results["error"] = f"{type(error).__name__}: {error}"
        print(results["error"], file=sys.stderr, flush=True)
    if owns_output:
        (output / "measurements.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": results["status"], "measurements": str(output / "measurements.json")}), flush=True)
    return 0 if results["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
