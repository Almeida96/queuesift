package io.github.almeida96.queuesift

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlin.system.exitProcess

fun main(args: Array<String>) { exitProcess(Cli.run(args, System.out, System.err)) }

object Cli {
    const val VERSION = "0.1.0"
    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int = try {
        execute(args, out)
    } catch (exception: Exception) {
        err.println("queuesift: ${exception.message ?: exception.javaClass.simpleName}")
        1
    }

    private fun execute(args: Array<String>, out: PrintStream): Int {
        if (args.isEmpty() || args[0] in setOf("--help", "help")) {
            out.println("""
                QueueSift 0.1.0 — dead-letter investigation and recovery planning

                import --db FILE --id ID --queue NAME --captured-at ISO --coverage PARTIAL|DECLARED_COMPLETE
                       --messages FILE [--receipts FILE] [--checkpoints FILE]
                snapshots --db FILE
                analyze --db FILE --snapshot ID --policy FILE --as-of ISO --out NEW_DIR [--gate]
                mcp --db FILE --snapshot ID --policy FILE --as-of ISO [--include-payload]
                adapt-sqs --input FILE --tenant-pointer PTR --event-id-pointer PTR --event-type-pointer PTR
                          --failure-code CODE --out NEW_DIR [--occurred-at-pointer PTR] [--expires-at-pointer PTR]
                fingerprint --event-type TYPE --payload FILE
                demo --out NEW_DIR
                --version

                No command connects to a broker or sends messages. Candidates require operator review.
                analyze --gate exits 2 for any HOLD, 0 otherwise. Input/execution errors exit 1.
                All output directories must be new. Reports and manifests omit payloads.
            """.trimIndent())
            return 0
        }
        if (args.contentEquals(arrayOf("--version"))) { out.println(VERSION); return 0 }
        val command = args[0]
        val allowed = when (command) {
            "import" -> setOf("db", "id", "queue", "captured-at", "coverage", "messages", "receipts", "checkpoints")
            "snapshots" -> setOf("db")
            "analyze" -> setOf("db", "snapshot", "policy", "as-of", "out", "gate")
            "mcp" -> setOf("db", "snapshot", "policy", "as-of", "include-payload")
            "adapt-sqs" -> setOf("input", "tenant-pointer", "event-id-pointer", "event-type-pointer", "occurred-at-pointer", "expires-at-pointer", "failure-code", "out")
            "fingerprint" -> setOf("event-type", "payload")
            "demo" -> setOf("out")
            else -> throw IllegalArgumentException("Unknown command: $command")
        }
        val options = options(args.drop(1), allowed)
        fun required(key: String) = options[key]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Required option: --$key")
        when (command) {
            "import" -> Ledger(Path.of(required("db")), true).use { ledger ->
                val meta = SnapshotMeta(required("id"), required("queue"), required("captured-at"), required("coverage"))
                out.print(Json.pretty(ledger.importSnapshot(meta, Path.of(required("messages")), options["receipts"]?.let(Path::of), options["checkpoints"]?.let(Path::of))))
            }
            "snapshots" -> Ledger(Path.of(required("db"))).use { out.print(Json.pretty(it.snapshots())) }
            "analyze", "mcp" -> {
                val snapshot = Ledger(Path.of(required("db"))).use { it.load(required("snapshot")) }
                val policy = PolicyReader.read(Path.of(required("policy")))
                val plan = RecoveryEngine.analyze(snapshot, policy, required("as-of"))
                if (command == "mcp") McpServer(plan, snapshot.messages, options.containsKey("include-payload")).run(System.`in`, out)
                else {
                    Reports.write(plan, newDirectory(Path.of(required("out"))))
                    out.print(Json.pretty(mapOf("planId" to plan.planId, "snapshot" to plan.snapshot.id, "counts" to plan.counts, "batches" to plan.batches.size, "requiresOperatorReview" to true)))
                    if (options.containsKey("gate") && (plan.counts["HOLD"] ?: 0) > 0) return 2
                }
            }
            "adapt-sqs" -> {
                val result = SqsAdapter.convert(Path.of(required("input")), options, Path.of(required("out")))
                out.print(Json.pretty(result))
            }
            "fingerprint" -> {
                val eventType = required("event-type")
                Records.text(eventType, "event type")
                val payload = Json.readFile(Path.of(required("payload")))
                out.println(Json.text(mapOf("eventType" to eventType, "contentHash" to Json.contentHash(eventType, payload))))
            }
            "demo" -> Demo.run(Path.of(required("out")), out)
        }
        return 0
    }

