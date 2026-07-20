package io.github.weidongxu.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end develop against the real {@code rawtherapee-cli} on a real RAW file. Gated on two
 * environment variables so CI without the native tool skips cleanly:
 * <ul>
 *   <li>{@code RAWTHERAPEE_CLI} — path to the {@code rawtherapee-cli} executable;</li>
 *   <li>{@code RAW_SAMPLE} — path to a sample RAW file (e.g. a {@code .RAF}).</li>
 * </ul>
 * Proves the baseline (neutral) and adjusted develops both produce valid, distinct JPEGs.
 */
class RawTherapeeDeveloperIT {

    @Test
    void developsBaselineAndAdjusted(@TempDir Path tempDir) throws IOException {
        String cli = System.getenv(RawTherapeeOptions.CLI_ENV);
        String sample = System.getenv("RAW_SAMPLE");
        assumeTrue(cli != null && !cli.isBlank(), "set RAWTHERAPEE_CLI to run this IT");
        assumeTrue(sample != null && !sample.isBlank(), "set RAW_SAMPLE to run this IT");
        Path raw = Path.of(sample);
        assumeTrue(Files.isRegularFile(raw), "RAW_SAMPLE file must exist: " + sample);

        RawDeveloper developer = new RawTherapeeDeveloper(
                RawTherapeeOptions.builder().cliPath(cli).build());

        Path baseline = developer.develop(raw, DevelopSettings.neutral(), tempDir.resolve("baseline.jpg"));
        Path adjusted = developer.develop(
                raw,
                DevelopSettings.builder()
                        .whiteBalanceTempK(4800)
                        .tint(1.15)
                        .exposureEv(0.8)
                        .contrast(15)
                        .saturation(8)
                        .highlights(45)
                        .shadows(50)
                        .addToneCurvePoint(0, 0)
                        .addToneCurvePoint(0.25, 0.18)
                        .addToneCurvePoint(0.75, 0.86)
                        .addToneCurvePoint(1, 1)
                        .build(),
                tempDir.resolve("adjusted.jpg"));

        assertValidJpeg(baseline);
        assertValidJpeg(adjusted);
        assertNotEquals(
                Files.size(baseline), Files.size(adjusted),
                "adjusted develop should differ from baseline");
    }

    private static void assertValidJpeg(Path jpeg) throws IOException {
        assertTrue(Files.size(jpeg) > 0, "JPEG must be non-empty: " + jpeg);
        byte[] header = Arrays.copyOf(Files.readAllBytes(jpeg), 3);
        assertTrue(
                (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF,
                "output must start with the JPEG SOI marker: " + jpeg);
    }
}
