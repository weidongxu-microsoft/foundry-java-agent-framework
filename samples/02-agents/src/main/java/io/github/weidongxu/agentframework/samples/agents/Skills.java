package io.github.weidongxu.agentframework.samples.agents;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.skill.AgentSkillsProvider;
import io.github.weidongxu.agentframework.skill.FileSkillSource;

import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 02 — Agent Skills (progressive disclosure).
 *
 * <p>An {@link AgentSkillsProvider} is an {@code AIContextProvider}: it advertises each on-disk
 * skill's name/description as an {@code <available_skills>} index and contributes read-only
 * {@code load_skill} / {@code read_skill_resource} tools. The model loads a skill's full
 * instructions only when it decides the skill is relevant.
 *
 * <p>Point {@code SKILLS_DIR} at a directory whose subfolders each contain a {@code SKILL.md}.
 *
 * <pre>{@code
 *   $env:SKILLS_DIR = "C:\path\to\skills"
 *   mvn -q -f samples\02-agents\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.Skills
 * }</pre>
 */
public final class Skills {

    private Skills() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        String skillsDir = Support.env("SKILLS_DIR", "skills");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            AgentSkillsProvider skills = new AgentSkillsProvider(
                    new FileSkillSource(Paths.get(skillsDir)));

            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("skilled-agent")
                    .instructions("You are a helpful assistant. Consult the available skills and "
                            + "load one when it is relevant to the request.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .aiContextProvider(skills)
                    .build();

            String question = args.length > 0
                    ? String.join(" ", args)
                    : "Which of your skills could help, and what does it do?";
            System.out.println("Skills dir: " + skillsDir);
            System.out.println("Q: " + question);

            AgentResponse response = agent.run(question).toCompletableFuture().get();
            System.out.println("A: " + response.getText());
        } finally {
            executor.shutdown();
        }
    }
}
