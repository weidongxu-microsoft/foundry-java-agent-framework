package com.example.memadmin;

import com.azure.ai.agents.AgentsClientBuilder;
import com.azure.ai.agents.BetaMemoryStoresClient;
import com.azure.ai.agents.models.MemoryStoreDefaultDefinition;
import com.azure.ai.agents.models.MemoryStoreDefaultOptions;
import com.azure.identity.DefaultAzureCredentialBuilder;

/**
 * One-time bootstrap (run with developer/CI credentials, NOT inside the per-session container).
 *
 * <p>Creates the single piece of durable Foundry state the coded container depends on: a managed
 * <b>memory store</b> (chat + embedding model backed). The container itself owns its behavior — it
 * attaches the hosted tools inline and drives the memory store directly via its gated memory
 * provider, so there is <b>no backing prompt agent</b> to provision here.</p>
 *
 * <p>Run from the {@code admin} module:
 * {@code mvn -q -f admin/pom.xml compile exec:java -Dexec.mainClass=com.example.memadmin.Bootstrap}</p>
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    public static void main(String[] args) {
        String projectEndpoint = require("FOUNDRY_PROJECT_ENDPOINT");
        String chatModel = env("CHAT_MODEL", "gpt-5.4");
        String embeddingModel = env("EMBEDDING_MODEL", "text-embedding-3-small");
        String memoryStoreName = env("MEMORY_STORE_NAME", "memstore-hostagent");
        String memoryScope = env("MEMORY_SCOPE", "demo-user");
        String profileDetails = env("MEMORY_USER_PROFILE_DETAILS",
                "Store durable user facts and stable preferences. Do not store secrets or "
                        + "credentials (API keys, tokens, passwords, connection strings) or other "
                        + "sensitive data such as financial details or precise location. Ignore "
                        + "pleasantries and small talk.");

        AgentsClientBuilder builder = new AgentsClientBuilder()
                .endpoint(projectEndpoint)
                .credential(new DefaultAzureCredentialBuilder().build());

        BetaMemoryStoresClient memoryStores =
                builder.beta().buildBetaMemoryStoresClient();

        // Memory store (idempotent: reuse if it already exists).
        ensureMemoryStore(memoryStores, memoryStoreName, chatModel, embeddingModel, profileDetails);

        System.out.println("=== Bootstrap complete ===");
        System.out.println("MEMORY_STORE_NAME=" + memoryStoreName);
        System.out.println("MEMORY_SCOPE=" + memoryScope);
        System.out.println("MODEL=" + chatModel);
    }

    private static void ensureMemoryStore(
            BetaMemoryStoresClient client,
            String name,
            String chatModel,
            String embeddingModel,
            String profileDetails) {
        try {
            client.getMemoryStore(name);
            System.out.println("Memory store already exists, reusing: " + name);
        } catch (RuntimeException notFound) {
            MemoryStoreDefaultDefinition def =
                    new MemoryStoreDefaultDefinition(chatModel, embeddingModel)
                            .setOptions(new MemoryStoreDefaultOptions(true, true)
                                    .setUserProfileDetails(profileDetails));
            client.createMemoryStore(name, def, "Hosted agent demo memory store", null);
            System.out.println("Created memory store: " + name);
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String require(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }
}
