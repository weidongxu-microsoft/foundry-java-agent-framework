package io.github.weidongxu.agentframework.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure mappers for the skills-over-MCP wire format: parsing the {@code skill://index.json} discovery
 * document and flattening a {@link McpSchema.ReadResourceResult} into text. Kept separate from
 * {@link McpSkillSource} so the (transport-free) parsing logic is unit-testable in isolation.
 *
 * <p>Follows the SEP-2640 MCP binding of the Agent Skills Discovery schema: the index is a JSON
 * object with a {@code skills} array, each entry carrying {@code name}, {@code type},
 * {@code description}, and {@code url} (a full MCP resource URI).</p>
 */
final class McpSkillMappers {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpSkillMappers() {
    }

    /** A single, minimally-validated entry from {@code skill://index.json}. */
    static final class IndexEntry {
        final String name;
        final String type;
        final String description;
        final String url;

        IndexEntry(String name, String type, String description, String url) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.url = url;
        }
    }

    /**
     * Parses the raw {@code skill://index.json} text into a list of entries. Returns an empty list
     * when the text is blank, not an object, has no {@code skills} array, or fails to parse — the
     * source treats all of these as "no skills advertised" rather than an error.
     */
    static List<IndexEntry> parseIndex(String indexText) {
        List<IndexEntry> entries = new ArrayList<>();
        if (indexText == null || indexText.trim().isEmpty()) {
            return entries;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(indexText);
        } catch (Exception e) {
            return entries;
        }
        JsonNode skills = root == null ? null : root.get("skills");
        if (skills == null || !skills.isArray()) {
            return entries;
        }
        for (JsonNode entry : skills) {
            entries.add(new IndexEntry(
                    text(entry, "name"),
                    text(entry, "type"),
                    text(entry, "description"),
                    text(entry, "url")));
        }
        return entries;
    }

    /** Concatenates all text-resource contents of a read result with newlines. */
    static String textContent(McpSchema.ReadResourceResult result) {
        if (result == null || result.contents() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.ResourceContents content : result.contents()) {
            if (content instanceof McpSchema.TextResourceContents) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(((McpSchema.TextResourceContents) content).text());
            }
        }
        return sb.toString();
    }

    /**
     * Derives a skill's root resource URI from its {@code SKILL.md} URI by stripping the trailing
     * {@code SKILL.md} segment, so sibling resources resolve against it (mirrors the .NET
     * {@code ComputeSkillRootUri}).
     */
    static String skillRootUri(String skillMdUri) {
        if (skillMdUri.endsWith("SKILL.md")) {
            return skillMdUri.substring(0, skillMdUri.length() - "SKILL.md".length());
        }
        return skillMdUri.endsWith("/") ? skillMdUri : skillMdUri + "/";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
