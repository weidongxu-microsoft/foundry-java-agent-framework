package io.github.weidongxu.agentframework.skill;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.tool.Tool;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillsProviderTest {

    private static final Agent STUB_AGENT = new Agent() {
        @Override
        public CompletionStage<AgentResponse> run(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }
    };

    private static AIContext provide(AgentSkillsProvider provider) throws Exception {
        AgentInvokingContext context =
                new AgentInvokingContext(STUB_AGENT, null, AIContext.empty());
        return provider.invoking(context).toCompletableFuture().get();
    }

    private static Tool toolNamed(AIContext context, String name) {
        return context.getTools().stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst()
                .orElse(null);
    }

    private static InMemorySkill sampleSkill() {
        return InMemorySkill.builder(
                        SkillFrontmatter.of("draft-changelog", "Draft a CHANGELOG entry for the repo."),
                        "Full body: run git log, then return CHANGELOG text.")
                .resource(new AgentSkillResource("references/format.md", "## Format\nKeepAChangelog."))
                .build();
    }

    @Test
    void advertisesSkillIndexAndTools() throws Exception {
        AgentSkillsProvider provider = new AgentSkillsProvider(new InMemorySkillSource(sampleSkill()));

        AIContext context = provide(provider);

        assertTrue(context.getInstructions().contains("<available_skills>"));
        assertTrue(context.getInstructions().contains("<name>draft-changelog</name>"));
        assertTrue(context.getInstructions().contains(
                "<description>Draft a CHANGELOG entry for the repo.</description>"));
        assertNotNull(toolNamed(context, AgentSkillsProvider.LOAD_SKILL_TOOL_NAME));
        assertNotNull(toolNamed(context, AgentSkillsProvider.READ_SKILL_RESOURCE_TOOL_NAME));
    }

    @Test
    void loadSkillReturnsBody() throws Exception {
        AgentSkillsProvider provider = new AgentSkillsProvider(new InMemorySkillSource(sampleSkill()));
        AIContext context = provide(provider);

        Tool loadSkill = toolNamed(context, AgentSkillsProvider.LOAD_SKILL_TOOL_NAME);
        String body = loadSkill.invoke(Map.of("skill_name", "draft-changelog"))
                .toCompletableFuture().get();

        assertEquals("Full body: run git log, then return CHANGELOG text.", body);
    }

    @Test
    void readSkillResourceReturnsResourceAndErrorsOnUnknown() throws Exception {
        AgentSkillsProvider provider = new AgentSkillsProvider(new InMemorySkillSource(sampleSkill()));
        AIContext context = provide(provider);

        Tool read = toolNamed(context, AgentSkillsProvider.READ_SKILL_RESOURCE_TOOL_NAME);
        String resource = read.invoke(
                        Map.of("skill_name", "draft-changelog", "resource_name", "references/format.md"))
                .toCompletableFuture().get();
        assertTrue(resource.contains("KeepAChangelog"));

        String missing = read.invoke(
                        Map.of("skill_name", "draft-changelog", "resource_name", "nope.md"))
                .toCompletableFuture().get();
        assertTrue(missing.startsWith("Error:"));
    }

    @Test
    void loadUnknownSkillReturnsError() throws Exception {
        AgentSkillsProvider provider = new AgentSkillsProvider(new InMemorySkillSource(sampleSkill()));
        AIContext context = provide(provider);

        Tool loadSkill = toolNamed(context, AgentSkillsProvider.LOAD_SKILL_TOOL_NAME);
        String result = loadSkill.invoke(Map.of("skill_name", "ghost"))
                .toCompletableFuture().get();

        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void emptySourceContributesNothing() throws Exception {
        AgentSkillsProvider provider = new AgentSkillsProvider(new InMemorySkillSource());
        AIContext context = provide(provider);

        assertTrue(context.getTools().isEmpty());
    }

    @Test
    void fileSkillSourceParsesFrontmatterAndResources() throws Exception {
        Path root = Files.createTempDirectory("skills");
        try {
            Path skillDir = Files.createDirectory(root.resolve("draft-changelog"));
            String skillMd = "---\n"
                    + "name: draft-changelog\n"
                    + "description: Draft a CHANGELOG entry.\n"
                    + "license: MIT\n"
                    + "allowed-tools:\n"
                    + "  - git_log\n"
                    + "  - git_status\n"
                    + "---\n"
                    + "Body line one.\nBody line two.\n";
            Files.write(skillDir.resolve("SKILL.md"), skillMd.getBytes(StandardCharsets.UTF_8));
            Files.createDirectory(skillDir.resolve("references"));
            Files.write(skillDir.resolve("references").resolve("faq.md"),
                    "the FAQ".getBytes(StandardCharsets.UTF_8));

            FileSkillSource source = new FileSkillSource(root);
            List<AgentSkill> skills = source.getSkills();

            assertEquals(1, skills.size());
            AgentSkill skill = skills.get(0);
            assertEquals("draft-changelog", skill.getFrontmatter().getName());
            assertEquals("Draft a CHANGELOG entry.", skill.getFrontmatter().getDescription());
            assertEquals("MIT", skill.getFrontmatter().getLicense());
            assertEquals(List.of("git_log", "git_status"), skill.getFrontmatter().getAllowedTools());
            assertTrue(skill.getContent().contains("Body line one."));
            assertTrue(skill.getResource("references/faq.md").isPresent());
            assertEquals("the FAQ", skill.getResource("references/faq.md").get().getContent());
        } finally {
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path child : (Iterable<Path>) entries::iterator) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
