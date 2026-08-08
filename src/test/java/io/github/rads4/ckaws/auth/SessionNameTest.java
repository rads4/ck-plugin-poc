package io.github.rads4.ckaws.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Plain-Java unit tests for {@link SessionName}; no Jenkins, no AWS. */
class SessionNameTest {

    /** The STS RoleSessionName constraint the future IAM trust policy will match against jk-*. */
    private static final Pattern STS_VALID = Pattern.compile("jk-.*");

    private static final Pattern STS_CHARSET = Pattern.compile("[\\w+=,.@-]{2,64}");

    @Test
    void generatesFrozenShape() {
        assertEquals("jk-myjob-123", SessionName.forBuild("myjob", 123).value());
    }

    @Test
    void sanitizesFolderSlashesAndSpaces() {
        SessionName name = SessionName.forBuild("team/app deploy", 7);
        assertEquals("jk-team-app-deploy-7", name.value());
        assertTrue(STS_CHARSET.matcher(name.value()).matches());
    }

    @Test
    void collapsesDashRunsFromSanitization() {
        // "a//b" -> "a--b" -> "a-b"
        assertEquals("jk-a-b-5", SessionName.forBuild("a//b", 5).value());
    }

    @Test
    void sanitizesUnicodeAndTrimsEdgeDashes() {
        // leading/trailing disallowed chars must not produce "jk--...--build"
        SessionName name = SessionName.forBuild("__café job__", 9);
        assertTrue(name.value().startsWith("jk-"));
        assertTrue(STS_CHARSET.matcher(name.value()).matches());
        assertTrue(name.value().endsWith("-9"));
    }

    @Test
    void truncatesLongJobButKeepsPrefixAndBuild() {
        String longJob = repeat("x", 200);
        SessionName name = SessionName.forBuild(longJob, 4242);
        assertTrue(name.value().length() <= 64, "must respect STS 64-char limit");
        assertTrue(name.value().startsWith("jk-"), "must keep the load-bearing prefix");
        assertTrue(name.value().endsWith("-4242"), "must keep the build number");
        assertTrue(STS_CHARSET.matcher(name.value()).matches());
        assertTrue(STS_VALID.matcher(name.value()).matches());
    }

    /**
     * Truncation keeps the end of the job name, not the beginning.
     *
     * <p>In a folder hierarchy the leading segments are shared by every job in that folder while the
     * trailing segment names the job itself. Truncating from the front therefore discards precisely the
     * part that distinguishes one build from another, and two jobs in the same deep folder would then
     * produce the same session name — silently merging two builds in an audit trail.
     */
    @Test
    void truncationKeepsTheDistinguishingTailOfTheJobName() {
        String folder = repeat("deep-folder/", 6);

        SessionName first = SessionName.forBuild(folder + "authbridge", 12);
        SessionName second = SessionName.forBuild(folder + "rivon", 12);

        assertTrue(first.value().length() <= 64);
        assertTrue(second.value().length() <= 64);
        assertTrue(first.value().endsWith("authbridge-12"), "the job's own name must survive: " + first.value());
        assertTrue(second.value().endsWith("rivon-12"), "the job's own name must survive: " + second.value());
        assertNotEquals(first.value(), second.value(), "two jobs in one folder must not share a session name");
    }

    @Test
    void jobThatSanitizesToNothingStillYieldsValidJkName() {
        // All chars disallowed -> empty job segment -> "jk-<build>"
        SessionName name = SessionName.forBuild("///", 12);
        assertEquals("jk-12", name.value());
        assertTrue(STS_CHARSET.matcher(name.value()).matches());
    }

    @Test
    void rejectsBlankJob() {
        assertThrows(SessionNameException.class, () -> SessionName.forBuild("   ", 1));
    }

    @Test
    void rejectsNullJob() {
        assertThrows(SessionNameException.class, () -> SessionName.forBuild(null, 1));
    }

    @Test
    void rejectsNonPositiveBuild() {
        assertThrows(SessionNameException.class, () -> SessionName.forBuild("job", 0));
        assertThrows(SessionNameException.class, () -> SessionName.forBuild("job", -5));
    }

    @Test
    void equalityIsValueBased() {
        assertEquals(SessionName.forBuild("job", 1), SessionName.forBuild("job", 1));
        assertEquals(
                SessionName.forBuild("job", 1).hashCode(),
                SessionName.forBuild("job", 1).hashCode());
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
