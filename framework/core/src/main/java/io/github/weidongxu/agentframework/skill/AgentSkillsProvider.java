package io.github.weidongxu.agentframework.skill;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * An {@link AIContextProvider} that exposes {@link AgentSkill}s from a {@link SkillSource} using the
 * progressive disclosure pattern from the
 * <a href="https://agentskills.io/">Agent Skills specification</a>, mirroring the .NET
 * {@code AgentSkillsProvider} / Python {@code SkillsProvider}:
 *
 * <ol>
 *   <li><strong>Advertise</strong> — each skill's name and description are injected into the
 *       instructions as an {@code <available_skills>} index.</li>
 *   <li><strong>Load</strong> — the full skill body is returned by the {@value #LOAD_SKILL_TOOL_NAME}
 *       tool.</li>
 *   <li><strong>Read resources</strong> — supplementary content is read on demand by the
 *       {@value #READ_SKILL_RESOURCE_TOOL_NAME} tool.</li>
 * </ol>
 *
 * <p>Script execution ({@code run_skill_script}) is intentionally not implemented — it requires
 * sandboxing and is deferred. The tools contributed here are ordinary read-only
 * {@link FunctionTool}s, so the agent both advertises them to the model and executes them locally in
 * its function-invocation loop.</p>
 */
public final class AgentSkillsProvider extends AIContextProvider {
    /** The name of the tool that loads a skill's full body. */
    public static final String LOAD_SKILL_TOOL_NAME = "load_skill";

    /** The name of the tool that reads a skill resource. */
    public static final String READ_SKILL_RESOURCE_TOOL_NAME = "read_skill_resource";

    private final SkillSource source;
    private final AgentSkillsProviderOptions options;

    public AgentSkillsProvider(SkillSource source) {
        this(source, AgentSkillsProviderOptions.defaults());
    }

    public AgentSkillsProvider(SkillSource source, AgentSkillsProviderOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
        List<AgentSkill> skills = source.getSkills();
        if (skills == null || skills.isEmpty()) {
            return CompletableFuture.completedFuture(AIContext.empty());
        }
        AIContext.Builder builder = AIContext.builder()
                .instructions(buildInstructions(skills))
                .tool(loadSkillTool(skills));
        if (options.isEnableReadSkillResource()) {
            builder.tool(readSkillResourceTool(skills));
        }
        return CompletableFuture.completedFuture(builder.build());
    }

    private String buildInstructions(List<AgentSkill> skills) {
        StringBuilder index = new StringBuilder();
        for (AgentSkill skill : skills) {
            SkillFrontmatter fm = skill.getFrontmatter();
            index.append("  <skill>\n")
                    .append("    <name>").append(escape(fm.getName())).append("</name>\n")
                    .append("    <description>").append(escape(fm.getDescription()))
                    .append("</description>\n")
                    .append("  </skill>\n");
        }
        String rendered = index.toString();
        if (rendered.endsWith("\n")) {
            rendered = rendered.substring(0, rendered.length() - 1);
        }
        return options.getSkillsInstructionPrompt()
                .replace(AgentSkillsProviderOptions.SKILLS_PLACEHOLDER, rendered);
    }

    private Tool loadSkillTool(List<AgentSkill> skills) {
        Map<String, Object> schema = objectSchema(
                Map.of("skill_name", stringProperty("The name of the skill to load, exactly as advertised.")),
                List.of("skill_name"));
        return new FunctionTool(
                LOAD_SKILL_TOOL_NAME,
                "Loads the full instructions of a specific skill by name.",
                schema,
                arguments -> CompletableFuture.completedFuture(loadSkill(skills, arguments)));
    }

    private Tool readSkillResourceTool(List<AgentSkill> skills) {
        Map<String, Object> schema = objectSchema(
                Map.of(
                        "skill_name", stringProperty("The name of the skill that owns the resource."),
                        "resource_name", stringProperty(
                                "The resource name, exactly as referenced in the skill body.")),
                List.of("skill_name", "resource_name"));
        return new FunctionTool(
                READ_SKILL_RESOURCE_TOOL_NAME,
                "Reads a resource associated with a skill, such as references or assets.",
                schema,
                arguments -> CompletableFuture.completedFuture(readSkillResource(skills, arguments)));
    }

    private static String loadSkill(List<AgentSkill> skills, Map<String, Object> arguments) {
        String skillName = string(arguments, "skill_name");
        AgentSkill skill = find(skills, skillName);
        if (skill == null) {
            return "Error: no skill named '" + skillName + "'.";
        }
        return skill.getContent();
    }

    private static String readSkillResource(List<AgentSkill> skills, Map<String, Object> arguments) {
        String skillName = string(arguments, "skill_name");
        String resourceName = string(arguments, "resource_name");
        AgentSkill skill = find(skills, skillName);
        if (skill == null) {
            return "Error: no skill named '" + skillName + "'.";
        }
        return skill.getResource(resourceName)
                .map(AgentSkillResource::getContent)
                .orElse("Error: skill '" + skillName + "' has no resource named '" + resourceName + "'.");
    }

    private static AgentSkill find(List<AgentSkill> skills, String name) {
        if (name == null) {
            return null;
        }
        for (AgentSkill skill : skills) {
            if (name.equals(skill.getFrontmatter().getName())) {
                return skill;
            }
        }
        return null;
    }

    private static String string(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(key);
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(required));
        return schema;
    }

    private static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new java.util.LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
