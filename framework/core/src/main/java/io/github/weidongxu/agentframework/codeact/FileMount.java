package io.github.weidongxu.agentframework.codeact;

import java.util.Objects;

/**
 * Describes a filesystem location made available to code executed by a {@link CodeActProvider},
 * mirroring the MAF {@code FileMount}. The {@code mountPath} is the logical path shown to the model,
 * while {@code hostPath} is the actual location on the host.
 */
public final class FileMount {
    private final String mountPath;
    private final String hostPath;
    private final FileMountMode mode;

    public FileMount(String mountPath, String hostPath, FileMountMode mode) {
        this.mountPath = Objects.requireNonNull(mountPath, "mountPath");
        this.hostPath = Objects.requireNonNull(hostPath, "hostPath");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public String getMountPath() {
        return mountPath;
    }

    public String getHostPath() {
        return hostPath;
    }

    public FileMountMode getMode() {
        return mode;
    }
}
