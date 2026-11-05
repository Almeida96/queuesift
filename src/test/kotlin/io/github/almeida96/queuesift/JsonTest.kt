package io.github.almeida96.queuesift

import org.junit.jupiter.api.Test
import kotlin.test.*

class JsonTest {
    @Test fun `canonical hash ignores field order and numeric spelling but binds event type and array order`() {
        val a = Json.read("""{"b":2.0,"a":[1,2]}""")
        val b = Json.read("""{"a":[1.00,2e0],"b":2}""")
        assertEquals(Json.contentHash("created", a), Json.contentHash("created", b))
        assertNotEquals(Json.contentHash("created", a), Json.contentHash("updated", a))
        assertNotEquals(Json.contentHash("created", a), Json.contentHash("created", Json.read("""{"a":[2,1],"b":2}""")))
    }
    @Test fun `ambiguous JSON and malformed Unicode are rejected`() {
        for (json in listOf("{\"a\":1,\"a\":2}", "{} {}", "\"\\uD800\"", "{\"\\uDC00\":1}", "1e9999")) assertFails { Json.read(json) }
        assertEquals("😀", Json.read("\"\\ud83d\\ude00\"").textValue())
        assertFails { Json.utf8(byteArrayOf(0xc3.toByte(), 0x28)) }
    }
}
