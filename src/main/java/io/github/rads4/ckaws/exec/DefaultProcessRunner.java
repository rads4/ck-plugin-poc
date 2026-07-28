package io.github.rads4.ckaws.exec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * {@link ProcessRunner} backed by {@link ProcessBuilder}.
 *
 * <p>The child process inherits this JVM's environment, so ambient AWS configuration (e.g.
 * {@code AWS_PROFILE}, {@code AWS_DEFAULT_REGION}, or an instance-metadata role) is available to the
 * command without this class reading any AWS config files itself.
 *
 * <p>stdout and stderr are captured <em>separately</em> and drained concurrently (stderr on a helper
 * thread) so a process that writes a lot to one stream cannot deadlock by filling a pipe buffer while
 * we block reading the other. There is intentionally no timeout in this milestone.
 */
public final class DefaultProcessRunner implements ProcessRunner {

    @Override
    public ProcessResult run(List<String> command) {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        Process process;
        try {
            process = new ProcessBuilder(command).start();
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
