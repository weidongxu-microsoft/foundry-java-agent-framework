package io.github.weidongxu.photo;

import java.nio.file.Path;

/**
 * Develops a camera RAW file into a JPEG. The baseline develop passes {@link DevelopSettings#neutral()};
 * the adjusted develop passes settings the vision advice step produced. Both are the same operation.
 */
public interface RawDeveloper {

    /**
     * Develops {@code rawInput} into a JPEG at {@code outputJpeg} using {@code settings}.
     *
     * @param rawInput   an existing camera RAW file (e.g. {@code .RAF}, {@code .CR2}, {@code .DNG})
     * @param settings   the adjustments to apply ({@link DevelopSettings#neutral()} for baseline)
     * @param outputJpeg the JPEG path to write (overwritten if present)
     * @return {@code outputJpeg}
     * @throws RawDevelopException if the develop fails
     */
    Path develop(Path rawInput, DevelopSettings settings, Path outputJpeg);
}
