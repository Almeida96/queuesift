#!/usr/bin/env python3
"""Bounded stdlib-only MCP startup check for an already imported scale fixture.

Starts the installed distribution with -Xmx512m, initializes MCP, then reads a
summary and one message's evidence. It does not write to the ledger, call a model,
request payloads, or create a fixture. Logs go to a required new output directory.

Example after the CLI scale fixture has been imported:
  python checks/mcp_scale_smoke.py --install-dir build/install/queuesift \
      --db build/scale-smoke-20261104/failed-rules/evidence.db \
      --snapshot failed-rules --policy build/scale-smoke-20261104/failed-rules/policy.json \
      --as-of 2026-11-04T12:00:00Z --out build/mcp-scale-check
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
from typing import BinaryIO

STDOUT_LIMIT = 4 * 1024 * 1024
STDERR_LIMIT = 256 * 1024


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--install-dir", required=True, type=Path)
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--snapshot", required=True)
    parser.add_argument("--policy", required=True, type=Path)
    parser.add_argument("--as-of", required=True)
    parser.add_argument("--out", required=True, type=Path, help="New directory for stdout, stderr and summary logs")
    parser.add_argument("--message-id", default="m00000")
    parser.add_argument("--expected-total", type=int, default=10000)
    parser.add_argument("--expected-hold", type=int, default=10000)
    parser.add_argument("--canary", default="QUEUESIFT_SCALE_PRIVATE_PAYLOAD_CANARY")
    parser.add_argument("--timeout", type=int, default=120, help="Process deadline in seconds, 1..300")
    parser.add_argument("--java", help="Java executable; otherwise JAVA_HOME/bin/java or PATH")
    options = parser.parse_args()
    if not 1 <= options.timeout <= 300:
        parser.error("--timeout must be between 1 and 300 seconds")
    if options.expected_total < 1 or not 0 <= options.expected_hold <= options.expected_total:
        parser.error("Expected counts must describe a nonempty fixture")
    return options


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            result.update(chunk)
    return result.hexdigest()


def capture(pipe: BinaryIO, destination: Path, maximum: int, process: subprocess.Popen,
            exceeded: threading.Event, failures: list[str]) -> None:
    """Drain one pipe without unbounded RAM/disk use; overflow terminates the child."""
    written = 0
    try:
        with destination.open("xb") as output:
            while chunk := pipe.read(65536):
                room = maximum - written
                output.write(chunk[:room])
                written += min(len(chunk), room)
                if len(chunk) > room:
                    exceeded.set()
                    process.kill()
                    break
    except Exception as failure:
        failures.append(f"Could not capture {destination.name}: {failure}")
        process.kill()
    finally:
        pipe.close()


def no_payload(value: object) -> None:
    if isinstance(value, dict):
        check("payload" not in value, "Default MCP session returned a payload field")
        for child in value.values():
            no_payload(child)
    elif isinstance(value, list):
        for child in value:
            no_payload(child)


def run(options: argparse.Namespace) -> dict:
    distribution = options.install_dir.resolve(strict=True)
    check(any((distribution / "lib").glob("*.jar")), "Distribution has no lib/*.jar; run installDist")
    database = options.db.resolve(strict=True)
    policy = options.policy.resolve(strict=True)
    java_arg = options.java or (str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
                                if os.environ.get("JAVA_HOME") else "java")
    java = str(Path(java_arg).resolve()) if Path(java_arg).is_file() else shutil.which(java_arg)
    check(java is not None, "Java executable not found; supply --java")
    output = options.out.resolve()
    output.mkdir(parents=True, exist_ok=False)
    before = digest(database)
    requests = [
        {"jsonrpc": "2.0", "id": "init", "method": "initialize", "params": {
            "protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "queuesift-scale-check", "version": "1"}}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "recovery_summary", "arguments": {}}},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "message_evidence", "arguments": {"messageId": options.message_id}}},
    ]
    encoded = ("".join(json.dumps(request) + "\n" for request in requests)).encode("utf-8")
    check(len(encoded) < 4096, "Smoke requests exceed the bounded stdin budget")
    command = [java, "-Xmx512m", "-cp", str(distribution / "lib" / "*"), "io.github.almeida96.queuesift.MainKt", "mcp",
               "--db", str(database), "--snapshot", options.snapshot, "--policy", str(policy), "--as-of", options.as_of]
    started = time.monotonic()
    stdout_path, stderr_path = output / "stdout.jsonl", output / "stderr.log"
    report = {"status": "failed", "heapLimit": "512 MiB", "snapshot": options.snapshot,
              "logs": str(output), "modelInvoked": False, "messagesReplayed": 0}
    try:
        exceeded = threading.Event()
        failures: list[str] = []
        with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
            readers = [
                threading.Thread(target=capture, args=(process.stdout, stdout_path, STDOUT_LIMIT, process, exceeded, failures), daemon=True),
                threading.Thread(target=capture, args=(process.stderr, stderr_path, STDERR_LIMIT, process, exceeded, failures), daemon=True),
            ]
            for reader in readers:
                reader.start()
            try:
                process.stdin.write(encoded)
                process.stdin.close()
                process.wait(timeout=options.timeout)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
                failures.append(f"MCP startup/exchange exceeded {options.timeout} seconds")
            finally:
                for reader in readers:
                    reader.join(timeout=5)
            report["exitCode"] = process.returncode
            check(not any(reader.is_alive() for reader in readers), "Output readers did not finish")
            check(not exceeded.is_set(), "MCP exceeded bounded stdout/stderr limits")
            check(not failures, "; ".join(failures))
            check(process.returncode == 0, f"MCP exited {process.returncode}; inspect stderr.log")
        raw = stdout_path.read_text(encoding="utf-8")
        responses = [json.loads(line) for line in raw.splitlines()]
        check([response.get("id") for response in responses] == ["init", 1, 2], "Expected exactly three JSON-RPC responses")
        check(all("result" in response and "error" not in response for response in responses), "Protocol error in response")
        initialized = responses[0]["result"]
        check(initialized["serverInfo"]["name"] == "queuesift", "Unexpected MCP server")
        check(initialized["protocolVersion"] == "2025-11-25", "Unexpected protocol version")
        for response in responses[1:]:
            result = response["result"]
            check(result["isError"] is False, "Tool call failed")
            check(len(result["content"]) == 1 and result["content"][0]["type"] == "text", "Unexpected content shape")
            check(json.loads(result["content"][0]["text"]) == result["structuredContent"], "JSON text/structuredContent mismatch")
            no_payload(result["structuredContent"])
        summary = responses[1]["result"]["structuredContent"]
        evidence = responses[2]["result"]["structuredContent"]
        check(summary["snapshot"]["id"] == options.snapshot, "Wrong snapshot")
        check(sum(summary["counts"].values()) == options.expected_total, "Unexpected total decision count")
        check(summary["counts"]["HOLD"] == options.expected_hold, "Unexpected HOLD count")
        check(summary["payloadAccessEnabled"] is False and "decisions" not in summary, "Unexpected summary privacy state")
        check(evidence["messageId"] == options.message_id and evidence["payloadOmitted"] is True, "Wrong evidence/privacy state")
        check(bool(evidence["reasons"]), "Expected decision reasons")
        check(not options.canary or options.canary not in raw, "Payload canary leaked")
        report.update(status="passed", counts=summary["counts"], evidenceMessageId=options.message_id,
                      evidenceReasonCount=len(evidence["reasons"]), stdoutBytes=stdout_path.stat().st_size)
    except Exception as failure:
        report["error"] = str(failure)
    finally:
        report["seconds"] = round(time.monotonic() - started, 2)
        report["dbUnchanged"] = before == digest(database)
        if not report["dbUnchanged"]:
            report.update(status="failed", error="Read-only MCP run changed the database bytes")
        with (output / "summary.json").open("x", encoding="utf-8") as destination:
            json.dump(report, destination, indent=2)
    return report


def main() -> int:
    try:
        result = run(arguments())
    except Exception as failure:
        print(f"mcp-scale-check: {failure}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
