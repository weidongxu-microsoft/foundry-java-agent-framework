package io.github.weidongxu.agentframework.shell;

import java.time.Duration;

/**
 * Runs shell probe commands on behalf of {@link ShellEnvironmentProvider}. Implementations may target
 * the host shell ({@link LocalShellExecutor}) or a container/remote shell. Mirrors MAF's
 * {@code ShellExecutor} abstraction so the same provider works across execution backends.
 */
public interface ShellExecutor {
    /**
     * Runs a command and returns its result.
     *
     * @param command the command line to execute in the shell
     * @param timeout the maximum time to allow the command to run
     * @return the command result
     * @throws Exception if the command cannot be spawned, times out, or is rejected by policy
     */
    ShellResult run(String command, Duration timeout) throws Exception;
}
