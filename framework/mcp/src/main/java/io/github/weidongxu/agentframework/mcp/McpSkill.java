package io.github.weidongxu.agentframework.mcp;

import io.github.weidongxu.agentframework.skill.AgentSkill;
import io.github.weidongxu.agentframework.skill.AgentSkillResource;
import io.github.weidongxu.agentframework.skill.SkillFrontmatter;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Objects;
import java.util.Optional;

/**
 * An {@link AgentSkill} discovered from an MCP server that exposes the Agent Skills convention. Only
 * the L1 frontmatter (name/description) is known up front — the full {@code SKILL.md} body and any
 * sibling resources are fetched lazily from the server via {@code resources/read} on demand,
 * preserving the progressive-disclosure pattern. Mirrors the .NET {@code AgentMcpSkill}.
 */
final class McpSkill implements AgentSkill {

    private final SkillFrontmatter frontmatter;
    private final String skillMdUri;
    private final String rootUri;
    private final McpClientHandle client;
    private String content;

    McpSkill(SkillFrontmatter frontmatter, String skillMdUri, McpClientHandle client) {
        this.frontmatter = Objects.requireNonNull(frontmatter, "frontmatter");
        this.skillMdUri = Objects.requireNonNull(skillMdUri, "skillMdUri");
        this.rootUri = McpSkillMappers.skillRootUri(skillMdUri);
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public SkillFrontmatter getFrontmatter() {
        return frontmatter;
    }

    @Override
    public synchronized String getContent() {
        if (content != null) {
            return content;
        }
        McpSchema.ReadResourceResult result = client.readResource(skillMdUri);
        String text = McpSkillMappers.textContent(result);
        if (text.isEmpty()) {
            throw new IllegalStateException(
                    "MCP server returned no text content for SKILL.md resource '" + skillMdUri + "'.");
        }
        content = text;
        return content;
    }

    @Override
    public Optional<AgentSkillResource> getResource(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        McpSchema.ReadResourceResult result;
        try {
            result = client.readResource(rootUri + name);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        String text = McpSkillMappers.textContent(result);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AgentSkillResource(name, text));
    }
}
