package io.github.rads4.ckaws.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import io.github.rads4.ckaws.auth.AuthCore;
import io.github.rads4.ckaws.auth.AwsCredentials;
import io.github.rads4.ckaws.auth.CkAwsAuthException;
import io.github.rads4.ckaws.auth.SessionName;
import io.github.rads4.ckaws.auth.cli.CliStsAssumeRole;
import io.github.rads4.ckaws.exec.DefaultProcessRunner;
import io.github.rads4.ckaws.exec.ProcessResult;
import io.github.rads4.ckaws.exec.ProcessRunner;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.util.SystemProperties;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Pipeline step {@code ckAwsAssumeRole}: the plugin's first Jenkins integration point (milestone M3).
 *
 * <pre>{@code
 * def session = ckAwsAssumeRole(roleArn: 'arn:aws:iam::123456789012:role/non_prod')
 * echo "assumed as ${session}"
 * }</pre>
 *
 * <p>Its whole job is to bridge Jenkins to the existing, Jenkins-agnostic auth stack: read the job name
 * and build number off the running build, hand them plus the role ARN to {@link AuthCore}, and translate
 * failures into an {@link AbortException} so the build log shows an actionable message rather than a
 * stack trace. Everything below it — {@link AuthCore}, {@link CliStsAssumeRole},
 * {@link DefaultProcessRunner} — is unchanged from milestones M1/M2.
 *
 * <p><b>The step returns only the generated session name.</b> No credential material (not even the
 * access key id) is exposed to the Pipeline DSL: anything returned here is persisted in the CPS program
 * state and is trivially printable from a pipeline. Exporting credentials for subsequent steps is the
 * job of a future block-scoped {@code withProfile} step, not this one.
 *
 * <p><b>Known POC limitation:</b> this executes on the Jenkins controller JVM (via
 * {@link DefaultProcessRunner}), not on the agent selected by any enclosing {@code node} block. That is
 * an accepted constraint for the POC; an agent-backed {@code ProcessRunner} built from the step
 * context's {@code Launcher} can be added later as a new {@code .exec} implementation, with no change to
 * the auth layer.
 *
 * <p><b>Temporary milestone-M4 scaffolding — remove in M5.</b> Two system properties exist purely to run
 * this step against a real AWS account from a local {@code mvn hpi:run} Jenkins:
 * {@link #AWS_PROFILE_PROPERTY} selects the base AWS CLI profile, and {@link #VALIDATE_IDENTITY_PROPERTY}
 * turns on a one-off {@code sts get-caller-identity} check of the credentials that were just issued. Both
 * are deliberately invisible to the Pipeline DSL — the step's public API is unchanged from M3 — and both,
 * together with the {@code verifyIdentity} and {@code temporaryProfileEnvironment} methods below, are
 * meant to be deleted as a single unit. Nothing else in the plugin refers to them.
 */
public final class CkAwsAssumeRoleStep extends Step {

    /**
     * Overrides the {@code aws} executable used for the AssumeRole call. Exists so the step can be
     * exercised end-to-end without the real AWS CLI (the child process inherits this JVM's environment,
     * so a test cannot simply prepend to {@code PATH}).
     */
    static final String AWS_EXECUTABLE_PROPERTY = "io.github.rads4.ckaws.awsExecutable";

    /**
     * TEMPORARY (M4, remove in M5). Selects the AWS CLI profile that provides the <em>base</em> identity
     * for the AssumeRole call, by setting {@code AWS_PROFILE} on that child process only.
     *
     * <p>This does not read {@code ~/.aws/config} — the plugin only names a profile and the AWS CLI
     * resolves it. Long term, profile-to-role configuration belongs in JCasC (see CLAUDE.md); this exists
     * solely so a local validation run does not depend on which shell started Jenkins. When unset, the
     * child simply inherits this JVM's environment exactly as it did in M3.
     */
    static final String AWS_PROFILE_PROPERTY = "io.github.rads4.ckaws.awsProfile";

    /**
     * TEMPORARY (M4, remove in M5). When {@code true}, the step verifies the credentials it just obtained
     * with a single {@code sts get-caller-identity} call. Off by default, so no existing behaviour and no
     * existing test changes.
     */
    static final String VALIDATE_IDENTITY_PROPERTY = "io.github.rads4.ckaws.validateIdentity";

    private static final String DEFAULT_AWS_EXECUTABLE = "aws";

    private final String roleArn;

    @DataBoundConstructor
    public CkAwsAssumeRoleStep(String roleArn) {
        // Deliberately permissive: validation happens in the execution, so a bad value fails the build
        // with a readable message instead of an opaque data-binding error.
        this.roleArn = roleArn;
    }

    public String getRoleArn() {
        return roleArn;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(roleArn, context);
    }

    /**
     * Runs off the CPS VM thread: the AssumeRole call blocks on a subprocess, and blocking the CPS
     * thread would stall the whole build's flow execution.
     */
    private static final class Execution extends SynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1L;

        /** Only serializable state is held here; {@link AuthCore} is built per invocation in {@link #run()}. */
        private final String roleArn;

        Execution(String roleArn, StepContext context) {
            super(context);
            this.roleArn = roleArn;
        }

        @Override
        protected String run() throws Exception {
            String arn = roleArn == null ? "" : roleArn.trim();
            if (arn.isEmpty()) {
                throw new AbortException("ckAwsAssumeRole requires a non-blank roleArn, e.g. "
                        + "ckAwsAssumeRole(roleArn: 'arn:aws:iam::123456789012:role/non_prod')");
            }

            Run<?, ?> build = getContext().get(Run.class);
            TaskListener listener = getContext().get(TaskListener.class);
            if (build == null || listener == null) {
                throw new AbortException("ckAwsAssumeRole must run in the context of a build.");
            }

            // getFullName(), not getName(): folder jobs contribute their folder path, which SessionName
            // sanitizes. This is where Jenkins identity enters the auth stack.
            String jobName = build.getParent().getFullName();
            int buildNumber = build.getNumber();

            String sessionName;
            try {
                // Deterministic and side-effect-free, so computing it up front (AuthCore derives the same
                // value internally) both fails closed before spawning a process and gives us the value to
                // return, without changing AuthCore's M1 signature.
                sessionName = SessionName.forBuild(jobName, buildNumber).value();
            } catch (CkAwsAuthException e) {
                throw abort(listener, e);
            }

            DefaultProcessRunner processRunner = new DefaultProcessRunner();

            // TEMPORARY (M4): decorating the runner here is what keeps the profile override out of the
            // auth layer -- ProcessRunner is a @FunctionalInterface, so CliStsAssumeRole and AuthCore are
            // untouched. Deleting this leaves `new CliStsAssumeRole(processRunner, awsExecutable())`.
            Map<String, String> baseEnvironment = temporaryProfileEnvironment(listener);
            ProcessRunner runner = command -> processRunner.run(command, baseEnvironment);

            listener.getLogger().println("[ck-aws] Assuming role " + arn + " as session " + sessionName);

            AuthCore authCore = new AuthCore(new CliStsAssumeRole(runner, awsExecutable()));
            AwsCredentials credentials;
            try {
                credentials = authCore.authenticate(arn, jobName, buildNumber);
            } catch (CkAwsAuthException e) {
                throw abort(listener, e);
            }

            listener.getLogger().println("[ck-aws] Assumed role " + arn + " as session " + sessionName);

            // TEMPORARY (M4): the only consumer of `credentials` in this step. Once removed, the
            // authenticate(...) result goes back to being discarded, as documented above: exporting
            // credentials for later steps is a future withProfile-block concern, not this step's.
            if (SystemProperties.getBoolean(VALIDATE_IDENTITY_PROPERTY)) {
                verifyIdentity(listener, processRunner, baseEnvironment, credentials, sessionName);
            }

            return sessionName;
        }

        /**
         * TEMPORARY (M4, remove in M5). Second and last AWS call of a validation run: proves the
         * temporary credentials actually work, and that they belong to this build's session.
         *
         * <p>This runs with the same base environment the AssumeRole call got, but with
         * {@code AWS_PROFILE}/{@code AWS_DEFAULT_PROFILE} <em>removed</em> — whether they came from
         * {@code baseEnvironment} or were inherited from the shell that started Jenkins. Without that
         * removal the AWS CLI could resolve the base profile instead of the credentials passed here, and
         * {@code get-caller-identity} would succeed and print a plausible ARN while proving nothing. The
         * session-name check below is what makes this a real assertion: only the temporary credentials
         * yield an {@code assumed-role/<role>/jk-<job>-<build>} ARN.
         *
         * <p>Credentials travel by environment, never in the argument list, since arguments are readable
         * by any local user via {@code ps}.
         */
        private static void verifyIdentity(
                TaskListener listener,
                DefaultProcessRunner runner,
                Map<String, String> baseEnvironment,
                AwsCredentials credentials,
                String sessionName)
                throws AbortException {
            Map<String, String> environment = new HashMap<>(baseEnvironment);
            environment.put("AWS_ACCESS_KEY_ID", credentials.accessKeyId());
            environment.put("AWS_SECRET_ACCESS_KEY", credentials.secretAccessKey());
            environment.put("AWS_SESSION_TOKEN", credentials.sessionToken());
            environment.put("AWS_PROFILE", null);
            environment.put("AWS_DEFAULT_PROFILE", null);

            List<String> command = List.of(awsExecutable(), "sts", "get-caller-identity", "--output", "text");

            ProcessResult result;
            try {
                result = runner.run(command, environment);
            } catch (RuntimeException e) {
                // Only the message is used: the execution layer's exception type stays out of the log.
                throw new AbortException(
                        "[ck-aws] identity check could not run 'aws sts get-caller-identity': " + e.getMessage());
            }
            if (!result.succeeded()) {
                // Surfaced verbatim so the CLI's own diagnostics (a missing region, expired base
                // credentials, a denied call) reach the build log unedited. No region is defaulted here:
                // dropping AWS_PROFILE can drop the profile's region with it, and that must fail loudly.
                throw new AbortException("[ck-aws] identity check failed: 'aws sts get-caller-identity' exited "
                        + result.exitCode() + ": " + result.stderr().trim());
            }

            String identity = result.stdout().trim();
            listener.getLogger().println("[ck-aws] Caller identity: " + identity);
            if (!identity.contains("/" + sessionName)) {
                throw new AbortException("[ck-aws] identity check failed: caller identity '" + identity
                        + "' does not contain the expected session name '" + sessionName
                        + "', so the call did not use the credentials just issued.");
            }
            listener.getLogger().println("[ck-aws] Session name confirmed in caller identity: " + sessionName);
        }

        /**
         * TEMPORARY (M4, remove in M5). The {@link #AWS_PROFILE_PROPERTY} override as an environment map,
         * or empty when the property is unset (in which case the child inherits this JVM's environment
         * unchanged, exactly as in M3).
         */
        private static Map<String, String> temporaryProfileEnvironment(TaskListener listener) {
            String profile = SystemProperties.getString(AWS_PROFILE_PROPERTY);
            if (profile == null || profile.trim().isEmpty()) {
                return Map.of();
            }
            listener.getLogger().println("[ck-aws] AWS CLI profile: " + profile.trim() + " (temporary M4 override)");
            return Map.of("AWS_PROFILE", profile.trim());
        }

        /**
         * Turns an auth-layer failure into the Jenkins idiom for an expected, user-actionable error:
         * {@link AbortException} prints its message alone, with no Java stack trace.
         *
         * <p>The root cause's <em>message</em> is logged separately so diagnostics are not lost — but
         * never the exception object, whose class name would leak the auth layer's transport (the M2
         * boundary that keeps "authentication happens via a subprocess" invisible to callers).
         */
        private static AbortException abort(TaskListener listener, CkAwsAuthException e) {
            String detail = rootCauseMessage(e);
            if (detail != null) {
                listener.getLogger().println("[ck-aws] cause: " + detail);
            }
            return new AbortException(e.getMessage());
        }

        private static String rootCauseMessage(Throwable e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return root == e ? null : root.getMessage();
        }

        private static String awsExecutable() {
            String configured = SystemProperties.getString(AWS_EXECUTABLE_PROPERTY, DEFAULT_AWS_EXECUTABLE);
            return configured == null || configured.trim().isEmpty() ? DEFAULT_AWS_EXECUTABLE : configured;
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "ckAwsAssumeRole";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Assume an AWS role for this build";
        }

        /** Declaring this makes misuse fail with a clear "step requires ..." message. */
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }

        /**
         * Snippet-generator validation. Deliberately not a full ARN regex — over-validating would reject
         * valid partition/ARN forms — so a non-ARN-looking value is only a warning.
         */
        public FormValidation doCheckRoleArn(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("A role ARN is required.");
            }
            if (!value.trim().startsWith("arn:")) {
                return FormValidation.warning("This does not look like an ARN (expected something like "
                        + "arn:aws:iam::<account>:role/<name>).");
            }
            return FormValidation.ok();
        }
    }
}
