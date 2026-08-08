package io.github.rads4.ckaws.managed;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.github.rads4.ckaws.config.AwsProfile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces the build's AWS configuration by <b>decorating</b> the executing node's own configuration,
 * never by replacing it.
 *
 * <p>The plugin is not an authentication provider. Whatever the node's configuration already says —
 * which role, which base identity, which region, which chained profile — is left exactly as it is. The
 * single thing added is {@code role_session_name}, and only on profiles that assume a role and do not
 * already pin one. That is the only difference a build can observe, and the only difference intended.
 *
 * <p><b>Line-based, deliberately.</b> Parsing an INI and regenerating it silently drops comments,
 * reorders keys, and mangles sections the parser does not model — {@code [sso-session …]},
 * {@code [services …]}, nested sub-sections, and whatever AWS adds next. Here everything is copied
 * verbatim and the transform only ever <em>inserts</em> lines, so a diff between input and output
 * contains additions and nothing else. The tests assert exactly that.
 *
 * <p><b>Two rules that exist because breaking them would break builds.</b>
 *
 * <ol>
 *   <li>A profile that already carries {@code role_session_name} is left untouched: an administrator
 *       pinned it deliberately, and overriding a deliberate decision is worse than losing attribution.
 *   <li>Jenkins-configured profiles are <em>appended only when the node does not define them</em>. The
 *       node is the source of truth; Jenkins is the fallback for what the node does not know about.
 * </ol>
 */
public final class AwsConfigOverlay {

