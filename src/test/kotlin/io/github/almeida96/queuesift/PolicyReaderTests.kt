package io.github.almeida96.queuesift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PolicyReaderTests {
    private fun node(): ObjectNode = Json.read("""{"schemaVersion":1,"consumerIdempotent":true,"resolvedFailureCodes":["FIXED"],"maxAttempts":5,"maxAgeSeconds":86400,"maxSnapshotAgeSeconds":3600,"batchSize":2,"ordering":"UNORDERED","payloadRules":[]}""").deepCopy()

    @Test fun `all root fields are required and unknown fields are rejected`() {
        node().fieldNames().asSequence().toList().forEach { field ->
            val missing = node().also { it.remove(field) }
            assertFailsWith<IllegalArgumentException>(field) { PolicyReader.parse(missing) }
        }
        assertFailsWith<IllegalArgumentException> { PolicyReader.parse(node().put("allowUnsafeReplay", true)) }
    }

    @Test fun `invalid root field values fail closed`() {
        val invalid = listOf(
            "schemaVersion" to "2", "consumerIdempotent" to "\"true\"", "maxAttempts" to "0",
            "maxAgeSeconds" to "-1", "maxSnapshotAgeSeconds" to "1.5", "batchSize" to "0",
            "batchSize" to "1001", "ordering" to "\"UNKNOWN\"", "resolvedFailureCodes" to "[\"X\",\"X\"]",
            "resolvedFailureCodes" to "[\"\"]", "payloadRules" to "{}",
        )
        invalid.forEach { (field, value) ->
            assertFailsWith<IllegalArgumentException>("$field=$value") { PolicyReader.parse(node().set(field, Json.read(value))) }
        }
    }

    @Test fun `payload rule format rejects unsupported keywords types and inconsistent bounds`() {
        val invalid = listOf(
            """{"path":"/x","type":"string","pattern":".*"}""",
            """{"path":"/x","type":"null"}""",
            """{"path":"x","type":"string"}""",
            """{"path":"/x~2","type":"string"}""",
            """{"path":"/x","type":"string","required":"yes"}""",
            """{"path":"/x","type":"string","minimum":1}""",
            """{"path":"/x","type":"number","maxLength":1}""",
            """{"path":"/x","type":"number","minimum":2,"maximum":1}""",
            """{"path":"/x","type":"string","minLength":3,"maxLength":2}""",
            """{"path":"/x","type":"object","allowed":[{}]}""",
            """{"path":"/x","type":"integer","allowed":[1.5]}""",
            """{"path":"/x","type":"string","allowed":[null]}""",
            """{"path":"/x","type":"number","allowed":[1,1.0]}""",
            """{"path":"/x","type":"string","allowed":[]}""",
        )
        invalid.forEach { rule ->
            assertFailsWith<IllegalArgumentException>(rule) { PolicyReader.parse(node().set("payloadRules", Json.read("[$rule]"))) }
        }
    }

    @Test fun `digest binds the exact canonical policy including explicit default fields`() {
        val first = node().set<ObjectNode>("payloadRules", Json.read("""[{"path":"","type":"object"}]"""))
        val reordered = Json.mapper.createObjectNode().also { result -> first.fieldNames().asSequence().toList().reversed().forEach { result.set<JsonNode>(it, first[it]) } }
        assertEquals(PolicyReader.parse(first).digest, PolicyReader.parse(reordered).digest)
        assertEquals(Json.hash(Json.text(Json.canonical(first))), PolicyReader.parse(first).digest)
        val explicit = node().set<ObjectNode>("payloadRules", Json.read("""[{"path":"","type":"object","required":true}]"""))
        assertNotEquals(PolicyReader.parse(first).digest, PolicyReader.parse(explicit).digest)
    }

    @Test fun `duplicate payload pointers are rejected instead of silently overriding`() {
        val duplicate = Json.read("""[{"path":"/x","type":"string"},{"path":"/x","type":"string","maxLength":4}]""")
        assertFailsWith<IllegalArgumentException> { PolicyReader.parse(node().set("payloadRules", duplicate)) }
    }

    @Test fun `policy rule count is bounded to 64`() {
        val rules = Json.mapper.createArrayNode().also { array -> repeat(65) { array.add(Json.mapper.createObjectNode().put("path", "/field$it").put("type", "string")) } }
        assertFailsWith<IllegalArgumentException> { PolicyReader.parse(node().set("payloadRules", rules)) }
    }
}
