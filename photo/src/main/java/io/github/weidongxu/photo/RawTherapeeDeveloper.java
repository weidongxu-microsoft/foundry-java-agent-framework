package io.github.weidongxu.photo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@link RawDeveloper} backed by the native {@code rawtherapee-cli} binary, invoked per develop via
 * {@link ProcessBuilder}. No pure-Java RAW decoder handles modern cameras, so the imaging is done by
 * RawTherapee; this class only translates {@link DevelopSettings} into a {@code .pp3} profile
 * (see {@link Pp3Writer}) and drives the process.
 *
 * <p>Command shape: {@code rawtherapee-cli -o <out> [-p <profile.pp3>] -j<quality> -Y -c <in>}
 * ({@code -c} must be last; {@code -Y} overwrites; the {@code -p} profile is omitted for the neutral
 * baseline develop). The develop succeeds only when the process exits 0 <em>and</em> a non-empty
 * output file exists.</p>
 */
public final class RawTherapeeDeveloper implements RawDeveloper {

    private final RawTherapeeOptions options;

    public RawTherapeeDeveloper() {
        this(RawTherapeeOptions.defaults());
    }

    public RawTherapeeDeveloper(RawTherapeeOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public Path develop(Path rawInput, DevelopSettings settings, Path outputJpeg) {
        Objects.requireNonNull(rawInput, "rawInput");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(outputJpeg, "outputJpeg");
        if (!Files.isRegularFile(rawInput)) {
            throw new RawDevelopException("RAW input not found: " + rawInput);
        }

        Path profile = null;
        try {
            if (outputJpeg.getParent() != null) {
                Files.createDirectories(outputJpeg.getParent());
            }
            String pp3 = Pp3Writer.toPp3(settings);
            if (pp3 != null) {
                profile = Files.createTempFile("develop-", ".pp3");
                Files.write(profile, pp3.getBytes(StandardCharsets.UTF_8));
            }
            runCli(rawInput, profile, outputJpeg);
        } catch (IOException e) {
            throw new RawDevelopException("Failed to develop " + rawInput, e);
        } finally {
            deleteQuietly(profile);
        }

        if (!Files.isRegularFile(outputJpeg) || sizeOf(outputJpeg) == 0) {
            throw new RawDevelopException("Developer produced no output: " + outputJpeg);
        }
        return outputJpeg;
    }

    private void runCli(Path rawInput, Path profile, Path outputJpeg) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(options.resolveCli());
        command.add("-o");
        command.add(outputJpeg.toString());
        if (profile != null) {
            command.add("-p");
            command.add(profile.toString());
        }
        command.add("-j" + options.getJpegQuality());
        command.add("-Y");
        command.add("-c");
        command.add(rawInput.toString());

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new RawDevelopException(
                    "Could not start '" + options.resolveCli() + "'. Is rawtherapee-cli installed "
                            + "and on PATH (or set " + RawTherapeeOptions.CLI_ENV + ")?", e);
        }

        String output = readAll(process);
        boolean finished;
        try {
            finished = process.waitFor(options.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RawDevelopException("Interrupted while developing " + rawInput, e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new RawDevelopException(
                    "Develop timed out after " + options.getTimeout().toSeconds() + "s: " + rawInput);
        }
        if (process.exitValue() != 0) {
            throw new RawDevelopException(
                    "rawtherapee-cli exited " + process.exitValue() + ":\n" + output);
        }
    }

    private static String readAll(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