    private fun options(args: List<String>, allowed: Set<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val token = args[index++]
            require(token.startsWith("--") && token.substring(2) in allowed) { "Unknown option: $token" }
            val name = token.substring(2)
            require(name !in result) { "Duplicate option: $token" }
            if (name in setOf("gate", "include-payload")) result[name] = "true"
            else {
                require(index < args.size && !args[index].startsWith("--")) { "Missing value: $token" }
                result[name] = args[index++]
            }
        }
        return result
    }

    fun newDirectory(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        require(!Files.exists(absolute, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Output already exists: $path" }
        absolute.parent?.let(Files::createDirectories)
        return Files.createDirectory(absolute)
    }
}

object Demo {
    const val AS_OF = "2026-11-04T12:05:00Z"
    fun run(path: Path, out: PrintStream) {
        val directory = Cli.newDirectory(path)
        val inputs = listOf("messages.jsonl", "receipts.jsonl", "ordered.jsonl", "checkpoints.jsonl", "policy.json", "unresolved-policy.json", "ordered-policy.json", "sqs-export.json")
        for (name in inputs) Demo::class.java.getResourceAsStream("/fulfillment/$name").use {
            require(it != null) { "Missing packaged demo: $name" }
            Files.copy(it, directory.resolve(name))
        }
        val database = directory.resolve("evidence.db")
        Ledger(database, true).use { ledger ->
            val main = SnapshotMeta("fulfillment", "orders.dead", "2026-11-04T12:00:00Z", "PARTIAL")
            val first = ledger.importSnapshot(main, directory.resolve("messages.jsonl"), directory.resolve("receipts.jsonl"))
            val repeated = ledger.importSnapshot(main, directory.resolve("messages.jsonl"), directory.resolve("receipts.jsonl"))
            check(first.inserted && !repeated.inserted) { "Idempotent import assertion failed" }
            ledger.importSnapshot(SnapshotMeta("ordered", "orders.ordered.dead", "2026-11-04T12:00:00Z", "PARTIAL"), directory.resolve("ordered.jsonl"), null, directory.resolve("checkpoints.jsonl"))
        }
        val summary = linkedMapOf<String, Any>("fixture" to "synthetic-fulfillment", "asOf" to AS_OF, "reimportIdempotent" to true)
        for ((name, snapshotId, policyFile) in listOf(Triple("unresolved", "fulfillment", "unresolved-policy.json"), Triple("recovery", "fulfillment", "policy.json"), Triple("ordered-review", "ordered", "ordered-policy.json"))) {
            val snapshot = Ledger(database).use { it.load(snapshotId) }
            val plan = RecoveryEngine.analyze(snapshot, PolicyReader.read(directory.resolve(policyFile)), AS_OF)
            val candidates = plan.decisions.filter { it.outcome == Outcome.CANDIDATE }.map { it.messageId }.toSet()
            val expected = when(name) { "unresolved" -> emptySet(); "recovery" -> setOf("m01", "m13", "m14"); else -> setOf("o01", "o02", "o04") }
            check(candidates == expected) { "$name candidates differ: $candidates" }
            Reports.write(plan, Files.createDirectory(directory.resolve(name)))
            summary[name] = mapOf("counts" to plan.counts, "batches" to plan.batches.map { it.messageIds }, "planId" to plan.planId)
        }
        Files.writeString(directory.resolve("summary.json"), Json.pretty(summary), StandardCharsets.UTF_8, CREATE_NEW)
        out.print(Json.pretty(summary))
    }
}
