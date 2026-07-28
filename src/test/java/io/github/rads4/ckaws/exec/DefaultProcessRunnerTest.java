package io.github.rads4.ckaws.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Real end-to-end tests for {@link DefaultProcessRunner}, exercising actual process stream/exit
 * handling. Uses a POSIX shell, so it is skipped on Windows. No Jenkins, no AWS.
 */
@DisabledOnOs(OS.WINDOWS)
class DefaultProcessRunnerTest {

    private final ProcessRunner runner = new DefaultProcessRunner();

    @Test
    void capturesStdoutStderrAndNonZeroExitSeparately() {
        ProcessResult result = runner.run(List.of("sh", "-c", "printf out; printf err 1>&2; exit 3"));

        assertEquals("out", result.stdout());
        assertEquals("err", result.stderr());
        assertEquals(3, result.exitCode());
        assertFalse(result.succeeded());
    }

    @Test
    void reportsSuccessOnZeroExit() {
        ProcessResult result = runner.run(List.of("sh", "-c", "printf hi"));

        assertEquals("hi", result.stdout());
        assertEquals("", result.stderr());
        assertTrue(result.succeeded());
    }

    @Test
    void throwsWhenExecutableDoesNotExist() {
        assertThrows(ProcessExecutionException.class, () -> runner.run(List.of("ck-aws-no-such-binary-42")));
    }

    @Test
    void rejectsEmptyCommand() {
        assertThrows(IllegalArgumentException.class, () -> runner.run(List.of()));
    }
}
