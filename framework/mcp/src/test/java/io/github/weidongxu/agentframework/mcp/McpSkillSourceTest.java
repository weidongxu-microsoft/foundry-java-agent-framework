package io.github.weidongxu.agentframework.mcp;

import io.github.weidongxu.agentframework.skill.AgentSkill;
import io.github.weidongxu.agentframework.skill.AgentSkillResource;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests for {@link McpSkillSource}: a fake {@link McpClientHandle} stands in for a live MCP
 * server so index parsing, allow-list filtering, type filtering, and lazy content/resource loading
 * are exercised without any process or transport.
 */
class McpSkillSourceTest {

    private static final String INDEX = "{\n"
            + "  \"$schema\": \"https://schemas.agentskills.io/discovery/0.2.0/schema.json\",\n"
            + "  \"skills\": [\n"
            + "    {\"name\": \"unit-converter\", \"type\": \"skill-md\","
            + " \"description\": \"Convert units\", \"url\": \"skill://unit-converter/SKILL.md\"},\n"
            + "    {\"name\": \"packaged\", \"type\": \"archive\","
            + " \"description\": \"Archived\", \"url\": \"skill://packaged.zip\"},\n"
            + "    {\"name\": \"draft-changelog\", \"type\": \"skill-md\","
            + " \"description\": \"Draft changelog\", \"url\": \"skill://draft-changelog/SKILL.md\"}\n"
            + "  ]\n"
            + "}";

    private static McpSchema.ReadResourceResult text(String uri, String body) {
        return McpSchema.ReadResourceResult.builder(
                List.of(McpSchema.TextResourceContents.builder(uri, body)
                        .mimeType("text/markdown").build())).build();
    }

    @Test
    void discoversSkillMdEntriesAndSkipsOtherTypes() {
        FakeClient fake = new FakeClient(uri ->
                McpSkillSource.INDEX_URI.equals(uri) ? text(uri, INDEX) : null);
        try (McpSkillSource source = new McpSkillSource(fake, null)) {
            List<String> names = new ArrayList<>();
            source.getSkills().forEach(s -> names.add(s.getFrontmatter().getName()));
            // "packaged" (archive) is skipped; only the two skill-md entries surface.
            assertEquals(List.of("unit-converter", "draft-changelog"), names);
        }
    }

    @Test
    void loadsSkillBodyLazilyAndCaches() {
        AtomicInteger reads = new AtomicInteger();
        FakeClient fake = new FakeClient(uri -> {
            if (McpSkillSource.INDEX_URI.equals(uri)) {
                return text(uri, INDEX);
            }
            if ("skill://unit-converter/SKILL.md".equals(uri)) {
                reads.incrementAndGet();
                return text(uri, "# Unit Converter\nBody here.");
            }
            return null;
        });
        try (McpSkillSource source = new McpSkillSource(fake, Set.of("unit-converter"))) {
            AgentSkill skill = source.getSkills().get(0);
            // The index read alone must not fetch the body (progressive disclosure).
            assertEquals(0, reads.get());
            assertEquals("# Unit Converter\nBody here.", skill.getContent());
            assertEquals("# Unit Converter\nBody here.", skill.getContent());
            assertEquals(1, reads.get(), "body should be fetched once and cached");
        }
    }

    @Test
    void resolvesSiblingResourceAgainstSkillRoot() {
        FakeClient fake = new FakeClient(uri -> {
            if (McpSkillSource.INDEX_URI.equals(uri)) {
                return text(uri, INDEX);
            }
            if ("skill://unit-converter/references/table.md".equals(uri)) {
                return text(uri, "conversion table");
            }
            return null;
        });
        try (McpSkillSource source = new McpSkillSource(fake, Set.of("unit-converter"))) {
            AgentSkill skill = source.getSkills().get(0);
            Optional<AgentSkillResource> resource = skill.getResource("references/table.md");
            assertTrue(resource.isPresent());
            assertEquals("references/table.md", resource.get().getName());
            assertEquals("conversion table", resource.get().getContent());
            assertFalse(skill.getResource("missing.md").isPresent());
        }
    }

    @Test
    void allowListFiltersSkillsByName() {
        FakeClient fake = new FakeClient(uri ->
                McpSkillSource.INDEX_URI.equals(uri) ? text(uri, INDEX) : null);
        try (McpSkillSource source = new McpSkillSource(fake, Set.of("draft-changelog"))) {
            List<String> names = new ArrayList<>();
            source.getSkills().forEach(s -> names.add(s.getFrontmatter().getName()));
            assertEquals(List.of("draft-changelog"), names);
        }
    }

    @Test
    void missingIndexYieldsNoSkills() {
        FakeClient fake = new FakeClient(uri -> {
            throw new IllegalStateException("resource not found");
        });
        try (McpSkillSource source = new McpSkillSource(fake, null)) {
            assertTrue(source.getSkills().isEmpty());
        }
    }

    @Test
    void emptyContentBodyThrowsWhenLoaded() {
        FakeClient fake = new FakeClient(uri -> {
            if (McpSkillSource.INDEX_URI.equals(uri)) {
                return text(uri, INDEX);
            }
            return McpSchema.ReadResourceResult.builder(List.of()).build();
        });
        try (McpSkillSource source = new McpSkillSource(fake, Set.of("unit-converter"))) {
            AgentSkill skill = source.getSkills().get(0);
            assertThrows(IllegalStateException.class, skill::getContent);
        }
    }

    @Test
    void closePropagatesToClient() {
        FakeClient fake = new FakeClient(uri -> null);
        McpSkillSource source = new McpSkillSource(fake, null);
        source.close();
        assertTrue(fake.closed);
    }

    /** In-memory {@link McpClientHandle} that scripts {@code resources/read} by URI. */
    private static final class FakeClient implements McpClientHandle {
        private final java.util.function.Function<String, McpSchema.ReadResourceResult> onRead;
        private boolean closed;

        FakeClient(java.util.function.Function<String, McpSchema.ReadResourceResult> onRead) {
            this.onRead = onRead;
        }

        @Override
        public List<McpSchema.Tool> listTools() {
            return List.of();
        }

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
            throw new UnsupportedOperationException("callTool not used by skill tests");
        }

        @Override
        public McpSchema.ReadResourceResult readResource(String uri) {
            McpSchema.ReadResourceResult result = onRead.apply(uri);
            if (result == null) {
                throw new IllegalStateException("no resource: " + uri);
            }
            return result;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
