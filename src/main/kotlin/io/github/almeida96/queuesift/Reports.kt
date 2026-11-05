package io.github.almeida96.queuesift

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

object Reports {
    fun write(plan: RecoveryPlan, directory: Path) {
        require(Files.isDirectory(directory)) { "Report directory must exist" }
        Files.list(directory).use { require(it.findAny().isEmpty) { "Report directory must be empty" } }
        Files.newBufferedWriter(directory.resolve("plan.json"), UTF_8, CREATE_NEW).use {
            Json.mapper.writerWithDefaultPrettyPrinter().writeValue(it, plan)
        }
        write(directory, "report.md", markdown(plan))
        write(directory, "report.html", html(plan))
        val byId = plan.decisions.associateBy { it.messageId }
        val manifest = buildString {
            for (batch in plan.batches) for (id in batch.messageIds) {
                val decision = requireNotNull(byId[id])
                check(decision.outcome == Outcome.CANDIDATE) { "Batch contains a non-candidate" }
                appendLine(Json.text(linkedMapOf(
                    "schemaVersion" to 1, "planId" to plan.planId,
                    "snapshot" to plan.snapshot.id, "snapshotDigest" to plan.snapshotDigest,
                    "policyDigest" to plan.policyDigest, "asOf" to plan.asOf,
                    "batch" to batch.number, "messageId" to id, "tenant" to decision.tenant,
                    "eventId" to decision.eventId, "eventType" to decision.eventType,
                    "contentHash" to decision.contentHash, "orderingKey" to decision.orderingKey,
                    "sequence" to decision.sequence, "source" to decision.source,
                    "requiresOperatorReview" to true,
                )))
            }
        }
        write(directory, "replay-candidates.jsonl", manifest)
    }

    private fun write(directory: Path, name: String, value: String) =
        Files.writeString(directory.resolve(name), value, UTF_8, CREATE_NEW)

    private fun markdown(plan: RecoveryPlan) = buildString {
        appendLine("# QueueSift recovery review")
        appendLine()
        appendLine("Snapshot: **${md(plan.snapshot.id)}** · Queue: **${md(plan.snapshot.queue)}**")
        appendLine("Captured: ${md(plan.snapshot.capturedAt)} · Evaluated: ${md(plan.asOf)} · Coverage: ${md(plan.snapshot.coverage)}")
        appendLine()
        appendLine("CANDIDATE: ${plan.counts["CANDIDATE"] ?: 0} · HOLD: ${plan.counts["HOLD"] ?: 0} · EXCLUDE: ${plan.counts["EXCLUDE"] ?: 0}")
        appendLine()
        appendLine("Candidates passed this policy using the supplied evidence and operator assertions. They require review; this tool neither authorizes nor executes replay. A missing receipt is not proof that a message was never processed.")
        appendLine()
        appendLine("## Decisions")
        appendLine()
        appendLine("| Message | Tenant | Event | Outcome | Reasons |")
        appendLine("| --- | --- | --- | --- | --- |")
        for (d in plan.decisions) appendLine("| ${md(d.messageId)} | ${md(d.tenant)} | ${md(d.eventId ?: "(missing)")} | ${d.outcome} | ${d.reasons.joinToString("; ") { md(it.code + ": " + it.detail) }} |")
        appendLine()
        appendLine("## Review batches")
        appendLine()
        if (plan.batches.isEmpty()) appendLine("No candidates.")
        for (batch in plan.batches) appendLine("${batch.number}. ${batch.messageIds.joinToString(", ") { md(it) }}")
        appendLine()
        appendLine("## Assumptions and evidence")
        appendLine()
        for (assumption in plan.assumptions) appendLine("- ${md(assumption)}")
        appendLine()
        appendLine("Plan: `${plan.planId}`  ")
        appendLine("Snapshot SHA-256: `${plan.snapshotDigest}`  ")
        appendLine("Policy SHA-256: `${plan.policyDigest}`")
        appendLine()
        appendLine("`plan.json` contains reason codes, policy paths, source line references and their hashes. `replay-candidates.jsonl` is a payload-free review manifest. Metadata may still be sensitive. Recheck current consumer and queue state before implementing any separate recovery procedure.")
    }

