package io.github.rads4.ckaws.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Layer 0 of the architecture: the Jenkins-owned {@code profile name -> role ARN} mapping, plus the
 * three switches that govern Managed Authentication.
 *
 * <p>Configurable through the global configuration UI or, preferably, through Configuration as Code:
 *
 * <pre>{@code
 * unclassified:
 *   ckAws:
 *     managedAuthentication: true
 *     jobNamePattern: "uat/.*"
 *     credentialSource: "Ec2InstanceMetadata"
 *     profiles:
 *       - name: "non_prod"
 *         roleArn: "arn:aws:iam::123456789012:role/non_prod"
 *         region: "us-east-1"
 *       - name: "ops"          # no roleArn: use the agent's own identity
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

    /** The default base identity for an agent: an EC2 instance role reached over IMDS. */
    public static final String DEFAULT_CREDENTIAL_SOURCE = "Ec2InstanceMetadata";

    /**
     * The values botocore's {@code CanonicalNameCredentialSourcer} accepts for {@code credential_source}
     * — verified against botocore 1.42.65. Exposed as configuration rather than hardcoded so the plugin
     * is not tied to EC2 agents: a controller whose agents run on ECS/EKS, or whose base credentials
     * arrive in the environment, configures this once and everything else is identical.
     */
    public static final List<String> CREDENTIAL_SOURCES =
            List.of(DEFAULT_CREDENTIAL_SOURCE, "EcsContainer", "Environment");

    private List<AwsProfile> profiles = new ArrayList<>();

    private boolean managedAuthentication;

    @CheckForNull
    private String jobNamePattern;

    @CheckForNull
    private String jobNameExcludePattern;

    @CheckForNull
    private String nodeLabelPattern;

    @CheckForNull
    private String unprofiledRoleArn;

    private boolean diagnostics;

    @NonNull
    private String credentialSource = DEFAULT_CREDENTIAL_SOURCE;

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
     * The master switch. Ships <b>off</b>, so installing or upgrading the plugin changes no build's
     * behaviour until an administrator opts in — and so turning it off again is a configuration change
     * rather than a plugin rollback, which would need a Jenkins restart.
     */
    public boolean isManagedAuthentication() {
        return managedAuthentication;
    }

    @DataBoundSetter
    public void setManagedAuthentication(boolean managedAuthentication) {
        this.managedAuthentication = managedAuthentication;
        save();
    }

    /** Optional regular expression against a job's full name. Blank means every job. */
    @CheckForNull
    public String getJobNamePattern() {
        return jobNamePattern;
    }

    @DataBoundSetter
    public void setJobNamePattern(@CheckForNull String jobNamePattern) {
        String trimmed = jobNamePattern == null ? "" : jobNamePattern.trim();
        this.jobNamePattern = trimmed.isEmpty() ? null : trimmed;
        save();
    }

    /**
     * Jobs to exclude even when they match the include pattern. Blank excludes nothing.
     *
     * <p>Exists because the alternative — expressing "everything except this one job" as a
     * negative-lookahead over a controller's entire job list — is error-prone under incident
     * pressure, and its failure mode is silently matching nothing.
     */
    @CheckForNull
    public String getJobNameExcludePattern() {
        return jobNameExcludePattern;
    }

    @DataBoundSetter
    public void setJobNameExcludePattern(@CheckForNull String jobNameExcludePattern) {
        String trimmed = jobNameExcludePattern == null ? "" : jobNameExcludePattern.trim();
        this.jobNameExcludePattern = trimmed.isEmpty() ? null : trimmed;
        save();
    }

    /**
     * Optional regular expression matched against the executing node's labels. Blank means every node.
     *
     * <p>A cheaper rollout axis than job names when agents differ: one agent may carry a divergent AWS
     * configuration, another may make no AWS calls at all.
     */
    @CheckForNull
    public String getNodeLabelPattern() {
        return nodeLabelPattern;
    }

    @DataBoundSetter
    public void setNodeLabelPattern(@CheckForNull String nodeLabelPattern) {
        String trimmed = nodeLabelPattern == null ? "" : nodeLabelPattern.trim();
        this.nodeLabelPattern = trimmed.isEmpty() ? null : trimmed;
        save();
    }

    /**
     * Role ARN used to attribute AWS calls that name no profile. Blank leaves them exactly as they are.
     *
     * <p>Calls that name no profile fall through the credential chain to the agent's base identity,
     * whose session name is assigned by the platform and cannot be changed — so they are unattributable
     * unless something assumes a role on their behalf. Setting this makes the generated {@code [default]}
     * assume a role under the build's session name.
     *
     * <p><b>Set this to the agent's own instance role.</b> Assuming a <em>different</em> role changes the
     * principal ARN, and resource-based policies (bucket, key and repository policies) grant access by
     * principal ARN — so a different role with identical permissions is still denied by every policy
     * that names the original. Self-assume keeps the ARN, and therefore keeps every such grant working.
     */
    @CheckForNull
    public String getUnprofiledRoleArn() {
        return unprofiledRoleArn;
    }

    @DataBoundSetter
    public void setUnprofiledRoleArn(@CheckForNull String unprofiledRoleArn) {
        String trimmed = unprofiledRoleArn == null ? "" : unprofiledRoleArn.trim();
        this.unprofiledRoleArn = trimmed.isEmpty() ? null : trimmed;
        save();
    }

    /**
     * Prints what the plugin discovered to the build console.
     *
     * <p>Deliberately configuration rather than a system property: a system property needs a controller
     * restart to change, which means the one switch needed during an incident is the one that cannot be
     * thrown — and the workaround is building and deploying a diagnostic release, which has happened.
     */
    public boolean isDiagnostics() {
        return diagnostics;
    }

    @DataBoundSetter
    public void setDiagnostics(boolean diagnostics) {
        this.diagnostics = diagnostics;
        save();
    }

    @NonNull
    public String getCredentialSource() {
        return credentialSource;
    }

    @DataBoundSetter
    public void setCredentialSource(@CheckForNull String credentialSource) {
        String trimmed = credentialSource == null ? "" : credentialSource.trim();
        this.credentialSource = CREDENTIAL_SOURCES.contains(trimmed) ? trimmed : DEFAULT_CREDENTIAL_SOURCE;
        save();
    }

    /**
     * Rebuilding from the submitted form rather than relying on setters alone: an unchecked checkbox
     * and a cleared text field are both <em>absent</em> from the JSON, so without this a value could
     * never be turned off again once set.
     */
    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        profiles = new ArrayList<>();
        managedAuthentication = false;
        jobNamePattern = null;
        jobNameExcludePattern = null;
        nodeLabelPattern = null;
        unprofiledRoleArn = null;
        diagnostics = false;
        credentialSource = DEFAULT_CREDENTIAL_SOURCE;
        req.bindJSON(this, json);
        save();
        return true;
    }

    /**
     * Resolves a profile name to its configured entry.
     *
     * @param name the profile name a pipeline asked for
     * @return the matching entry, or empty if there is no such profile. Entries without a usable name
     *     are treated as absent — a half-configured profile must not silently resolve to something.
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

    /**
     * @return every entry that can be rendered into a generated AWS configuration file: a usable name,
     *     and whatever its declared mode requires. An assume-role profile with no ARN is deliberately
     *     excluded rather than downgraded to the agent's identity — a half-configured profile must
     *     never quietly authenticate as something else.
     */
    @NonNull
    public List<AwsProfile> usableProfiles() {
        return profiles.stream().filter(AwsProfile::isUsable).collect(Collectors.toList());
    }

    /** @return the configured, usable profile names, for error messages and form help. */
    @NonNull
    public List<String> configuredProfileNames() {
        return usableProfiles().stream().map(AwsProfile::getName).collect(Collectors.toList());
    }

    /**
     * @param jobFullName the job's full name, e.g. {@code uat/Backend/deploy}
     * @return whether Managed Authentication should apply to this job. Fails <em>closed on the
     *     pattern</em>: an unparseable regular expression matches nothing, so a typo narrows the
     *     rollout rather than silently widening it to the whole controller.
     */
    public boolean appliesTo(@CheckForNull String jobFullName) {
        if (!managedAuthentication || jobFullName == null) {
            return false;
        }
        // Include: absent means every job; unparseable matches nothing, so a typo narrows the rollout.
        if (!matches(jobNamePattern, jobFullName, true, false)) {
            return false;
        }
        // Exclude: evaluated after include and wins. Absent excludes nothing; unparseable also excludes
        // nothing, so a typo here cannot silently switch attribution off across the controller.
        return !matches(jobNameExcludePattern, jobFullName, false, false);
    }

    /**
     * @param labels the executing node's label strings; empty when the node is unknown
     * @return whether the node is in scope. A blank pattern means every node. Fails <em>closed</em> on an
     *     unparseable pattern, consistent with {@link #appliesTo}.
     */
    public boolean appliesToNode(@CheckForNull Collection<String> labels) {
        if (nodeLabelPattern == null) {
            return true;
        }
        if (labels == null || labels.isEmpty()) {
            // A pattern was set and there is nothing to match it against: narrow rather than widen.
            return false;
        }
        try {
            Pattern compiled = Pattern.compile(nodeLabelPattern);
            return labels.stream().anyMatch(label -> compiled.matcher(label).matches());
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * Full-string match, as {@link java.util.regex.Matcher#matches()} rather than {@code find()}: a
     * pattern must describe the whole job name, so {@code deploy} does not match {@code folder/deploy}.
     *
     * <p>The two fallbacks are separate on purpose. "No pattern configured" and "the pattern does not
     * compile" are different situations, and collapsing them would make an invalid <em>include</em>
     * pattern behave like a blank one — silently widening the rollout to every job on a typo, which is
     * the one direction this must never fail in.
     *
     * @param whenAbsent result when no pattern is configured
     * @param whenUnparseable result when the pattern does not compile; always the narrower answer
     */
    private static boolean matches(
            @CheckForNull String pattern, String value, boolean whenAbsent, boolean whenUnparseable) {
        if (pattern == null) {
            return whenAbsent;
        }
        try {
            return Pattern.compile(pattern).matcher(value).matches();
        } catch (PatternSyntaxException e) {
            return whenUnparseable;
        }
    }

    public FormValidation doCheckJobNamePattern(@QueryParameter String value) {
        String pattern = value == null ? "" : value.trim();
        if (pattern.isEmpty()) {
            return FormValidation.warning("Blank: applies to EVERY Pipeline job on this controller.");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return FormValidation.error("Not a valid regular expression: " + e.getDescription());
        }
        return FormValidation.ok(describeMatches(pattern, "Applies to"));
    }

    public FormValidation doCheckJobNameExcludePattern(@QueryParameter String value) {
        String pattern = value == null ? "" : value.trim();
        if (pattern.isEmpty()) {
            return FormValidation.ok("Blank: nothing is excluded.");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return FormValidation.error("Not a valid regular expression: " + e.getDescription());
        }
        return FormValidation.ok(describeMatches(pattern, "Excludes"));
    }

    public FormValidation doCheckNodeLabelPattern(@QueryParameter String value) {
        String pattern = value == null ? "" : value.trim();
        if (pattern.isEmpty()) {
            return FormValidation.ok("Blank: applies on every node.");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return FormValidation.error("Not a valid regular expression: " + e.getDescription());
        }
        return FormValidation.ok("Matched against each of the executing node's labels, in full.");
    }

    /**
     * Validates the unprofiled-identity ARN, and warns when it is blank.
     *
     * <p>Blank is a legitimate, safe choice — calls naming no profile keep behaving exactly as they do
     * today — so this is a warning rather than an error, but it is worth surfacing because "attribution
     * is on" and "everything is attributed" are not the same thing.
     */
    public FormValidation doCheckUnprofiledRoleArn(@QueryParameter String value) {
        String arn = value == null ? "" : value.trim();
        if (arn.isEmpty()) {
            return FormValidation.warning(
                    "Blank: calls that name no profile stay unattributed, exactly as they are today.");
        }
        if (!AwsProfile.looksLikeRoleArn(arn)) {
            return FormValidation.error("Expected an IAM role ARN, e.g. arn:aws:iam::123456789012:role/name");
        }
        return FormValidation.ok("Calls naming no profile will assume this role under the build's session "
                + "name. Use the agent's own instance role: a different role changes the principal ARN, "
                + "and resource policies grant by principal ARN.");
    }

    /** Reports how many of this controller's current jobs a pattern selects, so blast radius is visible. */
    private static String describeMatches(String pattern, String verb) {
        Jenkins instance = Jenkins.getInstanceOrNull();
        if (instance == null) {
            return verb + " jobs whose full name matches, e.g. uat/Backend/deploy.";
        }
        Pattern compiled = Pattern.compile(pattern);
        List<String> matched = instance.getAllItems(Job.class).stream()
                .map(Job::getFullName)
                .filter(name -> compiled.matcher(name).matches())
                .sorted()
                .collect(Collectors.toList());
        if (matched.isEmpty()) {
            return verb + " no job that currently exists. Check the pattern: it is matched against the "
                    + "job's FULL name, e.g. uat/Backend/deploy.";
        }
        String preview = matched.stream().limit(5).collect(Collectors.joining(", "));
        return verb + " " + matched.size() + " current job" + (matched.size() == 1 ? "" : "s") + ": " + preview
                + (matched.size() > 5 ? ", …" : "");
    }

    /** Populates the credential-source dropdown. Values, not labels — they go into the file verbatim. */
    public hudson.util.ListBoxModel doFillCredentialSourceItems() {
        hudson.util.ListBoxModel items = new hudson.util.ListBoxModel();
        for (String source : CREDENTIAL_SOURCES) {
            items.add(source, source);
        }
        return items;
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "CK AWS";
    }
}
