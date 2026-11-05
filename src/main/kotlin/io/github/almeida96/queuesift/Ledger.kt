package io.github.almeida96.queuesift

import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/** An append-only, bounded local evidence ledger. Opening for inspection never creates a database. */
class Ledger(path: Path, private val writable: Boolean = false) : AutoCloseable {
    private val connection: Connection

    init {
        val location = path.toAbsolutePath().normalize()
        require(writable || Files.isRegularFile(location)) { "Ledger does not exist: ${location.fileName}" }
        require(!Files.exists(location) || Files.isRegularFile(location)) { "Ledger path must be a regular file" }
        val config = SQLiteConfig().apply {
            setReadOnly(!writable)
            enforceForeignKeys(true)
            setBusyTimeout(5000)
        }
        connection = DriverManager.getConnection("jdbc:sqlite:${location.toUri()}", config.toProperties())
        try {
            if (!writable) execute("PRAGMA query_only=ON")
            val applicationId = pragma("application_id")
            val version = pragma("user_version")
            val existing = tableNames()
            if (writable && applicationId == 0 && version == 0 && existing.isEmpty() && schemaObjectCount() == 0) {
                createSchema()
            } else {
                require(applicationId == APPLICATION_ID) { "Not a QueueSift ledger; database was not changed" }
                require(version == SCHEMA_VERSION) { "Unsupported QueueSift ledger schema version: $version" }
                require(existing == setOf("snapshots", "sources", "records")) { "Invalid QueueSift ledger schema" }
            }
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }

    fun importSnapshot(meta: SnapshotMeta, messages: Path, receipts: Path? = null, checkpoints: Path? = null): ImportResult {
        check(writable) { "Ledger is read-only" }
        val normalized = Records.normalizeMeta(meta)
        connection.autoCommit = false
        try {
            val existing = snapshotRow(normalized.id)
            if (existing != null) require(existing.meta == normalized) { "Snapshot id already exists with different metadata: ${normalized.id}" }
            if (existing == null) require(snapshotCount() < MAX_SNAPSHOTS) { "Ledger limit is $MAX_SNAPSHOTS snapshots" }
            val budget = ByteBudget()
            val sourceFiles = listOf("messages" to messages, "receipts" to receipts, "checkpoints" to checkpoints)
            val imported = mutableListOf<SourceFile>()
            val transportIds = HashSet<String>()
            connection.prepareStatement("INSERT INTO records(snapshot_id, kind, line, line_sha256, transport_id, record_json) VALUES(?,?,?,?,?,?)").use { insert ->
                for ((kind, file) in sourceFiles) {
                    if (file == null) continue
                    val basename = Records.text(file.fileName.toString(), "source basename", 256)
                    var pending = 0
                    val source = stream(file, kind, basename, budget) { bytes, line ->
                        val lineHash = Json.hash(bytes)
                        val ref = SourceRef(basename, "", line, lineHash)
                        val raw = try { Json.utf8(bytes) } catch (error: Exception) {
                            throw IllegalArgumentException("$basename:$line: invalid UTF-8: ${error.message}", error)
                        }
                        val node = try { Json.read(raw) } catch (error: Exception) {
                            throw IllegalArgumentException("$basename:$line: invalid UTF-8 or JSON: ${error.message}", error)
                        }
                        val transportId = try {
                            when (kind) {
                                "messages" -> Records.parseMessage(node, ref).id.also {
                                    require(transportIds.add(it)) { "Duplicate message transport id: $it" }
                                }
                                "receipts" -> { Records.parseReceipt(node, ref); null }
                                "checkpoints" -> { Records.parseCheckpoint(node, ref); null }
                                else -> error("Unknown source kind")
                            }
                        } catch (error: IllegalArgumentException) {
                            throw IllegalArgumentException("$basename:$line: ${error.message}", error)
                        }
                        if (existing == null) {
                            insert.setString(1, normalized.id)
                            insert.setString(2, kind)
                            insert.setInt(3, line)
                            insert.setString(4, lineHash)
                            insert.setString(5, transportId)
                            insert.setString(6, raw)
                            insert.addBatch()
                            pending++
                            if (pending == BATCH_SIZE) { insert.executeBatch(); insert.clearBatch(); pending = 0 }
                        }
                    }
                    if (pending > 0) { insert.executeBatch(); insert.clearBatch() }
                    imported.add(source)
                    if (existing == null) insertSource(normalized.id, source)
                }
            }
            val digest = digest(normalized, imported)
            val counts = imported.associate { it.kind to it.rows }
            if (existing != null) require(existing.digest == digest) { "Snapshot id already exists with different source bytes: ${normalized.id}" }
            else insertSnapshot(normalized, digest, counts)
            connection.commit()
            return ImportResult(normalized.id, existing == null, digest, counts["messages"] ?: 0, counts["receipts"] ?: 0, counts["checkpoints"] ?: 0)
        } catch (failure: Throwable) {
            try { connection.rollback() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    fun load(id: String): Snapshot {
        Records.text(id, "snapshot id")
        val row = snapshotRow(id) ?: throw IllegalArgumentException("Unknown snapshot: $id")
        val sources = readSources(id)
        require(sources.any { it.kind == "messages" }) { "Ledger snapshot has no message source" }
        require(digest(row.meta, sources) == row.digest) { "Ledger snapshot digest does not match its metadata and sources" }
        val messages = mutableListOf<Message>()
        val receipts = mutableListOf<Receipt>()
        val checkpoints = mutableListOf<Checkpoint>()
        val ids = HashSet<String>()
        connection.prepareStatement("SELECT line, line_sha256, CASE WHEN length(record_json) <= $MAX_LINE_BYTES THEN record_json ELSE NULL END AS record_json FROM records WHERE snapshot_id=? AND kind=? ORDER BY line LIMIT ${MAX_ROWS + 1}").use { query ->
            for (source in sources) {
                query.setString(1, id)
                query.setString(2, source.kind)
                var count = 0
                query.executeQuery().use { records ->
                    while (records.next()) {
                        count++
                        require(count <= MAX_ROWS && records.getInt("line") == count) { "Invalid ledger row count or line number" }
                        val lineHash = records.getString("line_sha256")
                        require(lineHash != null && Records.isSha256(lineHash)) { "Invalid ledger line digest" }
                        val raw = records.getString("record_json") ?: throw IllegalArgumentException("Stored record exceeds the size limit")
                        require(Json.hash(raw) == lineHash) { "Stored record does not match its source line digest" }
                        val node = Json.read(raw)
                        val ref = SourceRef(source.file, source.sha256, count, lineHash)
                        when (source.kind) {
                            "messages" -> messages.add(Records.parseMessage(node, ref).also { require(ids.add(it.id)) { "Duplicate stored transport id" } })
                            "receipts" -> receipts.add(Records.parseReceipt(node, ref))
                            "checkpoints" -> checkpoints.add(Records.parseCheckpoint(node, ref))
                        }
                    }
                }
                require(count == source.rows) { "Ledger source row count does not match stored records" }
            }
        }
        require(messages.size == row.messages && receipts.size == row.receipts && checkpoints.size == row.checkpoints) { "Ledger snapshot counts do not match stored sources" }
        return Snapshot(row.meta, row.digest, messages, receipts, checkpoints)
    }

    fun snapshots(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        connection.createStatement().use { query ->
            query.executeQuery("SELECT id, queue, captured_at, coverage, digest, messages, receipts, checkpoints FROM snapshots ORDER BY captured_at, id LIMIT ${MAX_SNAPSHOTS + 1}").use { rows ->
                while (rows.next()) {
                    require(result.size < MAX_SNAPSHOTS) { "Ledger exceeds $MAX_SNAPSHOTS snapshots" }
                    val row = snapshotRow(rows)
                    result.add(linkedMapOf("id" to row.meta.id, "queue" to row.meta.queue, "capturedAt" to row.meta.capturedAt, "coverage" to row.meta.coverage, "digest" to row.digest, "messages" to row.messages, "receipts" to row.receipts, "checkpoints" to row.checkpoints))
                }
            }
        }
        return result
    }

    override fun close() = connection.close()

    private fun createSchema() {
        connection.autoCommit = false
        try {
            execute("CREATE TABLE snapshots(id TEXT PRIMARY KEY, queue TEXT NOT NULL, captured_at TEXT NOT NULL, coverage TEXT NOT NULL CHECK(coverage IN ('PARTIAL','DECLARED_COMPLETE')), digest TEXT NOT NULL, messages INTEGER NOT NULL CHECK(messages BETWEEN 0 AND $MAX_ROWS), receipts INTEGER NOT NULL CHECK(receipts BETWEEN 0 AND $MAX_ROWS), checkpoints INTEGER NOT NULL CHECK(checkpoints BETWEEN 0 AND $MAX_ROWS))")
            execute("CREATE TABLE sources(snapshot_id TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('messages','receipts','checkpoints')), file TEXT NOT NULL, sha256 TEXT NOT NULL, rows INTEGER NOT NULL CHECK(rows BETWEEN 0 AND $MAX_ROWS), PRIMARY KEY(snapshot_id, kind), FOREIGN KEY(snapshot_id) REFERENCES snapshots(id) DEFERRABLE INITIALLY DEFERRED)")
            execute("CREATE TABLE records(snapshot_id TEXT NOT NULL, kind TEXT NOT NULL, line INTEGER NOT NULL CHECK(line BETWEEN 1 AND $MAX_ROWS), line_sha256 TEXT NOT NULL, transport_id TEXT, record_json TEXT NOT NULL, PRIMARY KEY(snapshot_id, kind, line), UNIQUE(snapshot_id, transport_id), FOREIGN KEY(snapshot_id, kind) REFERENCES sources(snapshot_id, kind) DEFERRABLE INITIALLY DEFERRED)")
            for (table in listOf("snapshots", "sources", "records")) for (action in listOf("UPDATE", "DELETE")) {
                execute("CREATE TRIGGER immutable_${table}_${action.lowercase()} BEFORE $action ON $table BEGIN SELECT RAISE(ABORT, 'QueueSift evidence is immutable'); END")
            }
            for (table in listOf("sources", "records")) {
                execute("CREATE TRIGGER immutable_${table}_insert BEFORE INSERT ON $table WHEN EXISTS(SELECT 1 FROM snapshots WHERE id=NEW.snapshot_id) BEGIN SELECT RAISE(ABORT, 'QueueSift snapshot is sealed'); END")
            }
            execute("PRAGMA application_id=$APPLICATION_ID")
            execute("PRAGMA user_version=$SCHEMA_VERSION")
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally { connection.autoCommit = true }
    }

    private fun insertSnapshot(meta: SnapshotMeta, digest: String, counts: Map<String, Int>) {
        connection.prepareStatement("INSERT INTO snapshots(id,queue,captured_at,coverage,digest,messages,receipts,checkpoints) VALUES(?,?,?,?,?,?,?,?)").use {
            it.setString(1, meta.id); it.setString(2, meta.queue); it.setString(3, meta.capturedAt); it.setString(4, meta.coverage); it.setString(5, digest)
            it.setInt(6, counts["messages"] ?: 0); it.setInt(7, counts["receipts"] ?: 0); it.setInt(8, counts["checkpoints"] ?: 0)
            it.executeUpdate()
        }
    }

    private fun insertSource(id: String, source: SourceFile) {
        connection.prepareStatement("INSERT INTO sources(snapshot_id,kind,file,sha256,rows) VALUES(?,?,?,?,?)").use {
            it.setString(1, id); it.setString(2, source.kind); it.setString(3, source.file); it.setString(4, source.sha256); it.setInt(5, source.rows)
            it.executeUpdate()
        }
    }

    private fun snapshotRow(id: String): StoredSnapshot? = connection.prepareStatement("SELECT id,queue,captured_at,coverage,digest,messages,receipts,checkpoints FROM snapshots WHERE id=?").use {
        it.setString(1, id)
        it.executeQuery().use { rows -> if (rows.next()) snapshotRow(rows) else null }
    }

    private fun snapshotRow(rows: java.sql.ResultSet): StoredSnapshot {
        val meta = SnapshotMeta(rows.getString("id"), rows.getString("queue"), rows.getString("captured_at"), rows.getString("coverage"))
        require(Records.normalizeMeta(meta) == meta) { "Invalid ledger snapshot metadata" }
        val digest = rows.getString("digest")
        require(digest != null && Records.isSha256(digest)) { "Invalid ledger snapshot digest" }
        val counts = listOf("messages", "receipts", "checkpoints").map {
            val value = rows.getLong(it)
            require(!rows.wasNull() && value in 0..MAX_ROWS.toLong()) { "Invalid ledger snapshot row count" }
            value.toInt()
        }
        return StoredSnapshot(meta, digest, counts[0], counts[1], counts[2])
    }

    private fun readSources(id: String): List<SourceFile> = connection.prepareStatement("SELECT kind,file,sha256,rows FROM sources WHERE snapshot_id=? ORDER BY kind LIMIT 4").use { query ->
        query.setString(1, id)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    require(size < 3) { "Invalid ledger source count" }
                    val kind = rows.getString("kind")
                    require(kind in setOf("messages", "receipts", "checkpoints")) { "Invalid ledger source kind" }
                    val file = Records.text(rows.getString("file"), "source basename", 256)
                    require(!file.contains('/') && !file.contains('\\')) { "Invalid ledger source basename" }
                    val hash = rows.getString("sha256")
                    require(hash != null && Records.isSha256(hash)) { "Invalid ledger source digest" }
                    val count = rows.getLong("rows")
                    require(!rows.wasNull() && count in 0..MAX_ROWS.toLong()) { "Invalid ledger source row count" }
                    add(SourceFile(kind, file, hash, count.toInt()))
                }
            }
        }
    }

    private fun digest(meta: SnapshotMeta, sources: List<SourceFile>): String {
        val node = Json.mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION)
        node.set<com.fasterxml.jackson.databind.JsonNode>("metadata", Json.mapper.valueToTree(meta))
        val hashes = node.putObject("sources")
        for (kind in listOf("messages", "receipts", "checkpoints")) {
            val source = sources.find { it.kind == kind }
            if (source == null) hashes.putNull(kind) else hashes.put(kind, source.sha256)
        }
        return Json.hash(Json.text(Json.canonical(node)))
    }

    /** SHA-256 covers the entire file. Line SHA-256 excludes LF, or both bytes of CRLF. */
    private fun stream(path: Path, kind: String, basename: String, budget: ByteBudget, consume: (ByteArray, Int) -> Unit): SourceFile {
        require(Files.isRegularFile(path)) { "Source must be a regular file: $basename" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        val line = ByteArray(MAX_LINE_BYTES + 1)
        var length = 0
        var rows = 0
        fun finish(newline: Boolean) {
            val end = if (newline && length > 0 && line[length - 1] == '\r'.code.toByte()) length - 1 else length
            require(end > 0) { "$basename:${rows + 1}: blank lines are not allowed" }
            require(end <= MAX_LINE_BYTES) { "$basename:${rows + 1}: line exceeds $MAX_LINE_BYTES bytes" }
            rows++
            require(rows <= MAX_ROWS) { "$basename exceeds $MAX_ROWS records" }
            consume(line.copyOf(end), rows)
            length = 0
        }
        Files.newInputStream(path).buffered().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                budget.add(count)
                digest.update(buffer, 0, count)
                for (index in 0 until count) {
                    val byte = buffer[index]
                    if (byte == '\n'.code.toByte()) finish(true)
                    else {
                        require(length < line.size) { "$basename:${rows + 1}: line exceeds $MAX_LINE_BYTES bytes" }
                        line[length++] = byte
                    }
                }
            }
        }
        if (length > 0) finish(false)
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return SourceFile(kind, basename, hash, rows)
    }

