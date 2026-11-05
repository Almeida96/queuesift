package io.github.almeida96.queuesift

import com.fasterxml.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Converts a supplied ReceiveMessage-shaped export; never reads from an SQS queue. */
object SqsAdapter {
    fun convert(input: Path, options: Map<String, String>, output: Path): Map<String, Any> {
        val bytes = Files.newInputStream(input).use { it.readNBytes(4 * 1024 * 1024 + 1) }
        require(bytes.size <= 4 * 1024 * 1024) { "SQS export exceeds 4 MiB" }
        val root = Json.read(Json.utf8(bytes))
        require(root.isObject && root.path("Messages").isArray) { "Expected an SQS ReceiveMessage object containing Messages" }
        val messages = root.path("Messages")
        require(messages.size() in 1..1000) { "SQS export must contain 1–1000 messages" }
        for (key in listOf("tenant-pointer", "event-id-pointer", "event-type-pointer")) require(options[key] != null) { "Required option: --$key" }
        val failure = options["failure-code"] ?: throw IllegalArgumentException("Required option: --failure-code")
        Records.text(failure, "failure code")
        val ids = mutableSetOf<String>()
        val lines = messages.map { message ->
            require(message.isObject) { "SQS Messages entries must be objects" }
            val id = message.path("MessageId")
            require(id.isTextual && ids.add(id.asText())) { "MessageId must be a unique string" }
            Records.text(id.textValue(), "MessageId")
            require(message.path("Body").isTextual) { "Message $id requires a JSON string Body" }
            val payload = Json.read(message.path("Body").textValue())
            val attemptsText = message.path("Attributes").path("ApproximateReceiveCount")
            require(attemptsText.isTextual && attemptsText.textValue().matches(Regex("[1-9][0-9]{0,5}"))) { "Message ${id.asText()} needs Attributes.ApproximateReceiveCount" }
            fun extract(option: String, required: Boolean): JsonNode? {
                val pointer = options[option] ?: return null
                require(pointer.isEmpty() || pointer.startsWith('/')) { "$option must be a JSON Pointer" }
                require(!Regex("~(?:[^01]|$)").containsMatchIn(pointer)) { "$option contains an invalid JSON Pointer escape" }
                val value = payload.at(pointer)
                if (value.isMissingNode || value.isNull) {
                    require(!required) { "Missing $option in ${id.asText()}" }
                    return null
                }
                require(value.isTextual) { "$option must select a string" }
                Records.text(value.textValue(), option)
                return value
            }
            val record = Json.mapper.createObjectNode().put("id", id.textValue())
            record.set<JsonNode>("tenant", extract("tenant-pointer", true))
            record.set<JsonNode>("eventId", extract("event-id-pointer", true))
            record.set<JsonNode>("eventType", extract("event-type-pointer", true))
            for ((option, field) in listOf("occurred-at-pointer" to "occurredAt", "expires-at-pointer" to "expiresAt")) {
                val value = extract(option, false)
                if (value != null) record.put(field, Records.instant(value.textValue(), field))
            }
            record.put("failureCode", failure).put("attempts", attemptsText.textValue().toInt()).set<JsonNode>("payload", payload)
            val line = Json.text(record)
            require(line.toByteArray(StandardCharsets.UTF_8).size <= 65536) { "Normalized message exceeds 64 KiB" }
            line
        }
        val directory = Cli.newDirectory(output)
        Files.writeString(directory.resolve("messages.jsonl"), lines.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8, CREATE_NEW)
        val provenance = linkedMapOf<String, Any>(
            "adapter" to "sqs-receive-message-export", "toolVersion" to Cli.VERSION,
            "source" to input.fileName.toString(), "sourceSha256" to Json.hash(bytes), "records" to lines.size,
            "mapping" to options.filterKeys { it.endsWith("-pointer") || it == "failure-code" },
            "limitations" to listOf("No queue was contacted. Receipt handles and unneeded broker attributes were discarded.", "Failure code is an operator assertion. Broker receive count is approximate.", "Event timestamps come only from configured payload fields; broker retention is not inferred.", "The output is a normalized investigation input, not an SQS redrive request.")
        )
        Files.writeString(directory.resolve("adaptation.json"), Json.pretty(provenance), StandardCharsets.UTF_8, CREATE_NEW)
        return provenance
    }
}
