package io.github.almeida96.queuesift;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpServerTest {
  private static final String INITIALIZED =
      "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

  @Test
  void negotiatesBothSupportedVersionsAndOffersLatestForUnknownVersion() throws Exception {
    for (String version : List.of("2025-11-25", "2025-06-18", "1999-01-01")) {
      JsonNode result = run(initialize(version)).getFirst().get("result");
      assertEquals(
          version.equals("2025-06-18") ? version : "2025-11-25",
          result.path("protocolVersion").asText());
      assertFalse(result.path("capabilities").path("tools").path("listChanged").asBoolean());
      assertEquals("queuesift", result.path("serverInfo").path("name").asText());
    }
  }

  @Test
  void requiresInitializeAndReadyNotificationBeforeTools() throws Exception {
    List<JsonNode> responses =
        run(
            request(1, "tools/list", Map.of()),
            initialize("2025-11-25"),
            request(2, "tools/list", Map.of()),
            INITIALIZED,
            request(3, "tools/list", Map.of()));
    assertCode(responses.get(0), -32002);
    assertCode(responses.get(2), -32002);
    assertEquals(5, responses.get(3).path("result").path("tools").size());
  }

  @Test
  void pingWorksBeforeDuringAndAfterInitialization() throws Exception {
    List<JsonNode> responses =
        run(
            request("early", "ping", Map.of()),
            initialize("2025-11-25"),
            request(2, "ping", Map.of()),
            INITIALIZED,
            request(3, "ping", Map.of()));
    for (int index : List.of(0, 2, 3)) assertTrue(responses.get(index).path("result").isEmpty());
    assertEquals("early", responses.getFirst().path("id").asText());
  }

  @Test
  void rejectsRepeatedInitialization() throws Exception {
    List<JsonNode> responses = run(initialize("2025-11-25"), initialize("2025-06-18"));
    assertCode(responses.getLast(), -32600);
  }

  @Test
  void rejectsMalformedInitializationWithoutAdvancingState() throws Exception {
    List<Object> invalid =
        List.of(
            Map.of(),
            Map.of("protocolVersion", "2025-11-25"),
            Map.of(
                "protocolVersion",
                123,
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "test", "version", "1")),
            Map.of(
                "protocolVersion",
                "2025-11-25",
                "capabilities",
                List.of(),
                "clientInfo",
                Map.of("name", "test", "version", "1")),
            Map.of(
                "protocolVersion",
                "2025-11-25",
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "test")),
            Map.of(
                "protocolVersion",
                "2025-11-25",
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "test", "version", "1"),
                "path",
                "/tmp"));
    for (Object params : invalid) {
      List<JsonNode> responses = run(request(1, "initialize", params), initialize("2025-11-25"));
      assertCode(responses.getFirst(), -32602);
      assertTrue(responses.getLast().has("result"));
    }
  }

  @Test
  void notificationsAreSilentAndCannotInitializeOrInvokeTools() throws Exception {
    List<JsonNode> responses =
        run(
            INITIALIZED,
            "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"recovery_summary\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"unknown\",\"params\":null}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"unknown\",\"extra\":true}",
            request(1, "tools/list", Map.of()));
    assertEquals(1, responses.size());
    assertCode(responses.getFirst(), -32002);
  }

  @Test
  void malformedReadyNotificationDoesNotAdvanceState() throws Exception {
    List<JsonNode> responses =
        run(
            initialize("2025-11-25"),
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":[]}",
            request(1, "tools/list", Map.of()),
            INITIALIZED,
            request(2, "tools/list", Map.of()));
    assertEquals(3, responses.size());
    assertCode(responses.get(1), -32002);
    assertTrue(responses.get(2).has("result"));
  }

  @Test
  void acceptsOnlyStringAndIntegerRequestIdsAndPreservesThem() throws Exception {
    for (String id : List.of("null", "true", "1.25", "[]", "{}")) {
      JsonNode response =
          run("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}").getFirst();
      assertCode(response, -32600);
      assertTrue(response.path("id").isNull());
    }
    for (Object id : List.of("request-α", 0, -3, Long.MAX_VALUE)) {
      JsonNode response = run(request(id, "ping", Map.of())).getFirst();
      assertEquals(Json.INSTANCE.getMapper().valueToTree(id), response.path("id"));
    }
  }

  @Test
  void malformedJsonDuplicateFieldsTrailingDocumentsAndBatchProduceProtocolErrors()
      throws Exception {
    for (String input :
        List.of("{", "", "{}{}", "{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"ping\"}")) {
      assertCode(run(input).getFirst(), -32700);
    }
    for (String input :
        List.of(
            "[]",
            "[{}]",
            "null",
            "42",
            "{}",
            "{\"jsonrpc\":2.0,\"id\":1,\"method\":\"ping\"}",
            "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}")) {
      assertCode(run(input).getFirst(), -32600);
    }
  }

  @Test
  void invalidUtf8AndOversizedInputRecoverOnTheNextLine() throws Exception {
    byte[] ping = (request(2, "ping", Map.of()) + "\n").getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.write(new byte[] {(byte) 0xc3, (byte) 0x28, '\n'});
    input.write(ping);
    List<JsonNode> utf8Responses = runBytes(server(), input.toByteArray());
    assertCode(utf8Responses.getFirst(), -32700);
    assertTrue(utf8Responses.getLast().has("result"));
    input.reset();
    input.write(" ".repeat(McpServer.MAX_LINE_BYTES + 1).getBytes(StandardCharsets.UTF_8));
    input.write('\n');
    input.write(ping);
    List<JsonNode> oversizedResponses = runBytes(server(), input.toByteArray());
    assertCode(oversizedResponses.getFirst(), -32700);
    assertTrue(oversizedResponses.getLast().has("result"));
  }

  @Test
  void acceptsExactByteBoundaryCrLfAndFinalLineWithoutNewline() throws Exception {
    String ping = request(1, "ping", Map.of());
    String exact =
        " ".repeat(McpServer.MAX_LINE_BYTES - ping.getBytes(StandardCharsets.UTF_8).length)
            + ping
            + "\n";
    assertTrue(
        runBytes(server(), exact.getBytes(StandardCharsets.UTF_8))
            .getFirst()
            .has("result"));
    assertEquals(
        2,
        runBytes(server(), (ping + "\r\n" + ping).getBytes(StandardCharsets.UTF_8))
            .size());
  }

  @Test
  void advertisesOnlyFiveClosedReadOnlyTools() throws Exception {
    JsonNode tools = ready(request(1, "tools/list", Map.of())).getFirst().path("result").path("tools");
    assertEquals(List.of("recovery_summary", "failure_groups", "list_decisions", "message_evidence", "list_batches"),
        asList(tools).stream().map(tool -> tool.path("name").asText()).toList());
    for (JsonNode tool : tools) {
      assertEquals("object", tool.path("inputSchema").path("type").asText());
      assertFalse(tool.path("inputSchema").path("additionalProperties").asBoolean());
      JsonNode hints = tool.path("annotations");
      assertTrue(hints.path("readOnlyHint").asBoolean());
      assertFalse(hints.path("destructiveHint").asBoolean());
      assertTrue(hints.path("idempotentHint").asBoolean());
      assertFalse(hints.path("openWorldHint").asBoolean());
      assertFalse(tool.path("inputSchema").path("properties").has("includePayload"));
    }
    assertEquals("messageId", tools.get(3).path("inputSchema").path("required").get(0).asText());
    for (int index : List.of(1, 2, 4))
      assertEquals(50, tools.get(index).path("inputSchema").path("properties").path("limit").path("maximum").asInt());
  }

  @Test
  void summaryHasAuditableContextWithoutDecisionsOrPayload() throws Exception {
    JsonNode summary = structured(ready(call("recovery_summary", Map.of())).getFirst());
    assertEquals(24, summary.path("counts").path("total").asInt());
    assertEquals("snapshot-a", summary.path("snapshot").path("id").asText());
    assertEquals("a".repeat(64), summary.path("snapshotDigest").asText());
    assertEquals("b".repeat(64), summary.path("policyDigest").asText());
    assertEquals("2026-01-02T12:00:00Z", summary.path("asOf").asText());
    assertEquals("Consumer idempotency is an operator assertion", summary.path("assumptions").get(0).asText());
    assertEquals(8, summary.path("batchCount").asInt());
    assertFalse(summary.path("payloadAccessEnabled").asBoolean());
    assertFalse(summary.has("decisions"));
    assertFalse(summary.has("groups"));
    assertFalse(summary.has("batches"));
    assertPayloadAbsent(summary);
  }

  @Test
  void allCollectionToolsPaginateWithStableOrderingAndHandleLargeOffsets() throws Exception {
    for (String tool : List.of("failure_groups", "list_decisions", "list_batches")) {
      String field = switch (tool) { case "failure_groups" -> "groups"; case "list_decisions" -> "decisions"; default -> "batches"; };
      List<JsonNode> responses = ready(call(tool, Map.of("limit", 1)), call(tool, Map.of("offset", 1, "limit", 1)),
          call(tool, Map.of("offset", Integer.MAX_VALUE)), call(tool, Map.of("limit", 1)));
      JsonNode first = structured(responses.get(0));
      JsonNode second = structured(responses.get(1));
      assertEquals(1, first.path(field).size());
      assertEquals(1, first.path("nextOffset").asInt());
      assertEquals(1, second.path("offset").asInt());
      assertEquals(first.path("total"), second.path("total"));
      assertNotEquals(first.path(field).get(0), second.path(field).get(0));
      assertTrue(structured(responses.get(2)).path(field).isEmpty());
      assertTrue(structured(responses.get(2)).path("nextOffset").isNull());
      assertEquals(first, structured(responses.get(3)));
    }
    JsonNode defaultPage = structured(ready(call("list_decisions", Map.of())).getFirst());
    assertEquals(20, defaultPage.path("decisions").size());
    assertEquals(20, defaultPage.path("limit").asInt());
    assertEquals("m000", defaultPage.path("decisions").get(0).path("messageId").asText());
    JsonNode last = structured(ready(call("list_decisions", Map.of("offset", 20))).getFirst());
    assertEquals(4, last.path("decisions").size());
    assertTrue(last.path("nextOffset").isNull());
    assertEquals("m020", last.path("decisions").get(0).path("messageId").asText());
    assertFalse(defaultPage.path("decisions").get(0).has("reasons"));
    assertEquals("RESOLVED_FAILURE", defaultPage.path("decisions").get(0).path("reasonCodes").get(0).asText());
    assertEquals("SCHEMA", structured(ready(call("failure_groups", Map.of())).getFirst()).path("groups").get(0).path("code").asText());
    assertEquals(1, structured(ready(call("list_batches", Map.of())).getFirst()).path("batches").get(0).path("number").asInt());
  }

  @Test
  void combinesAllExactFiltersAndReturnsNoFalseMatches() throws Exception {
    JsonNode page = structured(ready(call("list_decisions", Map.of("outcome", "CANDIDATE", "failureCode", "TIMEOUT", "tenant", "north"))).getFirst());
    assertEquals(2, page.path("total").asInt());
    for (JsonNode item : page.path("decisions")) {
      assertEquals("CANDIDATE", item.path("outcome").asText());
      assertEquals("TIMEOUT", item.path("failureCode").asText());
      assertEquals("north", item.path("tenant").asText());
    }
    for (String outcome : List.of("CANDIDATE", "HOLD", "EXCLUDE"))
      assertEquals(8, structured(ready(call("list_decisions", Map.of("outcome", outcome))).getFirst()).path("total").asInt());
    for (Map<String, Object> filter : List.of(Map.<String, Object>of("tenant", "North"), Map.<String, Object>of("failureCode", "time")))
      assertEquals(0, structured(ready(call("list_decisions", filter)).getFirst()).path("total").asInt());
  }

  @Test
  void evidenceUsesExactMessageIdAndContainsDecisionProvenance() throws Exception {
    JsonNode evidence = structured(ready(call("message_evidence", Map.of("messageId", "m002"))).getFirst());
    assertEquals("m002", evidence.path("messageId").asText());
    assertEquals("d002", evidence.path("id").asText());
    assertEquals("EXCLUDE", evidence.path("outcome").asText());
    assertEquals("event-2", evidence.path("eventId").asText());
    assertEquals("RECEIPT_MATCH", evidence.path("reasons").get(0).path("code").asText());
    assertEquals("/consumerIdempotent", evidence.path("reasons").get(0).path("policyPath").asText());
    assertEquals(4, evidence.path("source").path("line").asInt());
    assertEquals(evidence.path("source"), evidence.path("reasons").get(0).path("evidence").get(0));
    assertTrue(evidence.path("payloadOmitted").asBoolean());
    assertPayloadAbsent(evidence);
    for (String missingId : List.of("d002", "M002", "m002 ", "absent")) {
      JsonNode response = ready(call("message_evidence", Map.of("messageId", missingId))).getFirst();
      assertTrue(response.path("result").path("isError").asBoolean());
      assertEquals("MESSAGE_NOT_FOUND", structured(response).path("error").path("code").asText());
    }
  }

  @Test
  void payloadRequiresStartupOptInAndNeverAppearsInCollectionTools() throws Exception {
    Fixture fixture = fixture();
    McpServer server = new McpServer(fixture.plan(), fixture.messages(), true);
    List<JsonNode> responses = runReady(server,
        call("recovery_summary", Map.of()), call("failure_groups", Map.of()),
        call("list_decisions", Map.of("limit", 50)), call("list_batches", Map.of()),
        call("message_evidence", Map.of("messageId", "m002")));
    for (int i = 0; i < 4; i++) assertPayloadAbsent(structured(responses.get(i)));
    JsonNode evidence = structured(responses.getLast());
    assertFalse(evidence.path("payloadOmitted").asBoolean());
    assertEquals("secret-canary-002", evidence.path("payload").path("secret").asText());
    assertEquals(2, evidence.path("payload").path("nested").path("ordinal").asInt());
    for (boolean optIn : List.of(false, true)) {
      McpServer configured = new McpServer(fixture.plan(), fixture.messages(), optIn);
      for (String key : List.of("includePayload", "include-payload", "allowPayload", "payload", "path"))
        assertCode(runReady(configured, call("message_evidence", Map.of("messageId", "m002", key, true))).getFirst(), -32602);
    }
  }

  @Test
  void snapshotsMutableListsReasonsCountsBatchesAndPayloads() throws Exception {
    Fixture fixture = fixture();
    McpServer server = new McpServer(fixture.plan(), fixture.messages(), true);
    ((ObjectNode) fixture.messages().get(2).getPayload()).put("secret", "modified-after-startup");
    fixture.plan().getDecisions().getLast().getReasons().clear();
    fixture.plan().getCounts().put("total", 999);
    fixture.plan().getAssumptions().clear();
    fixture.plan().getBatches().getLast().getMessageIds().clear();
    fixture.plan().getDecisions().clear();
    fixture.plan().getGroups().clear();
    fixture.messages().clear();
    List<JsonNode> responses = runReady(server, call("recovery_summary", Map.of()),
        call("list_decisions", Map.of()), call("failure_groups", Map.of()), call("list_batches", Map.of()),
        call("message_evidence", Map.of("messageId", "m002")), call("message_evidence", Map.of("messageId", "m000")));
    assertEquals(24, structured(responses.get(0)).path("counts").path("total").asInt());
    assertEquals(1, structured(responses.get(0)).path("assumptions").size());
    assertEquals(24, structured(responses.get(1)).path("total").asInt());
    assertEquals(2, structured(responses.get(2)).path("total").asInt());
    assertEquals("m000", structured(responses.get(3)).path("batches").get(0).path("messageIds").get(0).asText());
    assertEquals("secret-canary-002", structured(responses.get(4)).path("payload").path("secret").asText());
    assertEquals(1, structured(responses.get(5)).path("reasons").size());
  }

  @Test
  void rejectsAmbiguousOrMissingEvidenceBeforeServing() {
    Fixture duplicate = fixture();
    duplicate.plan().getDecisions().set(1, duplicate.plan().getDecisions().getFirst());
    assertThrows(IllegalArgumentException.class, () -> new McpServer(duplicate.plan(), duplicate.messages(), false));
    Fixture missing = fixture();
    missing.messages().clear();
    assertThrows(IllegalArgumentException.class, () -> new McpServer(missing.plan(), missing.messages(), true));
    // With payload disabled, messages are never read or serialized by the server.
    assertDoesNotThrow(() -> new McpServer(missing.plan(), missing.messages(), false));
    Fixture duplicatePayload = fixture();
    duplicatePayload.messages().add(duplicatePayload.messages().getFirst());
    assertThrows(IllegalArgumentException.class, () -> new McpServer(duplicatePayload.plan(), duplicatePayload.messages(), true));
  }

  @Test
  void rejectsUnknownToolsMethodsFieldsPathsAndInvalidPageArguments() throws Exception {
    assertCode(ready(call("replay", Map.of())).getFirst(), -32602);
    assertCode(ready(request(2, "resources/list", Map.of())).getFirst(), -32601);
    for (String tool : List.of("recovery_summary", "failure_groups", "list_decisions", "message_evidence", "list_batches")) {
      assertCode(ready(call(tool, Map.of("path", "C:/secret.json"))).getFirst(), -32602);
      assertCode(ready(request(1, "tools/call", Map.of("name", tool, "arguments", List.of()))).getFirst(), -32602);
    }
    assertCode(ready(request(1, "tools/call", Map.of("name", "recovery_summary", "extra", true))).getFirst(), -32602);
    assertCode(ready(request(1, "tools/list", Map.of("cursor", "bogus"))).getFirst(), -32602);
    assertCode(ready(request(1, "ping", Map.of("extra", true))).getFirst(), -32602);
    List<Map<String, Object>> invalidPages = List.of(Map.of("limit", 0), Map.of("limit", 51), Map.of("limit", 1.5),
        Map.of("limit", "20"), Map.of("limit", true), Map.of("offset", -1), Map.of("offset", 999999999999L));
    for (String tool : List.of("failure_groups", "list_decisions", "list_batches"))
      for (Map<String, Object> args : invalidPages) assertCode(ready(call(tool, args)).getFirst(), -32602);
    for (Map<String, Object> args : List.of(Map.<String,Object>of("outcome", "candidate"), Map.<String,Object>of("outcome", 2),
        Map.<String,Object>of("tenant", " "), Map.<String,Object>of("failureCode", List.of("TIMEOUT"))))
      assertCode(ready(call("list_decisions", args)).getFirst(), -32602);
    assertCode(ready(call("message_evidence", Map.of())).getFirst(), -32602);
    assertCode(ready(call("message_evidence", Map.of("messageId", 42))).getFirst(), -32602);
    assertCode(ready(call("message_evidence", Map.of("messageId", "x".repeat(4097)))).getFirst(), -32602);
  }

  @Test
  void jsonReadLimitsAndInvalidMetadataRecoverWithoutCrashing() throws Exception {
    for (String malformed : List.of("1e9999", "[".repeat(41) + "0" + "]".repeat(41))) {
      List<JsonNode> responses = run(malformed, request(2, "ping", Map.of()));
      assertCode(responses.getFirst(), -32700);
      assertTrue(responses.getLast().has("result"));
    }
    assertCode(run(request(1, "ping", Map.of("_meta", "bad"))).getFirst(), -32602);
    assertCode(run("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"extra\":true}").getFirst(), -32600);
  }

  private static void assertPayloadAbsent(JsonNode node) {
    assertFalse(node.has("payload"), node.toString());
    assertFalse(node.toString().contains("secret-canary"), node.toString());
    if (node.isContainerNode()) for (JsonNode child : node) assertPayloadAbsent(child);
  }

  private record Fixture(RecoveryPlan plan, List<Message> messages) {}

  private static Fixture fixture() {
    List<Decision> decisions = new ArrayList<>();
    List<Message> messages = new ArrayList<>();
    List<Batch> batches = new ArrayList<>();
    for (int i = 0; i < 24; i++) {
      String messageId = "m%03d".formatted(i);
      String tenant = i % 4 < 2 ? "north" : "south";
      String failure = i % 2 == 0 ? "TIMEOUT" : "SCHEMA";
      SourceRef source = new SourceRef("messages.jsonl", "a".repeat(64), i + 2, "c".repeat(64));
      ObjectNode payload = Json.INSTANCE.getMapper().createObjectNode().put("secret", "secret-canary-%03d".formatted(i));
      payload.putObject("nested").put("ordinal", i);
      messages.add(new Message(messageId, tenant, "event-" + i, "OrderPlaced", "2026-01-01T00:00:00Z",
          null, failure, 1, "orders", (long) i, payload, "d".repeat(64), source));
      Outcome outcome = Outcome.values()[i % 3];
      String reason = switch (outcome) { case CANDIDATE -> "RESOLVED_FAILURE"; case HOLD -> "UNRESOLVED_FAILURE"; case EXCLUDE -> "RECEIPT_MATCH"; };
      decisions.add(new Decision("d%03d".formatted(i), messageId, tenant, "event-" + i, "OrderPlaced", "d".repeat(64),
          failure, outcome, new ArrayList<>(List.of(new Reason(reason, "Operator supplied evidence", "/consumerIdempotent", List.of(source)))),
          source, null, "orders", (long) i));
      if (outcome == Outcome.CANDIDATE) batches.add(new Batch(batches.size() + 1, new ArrayList<>(List.of(messageId))));
    }
    Collections.reverse(decisions);
    Collections.reverse(batches);
    RecoveryPlan plan = new RecoveryPlan(1, "0.1.0", new SnapshotMeta("snapshot-a", "orders-dlq", "2026-01-02T00:00:00Z", "COMPLETE"),
        "a".repeat(64), "b".repeat(64), "2026-01-02T12:00:00Z", "plan-a",
        new java.util.HashMap<>(Map.of("total", 24, "CANDIDATE", 8, "HOLD", 8, "EXCLUDE", 8)), decisions, batches,
        new ArrayList<>(List.of(new FailureGroup("TIMEOUT", 12, 4, 4, 4), new FailureGroup("SCHEMA", 12, 4, 4, 4))),
        new ArrayList<>(List.of("Consumer idempotency is an operator assertion")));
    return new Fixture(plan, messages);
  }

  private static McpServer server() {
    Fixture fixture = fixture();
    return new McpServer(fixture.plan(), fixture.messages(), false);
  }

  private static List<JsonNode> runReady(McpServer server, String... requests) throws Exception {
    List<String> messages = new ArrayList<>(List.of(initialize("2025-11-25"), INITIALIZED));
    messages.addAll(Arrays.asList(requests));
    return runBytes(server, lines(messages.toArray(String[]::new)).getBytes(StandardCharsets.UTF_8)).subList(1, requests.length + 1);
  }

  private static String initialize(String protocol) {
    return request(
        "init",
        "initialize",
        Map.of(
            "protocolVersion",
            protocol,
            "capabilities",
            Map.of(),
            "clientInfo",
            Map.of("name", "unit-test", "version", "1")));
  }

  private static String request(Object id, String method, Object params) {
    return Json.INSTANCE.text(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
  }

  private static String call(String name, Map<String, ?> args) {
    return request(7, "tools/call", Map.of("name", name, "arguments", args));
  }

  private static List<JsonNode> ready(String... requests) throws Exception {
    List<String> messages = new ArrayList<>(List.of(initialize("2025-11-25"), INITIALIZED));
    messages.addAll(Arrays.asList(requests));
    return run(messages.toArray(String[]::new)).subList(1, requests.length + 1);
  }

  private static List<JsonNode> run(String... messages) throws Exception {
    return runBytes(server(), lines(messages).getBytes(StandardCharsets.UTF_8));
  }

  private static String lines(String... messages) {
    return String.join("\n", messages) + "\n";
  }

  private static List<JsonNode> runBytes(McpServer server, byte[] bytes) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    server.run(new ByteArrayInputStream(bytes), output);
    List<JsonNode> results = new ArrayList<>();
    for (String line : output.toString(StandardCharsets.UTF_8).lines().toList()) {
      JsonNode response = Json.INSTANCE.read(line);
      assertEquals("2.0", response.path("jsonrpc").asText());
      results.add(response);
    }
    return results;
  }

  private static JsonNode structured(JsonNode response) throws Exception {
    assertFalse(response.has("error"), response.toString());
    JsonNode result = response.path("result");
    assertEquals(1, result.path("content").size());
    assertEquals("text", result.path("content").get(0).path("type").asText());
    JsonNode structured = result.path("structuredContent");
    assertEquals(structured, Json.INSTANCE.read(result.path("content").get(0).path("text").asText()));
    return structured;
  }

  private static void assertCode(JsonNode response, int expected) {
    assertEquals(expected, response.path("error").path("code").asInt(), response.toString());
    assertFalse(response.has("result"));
  }

  private static List<JsonNode> asList(JsonNode array) {
    List<JsonNode> values = new ArrayList<>();
    array.forEach(values::add);
    return values;
  }
}
