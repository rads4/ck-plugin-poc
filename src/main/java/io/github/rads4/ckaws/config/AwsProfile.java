package io.github.rads4.ckaws.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.util.Objects;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * One entry in the Jenkins-owned {@code profile name -> role ARN} mapping (architecture Layer 0).
 *
 * <p>A profile name is an opaque label chosen by the Jenkins administrator. This class attaches no
 * meaning to any particular value: {@code prod}, {@code non_prod}, {@code team-a-sandbox} and
 * {@code anything} are all equally valid, and nothing in the plugin branches on what a name is. That
 * is what keeps the plugin free of organization-specific knowledge — the organization's vocabulary
 * lives in its JCasC file, not in this source tree.
 *
 * <p>Role ARNs are deliberately <em>not</em> secrets and are not routed through the Credentials
 * plugin. Keeping them in global configuration means changing one requires Jenkins admin permission
 * and shows up in a JCasC diff, which is the actual control we want.
 */
public final class AwsProfile extends AbstractDescribableImpl<AwsProfile> {

    private final String name;
    private final String roleArn;

    /** Optional. When set, exported to the authenticated block as {@code AWS_REGION}. */
    @CheckForNull
    private String region;

    @DataBoundConstructor
    public AwsProfile(String name, String roleArn) {
        this.name = trimToEmpty(name);
        this.roleArn = trimToEmpty(roleArn);
    }

    @NonNull
    public String getName() {
        return name;
    }

    @NonNull
    public String getRoleArn() {
        return roleArn;
    }

    @CheckForNull
    public String getRegion() {
        return region;
    }

    @DataBoundSetter
    public void setRegion(@CheckForNull String region) {
        String trimmed = trimToEmpty(region);
        this.region = trimmed.isEmpty() ? null : trimmed;
    }

    /** @return {@code true} if this entry has both a name and a role ARN, i.e. it is usable. */
    public boolean isComplete() {
        return !name.isEmpty() && !roleArn.isEmpty();
    }

    private static String trimToEmpty(@CheckForNull String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AwsProfile)) {
            return false;
        }
        AwsProfile that = (AwsProfile) o;
        return name.equals(that.name) && roleArn.equals(that.roleArn) && Objects.equals(region, that.region);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, roleArn, region);
    }

    /** Never includes anything sensitive — a role ARN is not a secret, and there is nothing else here. */
    @Override
    public String toString() {
        return "AwsProfile{name=" + name + ", roleArn=" + roleArn + ", region=" + region + "}";
    }

    @Extension
    @Symbol("awsProfile")
    public static final class DescriptorImpl extends Descriptor<AwsProfile> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "AWS profile";
        }

        public FormValidation doCheckName(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("A profile name is required. Pipelines refer to this name, "
                        + "e.g. ckAwsWithProfile('non_prod') { ... }");
            }
            return FormValidation.ok();
        }

        /**
         * Advisory only. The shape of an ARN is AWS's business, not this plugin's, so a value that does
         * not look like one is a warning rather than an error — a partition or ARN format we have not
         * anticipated must not be rejected here.
         */
        public FormValidation doCheckRoleArn(@QueryParameter String value) {
            String arn = value == null ? "" : value.trim();
            if (arn.isEmpty()) {
                return FormValidation.error("A role ARN is required, e.g. arn:aws:iam::123456789012:role/example");
            }
            if (!arn.startsWith("arn:")) {
                return FormValidation.warning("This does not look like an ARN. Expected something like "
                        + "arn:aws:iam::123456789012:role/example");
            }
            return FormValidation.ok();
        }
    }
}
