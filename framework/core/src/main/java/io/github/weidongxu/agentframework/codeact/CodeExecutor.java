package io.github.weidongxu.agentframework.codeact;

import java.util.concurrent.CompletionStage;

/**
 * The pluggable seam that actually runs code for a {@link CodeActProvider}. Implementations are the
 * sandboxing / isolation boundary — the provider itself performs no isolation.
 *
 * <p>MAF ships a local (child-process) executor and a Hyperlight micro-VM executor. This framework
 * provides {@link LocalCodeExecutor} (a child-process runner that is <strong>not</strong> a sandbox)
 * and lets callers supply their own executor backed by a real sandbox.</p>
 */
@FunctionalInterface
public interface CodeExecutor {
    /** Executes the requested code and returns its captured result. */
    CompletionStage<CodeExecutionResult> execute(CodeExecutionRequest request);
}
