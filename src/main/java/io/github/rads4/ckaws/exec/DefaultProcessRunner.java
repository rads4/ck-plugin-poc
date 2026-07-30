package io.github.rads4.ckaws.exec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ProcessRunner} backed by {@link ProcessBuilder}.
 *
 * <p>The child process inherits this JVM's environment, so ambient AWS configuration (e.g.
 * {@code AWS_PROFILE}, {@code AWS_DEFAULT_REGION}, or an instance-metadata role) is available to the
 * command without this class reading any AWS config files itself.
 *
 * <p>{@link #run(List, Map)} additionally applies per-invocation environment overrides on top of that
 * inherited environment. Environment — not the argument list — is the correct channel for anything
 * sensitive: a process's arguments are world-readable via {@code ps} and {@code /proc/<pid>/cmdline},
 * while its environment is not. Like the rest of this class, the override map is completely generic; it
 * carries opaque name/value pairs and this class never inspects them.
 *
 * <p>stdout and stderr are captured <em>separately</em> and drained concurrently (stderr on a helper
 * thread) so a process that writes a lot to one stream cannot deadlock by filling a pipe buffer while
 * we block reading the other. There is intentionally no timeout in this milestone.
 */
public final class DefaultProcessRunner implements ProcessRunner {

    @Override
    public ProcessResult run(List<String> command) {
        return run(command, Map.of());
    }

    /**
     * Executes {@code command} with {@code environmentOverrides} applied on top of the environment
     * inherited from this JVM.
     *
     * @param command the executable and its arguments; must be non-empty
     * @param environmentOverrides variables to set in the child environment. A {@code null} value
     *     <em>removes</em> that variable from the child environment, which is how a caller can stop the
     *     child from seeing something the parent inherited.
     * @return the captured result (stdout, stderr, exit code)
     * @throws ProcessExecutionException if the process cannot be started or run to completion
     */
    public ProcessResult run(List<String> command, Map<String, String> environmentOverrides) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(environmentOverrides, "environmentOverrides");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> childEnvironment = builder.environment();
        for (Map.Entry<String, String> override : environmentOverrides.entrySet()) {
            if (override.getValue() == null) {
                childEnvironment.remove(override.getKey());
            } else {
                childEnvironment.put(override.getKey(), override.getValue());
            }
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessExecutionException("Failed to start process: " + command, e);
        }

        // Drain stderr on a helper thread while we read stdout on this thread.
        String[] stderrHolder = new String[1];
        IOException[] stderrFailure = new IOException[1];
        Thread stderrReader = new Thread(
                () -> {
                    try {
                        stderrHolder[0] = readFully(process.getErrorStream());
                    } catch (IOException e) {
                        stderrFailure[0] = e;
                    }
                },
                "ck-aws-stderr-reader");
        stderrReader.start();

        String stdout;
        try {
            stdout = readFully(process.getInputStream());
        } catch (IOException e) {
            process.destroyForcibly();
            throw new ProcessExecutionException("Failed reading stdout of process: " + command, e);
        }

        try {
            stderrReader.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ProcessExecutionException("Interrupted while reading stderr of process: " + command, e);
        }
        if (stderrFailure[0] != null) {
            process.destroyForcibly();
            throw new ProcessExecutionException("Failed reading stderr of process: " + command, stderrFailure[0]);
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ProcessExecutionException("Interrupted while waiting for process: " + command, e);
        }

        return new ProcessResult(command, exitCode, stdout, stderrHolder[0]);
    }

    private static String readFully(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
