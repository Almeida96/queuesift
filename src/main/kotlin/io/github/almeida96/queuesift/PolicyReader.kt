package io.github.almeida96.queuesift

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** The intentionally small, local-only recovery policy format; this is not JSON Schema. */
object PolicyReader {
    private val rootFields = setOf("schemaVersion", "consumerIdempotent", "resolvedFailureCodes", "maxAttempts",
        "maxAgeSeconds", "maxSnapshotAgeSeconds", "batchSize", "ordering", "payloadRules")
    private val ruleFields = setOf("path", "type", "required", "allowed", "minimum", "maximum", "minLength", "maxLength")
    private val types = setOf("string", "integer", "number", "boolean", "object", "array")

    fun read(path: Path): Policy = parse(Json.readFile(path))

    fun parse(node: JsonNode): Policy {
        fields(node, rootFields, rootFields, "policy")
        require(integer(node["schemaVersion"], "schemaVersion", 1, 1) == 1L)
        require(node["consumerIdempotent"].isBoolean) { "consumerIdempotent must be a boolean" }
        val codes = node["resolvedFailureCodes"]
        require(codes.isArray && codes.size() <= 256) { "resolvedFailureCodes must be an array with at most 256 entries" }
        val resolved = codes.mapIndexed { index, code -> string(code, "resolvedFailureCodes/$index") }
        require(resolved.distinct().size == resolved.size) { "resolvedFailureCodes must not contain duplicates" }
        val ordering = string(node["ordering"], "ordering")
        require(ordering in setOf("UNORDERED", "PER_KEY_SEQUENCE")) { "ordering must be UNORDERED or PER_KEY_SEQUENCE" }
        val rules = node["payloadRules"]
        require(rules.isArray && rules.size() <= 64) { "payloadRules must be an array with at most 64 entries" }
        val parsedRules = rules.mapIndexed { index, rule -> parseRule(rule, index) }
        require(parsedRules.map { it.path }.distinct().size == parsedRules.size) { "payloadRules paths must be unique" }
        return Policy(
            consumerIdempotent = node["consumerIdempotent"].booleanValue(), resolvedFailureCodes = resolved,
            maxAttempts = integer(node["maxAttempts"], "maxAttempts", 1, Int.MAX_VALUE.toLong()).toInt(),
            maxAgeSeconds = integer(node["maxAgeSeconds"], "maxAgeSeconds", 0, Long.MAX_VALUE),
            maxSnapshotAgeSeconds = integer(node["maxSnapshotAgeSeconds"], "maxSnapshotAgeSeconds", 0, Long.MAX_VALUE),
            batchSize = integer(node["batchSize"], "batchSize", 1, 1000).toInt(), ordering = ordering,
            payloadRules = parsedRules, digest = Json.hash(Json.text(Json.canonical(node))),
        )
    }

    private fun parseRule(node: JsonNode, index: Int): FieldRule {
        val where = "payloadRules/$index"
        fields(node, ruleFields, setOf("path", "type"), where)
        val pathNode = node["path"]
        require(pathNode.isTextual) { "$where/path must be a JSON Pointer string" }
        val path = pathNode.textValue()
        require(path.length <= 1024 && (path.isEmpty() || path.startsWith('/')) && !Regex("~(?![01])").containsMatchIn(path)) {
            "$where/path must be a valid JSON Pointer (only ~0 and ~1 escapes are supported)"
        }
        val type = string(node["type"], "$where/type")
        require(type in types) { "$where/type must be one of ${types.sorted().joinToString()}" }
        val required = node["required"]?.also { require(it.isBoolean) { "$where/required must be a boolean" } }?.booleanValue() ?: true
        val allowed = node["allowed"]?.let { values ->
            require(values.isArray && values.size() in 1..256) { "$where/allowed must have 1 to 256 scalar entries" }
            require(type !in setOf("object", "array")) { "$where/allowed is supported only for scalar types" }
            values.map { value ->
                require(matchesType(value, type)) { "$where/allowed entries must match type $type" }
                Json.canonical(value)
            }.also { require(it.distinct().size == it.size) { "$where/allowed must not contain duplicate values" } }
        }
        fun decimal(name: String) = node[name]?.let {
            require(type == "number" || type == "integer") { "$where/$name requires a numeric type" }
            require(it.isNumber) { "$where/$name must be numeric" }
            it.decimalValue()
        }
        val minimum = decimal("minimum")
        val maximum = decimal("maximum")
        require(minimum == null || maximum == null || minimum <= maximum) { "$where/minimum must not exceed maximum" }
        fun length(name: String) = node[name]?.let {
            require(type == "string") { "$where/$name requires type string" }
            integer(it, "$where/$name", 0, Int.MAX_VALUE.toLong()).toInt()
        }
        val minLength = length("minLength")
        val maxLength = length("maxLength")
        require(minLength == null || maxLength == null || minLength <= maxLength) { "$where/minLength must not exceed maxLength" }
        return FieldRule(path, type, required, allowed, minimum, maximum, minLength, maxLength)
    }

    internal fun matchesType(node: JsonNode, type: String): Boolean = when (type) {
        "string" -> node.isTextual
        "integer" -> node.isNumber && node.decimalValue().stripTrailingZeros().scale() <= 0
        "number" -> node.isNumber
        "boolean" -> node.isBoolean
        "object" -> node.isObject
        "array" -> node.isArray
        else -> false
    }

    private fun fields(node: JsonNode, allowed: Set<String>, required: Set<String>, where: String) {
        require(node.isObject) { "$where must be an object" }
        val actual = node.fieldNames().asSequence().toSet()
        require((actual - allowed).isEmpty()) { "$where has unknown fields: ${(actual - allowed).sorted().joinToString()}" }
        require((required - actual).isEmpty()) { "$where is missing fields: ${(required - actual).sorted().joinToString()}" }
    }

    private fun string(node: JsonNode, where: String): String {
        require(node.isTextual && node.textValue().isNotBlank() && node.textValue().length <= 256) { "$where must be a nonblank string of at most 256 characters" }
        return node.textValue()
    }

    private fun integer(node: JsonNode, where: String, min: Long, max: Long): Long {
        require(node.isIntegralNumber && node.canConvertToLong() && node.longValue() in min..max) { "$where must be an integer from $min to $max" }
        return node.longValue()
    }
}
