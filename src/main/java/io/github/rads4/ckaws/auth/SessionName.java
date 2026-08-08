package io.github.rads4.ckaws.auth;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The STS {@code RoleSessionName} for one Jenkins build, in the frozen {@code jk-<job>-<build>} shape.
 *
 * <p>This shape is <b>load-bearing</b>: it is the basis for a future IAM trust-policy condition
 * ({@code "StringLike": {"sts:RoleSessionName": "jk-*"}}). Do not change the {@code jk-} prefix or the
 * overall shape without discussion — see CLAUDE.md.
 *
 * <p>AWS constrains {@code RoleSessionName} to the pattern {@code [\w+=,.@-]{2,64}}. Job names can
 * contain characters outside that set (slashes for folders, spaces, unicode), so this class sanitizes
 * the job segment while always preserving the {@code jk-} prefix and the trailing build number, and
 * truncates the <i>middle</i> (job) segment if needed so the result stays {@code <= 64} chars and keeps
 * matching {@code jk-*}.
 */
public final class SessionName {

    /** Length bounds AWS enforces on a RoleSessionName. */
    private static final int MAX_LENGTH = 64;

    private static final int MIN_LENGTH = 2;

    /** The fixed, load-bearing prefix. */
    private static final String PREFIX = "jk-";

    /** Characters AWS permits in a RoleSessionName; anything else in the job segment is replaced. */
    private static final Pattern DISALLOWED = Pattern.compile("[^\\w+=,.@-]");

    /** Collapses runs of '-' left behind by sanitization into a single '-'. */
    private static final Pattern DASH_RUN = Pattern.compile("-{2,}");

    /** Full-value validity check, applied defensively to the assembled result. */
    private static final Pattern VALID = Pattern.compile("[\\w+=,.@-]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");

    private final String value;

    private SessionName(String value) {
        this.value = value;
    }

    /**
     * Builds the session name for a build.
     *
     * @param jobName the Jenkins job name (may contain folder slashes, spaces, etc.)
     * @param buildNumber the build number; must be positive
     * @return a valid, STS-safe session name of the form {@code jk-<sanitized-job>-<build>}
     * @throws SessionNameException if {@code jobName} is blank, {@code buildNumber} is not positive, or
     *     a valid name cannot be produced (e.g. an implausibly large build number leaves no room)
     */
    public static SessionName forBuild(String jobName, long buildNumber) {
        if (jobName == null || jobName.trim().isEmpty()) {
            throw new SessionNameException("Job name must not be null or blank when generating a session name.");
        }
        if (buildNumber <= 0) {
            throw new SessionNameException("Build number must be positive but was " + buildNumber + ".");
        }

        String suffix = "-" + buildNumber;
        int roomForJob = MAX_LENGTH - PREFIX.length() - suffix.length();
        if (roomForJob <= 0) {
            // Only reachable with an absurd build number; fail closed rather than emit a broken name.
            throw new SessionNameException("Build number " + buildNumber
                    + " is too large to form a session name within " + MAX_LENGTH + " characters.");
        }

        String job = sanitizeJobSegment(jobName);
        if (job.length() > roomForJob) {
            // Keep the TAIL, not the head. In a folder hierarchy the leading segments are shared by every
            // job in that folder while the trailing segment is the job itself, so truncating from the
            // front discards exactly the part that distinguishes one build from another — and two jobs in
            // the same deep folder would then collide on an identical session name.
            job = trimDashes(job.substring(job.length() - roomForJob));
        }

        // If the job segment sanitized/trimmed away to nothing, drop it entirely: "jk-<build>" still
        // matches jk-* and stays valid.
        String value = job.isEmpty() ? PREFIX + buildNumber : PREFIX + job + suffix;

        if (!VALID.matcher(value).matches()) {
            throw new SessionNameException("Generated session name is not STS-valid: '" + value + "'.");
        }
        return new SessionName(value);
    }

    private static String sanitizeJobSegment(String jobName) {
        String replaced = DISALLOWED.matcher(jobName).replaceAll("-");
        return trimDashes(DASH_RUN.matcher(replaced).replaceAll("-"));
    }

    private static String trimDashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(start, end);
    }

    /** The session-name string to pass to STS. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionName)) {
            return false;
        }
        return value.equals(((SessionName) o).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
