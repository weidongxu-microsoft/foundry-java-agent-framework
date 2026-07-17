package io.github.weidongxu.agentframework.tool;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * A tool executed by the model host (server-side) rather than by the local
 * function-invoking loop.
 *
 * <p>Mirrors the {@code Hosted*} tools in microsoft/agent-framework (.NET
 * {@code Microsoft.Extensions.AI} {@code Hosted*Tool}, Python {@code Supports*Tool}
 * client capabilities). Subclasses carry only declarative configuration; the
 * concrete {@link io.github.weidongxu.agentframework.chat.ChatClient} maps them to
 * its provider-specific tool representation.</p>
 *
 * <p>Because the host runs these tools, they expose no parameter schema and cannot
 * be invoked locally; the function-invoking loop simply forwards them to the
 * provider and ignores them when dispatching tool calls.</p>
 */
public abstract class HostedTool implements Tool {
    private final String name;

    protected HostedTool(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return "";
    }

    @Override
    public final Map<String, Object> getParametersSchema() {
        return Collections.emptyMap();
    }

    /** Hosted tools run on the model host; local invocation is unsupported. */
    @Override
    public final CompletionStage<String> invoke(Map<String, Object> arguments) {
        throw new UnsupportedOperationException(
                "Hosted tool '" + name + "' is executed by the model host and cannot be invoked locally");
    }
}
