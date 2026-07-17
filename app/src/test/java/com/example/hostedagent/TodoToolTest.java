package com.example.hostedagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoToolTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private TodoService service;
    private TodoTool tool;

    @BeforeEach
    void setUp() {
        service = new TodoService();
        tool = new TodoTool(service);
    }

    private JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void readOnEmptyReturnsEmptyList() throws Exception {
        JsonNode out = parse(tool.execute("{\"action\":\"read\"}", "u1"));
        assertTrue(out.has("todos"));
        assertEquals(0, out.get("todos").size());
    }

    @Test
    void writeReturnsFullListAndPersists() throws Exception {
        String args = "{\"action\":\"write\",\"todos\":["
                + "{\"id\":\"task-1\",\"content\":\"Scaffold\",\"status\":\"completed\",\"priority\":\"high\"},"
                + "{\"id\":\"task-2\",\"content\":\"Migrate\",\"status\":\"in_progress\"}]}";
        JsonNode out = parse(tool.execute(args, "u1"));
        assertEquals(2, out.get("todos").size());
        assertEquals("task-1", out.get("todos").get(0).get("id").asText());
        assertEquals("high", out.get("todos").get(0).get("priority").asText());
        // second item has no priority -> field omitted
        assertFalse(out.get("todos").get(1).has("priority"));

        // Persisted: a subsequent read returns the same list.
        JsonNode read = parse(tool.execute("{\"action\":\"read\"}", "u1"));
        assertEquals(2, read.get("todos").size());
        assertEquals(2, service.read("u1").size());
    }

    @Test
    void writeReplacesPreviousList() throws Exception {
        tool.execute("{\"action\":\"write\",\"todos\":["
                + "{\"id\":\"a\",\"content\":\"x\",\"status\":\"pending\"},"
                + "{\"id\":\"b\",\"content\":\"y\",\"status\":\"pending\"}]}", "u1");
        JsonNode out = parse(tool.execute("{\"action\":\"write\",\"todos\":["
                + "{\"id\":\"c\",\"content\":\"z\",\"status\":\"pending\"}]}", "u1"));
        assertEquals(1, out.get("todos").size());
        assertEquals("c", out.get("todos").get(0).get("id").asText());
    }

    @Test
    void emptyWriteClearsList() throws Exception {
        tool.execute("{\"action\":\"write\",\"todos\":["
                + "{\"id\":\"a\",\"content\":\"x\",\"status\":\"pending\"}]}", "u1");
        JsonNode out = parse(tool.execute("{\"action\":\"write\",\"todos\":[]}", "u1"));
        assertEquals(0, out.get("todos").size());
    }

    @Test
    void invalidStatusReturnsError() throws Exception {
        JsonNode out = parse(tool.execute("{\"action\":\"write\",\"todos\":["
                + "{\"id\":\"a\",\"content\":\"x\",\"status\":\"done\"}]}", "u1"));
        assertTrue(out.has("error"));
        // rejected write must not persist
        assertTrue(service.read("u1").isEmpty());
    }

    @Test
    void missingIdReturnsError() throws Exception {
        JsonNode out = parse(tool.execute("{\"action\":\"write\",\"todos\":["
                + "{\"content\":\"x\",\"status\":\"pending\"}]}", "u1"));
        assertTrue(out.has("error"));
    }

    @Test
    void missingContentReturnsError() throws Exception {
        JsonNode out = parse(tool.execute("{\"action\":\"write\",\"todos\":["
                + "{\"id\":\"a\",\"status\":\"pending\"}]}", "u1"));
        assertTrue(out.has("error"));
    }

    @Test
    void duplicateIdReturnsError() throws Exception {
        JsonNode out = parse(tool.execute("{\"action\":\"write\",\"todos\":["
                + "{\"id\":\"a\",\"content\":\"x\",\"status\":\"pending\"},"
                + "{\"id\":\"a\",\"content\":\"y\",\"status\":\"pending\"}]}", "u1"));
        assertTrue(out.has("error"));
    }

    @Test
    void invalidPriorityReturnsError() throws Exception {
        JsonNode out = parse(tool.execute("{\"action\":\"write\",\"todos\":["
                + "{\"id\":\"a\",\"content\":\"x\",\"status\":\"pending\",\"priority\":\"urgent\"}]}", "u1"));
        assertTrue(out.has("error"));
    }

    @Test
    void unknownActionReturnsError() throws Exception {
        JsonNode out = parse(tool.execute("{\"action\":\"delete\"}", "u1"));
        assertTrue(out.has("error"));
    }

    @Test
    void missingActionReturnsError() throws Exception {
        JsonNode out = parse(tool.execute("{}", "u1"));
        assertTrue(out.has("error"));
    }

    @Test
    void malformedArgumentsReturnsError() throws Exception {
        JsonNode out = parse(tool.execute("not-json", "u1"));
        assertTrue(out.has("error"));
    }

    @Test
    void scopesAreIsolated() throws Exception {
        tool.execute("{\"action\":\"write\",\"todos\":["
                + "{\"id\":\"a\",\"content\":\"x\",\"status\":\"pending\"}]}", "u1");
        JsonNode readOther = parse(tool.execute("{\"action\":\"read\"}", "u2"));
        assertEquals(0, readOther.get("todos").size());
    }

    @Test
    void exposesFrameworkToolMetadata() {
        io.github.weidongxu.agentframework.tool.FunctionTool ft = tool.asFunctionTool();
        assertEquals(TodoTool.NAME, ft.getName());
        assertEquals("object", ft.getParametersSchema().get("type"));
    }

    @Test
    void invokeUsesSessionScopeFromContext() throws Exception {
        io.github.weidongxu.agentframework.agent.AgentSession session =
                new io.github.weidongxu.agentframework.agent.AgentSession(
                        "user-x", java.util.Map.of());
        tool.asFunctionTool()
                .invoke(
                        java.util.Map.of(
                                "action", "write",
                                "todos", java.util.List.of(java.util.Map.of(
                                        "id", "a", "content", "x", "status", "pending"))),
                        new io.github.weidongxu.agentframework.tool.ToolContext(session))
                .toCompletableFuture()
                .get();
        assertEquals(1, service.read("user-x").size());
        assertTrue(service.read("u1").isEmpty());
    }
}
