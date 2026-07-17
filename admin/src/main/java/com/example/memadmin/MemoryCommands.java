package com.example.memadmin;

import com.azure.ai.agents.BetaMemoryStoresClient;
import com.azure.ai.agents.models.ListMemoriesOptions;
import com.azure.ai.agents.models.MemoryItem;
import com.azure.ai.agents.models.MemoryItemKind;
import com.azure.ai.agents.models.MemoryStoreDetails;
import com.azure.ai.agents.models.PageOrder;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.RequestOptions;
import com.azure.core.util.BinaryData;

/**
 * Memory-store administration commands (project-wide, distinct from the hosted agent's per-turn
 * recall/remember). Backed by {@link BetaMemoryStoresClient} from {@code azure-ai-agents}.
 */
final class MemoryCommands {

    private MemoryCommands() {
    }

    static void stores(BetaMemoryStoresClient client) {
        System.out.println("Memory stores:");
        int n = 0;
        for (MemoryStoreDetails s : client.listMemoryStores()) {
            System.out.printf("- %s  (id=%s, created=%s)%n", s.getName(), s.getId(), s.getCreatedAt());
            n++;
        }
        System.out.println("(" + n + " store(s))");
    }

    static void list(BetaMemoryStoresClient client, String store, String scope) {
        ListMemoriesOptions options = new ListMemoriesOptions(store, scope)
                .setLimit(100)
                .setOrder(PageOrder.DESC);
        System.out.println("Memories in store '" + store + "', scope '" + scope + "':");
        int n = 0;
        for (MemoryItem item : client.listMemories(options)) {
            printItem(item);
            n++;
        }
        System.out.println("(" + n + " memory item(s))");
    }

    static void listAll(BetaMemoryStoresClient client, String store) {
        System.out.println("Memories in store '" + store + "' (all scopes, best-effort):");
        try {
            PagedIterable<BinaryData> raw =
                    client.listMemories(store, BinaryData.fromString("{}"), new RequestOptions());
            int n = 0;
            for (BinaryData b : raw) {
                System.out.println(b.toString());
                n++;
            }
            System.out.println("(" + n + " raw item(s))");
        } catch (RuntimeException e) {
            System.err.println("list-all is not supported without a scope by this service "
                    + "(" + e.getMessage() + ").");
            System.err.println("Use:  list <scope>   (there is no 'list scopes' API).");
        }
    }

    static void get(BetaMemoryStoresClient client, String store, String memoryId) {
        printItem(client.getMemory(store, memoryId));
    }

    static void deleteMemory(BetaMemoryStoresClient client, String store, String memoryId) {
        client.deleteMemory(store, memoryId);
        System.out.println("Deleted memory " + memoryId + " from store '" + store + "'.");
    }

    static void deleteScope(BetaMemoryStoresClient client, String store, String scope, boolean autoYes) {
        if (!Cli.confirm("Delete ALL memories in scope '" + scope
                + "' of store '" + store + "'?", autoYes)) {
            System.out.println("Aborted.");
            return;
        }
        client.deleteScope(store, scope);
        System.out.println("Deleted all memories in scope '" + scope + "'.");
    }

    private static void printItem(MemoryItem item) {
        MemoryItemKind kind = item.getKind();
        System.out.printf("- id=%s  kind=%s  scope=%s  updated=%s%n",
                item.getMemoryId(),
                kind == null ? "?" : kind.toString(),
                item.getScope(),
                item.getUpdatedAt());
        String content = item.getContent();
        if (content != null && !content.isBlank()) {
            System.out.println("    " + content.replace("\n", "\n    "));
        }
    }
}
