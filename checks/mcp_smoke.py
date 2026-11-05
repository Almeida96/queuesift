#!/usr/bin/env python3
"""Check the packaged QueueSift CLI with the official MCP Python SDK; no model involved.

Install the pinned client: python -m pip install mcp==1.26.0
Use an imported demo snapshot containing CANDIDATE, HOLD and EXCLUDE decisions and
at least one payload string containing "canary". The check starts two local stdio
sessions, with payload access disabled and enabled, and never replays messages.

Example after Gradle installDist and demo import:
  python checks/mcp_smoke.py --install-dir build/install/queuesift \
      --db build/demo/evidence.db --snapshot fulfillment --policy build/demo/policy.json \
      --as-of 2026-11-04T12:05:00Z

Direct Java invocation of lib/* works on Windows and POSIX without shell quoting.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import shutil
from datetime import timedelta
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--install-dir", required=True, type=Path, help="Unpacked distribution or installDist directory")
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--snapshot", required=True, help="Imported snapshot ID")
    parser.add_argument("--policy", required=True, type=Path)
    parser.add_argument("--as-of", required=True, help="Fixed evaluation instant")
    parser.add_argument("--java", help="Java 21+ executable; defaults to JAVA_HOME/bin/java or PATH")
    return parser.parse_args()


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def structured(result: Any, *, error: bool = False) -> dict[str, Any]:
    check(result.isError is error, f"Unexpected tool status: {result}")
    check(isinstance(result.structuredContent, dict), "Tool must return a structured object")
    check(len(result.content) == 1 and result.content[0].type == "text", "Expected one JSON text block")
    check(json.loads(result.content[0].text) == result.structuredContent, "Text and structuredContent differ")
    return result.structuredContent


def assert_no_payload(value: Any) -> None:
    if isinstance(value, dict):
        check("payload" not in value, "Payload appeared outside opted-in message evidence")
        for child in value.values():
            assert_no_payload(child)
    elif isinstance(value, list):
        for child in value:
            assert_no_payload(child)


def canary_strings(value: Any) -> set[str]:
    if isinstance(value, str):
        return {value} if "canary" in value.casefold() else set()
    children = value.values() if isinstance(value, dict) else value if isinstance(value, list) else []
    return set().union(*(canary_strings(child) for child in children))


async def pages(session: Any, tool: str, field: str, filters: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    options = dict(filters or {})
    first = structured(await session.call_tool(tool, {**options, "limit": 1}))
    assert_no_payload(first)
    check(first["offset"] == 0 and first["limit"] == 1, "Incorrect page metadata")
    check(first == structured(await session.call_tool(tool, {**options, "limit": 1})), "Page is not repeatable")
    items = list(first[field])
    check(len(items) == min(1, first["total"]), "Incorrect first page length")
    check(first["nextOffset"] == (1 if first["total"] > 1 else None), "Incorrect first nextOffset")
    next_offset = first["nextOffset"]
    while next_offset is not None:
        page = structured(await session.call_tool(tool, {**options, "offset": next_offset, "limit": 17}))
        assert_no_payload(page)
        check(page["total"] == first["total"] and page["offset"] == next_offset, "Inconsistent page metadata")
        check(0 < len(page[field]) <= 17, "Unexpected page length")
        expected_next = next_offset + len(page[field])
        check(page["nextOffset"] == (expected_next if expected_next < page["total"] else None), "Incorrect nextOffset")
        items.extend(page[field])
        next_offset = page["nextOffset"]
    check(len(items) == first["total"], "Pagination lost or repeated items")
    beyond = structured(await session.call_tool(tool, {**options, "offset": 2147483647}))
    check(beyond[field] == [] and beyond["nextOffset"] is None, "Large offset must return an empty final page")
    return items


async def smoke(options: argparse.Namespace) -> dict[str, Any]:
    from jsonschema import Draft202012Validator
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    from mcp.shared.exceptions import McpError

    distribution = options.install_dir.resolve(strict=True)
    check((distribution / "lib").is_dir(), "No lib directory; run installDist first")
    check(any((distribution / "lib").glob("*.jar")), "Installed lib directory contains no JARs")
    java_arg = options.java or (str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
                                if os.environ.get("JAVA_HOME") else "java")
    java = str(Path(java_arg).resolve()) if Path(java_arg).is_file() else shutil.which(java_arg)
    check(java is not None, "Java executable not found; supply --java /path/to/java")
    args = ["-Xmx512m", "-cp", str(distribution / "lib" / "*"), "io.github.almeida96.queuesift.MainKt", "mcp",
            "--db", str(options.db.resolve(strict=True)), "--snapshot", options.snapshot,
            "--policy", str(options.policy.resolve(strict=True)), "--as-of", options.as_of]
    public_responses: list[Any] = []
    default_evidence: dict[str, Any] = {}
    private_evidence: dict[str, Any] = {}
    default_decisions: list[dict[str, Any]] | None = None
    protocol = ""
    tool_names = {"recovery_summary", "failure_groups", "list_decisions", "message_evidence", "list_batches"}
    for allow_payload in (False, True):
        command = StdioServerParameters(command=java, args=args + (["--include-payload"] if allow_payload else []))
        async with stdio_client(command) as (read, write):
            async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=30)) as session:
                initialized = await session.initialize()
                protocol = initialized.protocolVersion
                check(protocol in {"2025-11-25", "2025-06-18"}, "Unexpected protocol version")
                check(initialized.serverInfo.name == "queuesift", "Unexpected server name")
                check(initialized.capabilities.tools is not None, "Server did not advertise tools")
                await session.send_ping()
                listed = await session.list_tools()
                tools = {tool.name: tool for tool in listed.tools}
                check(set(tools) == tool_names, "Unexpected tool inventory")
                for tool in listed.tools:
                    Draft202012Validator.check_schema(tool.inputSchema)
                    check(tool.inputSchema.get("type") == "object", "Tool schema must describe an object")
                    check(tool.inputSchema.get("additionalProperties") is False, "Arguments must reject unknown fields")
                    check("includePayload" not in tool.inputSchema.get("properties", {}), "Caller must not enable payloads")
                    hints = tool.annotations
                    check(hints is not None and hints.readOnlyHint is True, "Missing read-only hint")
                    check(hints.destructiveHint is False and hints.idempotentHint is True and hints.openWorldHint is False,
                          "Annotations must describe a closed, read-only tool")
                check(tools["message_evidence"].inputSchema.get("required") == ["messageId"], "messageId must be required")
                for name in ("failure_groups", "list_decisions", "list_batches"):
                    check(tools[name].inputSchema["properties"]["limit"]["maximum"] == 50, "Page size must be bounded at 50")
                summary = structured(await session.call_tool("recovery_summary", {}))
                check("decisions" not in summary, "Summary leaked full decisions")
                check(all(key in summary for key in ("snapshot", "snapshotDigest", "policyDigest", "asOf", "planId", "counts", "assumptions")),
                      "Summary is missing audit context")
                check(summary["payloadAccessEnabled"] is allow_payload, "Incorrect startup payload state")
                check(summary["snapshot"]["id"] == options.snapshot, "Wrong snapshot returned")
                for key in ("snapshotDigest", "policyDigest"):
                    check(len(summary[key]) == 64, "Fingerprint must be SHA-256")
                assert_no_payload(summary)
                decisions = await pages(session, "list_decisions", "decisions")
                groups = await pages(session, "failure_groups", "groups")
                batches = await pages(session, "list_batches", "batches")
                public_responses.extend((summary, decisions, groups, batches))
                identifiers = [decision["messageId"] for decision in decisions]
                check(identifiers == sorted(set(identifiers)), "Decisions must have unique message IDs in stable order")
                check({decision["outcome"] for decision in decisions} == {"CANDIDATE", "HOLD", "EXCLUDE"},
                      "Use a demo fixture with all three outcomes")
                check([group["code"] for group in groups] == sorted({group["code"] for group in groups}), "Unstable failure group order")
                check([batch["number"] for batch in batches] == sorted({batch["number"] for batch in batches}), "Unstable batch order")
                candidates = {decision["messageId"] for decision in decisions if decision["outcome"] == "CANDIDATE"}
                batched_ids = [message_id for batch in batches for message_id in batch["messageIds"]]
                check(set(batched_ids) == candidates and len(batched_ids) == len(candidates), "Batches must include candidates exactly once")
                check(sum(group["total"] for group in groups) == len(decisions), "Failure groups disagree with decisions")
                if default_decisions is None:
                    default_decisions = decisions
                else:
                    check(decisions == default_decisions, "Payload opt-in changed the plan")
                selected = decisions[0]
                filters = {key: selected[key] for key in ("outcome", "failureCode", "tenant")}
                filtered = await pages(session, "list_decisions", "decisions", filters)
                check(any(item["messageId"] == selected["messageId"] for item in filtered), "Exact filters lost a decision")
                check(all(all(item[key] == value for key, value in filters.items()) for item in filtered), "Nonmatching decision in filtered results")
                public_responses.append(filtered)
                for decision in decisions:
                    evidence = structured(await session.call_tool("message_evidence", {"messageId": decision["messageId"]}))
                    check(evidence["messageId"] == decision["messageId"] and evidence["id"] == decision["id"], "Wrong message evidence")
                    check(all(key in evidence for key in ("reasons", "source", "contentHash", "outcome")), "Incomplete decision evidence")
                    check(evidence["source"]["line"] > 0 and len(evidence["source"]["lineSha256"]) == 64, "Incomplete line provenance")
                    check(evidence["payloadOmitted"] is (not allow_payload), "Incorrect payload omission flag")
                    if allow_payload:
                        check("payload" in evidence, "Startup opt-in did not expose payload")
                        private_evidence[decision["messageId"]] = evidence
                    else:
                        assert_no_payload(evidence)
                        default_evidence[decision["messageId"]] = evidence
                missing = structured(await session.call_tool("message_evidence", {"messageId": "smoke-test-missing-id"}), error=True)
                check(missing["error"]["code"] == "MESSAGE_NOT_FOUND", "Unknown messageId must produce explicit tool error")
                invalid_calls = [("list_decisions", {"path": "/arbitrary/file"}), ("list_decisions", {"outcome": "candidate"}),
                                 ("message_evidence", {"messageId": selected["messageId"], "includePayload": True})]
                for name in ("failure_groups", "list_decisions", "list_batches"):
                    invalid_calls.extend((name, invalid) for invalid in ({"limit": 51}, {"offset": -1}, {"limit": "20"}))
                for name, invalid in invalid_calls:
                    try:
                        await session.call_tool(name, invalid)
                    except McpError as failure:
                        check(failure.error.code == -32602, "Invalid arguments must produce Invalid params")
                    else:
                        raise AssertionError(f"Server accepted invalid arguments for {name}: {invalid}")
    canaries: set[str] = set()
    for message_id, evidence in private_evidence.items():
        canaries.update(canary_strings(evidence["payload"]))
        metadata = {key: value for key, value in evidence.items() if key not in {"payload", "payloadOmitted"}}
        expected = {key: value for key, value in default_evidence[message_id].items() if key != "payloadOmitted"}
        check(metadata == expected, "Payload opt-in changed decision evidence")
    check(bool(canaries), "Use a demo fixture with a payload string containing 'canary' to check privacy")
    public_text = json.dumps([public_responses, default_evidence], ensure_ascii=False)
    check(all(canary not in public_text for canary in canaries), "Secret canary appeared outside opted-in evidence")
    return {"status": "passed", "client": "official MCP Python SDK", "clientVersion": "1.26.0",
            "protocolVersion": protocol, "tools": sorted(tool_names), "decisionsPaged": len(default_evidence),
            "payloadPrivacy": "default omitted; startup opt-in only; collection tools never expose payloads",
            "canariesChecked": len(canaries), "modelInvoked": False, "messagesReplayed": 0}


def main() -> None:
    options = arguments()
    try:
        installed = version("mcp")
    except PackageNotFoundError:
        raise SystemExit("Install the official client first: python -m pip install mcp==1.26.0") from None
    if installed != "1.26.0":
        raise SystemExit(f"Expected mcp==1.26.0, found {installed}; use a dedicated virtual environment")
    print(json.dumps(asyncio.run(smoke(options)), indent=2))


if __name__ == "__main__":
    main()
