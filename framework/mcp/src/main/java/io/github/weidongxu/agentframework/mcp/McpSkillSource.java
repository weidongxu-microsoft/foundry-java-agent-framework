package io.github.weidongxu.agentframework.mcp;

import io.github.weidongxu.agentframework.skill.AgentSkill;
import io.github.weidongxu.agentframework.skill.SkillFrontmatter;
import io.github.weidongxu.agentframework.skill.SkillSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link SkillSource} that discovers Agent Skills served over the Model Context Protocol (MCP).
 * The framework acts as the MCP <em>client</em>, connects to a server (a stdio child process or a
 * streamable-HTTP endpoint), reads the well-known {@code skill://index.json} discovery resource, and
 * adapts each {@code skill-md} entry into a framework {@link AgentSkill} whose body and resources are
 * fetched lazily via {@code resources/read}. Mirrors the .NET/Python {@code AgentMcpSkillsSource}.
 *
 * <p>Discovery follows the SEP-2640 binding: entries of type {@code skill-md} are exposed; other
 * types ({@code archive}, {@code mcp-resource-template}) are skipped. If the index is absent,
 * unreadable, empty, or unparseable, {@link #getSkills()} returns an empty list rather than failing.</p>
 *
 * <p>An optional <em>allow-list</em> restricts which skills are surfaced by name.</p>
 *
 * <p><strong>Security:</strong> a skill source is a trust boundary. Skill names, descriptions, and
 * bodies from the MCP server are injected into the agent's context as-is; a malicious server can
 * return adversarial content (indirect prompt injection). Only connect to servers you trust.</p>
 *
 * <p>The source owns the underlying client/process and is {@link AutoCloseable}; close it (e.g. via
 * a Spring {@code @Bean(destroyMethod = "close")}) to shut the server down.</p>
 */
public final class McpSkillSource implements SkillSource, AutoCloseable {

    /** SEP-2640 canonical discovery document URI. */
    static final String INDEX_URI = "skill://index.json";

    /** The only distribution type this source loads; other types are skipped. */
    private static final String SKILL_MD_TYPE = "skill-md";

    private final McpClientHandle client;
    private final Set<String> allowedSkillNames;

    McpSkillSource(McpClientHandle client, Collection<String> allowedSkillNames) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowedSkillNames = allowedSkillNames == null
                ? null
                : new LinkedHashSet<>(allowedSkillNames);
    }

    /** Connects to an MCP server spawned as a stdio child process, exposing all its skills. */
    public static McpSkillSource stdio(String command, List<String> args, Map<String, String> env) {
        return stdio(command, args, env, null);
    }

    /**
     * Connects to an MCP server spawned as a stdio child process, exposing only skills whose names
     * are in {@code allowedSkillNames} (pass {@code null} to expose all).
     */
    public static McpSkillSource stdio(
            String command,
            List<String> args,
            Map<String, String> env,
            Collection<String> allowedSkillNames) {
        return new McpSkillSource(McpClients.stdio(command, args, env), allowedSkillNames);
    }

    /** Connects to a streamable-HTTP MCP server at {@code url}, exposing all its skills. */
    public static McpSkillSource streamableHttp(String url) {
        return streamableHttp(url, null);
    }

    /**
     * Connects to a streamable-HTTP MCP server at {@code url}, exposing only skills whose names are
     * in {@code allowedSkillNames} (pass {@code null} to expose all).
     */
    public static McpSkillSource streamableHttp(String url, Collection<String> allowedSkillNames) {
        return new McpSkillSource(McpClients.streamableHttp(url), allowedSkillNames);
    }

    @Override
    public List<AgentSkill> getSkills() {
        String indexText = readIndex();
        List<AgentSkill> skills = new ArrayList<>();
        for (McpSkillMappers.IndexEntry entry : McpSkillMappers.parseIndex(indexText)) {
            if (!SKILL_MD_TYPE.equalsIgnoreCase(entry.type)) {
                continue;
            }
            if (entry.name == null || entry.name.trim().isEmpty()
                    || entry.description == null
                    || entry.url == null || entry.url.trim().isEmpty()) {
                continue;
            }
            if (allowedSkillNames != null && !allowedSkillNames.contains(entry.name)) {
                continue;
            }
            SkillFrontmatter frontmatter = SkillFrontmatter.of(entry.name, entry.description);
            skills.add(new McpSkill(frontmatter, entry.url, client));
        }
        return skills;
    }

    private String readIndex() {
        try {
            return McpSkillMappers.textContent(client.readResource(INDEX_URI));
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
