package io.github.weidongxu.agentframework.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A {@link SkillSource} that discovers skills on the filesystem. Mirrors the .NET
 * {@code AgentFileSkillsSource} / Python {@code FileSkill} discovery.
 *
 * <p>Each immediate subdirectory of the root that contains a {@code SKILL.md} file is treated as one
 * skill. The {@code SKILL.md} may begin with a {@code ---}-delimited YAML frontmatter block; the
 * remaining text is the skill body. Only a small, dependency-free subset of YAML is understood
 * ({@code key: value} scalars and simple {@code - item} lists) — enough for the
 * {@code name}/{@code description}/{@code license}/{@code allowed-tools} fields — which keeps the
 * lean core free of a YAML dependency.</p>
 */
public final class FileSkillSource implements SkillSource {
    private static final String SKILL_FILE = "SKILL.md";

    private final Path root;

    public FileSkillSource(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public List<AgentSkill> getSkills() {
        List<AgentSkill> skills = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return skills;
        }
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory)
                    .sorted()
                    .forEach(dir -> {
                        Path skillFile = dir.resolve(SKILL_FILE);
                        if (Files.isRegularFile(skillFile)) {
                            skills.add(readSkill(dir, skillFile));
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list skills directory: " + root, e);
        }
        return skills;
    }

    private static AgentSkill readSkill(Path dir, Path skillFile) {
        String text;
        try {
            text = new String(Files.readAllBytes(skillFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + skillFile, e);
        }
        String fallbackName = dir.getFileName().toString();
        Frontmatter parsed = Frontmatter.parse(text, fallbackName);
        return new FileSkill(dir, skillFile, parsed.frontmatter, parsed.body);
    }

    /** Result of splitting a {@code SKILL.md} into frontmatter metadata and body. */
    private static final class Frontmatter {
        final SkillFrontmatter frontmatter;
        final String body;

        Frontmatter(SkillFrontmatter frontmatter, String body) {
            this.frontmatter = frontmatter;
            this.body = body;
        }

        static Frontmatter parse(String text, String fallbackName) {
            String normalized = text.replace("\r\n", "\n");
            String yaml = null;
            String body = normalized;
            if (normalized.startsWith("---\n")) {
                int end = normalized.indexOf("\n---", 3);
                if (end >= 0) {
                    yaml = normalized.substring(4, end + 1);
                    int bodyStart = normalized.indexOf('\n', end + 1);
                    body = bodyStart >= 0 ? normalized.substring(bodyStart + 1) : "";
                }
            }

            String name = fallbackName;
            String description = "";
            String license = null;
            List<String> allowedTools = new ArrayList<>();
            List<String[]> extras = new ArrayList<>();

            if (yaml != null) {
                String currentListKey = null;
                for (String rawLine : yaml.split("\n", -1)) {
                    if (rawLine.trim().isEmpty()) {
                        continue;
                    }
                    if (rawLine.startsWith("  ") && rawLine.trim().startsWith("- ")) {
                        String item = rawLine.trim().substring(2).trim();
                        if ("allowed-tools".equals(currentListKey) || "allowed_tools".equals(currentListKey)) {
                            allowedTools.add(unquote(item));
                        }
                        continue;
                    }
                    currentListKey = null;
                    int colon = rawLine.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String key = rawLine.substring(0, colon).trim();
                    String value = rawLine.substring(colon + 1).trim();
                    if (value.isEmpty()) {
                        currentListKey = key;
                        continue;
                    }
                    value = unquote(value);
                    switch (key) {
                        case "name":
                            name = value;
                            break;
                        case "description":
                            description = value;
                            break;
                        case "license":
                            license = value;
                            break;
                        default:
                            extras.add(new String[] {key, value});
                            break;
                    }
                }
            }

            SkillFrontmatter.Builder builder = SkillFrontmatter.builder(name, description);
            if (license != null) {
                builder.license(license);
            }
            builder.allowedTools(allowedTools);
            for (String[] extra : extras) {
                builder.metadata(extra[0], extra[1]);
            }
            return new Frontmatter(builder.build(), body);
        }

        private static String unquote(String value) {
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
    }
}