    /** A section header: {@code [default]}, {@code [profile x]}, {@code [sso-session y]}, anything. */
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([^\\]]*)\\]\\s*$");

    /** {@code key = value} or {@code key=value}, at any indentation. */
    private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=.*$");

    private static final String ROLE_ARN = "role_arn";
    private static final String ROLE_SESSION_NAME = "role_session_name";
    private static final String CREDENTIAL_SOURCE = "credential_source";

    /** The section consulted when a caller names no profile. */
    private static final String DEFAULT_SECTION = "default";

    private AwsConfigOverlay() {}

    /**
     * @param nodeConfig the executing node's AWS configuration, verbatim; may be empty
     * @param sessionName the build's {@code jk-<job>-<build>} session name
     * @param overrides profiles configured in Jenkins, appended only where the node is silent
     * @param credentialSource base-identity keyword, used for appended overrides only
     * @return the decorated configuration
     */
    @NonNull
    public static String apply(
            @NonNull String nodeConfig,
            @NonNull String sessionName,
            @NonNull List<AwsProfile> overrides,
            @NonNull String credentialSource) {
        return describe(nodeConfig, sessionName, overrides, credentialSource).content();
    }

    /**
     * As {@link #apply}, but also reporting what was found and what was changed.
     *
     * <p>Exists for diagnostics: when a build authenticates as something unexpected, the first question
     * is always "what did the plugin actually see in this node's configuration, and what did it touch?"
     * Guessing at that from the outside is what makes such an investigation slow.
     */
    @NonNull
    public static Result describe(
            @NonNull String nodeConfig,
            @NonNull String sessionName,
            @NonNull List<AwsProfile> overrides,
            @NonNull String credentialSource) {
        return describe(nodeConfig, sessionName, overrides, credentialSource, null);
    }

    /**
     * As above, additionally attributing calls that name no profile.
     *
     * @param unprofiledRoleArn role the generated {@code [default]} should assume, or {@code null} to
     *     leave unprofiled calls exactly as the node left them
     */
    @NonNull
    public static Result describe(
            @NonNull String nodeConfig,
            @NonNull String sessionName,
            @NonNull List<AwsProfile> overrides,
            @NonNull String credentialSource,
            @CheckForNull String unprofiledRoleArn) {

        List<String> lines = split(nodeConfig);
        List<String> out = new ArrayList<>(lines.size() + overrides.size() * 7);
        Set<String> present = new LinkedHashSet<>();
        List<String> decorated = new ArrayList<>();
        List<String> appended = new ArrayList<>();
        String currentSection = null;

        int start = 0;
        boolean inSection = false;
        boolean assumesRole = false;
        boolean pinned = false;
        boolean sawDefault = false;
        boolean defaultAttributed = false;

        for (int i = 0; i < lines.size(); i++) {
            Matcher header = SECTION.matcher(lines.get(i));
            if (header.matches()) {
                Emission emission = emissionFor(
                        currentSection, inSection, assumesRole, pinned, unprofiledRoleArn, credentialSource);
                if (emission.decorates() && currentSection != null) {
                    decorated.add(currentSection);
                    defaultAttributed |= DEFAULT_SECTION.equals(currentSection);
                }
                emit(out, lines.subList(start, i), emission, sessionName);
                currentSection = header.group(1).trim();
                present.add(currentSection);
                sawDefault |= DEFAULT_SECTION.equals(currentSection);
                inSection = true;
                assumesRole = false;
                pinned = false;
                start = i;
                continue;
            }
            if (inSection) {
                Matcher assignment = ASSIGNMENT.matcher(lines.get(i));
                if (assignment.matches()) {
                    String key = assignment.group(1).toLowerCase(Locale.ROOT);
                    assumesRole |= ROLE_ARN.equals(key);
                    pinned |= ROLE_SESSION_NAME.equals(key);
                }
            }
        }
        Emission last =
                emissionFor(currentSection, inSection, assumesRole, pinned, unprofiledRoleArn, credentialSource);
        if (last.decorates() && currentSection != null) {
            decorated.add(currentSection);
            defaultAttributed |= DEFAULT_SECTION.equals(currentSection);
        }
        emit(out, lines.subList(start, lines.size()), last, sessionName);

        List<String> sectionsFound = new ArrayList<>(present);
        appendOverrides(out, present, overrides, sessionName, credentialSource, appended);

        // A node with no [default] at all — a real shape in the wild — still needs one if unprofiled
        // calls are to be attributed. Appending is the only way to reach them.
        if (unprofiledRoleArn != null && !sawDefault) {
            out.add("");
            out.add("# Added by the ck-aws Jenkins plugin: attributes calls that name no profile.");
            out.add("[" + DEFAULT_SECTION + "]");
            out.add(ROLE_ARN + " = " + unprofiledRoleArn);
            out.add(CREDENTIAL_SOURCE + " = " + credentialSource);
            out.add(ROLE_SESSION_NAME + " = " + sessionName);
            appended.add(DEFAULT_SECTION);
            defaultAttributed = true;
        }

        String content = String.join("\n", out);
        return new Result(
                content.isEmpty() || content.endsWith("\n") ? content : content + "\n",
                sectionsFound,
                decorated,
                appended,
                defaultAttributed);
    }

    /**
     * Decides what a section needs, which is one of three things.
     *
     * <p>A section that already assumes a role needs only a session name. The {@code [default]} section
     * of a node that does <em>not</em> assume a role needs the whole assume-role triple, because that is
     * what turns an unattributable base-identity call into a named session. Everything else is copied
     * untouched.
     *
     * <p>A pinned {@code role_session_name} always wins: an administrator set it deliberately, and
     * overriding a deliberate decision is worse than losing attribution.
     */
    private static Emission emissionFor(
            @CheckForNull String section,
            boolean inSection,
            boolean assumesRole,
            boolean pinned,
            @CheckForNull String unprofiledRoleArn,
            String credentialSource) {
        if (!inSection || pinned) {
            return Emission.verbatim();
        }
        if (assumesRole) {
            return Emission.sessionNameOnly();
        }
        // A profile with no role_arn does not assume anything: it hands the build the agent's base
        // identity directly, whose session name the platform fixed and nobody can change. That is the
        // same unattributable path as [default], just reached by name instead of by omission, so it
        // gets the same treatment. The principal ARN is unchanged — it is the agent's own role — so
        // permissions, and every resource policy that grants to that role, are unaffected.
        if (unprofiledRoleArn != null && isProfileSection(section)) {
            return Emission.assumeRole(unprofiledRoleArn, credentialSource);
        }
        return Emission.verbatim();
    }

    /** What to insert into a section: nothing, a session name, or a full assume-role triple. */
    private static final class Emission {

        private final boolean sessionName;

        @CheckForNull
        private final String roleArn;

        @CheckForNull
        private final String credentialSource;

        private Emission(boolean sessionName, @CheckForNull String roleArn, @CheckForNull String credentialSource) {
            this.sessionName = sessionName;
            this.roleArn = roleArn;
            this.credentialSource = credentialSource;
        }

        static Emission verbatim() {
            return new Emission(false, null, null);
        }

        static Emission sessionNameOnly() {
            return new Emission(true, null, null);
        }

        static Emission assumeRole(String roleArn, String credentialSource) {
            return new Emission(true, roleArn, credentialSource);
        }

        boolean decorates() {
            return sessionName;
        }

        List<String> additions(String session) {
            if (!sessionName) {
                return List.of();
            }
            if (roleArn == null) {
                return List.of(ROLE_SESSION_NAME + " = " + session);
            }
            return List.of(
                    ROLE_ARN + " = " + roleArn,
                    CREDENTIAL_SOURCE + " = " + credentialSource,
                    ROLE_SESSION_NAME + " = " + session);
        }
    }

    /** What the decoration found and changed. Nothing here is sensitive: section names only. */
    public static final class Result {

        private final String content;
        private final List<String> sectionsFound;
        private final List<String> sectionsDecorated;
        private final List<String> sectionsAppended;
        private final boolean unprofiledAttributed;

        Result(
                String content,
                List<String> found,
                List<String> decorated,
                List<String> appended,
                boolean unprofiledAttributed) {
            this.content = content;
            this.sectionsFound = List.copyOf(found);
            this.sectionsDecorated = List.copyOf(decorated);
            this.sectionsAppended = List.copyOf(appended);
            this.unprofiledAttributed = unprofiledAttributed;
        }

        /**
         * @return whether calls naming no profile will carry the build's session name. False is a normal,
         *     safe outcome — it means those calls behave exactly as they do without the plugin.
         */
        public boolean unprofiledAttributed() {
            return unprofiledAttributed;
        }

        @NonNull
        public String content() {
            return content;
        }

        /** Every section header in the node's own configuration, in file order. */
        @NonNull
        public List<String> sectionsFound() {
            return sectionsFound;
        }

        /** Sections that gained a session name: they assume a role and had not pinned one. */
        @NonNull
        public List<String> sectionsDecorated() {
            return sectionsDecorated;
        }

        /** Sections added from Jenkins configuration because the node did not define them. */
        @NonNull
        public List<String> sectionsAppended() {
            return sectionsAppended;
        }
    }

    /** Copies one section verbatim, inserting any additions after its last non-blank line. */
    private static void emit(List<String> out, List<String> section, Emission emission, String sessionName) {
        List<String> additions = emission.additions(sessionName);
        if (additions.isEmpty()) {
            out.addAll(section);
            return;
        }
        int lastMeaningful = -1;
        for (int i = 0; i < section.size(); i++) {
            if (!section.get(i).trim().isEmpty()) {
                lastMeaningful = i;
            }
        }
        out.addAll(section.subList(0, lastMeaningful + 1));
        out.addAll(additions);
        out.addAll(section.subList(lastMeaningful + 1, section.size()));
    }

    /**
     * Checks that a generated configuration is safe to hand to a build.
     *
     * <p>The plugin's fail-open guard catches <em>exceptions</em>. It does not catch the case that
     * matters most here: producing a file successfully that is subtly wrong. If that file were exported,
     * every AWS call in the build would fail, nothing would have thrown, and the guard would never fire.
     * So the output is checked against the input before it is used, and anything unexpected means
     * contributing nothing — the same outcome as any other failure.
     *
     * <p>What is verified is exactly the property the transform claims: <b>additions only</b>. Every line
     * of the original must still be present, in order, and every section header must survive.
     *
     * @return empty when the output is safe, otherwise a human-readable reason it is not
     */
    @NonNull
    public static java.util.Optional<String> validate(@NonNull String original, @NonNull String generated) {
        // Trailing blank lines carry no meaning in an INI file, and the two sides do not agree about
        // them: joining lines with "\n" turns a final empty element into a single terminating newline,
        // so a node file ending in a blank line loses it whenever its last section is decorated.
        // Comparing without them keeps this check about content, which is what it is for. Measured on a
        // real controller configuration, where the false positive it caused suppressed decoration
        // entirely — the check failed safe, but for a difference that does not matter.
        List<String> before = withoutTrailingBlanks(split(original));
        List<String> after = withoutTrailingBlanks(split(generated));
        if (after.size() < before.size()) {
            return java.util.Optional.of("generated configuration is shorter than the node's (" + after.size()
                    + " lines vs " + before.size() + "): the transform must only add lines");
        }
        // Every original line must appear in the output, in the original order.
        int cursor = 0;
        for (String line : before) {
            boolean found = false;
            while (cursor < after.size()) {
                if (after.get(cursor++).equals(line)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return java.util.Optional.of("a line from the node's configuration is missing or reordered "
                        + "in the generated file: " + summarize(line));
            }
        }
        Set<String> beforeSections = sectionsOf(before);
        Set<String> afterSections = sectionsOf(after);
        if (!afterSections.containsAll(beforeSections)) {
            Set<String> missing = new LinkedHashSet<>(beforeSections);
            missing.removeAll(afterSections);
            return java.util.Optional.of("section(s) lost in the generated file: " + missing);
        }
        return java.util.Optional.empty();
    }

    /**
     * Whether a section is one an AWS SDK resolves credentials from.
     *
     * <p>Only {@code [default]} and {@code [profile x]} are. A shared configuration file also holds
     * sections that are not profiles at all — {@code [sso-session x]}, {@code [services x]},
     * {@code [plugins]} — and writing a {@code role_arn} into one of those would corrupt it. The
     * transform must know the difference rather than treat every section alike.
     */
    private static boolean isProfileSection(@CheckForNull String section) {
        return DEFAULT_SECTION.equals(section) || (section != null && section.startsWith("profile "));
    }

    /** Drops blank lines at the end of a file. They are not content, and the two sides disagree. */
    private static List<String> withoutTrailingBlanks(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).trim().isEmpty()) {
            end--;
        }
        return lines.subList(0, end);
    }

    private static Set<String> sectionsOf(List<String> lines) {
        Set<String> sections = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher header = SECTION.matcher(line);
            if (header.matches()) {
                sections.add(header.group(1).trim());
            }
        }
        return sections;
    }

    /** Truncates a line for an error message. Configuration lines can contain long ARNs. */
    private static String summarize(String line) {
        String trimmed = line.trim();
        return trimmed.length() <= 60 ? "\"" + trimmed + "\"" : "\"" + trimmed.substring(0, 57) + "…\"";
    }

    /** Appends profiles Jenkins knows about that the node does not. Never overrides the node. */
    private static void appendOverrides(
            List<String> out,
            Set<String> present,
            List<AwsProfile> overrides,
            String sessionName,
            String credentialSource,
            List<String> appended) {

        for (AwsProfile override : overrides) {
            if (!override.isUsable() || !override.hasRole()) {
                continue;
            }
            String section = "profile " + override.getName();
            if (present.contains(section) || present.contains(override.getName())) {
                continue; // the node already defines it; the node wins
            }
            present.add(section);
            appended.add(section);
            out.add("");
            out.add("# Added by the ck-aws Jenkins plugin: not defined in this node's configuration.");
            out.add("[" + section + "]");
            out.add(ROLE_ARN + " = " + override.getRoleArn());
            out.add("credential_source = " + credentialSource);
            out.add(ROLE_SESSION_NAME + " = " + sessionName);
            if (override.getRegion() != null) {
                out.add("region = " + override.getRegion());
            }
        }
    }

    private static List<String> split(String content) {
        List<String> lines = new ArrayList<>();
        if (content.isEmpty()) {
            return lines;
        }
        // -1 keeps trailing empties, so a file ending in a newline round-trips unchanged.
        for (String line : content.replace("\r\n", "\n").split("\n", -1)) {
            lines.add(line);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
