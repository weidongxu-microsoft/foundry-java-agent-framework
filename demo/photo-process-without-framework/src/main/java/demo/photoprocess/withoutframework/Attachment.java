package demo.photoprocess.withoutframework;

/**
 * A decoded inbound attachment: raw bytes + media type + filename.
 *
 * <p>The without-framework counterpart to the framework's {@code DataContent} — except there is no
 * library type to hand you this; {@link ResponsesJson} decodes it out of the request by hand.</p>
 */
record Attachment(byte[] bytes, String mediaType, String name) {
}