    private fun html(plan: RecoveryPlan) = buildString {
        append("""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<title>QueueSift · recovery review</title><style>
:root{color-scheme:light;--ink:#172b38;--muted:#526875;--line:#d4dedf;--paper:#f4f6f3;--accent:#17695c}*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:15px/1.55 system-ui,-apple-system,Segoe UI,sans-serif}main{max-width:1320px;margin:auto;padding:38px 36px 64px}.mast{display:flex;justify-content:space-between;gap:24px;align-items:center;border-bottom:1px solid var(--line);padding-bottom:20px}.brand{font-size:23px;font-weight:750;letter-spacing:-.7px}.mark{display:inline-grid;place-items:center;width:32px;height:32px;margin-right:10px;color:#fff;background:var(--accent);border-radius:7px;font-size:17px}.eyebrow{font-size:11px;letter-spacing:1.8px;text-transform:uppercase;font-weight:750;color:var(--accent)}h1{font-size:40px;letter-spacing:-1.5px;line-height:1.15;margin:20px 0 12px}h2{font-size:20px;letter-spacing:-.4px;margin:0 0 16px}p{margin:8px 0}.sub{max-width:800px;color:var(--muted)}.hero{padding:30px 0 25px}.tag{display:inline-block;border:1px solid var(--line);padding:5px 10px;border-radius:5px;font-size:12px;font-weight:600}.facts{display:flex;flex-wrap:wrap;gap:12px 28px;margin:22px 0 0;font-size:12px;color:var(--muted)}.facts strong{display:block;color:var(--ink);font-size:13px;margin-top:3px}.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin:0 0 24px}.stat{background:#fff;border:1px solid var(--line);border-top:3px solid var(--accent);border-radius:8px;padding:19px 22px}.stat.hold{border-top-color:#ae6b16}.stat.exclude{border-top-color:#687d8a}.num{font-size:38px;line-height:1.15;font-weight:720;letter-spacing:-1px}.stat small{display:block;color:var(--muted);margin-top:6px}.label{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;font-weight:650;font-size:13px}.panel{background:#fff;border:1px solid var(--line);border-radius:8px;padding:24px;margin-bottom:20px}.split{display:grid;grid-template-columns:1.1fr 1fr;gap:20px}.note{border-left:3px solid #b87522;padding:12px 18px;background:#fbf2df;font-size:13px;margin:20px 0 24px}.scroll{overflow:auto}table{width:100%;border-collapse:collapse;text-align:left;font-size:13px}th{font-size:10px;color:var(--muted);letter-spacing:1px;text-transform:uppercase;padding:10px 12px;border-bottom:1px solid var(--line);white-space:nowrap}td{padding:13px 12px;border-bottom:1px solid #e6ebeb;vertical-align:top}tbody tr:last-child td{border:0}.badge{display:inline-block;font-size:10px;letter-spacing:.6px;font-weight:750;border-radius:4px;padding:4px 7px;background:#e4f3eb;color:#155342;white-space:nowrap}.badge.HOLD{background:#fff0d4;color:#865312}.badge.EXCLUDE{background:#eaf0f4;color:#506575}code{font:12px/1.5 ui-monospace,Consolas,monospace;overflow-wrap:anywhere}.muted{color:var(--muted)}summary{cursor:pointer;font-weight:600}details p{font-size:12px;color:var(--muted)}.batch{display:flex;gap:14px;border-top:1px solid var(--line);padding:13px 0}.batch strong{font-size:12px;min-width:65px}ul{padding-left:18px;font-size:13px;color:var(--muted)}li+li{margin-top:8px}.hashes{display:grid;grid-template-columns:130px 1fr;gap:8px 16px;font-size:12px}.hashes span{color:var(--muted)}footer{font-size:12px;color:var(--muted);padding-top:12px}.empty{color:var(--muted);font-size:13px}@media(max-width:760px){main{padding:22px 16px}.mast{align-items:flex-start}.stats,.split{grid-template-columns:1fr}h1{font-size:32px}.panel{padding:18px}.hashes{grid-template-columns:1fr}.facts{gap:12px}.mast .muted{display:none}}@media print{body{background:white}main{max-width:none;padding:0}.panel,.stat{break-inside:avoid}details{display:block}.scroll{overflow:visible}}
</style></head><body><main><header class="mast"><div class="brand"><span class="mark">Q</span>QueueSift</div><div class="muted">Dead-letter investigation · v0.1.0</div></header>
<section class="hero"><div class="eyebrow">Recovery review</div><h1>Know what is waiting.<br>Decide what happens next.</h1><p class="sub">An evidence-backed review of failed deliveries, duplicate events and processing history. Every decision is linked to the imported source.</p>
""")
        append("<div class=\"facts\"><div>Queue<strong>${h(plan.snapshot.queue)}</strong></div><div>Snapshot<strong>${h(plan.snapshot.id)}</strong></div><div>Captured<strong>${h(plan.snapshot.capturedAt)}</strong></div><div>Evaluated<strong>${h(plan.asOf)}</strong></div><div>Coverage<strong>${h(plan.snapshot.coverage)}</strong></div></div></section>")
        append("<section class=\"stats\">")
        for ((outcome, label, description) in listOf(Triple("CANDIDATE", "Candidates for review", "Passed the selected policy checks"), Triple("HOLD", "Needs investigation", "Unresolved evidence or policy checks"), Triple("EXCLUDE", "Excluded from this plan", "Duplicate delivery or matching receipt"))) {
            append("<div class=\"stat ${outcome.lowercase()}\"><div class=\"label\">${h(label)}<span class=\"badge $outcome\">$outcome</span></div><div class=\"num\">${plan.counts[outcome] ?: 0}</div><small>${h(description)}</small></div>")
        }
        append("</section><div class=\"note\"><strong>This is a review plan, not a replay approval.</strong> Candidates depend on the supplied evidence and operator assertions. Missing receipts do not prove an event was never processed. QueueSift does not send messages.</div>")
        append("<div class=\"split\"><section class=\"panel\"><h2>Failure groups</h2><div class=\"scroll\"><table><thead><tr><th>Failure code</th><th>Total</th><th>Candidate</th><th>Hold</th><th>Exclude</th></tr></thead><tbody>")
        for (g in plan.groups) append("<tr><td><code>${h(g.code)}</code></td><td>${g.total}</td><td>${g.candidate}</td><td>${g.hold}</td><td>${g.exclude}</td></tr>")
        append("</tbody></table></div></section><section class=\"panel\"><h2>Review batches</h2><p class=\"muted\">Payload-free references, in planned order.</p>")
        if (plan.batches.isEmpty()) append("<p class=\"empty\">No candidates in this plan.</p>")
        for (b in plan.batches) append("<div class=\"batch\"><strong>Batch ${b.number}</strong><code>${b.messageIds.joinToString(" · ") { h(it) }}</code></div>")
        append("</section></div><section class=\"panel\"><h2>Message decisions</h2><div class=\"scroll\"><table><thead><tr><th>Message / event</th><th>Tenant</th><th>Outcome</th><th>Evidence and policy</th></tr></thead><tbody>")
        for (d in plan.decisions) {
            append("<tr><td><strong>${h(d.messageId)}</strong><br><code class=\"muted\">${h(d.eventId ?: "missing event ID")}</code></td><td>${h(d.tenant)}</td><td><span class=\"badge ${d.outcome}\">${d.outcome}</span></td><td>")
            for (r in d.reasons) {
                append("<details><summary><code>${h(r.code)}</code></summary><p>${h(r.detail)}</p>")
                r.policyPath?.let { append("<p>Policy: <code>${h(it)}</code></p>") }
                for (e in r.evidence) append("<p>Source: ${h(e.file)}:${e.line}<br>Line SHA-256: <code>${h(e.lineSha256)}</code></p>")
                append("</details>")
            }
            append("<p class=\"muted\">${h(d.source.file)}:${d.source.line}</p></td></tr>")
        }
        append("</tbody></table></div></section><section class=\"panel\"><h2>Assumptions and provenance</h2><ul>")
        for (a in plan.assumptions) append("<li>${h(a)}</li>")
        append("</ul><div class=\"hashes\"><span>Plan ID</span><code>${h(plan.planId)}</code><span>Snapshot SHA-256</span><code>${h(plan.snapshotDigest)}</code><span>Policy SHA-256</span><code>${h(plan.policyDigest)}</code></div></section><footer>Generated locally by QueueSift. No payloads, scripts, analytics or external assets are included in this report. Identifiers and metadata may still be sensitive. Full decision evidence: plan.json · Review manifest: replay-candidates.jsonl</footer></main></body></html>")
    }

    private fun h(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun md(value: String) = value.replace("\\", "\\\\").replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;").replace("[", "\\[").replace("]", "\\]").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_").replace("\n", " ").replace("\r", " ")
}
