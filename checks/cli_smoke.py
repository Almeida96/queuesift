#!/usr/bin/env python3
"""Exercise the installed QueueSift distribution end to end using only Python's stdlib.

Usage: python checks/cli_smoke.py build/install/queuesift
Java comes from --java, JAVA_HOME/bin/java, or PATH. The script invokes MainKt with
the installed lib/* classpath directly, so Windows and POSIX need no shell wrapper.
Every artifact is created in a temporary directory; no broker or model is contacted.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any


AS_OF = "2026-11-04T12:05:00Z"
CAPTURED_AT = "2026-11-04T12:00:00Z"
EXPECTED = {
    "unresolved": {
        "counts": {"CANDIDATE": 0, "HOLD": 14, "EXCLUDE": 2},
        "candidates": [],
        "batches": [],
    },
    "recovery": {
        "counts": {"CANDIDATE": 3, "HOLD": 11, "EXCLUDE": 2},
        "candidates": ["m01", "m13", "m14"],
        "batches": [["m01", "m13"], ["m14"]],
    },
    "ordered-review": {
        "counts": {"CANDIDATE": 3, "HOLD": 6, "EXCLUDE": 0},
        "candidates": ["o01", "o02", "o04"],
        "batches": [["o01", "o04"], ["o02"]],
    },
}


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def line_bytes(data: bytes) -> list[bytes]:
    """Match the importer's LF/CRLF convention without treating a bare CR as a newline."""
    if not data:
        return []
    chunks = data.split(b"\n")
    result = []
    for index, chunk in enumerate(chunks):
        terminated = index < len(chunks) - 1
        if not terminated and not chunk:
            continue
        result.append(chunk[:-1] if terminated and chunk.endswith(b"\r") else chunk)
    return result


def no_payload(value: Any) -> None:
    if isinstance(value, dict):
        check("payload" not in value, "A public review artifact contains a payload field")
        for child in value.values():
            no_payload(child)
    elif isinstance(value, list):
        for child in value:
            no_payload(child)


def file_hashes(directory: Path) -> dict[str, str]:
    return {str(path.relative_to(directory)): sha256(path.read_bytes())
            for path in sorted(directory.rglob("*")) if path.is_file()}


class Cli:
    def __init__(self, install: Path, java: str | None):
        install = install.resolve()
        check((install / "lib").is_dir() and bool(list((install / "lib").glob("*.jar"))),
              f"No installed distribution at {install}; run installDist first")
        if not java and os.environ.get("JAVA_HOME"):
            candidate = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
            if candidate.is_file():
                java = str(candidate)
        java = java or shutil.which("java")
        check(bool(java), "Java 21+ is required; set JAVA_HOME or pass --java")
        self.command = [str(java), "-Xmx512m", "-cp", str(install / "lib" / "*"),
                        "io.github.almeida96.queuesift.MainKt"]
        self.calls = 0

    def run(self, *args: str | Path, expected: int = 0, json_output: bool = True) -> Any:
        command = self.command + [str(value) for value in args]
        process = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, text=True, encoding="utf-8", timeout=60, check=False)
        self.calls += 1
        check(process.returncode == expected,
              f"CLI {' '.join(map(str, args))} exited {process.returncode}, expected {expected}\n"
              f"stdout: {process.stdout}\nstderr: {process.stderr}")
        if expected == 1:
            check("queuesift:" in process.stderr, "Rejected input must have an actionable stderr diagnostic")
            check(not process.stdout.strip(), "Rejected input unexpectedly produced a success result")
        if not json_output:
            return process.stdout
        return json.loads(process.stdout)


