package io.github.weidongxu.photo;

/** Thrown when a RAW develop fails (developer missing, non-zero exit, timeout, or no output). */
public class RawDevelopException extends RuntimeException {

    public RawDevelopException(String message) {
        super(message);
    }

    public RawDevelopException(String message, Throwable cause) {
        super(message, cause);
    }
}
