package io.github.weidongxu.agentframework.chat;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Binary (non-text) message content — bytes plus a media type and an optional name. The Java
 * counterpart of the {@code DataContent} in microsoft/agent-framework (.NET/Python), it lets a
 * {@link ChatMessage} carry an attachment (an uploaded file, an image) in either direction:
 * inbound (a user attaches a file) or outbound (an agent returns a generated image).
 *
 * <p>Not every provider can consume every media type — a model chat client forwards only what the
 * model understands (e.g. images) and ignores the rest, so a host pipeline can process bytes the
 * model cannot (e.g. a camera RAW) without ever sending them upstream.</p>
 */
public final class DataContent implements ChatContent {
    private final byte[] data;
    private final String mediaType;
    private final String name;

    public DataContent(byte[] data, String mediaType, String name) {
        this.data = Objects.requireNonNull(data, "data").clone();
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        this.name = name;
    }

    @Override
    public String getType() {
        return "data";
    }

    /** The raw bytes (defensive copy). */
    public byte[] getData() {
        return data.clone();
    }

    /** The IANA media type, e.g. {@code image/jpeg} or {@code image/x-fuji-raf}. */
    public String getMediaType() {
        return mediaType;
    }

    /** The original attachment/file name, or {@code null} when unknown. */
    public String getName() {
        return name;
    }

    /** @return a {@code data:<mediaType>;base64,<...>} URI for this content. */
    public String toDataUri() {
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(data);
    }

    /**
     * Parses a {@code data:<mediaType>;base64,<...>} URI into a {@link DataContent}.
     *
     * @throws IllegalArgumentException when {@code uri} is not a base64 data URI
     */
    public static DataContent fromDataUri(String uri, String name) {
        Objects.requireNonNull(uri, "uri");
        int comma = uri.indexOf(',');
        if (!uri.startsWith("data:") || comma < 0) {
            throw new IllegalArgumentException("Not a data URI");
        }
        String meta = uri.substring(5, comma);
        if (!meta.contains(";base64")) {
            throw new IllegalArgumentException("Only base64 data URIs are supported");
        }
        String mediaType = meta.substring(0, meta.indexOf(';'));
        byte[] bytes = Base64.getDecoder().decode(uri.substring(comma + 1));
        return new DataContent(bytes, mediaType.isEmpty() ? "application/octet-stream" : mediaType, name);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DataContent)) {
            return false;
        }
        DataContent that = (DataContent) other;
        return Arrays.equals(data, that.data)
                && mediaType.equals(that.mediaType)
                && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(data), mediaType, name);
    }

    @Override
    public String toString() {
        return "DataContent{mediaType=" + mediaType + ", name=" + name + ", bytes=" + data.length + "}";
    }
}