def verify_plan(directory: Path, source_directory: Path, expected: dict[str, Any]) -> dict[str, Any]:
    plan = read_json(directory / "plan.json")
    check(plan["counts"] == expected["counts"], f"Wrong outcome counts in {directory.name}: {plan['counts']}")
    decisions = plan["decisions"]
    check(len(decisions) == sum(plan["counts"].values()), "Counts do not match decisions")
    check([item["messageId"] for item in decisions if item["outcome"] == "CANDIDATE"] == expected["candidates"],
          f"Wrong candidate IDs in {directory.name}")
    check([batch["messageIds"] for batch in plan["batches"]] == expected["batches"],
          f"Wrong review batches in {directory.name}")
    check([batch["number"] for batch in plan["batches"]] == list(range(1, len(plan["batches"]) + 1)),
          "Batch numbering must start at one and be contiguous")
    manifest = [json.loads(line) for line in (directory / "replay-candidates.jsonl").read_text(encoding="utf-8").splitlines()]
    flattened = [(number, identifier) for number, batch in enumerate(expected["batches"], 1) for identifier in batch]
    check([(item["batch"], item["messageId"]) for item in manifest] == flattened, "Manifest lost or changed planned batch order")
    for item in manifest:
        check(item["requiresOperatorReview"] is True, "Manifest must require operator review")
        check(item["snapshot"] == plan["snapshot"]["id"], "Manifest snapshot mismatch")
        for key in ("planId", "snapshotDigest", "policyDigest", "asOf"):
            check(item[key] == plan[key], f"Manifest binding mismatch for {key}")
    sources: dict[str, tuple[str, list[bytes]]] = {}

    def provenance(ref: dict[str, Any]) -> None:
        name = ref["file"]
        check(name == Path(name).name and "/" not in name and "\\" not in name, "Source must expose a basename only")
        if name not in sources:
            raw = (source_directory / name).read_bytes()
            sources[name] = sha256(raw), line_bytes(raw)
        digest, lines = sources[name]
        check(ref["sha256"] == digest, f"Wrong whole-file hash for {name}")
        check(isinstance(ref["line"], int) and 1 <= ref["line"] <= len(lines), f"Invalid source line for {name}")
        check(ref["lineSha256"] == sha256(lines[ref["line"] - 1]), f"Wrong exact line hash for {name}:{ref['line']}")

    for decision in decisions:
        provenance(decision["source"])
        check(bool(decision["reasons"]), "Every decision needs a reason")
        for reason in decision["reasons"]:
            for reference in reason["evidence"]:
                provenance(reference)
    for item in manifest:
        provenance(item["source"])
    no_payload(plan)
    no_payload(manifest)
    for filename in ("plan.json", "replay-candidates.jsonl", "report.md", "report.html"):
        raw = (directory / filename).read_bytes()
        check(b"PRIVATE_PAYLOAD_CANARY" not in raw, f"Payload canary leaked into {filename}")
    check("default-src 'none'" in (directory / "report.html").read_text(encoding="utf-8"), "HTML report lost its local-only CSP")
    return plan