    private fun execute(sql: String) = connection.createStatement().use { it.execute(sql) }
    private fun pragma(name: String): Int = connection.createStatement().use { it.executeQuery("PRAGMA $name").use { result -> check(result.next()); result.getInt(1) } }
    private fun tableNames(): Set<String> = connection.createStatement().use { it.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' LIMIT 4").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } } }
    private fun schemaObjectCount(): Int = connection.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'").use { rows -> rows.next(); rows.getInt(1) } }
    private fun snapshotCount(): Int = connection.createStatement().use { it.executeQuery("SELECT count(*) FROM snapshots").use { rows -> rows.next(); rows.getInt(1) } }

    private data class SourceFile(val kind: String, val file: String, val sha256: String, val rows: Int)
    private data class StoredSnapshot(val meta: SnapshotMeta, val digest: String, val messages: Int, val receipts: Int, val checkpoints: Int)
    private class ByteBudget {
        var bytes = 0L
        fun add(count: Int) { bytes += count; require(bytes <= MAX_IMPORT_BYTES) { "Import exceeds $MAX_IMPORT_BYTES bytes across all sources" } }
    }

    companion object {
        const val MAX_ROWS = 10_000
        const val MAX_LINE_BYTES = 65_536
        const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
        const val MAX_SNAPSHOTS = 1000
        private const val BATCH_SIZE = 500
        private const val APPLICATION_ID = 0x51534654
        private const val SCHEMA_VERSION = 1
    }
}
