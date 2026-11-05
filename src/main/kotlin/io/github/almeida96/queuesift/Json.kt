package io.github.almeida96.queuesift

import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object Json {
    val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build().apply {
            factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40)
                .maxStringLength(65536).maxNumberLength(256).build())
        }
    @Throws(java.io.IOException::class)
    fun read(text: String): JsonNode = mapper.createParser(text).use { parser ->
        val node = requireNotNull(mapper.readTree<JsonNode>(parser)) { "Expected a JSON document" }
        require(parser.nextToken() == null) { "Expected exactly one JSON document" }
        checkNumbers(node)
        node
    }
    fun readFile(path: Path, maxBytes: Int = 65536): JsonNode = Files.newInputStream(path).use {
        val bytes = it.readNBytes(maxBytes + 1)
        require(bytes.size <= maxBytes) { "File exceeds $maxBytes bytes: ${path.fileName}" }
        read(utf8(bytes))
    }
    fun utf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    fun text(value: Any?): String = mapper.writeValueAsString(value)
    fun pretty(value: Any?): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun hash(text: String): String = hash(text.toByteArray(StandardCharsets.UTF_8))
    fun hashValue(value: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest).use { mapper.writeValue(it, value) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun canonical(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { result -> node.fieldNames().asSequence().sorted().forEach { result.set<JsonNode>(it, canonical(node.get(it))) } }
        node.isArray -> mapper.createArrayNode().also { result -> node.forEach { result.add(canonical(it)) } }
        node.isNumber -> mapper.nodeFactory.numberNode(node.decimalValue().stripTrailingZeros())
        else -> node.deepCopy()
    }
    fun contentHash(eventType: String, payload: JsonNode): String = hash(text(canonical(mapper.createObjectNode().put("eventType", eventType).set<JsonNode>("payload", payload))))
    private fun checkNumbers(node: JsonNode) {
        if (node.isNumber) require(node.decimalValue().precision() <= 256 && kotlin.math.abs(node.decimalValue().scale().toLong()) <= 256) { "Numeric precision or scale exceeds 256" }
        if (node.isTextual) checkUnicode(node.textValue())
        if (node.isObject) node.fieldNames().forEachRemaining(::checkUnicode)
        if (node.isContainerNode) node.forEach { checkNumbers(it) }
    }
    private fun checkUnicode(value: String) {
        var i = 0
        while (i < value.length) {
            val ch = value[i++]
            if (ch.isHighSurrogate()) require(i < value.length && value[i++].isLowSurrogate()) { "Unpaired Unicode surrogate" }
            else require(!ch.isLowSurrogate()) { "Unpaired Unicode surrogate" }
        }
    }
}
