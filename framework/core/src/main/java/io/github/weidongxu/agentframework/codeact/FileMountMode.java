package io.github.weidongxu.agentframework.codeact;

/** The access mode of a {@link FileMount} exposed to code executed by a {@link CodeActProvider}. */
public enum FileMountMode {
    /** The mount is read-only. */
    READ_ONLY,
    /** The mount is readable and writable; files written under it may be captured after execution. */
    READ_WRITE
}