def smoke(cli: Cli, temporary: Path) -> dict[str, Any]:
    check(cli.run("--version", json_output=False).strip() == "0.1.0", "Wrong installed version")
    demo = temporary / "demo"
    summary = cli.run("demo", "--out", demo)
    check(summary["reimportIdempotent"] is True, "Packaged demo did not verify idempotency")
    plans = {name: verify_plan(demo / name, demo, expected) for name, expected in EXPECTED.items()}
    for name, expected in EXPECTED.items():
        check(summary[name]["counts"] == expected["counts"], "Demo summary and report counts disagree")
        check(summary[name]["batches"] == expected["batches"], "Demo summary and manifest batches disagree")
    database = demo / "evidence.db"
    policy = demo / "policy.json"
    inventory = cli.run("snapshots", "--db", database)
    check({row["id"] for row in inventory} == {"fulfillment", "ordered"}, "Demo packaged the wrong snapshots")
    recovery = plans["recovery"]

    def analyze(out: Path, *, policy_file: Path = policy, as_of: str = AS_OF, gate: bool = False,
                expected: int = 0, snapshot: str = "fulfillment") -> Any:
        args: list[str | Path] = ["analyze", "--db", database, "--snapshot", snapshot,
                                 "--policy", policy_file, "--as-of", as_of, "--out", out]
        if gate:
            args.append("--gate")
        return cli.run(*args, expected=expected, json_output=expected != 1)

    before = database.read_bytes()
    repeated = temporary / "repeated"
    analyze(repeated, gate=True, expected=2)
    repeated_plan = verify_plan(repeated, demo, EXPECTED["recovery"])
    check(repeated_plan == recovery, "Identical analysis inputs did not reproduce the entire plan")
    for filename in ("plan.json", "replay-candidates.jsonl", "report.md", "report.html"):
        check((repeated / filename).read_bytes() == (demo / "recovery" / filename).read_bytes(),
              f"Repeated analysis changed {filename}")
    check(database.read_bytes() == before, "Read-only analysis modified ledger bytes")
    check(not Path(str(database) + "-wal").exists() and not Path(str(database) + "-shm").exists(),
          "Read-only analysis created WAL sidecars")
    protected = file_hashes(repeated)
    analyze(repeated, expected=1)
    check(file_hashes(repeated) == protected, "Rejected overwrite changed existing output")

    original_import = cli.run("import", "--db", database, "--id", "fulfillment", "--queue", "orders.dead",
                              "--captured-at", CAPTURED_AT, "--coverage", "PARTIAL",
                              "--messages", demo / "messages.jsonl", "--receipts", demo / "receipts.jsonl")
    check(original_import["inserted"] is False and original_import["digest"] == recovery["snapshotDigest"],
          "Repeated CLI import was not idempotent")
    check(original_import["messages"] == 16 and original_import["receipts"] == 2, "Idempotent import returned wrong counts")
    check(cli.run("snapshots", "--db", database) == inventory, "Idempotent import changed snapshot inventory")
    check(database.read_bytes() == before, "Idempotent import modified ledger bytes")

    bad_input = temporary / "rollback.jsonl"
    first_line = line_bytes((demo / "messages.jsonl").read_bytes())[0]
    bad_input.write_bytes(first_line + b"\n{\"unknown\":true}\n")
    import_args: list[str | Path] = ["import", "--db", database, "--id", "rollback-check", "--queue", "rollback.dead",
                                   "--captured-at", CAPTURED_AT, "--coverage", "PARTIAL", "--messages", bad_input]
    cli.run(*import_args, expected=1, json_output=False)
    check(cli.run("snapshots", "--db", database) == inventory, "Failed import left a visible partial snapshot")
    bad_input.write_bytes(first_line + b"\r\n")
    retried = cli.run(*import_args)
    check(retried["inserted"] is True and retried["messages"] == 1, "Failed import left rows that prevent a clean retry")
    one = temporary / "rollback-retried"
    analyze(one, snapshot="rollback-check")
    verify_plan(one, temporary, {"counts": {"CANDIDATE": 1, "HOLD": 0, "EXCLUDE": 0},
                                 "candidates": ["m01"], "batches": [["m01"]]})

    changed_policy = temporary / "changed-policy.json"
    changed = read_json(policy)
    changed["batchSize"] = 1
    write_json(changed_policy, changed)
    changed_output = temporary / "changed-policy"
    analyze(changed_output, policy_file=changed_policy)
    changed_plan = read_json(changed_output / "plan.json")
    check(changed_plan["snapshotDigest"] == recovery["snapshotDigest"], "Policy change altered snapshot binding")
    check(changed_plan["policyDigest"] != recovery["policyDigest"] and changed_plan["planId"] != recovery["planId"],
          "Changed policy did not change its digest and plan ID")
    check([batch["messageIds"] for batch in changed_plan["batches"]] == [["m01"], ["m13"], ["m14"]],
          "Changed batch policy did not affect installed analysis")
    later = temporary / "later"
    analyze(later, as_of="2026-11-04T12:06:00Z")
    later_plan = read_json(later / "plan.json")
    check(later_plan["counts"] == recovery["counts"] and later_plan["policyDigest"] == recovery["policyDigest"],
          "Small evaluation-time change unexpectedly changed policy or counts")
    check(later_plan["planId"] != recovery["planId"], "Evaluation time is not bound into the plan ID")

    adapted = temporary / "adapted"
    provenance = cli.run("adapt-sqs", "--input", demo / "sqs-export.json", "--tenant-pointer", "/tenant",
                         "--event-id-pointer", "/eventId", "--event-type-pointer", "/eventType",
                         "--occurred-at-pointer", "/occurredAt", "--expires-at-pointer", "/expiresAt",
                         "--failure-code", "DEPENDENCY_TIMEOUT", "--out", adapted)
    check(provenance["sourceSha256"] == sha256((demo / "sqs-export.json").read_bytes()), "Adapter export hash is incorrect")
    check(read_json(adapted / "adaptation.json") == provenance, "Adapter stdout and saved provenance disagree")
    adapted_rows = [json.loads(line) for line in (adapted / "messages.jsonl").read_text(encoding="utf-8").splitlines()]
    check(len(adapted_rows) == 1 and adapted_rows[0]["attempts"] == 3, "Adapter lost records or receive count")
    check(adapted_rows[0]["occurredAt"] == "2026-11-04T10:00:00Z", "Adapter did not use the mapped application timestamp")
    check(b"SYNTHETIC_RECEIPT_HANDLE_NOT_TO_FORWARD" not in (adapted / "messages.jsonl").read_bytes(), "Adapter retained a receipt handle")
    imported = cli.run("import", "--db", database, "--id", "sqs-smoke", "--queue", "sqs.dead",
                       "--captured-at", CAPTURED_AT, "--coverage", "PARTIAL", "--messages", adapted / "messages.jsonl")
    check(imported["inserted"] is True and imported["messages"] == 1, "Normalized SQS output was not accepted by import")
    sqs_review = temporary / "sqs-review"
    before_sqs_analysis = database.read_bytes()
    analyze(sqs_review, snapshot="sqs-smoke", gate=True)
    verify_plan(sqs_review, adapted, {"counts": {"CANDIDATE": 1, "HOLD": 0, "EXCLUDE": 0},
                                      "candidates": ["synthetic-sqs-message-1"], "batches": [["synthetic-sqs-message-1"]]})
    check(database.read_bytes() == before_sqs_analysis, "SQS review modified the ledger")

    return {"status": "passed", "installedCliCalls": cli.calls, "demoCounts": {name: value["counts"] for name, value in EXPECTED.items()},
            "verified": ["packaged demo resources", "exact candidate and manifest batches", "raw source/line provenance",
                         "byte-stable read-only analysis", "idempotent import", "atomic rejection and retry",
                         "output overwrite protection", "policy/time plan binding", "offline SQS conversion and import", "payload omission"],
            "messagesReplayed": 0}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("install_dir", type=Path, help="installDist or unpacked distribution directory")
    parser.add_argument("--java", help="Java 21+ executable; otherwise use JAVA_HOME or PATH")
    args = parser.parse_args()
    cli = Cli(args.install_dir, args.java)
    with tempfile.TemporaryDirectory(prefix="queuesift-cli-smoke-") as directory:
        result = smoke(cli, Path(directory))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
