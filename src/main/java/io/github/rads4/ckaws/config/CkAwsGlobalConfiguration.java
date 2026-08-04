package io.github.rads4.ckaws.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import jenkins.model.GlobalConfiguration;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Layer 0 of the architecture: the Jenkins-owned {@code profile name -> role ARN} mapping.
 *
 * <p>Configurable through the global configuration UI or, preferably, through Configuration as Code:
 *
 * <pre>{@code
 * unclassified:
 *   ckAws:
 *     profiles:
 *       - name: "non_prod"
 *         roleArn: "arn:aws:iam::123456789012:role/non_prod"
 *         region: "us-east-1"
 * }</pre>
 *
 * <p>This is deliberately <em>not</em> {@code ~/.aws/config}. That file lives on the agent filesystem,
 * outside Jenkins' permission model, and is editable by anyone with agent or SSH access — so an
 * identity decision made there is a decision Jenkins does not control. Putting the mapping here means
 * changing it requires Jenkins admin permission and appears in a JCasC diff.
 *
 * <p>Resolution is exact-match and case-sensitive, and returns {@link Optional#empty()} for anything
 * unknown. Callers are expected to fail the build rather than fall back to a default: guessing an
 * identity is worse than refusing to pick one.
 */
@Extension
@Symbol("ckAws")
public class CkAwsGlobalConfiguration extends GlobalConfiguration {

    private List<AwsProfile> profiles = new ArrayList<>();

    public CkAwsGlobalConfiguration() {
        load();
    }

    /** @return the singleton, or {@code null} if Jenkins is not available (e.g. during shutdown). */
    @CheckForNull
    public static CkAwsGlobalConfiguration get() {
        return GlobalConfiguration.all().get(CkAwsGlobalConfiguration.class);
    }

    @NonNull
    public List<AwsProfile> getProfiles() {
        return new ArrayList<>(profiles);
    }

    @DataBoundSetter
    public void setProfiles(@CheckForNull List<AwsProfile> profiles) {
        this.profiles = profiles == null ? new ArrayList<>() : new ArrayList<>(profiles);
        save();
    }

    /**
     * Resolves a profile name to its configured entry.
     *
     * @param name the profile name a pipeline asked for
     * @return the matching entry, or empty if there is no such profile. Incomplete entries (missing a
     *     name or a role ARN) are treated as absent — a half-configured profile must not silently
     *     resolve to something.
     */
    @NonNull
    public Optional<AwsProfile> resolve(@CheckForNull String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return profiles.stream()
                .filter(AwsProfile::isComplete)
                .filter(profile -> profile.getName().equals(wanted))
                .findFirst();
    }

    /** @return the configured, usable profile names, for error messages and form help. */
    @NonNull
    public List<String> configuredProfileNames() {
        return profiles.stream()
                .filter(AwsProfile::isComplete)
                .map(AwsProfile::getName)
                .collect(Collectors.toList());
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "CK AWS";
    }
}
