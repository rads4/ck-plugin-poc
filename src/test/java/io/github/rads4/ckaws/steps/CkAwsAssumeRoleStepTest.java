package io.github.rads4.ckaws.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import hudson.util.FormValidation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Milestone M3 tests: the Jenkins integration point.
 *
 * <p>{@link JenkinsRule} is genuinely required here — this milestone <em>is</em> extension registration
 * and DSL wiring. Every test drives the real path Step -&gt; AuthCore -&gt; CliStsAssumeRole -&gt;
 * DefaultProcessRunner -&gt; a real subprocess, with a stub {@code aws} script standing in for the AWS
 * CLI. No AWS account, no credentials and no installed AWS CLI are needed.
 *
 * <p>POSIX-only (the stubs are {@code /bin/sh} scripts), matching {@code DefaultProcessRunnerTest}.
 */
@WithJenkins
@DisabledOnOs(OS.WINDOWS)
class CkAwsAssumeRoleStepTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/non_prod";

    /** One tab-separated line, exactly as {@code --query Credentials.[...] --output text} produces. */
    private static final String CREDENTIAL_LINE =
            "ASIAEXAMPLE\\tSECRET/EXAMPLE+VALUE\\tSESSIONTOKENVALUE==\\t2026-07-24T13:00:00+00:00\\n";

    @TempDir
    private Path tmp;

    /** The profile the M4 stubs below insist on seeing, standing in for the real {@code ops-admin}. */
    private static final String TEST_PROFILE = "ck-test-profile";

    @AfterEach
    void clearSystemPropertyOverrides() {
        System.clearProperty(CkAwsAssumeRoleStep.AWS_EXECUTABLE_PROPERTY);
        System.clearProperty(CkAwsAssumeRoleStep.AWS_PROFILE_PROPERTY);
        System.clearProperty(CkAwsAssumeRoleStep.VALIDATE_IDENTITY_PROPERTY);
    }

    @Test
    void stepIsRegisteredWithItsFunctionName(JenkinsRule j) {
        StepDescriptor descriptor = StepDescriptor.byFunctionName("ckAwsAssumeRole");
        assertNotNull(descriptor, "ckAwsAssumeRole step should be registered");
        assertInstanceOf(CkAwsAssumeRoleStep.DescriptorImpl.class, descriptor);
        assertTrue(
                descriptor.getRequiredContext().contains(hudson.model.Run.class),
                "step should declare Run as required context");
    }

    @Test
    void returnsTheGeneratedSessionNameOnSuccess(JenkinsRule j) throws Exception {
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "myjob");
        job.setDefinition(new CpsFlowDefinition(assertingScript("jk-myjob-1"), true));

        // The build only succeeds if the in-pipeline assertion on the returned value held.
        WorkflowRun build = j.buildAndAssertSuccess(job);
        j.assertLogContains("[ck-aws] Assumed role " + ROLE + " as session jk-myjob-1", build);
    }

    @Test
    void jobNameAndBuildNumberReachTheAssumeRoleCall(JenkinsRule j) throws Exception {
        Path args = tmp.resolve("argv.txt");
        useStub(argvRecordingStub(args));

        WorkflowJob job = j.createProject(WorkflowJob.class, "paramflow");
        job.setDefinition(new CpsFlowDefinition("ckAwsAssumeRole(roleArn: '" + ROLE + "')\n", true));
        j.buildAndAssertSuccess(job);

        List<String> argv = Files.readAllLines(args, StandardCharsets.UTF_8);
        assertEquals("sts", argv.get(0));
        assertEquals("assume-role", argv.get(1));
        assertEquals(ROLE, argv.get(argv.indexOf("--role-arn") + 1), "roleArn from the DSL should reach the CLI");
        assertEquals(
                "jk-paramflow-1",
                argv.get(argv.indexOf("--role-session-name") + 1),
                "job name and build number from Jenkins should reach the CLI as the session name");
    }

    @Test
    void awkwardJobNamesAreSanitizedIntoAValidSessionName(JenkinsRule j) throws Exception {
        useStub(successStub());

        // Jenkins rejects most punctuation in job names, but spaces are legal - and they are outside
        // STS's [\w+=,.@-] set, so this exercises SessionName's sanitizer through the real step.
        WorkflowJob job = j.createProject(WorkflowJob.class, "my awkward job");
        job.setDefinition(new CpsFlowDefinition(assertingScript("jk-my-awkward-job-1"), true));

        j.buildAndAssertSuccess(job);
    }

    @Test
    void assumeRoleFailureAbortsTheBuildWithAnActionableMessage(JenkinsRule j) throws Exception {
        useStub(failingStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "denied");
        job.setDefinition(new CpsFlowDefinition("ckAwsAssumeRole(roleArn: '" + ROLE + "')\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        String log = JenkinsRule.getLog(build);

        assertTrue(log.contains(ROLE), "should name the role");
        assertTrue(log.contains("jk-denied-1"), "should name the session");
        assertTrue(log.contains("255"), "should report the exit code");
        assertTrue(log.contains("AccessDenied"), "should surface stderr");
        assertFalse(log.contains("\tat io.github.rads4"), "should not print a Java stack trace");
    }

    @Test
    void missingAwsExecutableAbortsTheBuildCleanly(JenkinsRule j) throws Exception {
        System.setProperty(
                CkAwsAssumeRoleStep.AWS_EXECUTABLE_PROPERTY,
                tmp.resolve("no-such-aws").toString());

        WorkflowJob job = j.createProject(WorkflowJob.class, "nobinary");
        job.setDefinition(new CpsFlowDefinition("ckAwsAssumeRole(roleArn: '" + ROLE + "')\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        String log = JenkinsRule.getLog(build);

        assertTrue(log.contains(ROLE), "should name the role");
        assertFalse(log.contains("ProcessExecutionException"), "execution-layer exception must not leak");
        assertFalse(log.contains("\tat io.github.rads4"), "should not print a Java stack trace");
    }

    @Test
    void blankRoleArnAbortsTheBuild(JenkinsRule j) throws Exception {
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "noarn");
        job.setDefinition(new CpsFlowDefinition("ckAwsAssumeRole(roleArn: '  ')\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.assertLogContains("requires a non-blank roleArn", build);
    }

    @Test
    void roleArnFormValidation(JenkinsRule j) {
        CkAwsAssumeRoleStep.DescriptorImpl descriptor =
                (CkAwsAssumeRoleStep.DescriptorImpl) StepDescriptor.byFunctionName("ckAwsAssumeRole");
        assertNotNull(descriptor);

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckRoleArn("").kind);
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckRoleArn("non_prod").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckRoleArn(ROLE).kind);
    }

    // --- temporary M4 validation path (delete with the scaffolding in M5) ----

    /**
     * The whole M4 mechanism in one test. The stub {@code aws} refuses to play along unless every part is
     * wired correctly, so a green build here means: the profile override reached the AssumeRole call, it
     * was <em>removed</em> again for the identity check, the temporary credentials were passed by
     * environment, and the session name generated from this job/build survived the round trip.
     */
    @Test
    void identityCheckConfirmsTheSessionNameUsingTheTemporaryCredentials(JenkinsRule j) throws Exception {
        useStub(liveValidationStub(tmp.resolve("session.txt"), true));
        System.setProperty(CkAwsAssumeRoleStep.AWS_PROFILE_PROPERTY, TEST_PROFILE);
        System.setProperty(CkAwsAssumeRoleStep.VALIDATE_IDENTITY_PROPERTY, "true");

        WorkflowJob job = j.createProject(WorkflowJob.class, "live");
        // Unchanged M3 DSL: enabling validation is invisible to the pipeline.
        job.setDefinition(new CpsFlowDefinition(assertingScript("jk-live-1"), true));

        WorkflowRun build = j.buildAndAssertSuccess(job);

        j.assertLogContains("[ck-aws] AWS CLI profile: " + TEST_PROFILE + " (temporary M4 override)", build);
        j.assertLogContains("assumed-role/non_prod/jk-live-1", build);
        j.assertLogContains("[ck-aws] Session name confirmed in caller identity: jk-live-1", build);
    }

    @Test
    void identityCheckFailsWhenTheCallerIsNotTheAssumedSession(JenkinsRule j) throws Exception {
        // The stub answers with the *base* identity, i.e. what a leaked AWS_PROFILE would have produced.
        useStub(liveValidationStub(tmp.resolve("session.txt"), false));
        System.setProperty(CkAwsAssumeRoleStep.AWS_PROFILE_PROPERTY, TEST_PROFILE);
        System.setProperty(CkAwsAssumeRoleStep.VALIDATE_IDENTITY_PROPERTY, "true");

        WorkflowJob job = j.createProject(WorkflowJob.class, "wrongidentity");
        job.setDefinition(new CpsFlowDefinition("ckAwsAssumeRole(roleArn: '" + ROLE + "')\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        String log = JenkinsRule.getLog(build);

        assertTrue(log.contains("does not contain the expected session name"), "should explain the mismatch");
        assertTrue(log.contains("jk-wrongidentity-1"), "should name the expected session");
        assertFalse(log.contains("\tat io.github.rads4"), "should not print a Java stack trace");
    }

    @Test
    void noIdentityCheckHappensUnlessTheValidationPropertyIsSet(JenkinsRule j) throws Exception {
        Path args = tmp.resolve("argv.txt");
        useStub(argvRecordingStub(args));

        WorkflowJob job = j.createProject(WorkflowJob.class, "novalidation");
        job.setDefinition(new CpsFlowDefinition("ckAwsAssumeRole(roleArn: '" + ROLE + "')\n", true));
        j.buildAndAssertSuccess(job);

        assertFalse(
                Files.readAllLines(args, StandardCharsets.UTF_8).contains("get-caller-identity"),
                "exactly one AWS call should be made when validation is off");
    }

    // --- pipeline scripts ---------------------------------------------------

    /**
     * Asserts the step's return value with plain Groovy rather than {@code echo}, so the tests need no
     * dependency on workflow-basic-steps. A mismatch fails the build.
     */
    private static String assertingScript(String expectedSessionName) {
        return "def session = ckAwsAssumeRole(roleArn: '" + ROLE + "')\n" + "assert session == '" + expectedSessionName
                + "'\n";
    }

    // --- stub 'aws' scripts -------------------------------------------------

    private void useStub(Path stub) {
        System.setProperty(CkAwsAssumeRoleStep.AWS_EXECUTABLE_PROPERTY, stub.toString());
    }

    private Path successStub() throws IOException {
        return writeScript("aws-success", "#!/bin/sh\nprintf '" + CREDENTIAL_LINE + "'\n");
    }

    private Path argvRecordingStub(Path argsFile) throws IOException {
        return writeScript(
                "aws-argv",
                "#!/bin/sh\n" + "for a in \"$@\"; do echo \"$a\" >> '" + argsFile + "'; done\n" + "printf '"
                        + CREDENTIAL_LINE + "'\n");
    }

    /**
     * Stands in for the AWS CLI across both calls of an M4 validation run, and asserts the environment it
     * is handed. {@code assume-role} must see {@code AWS_PROFILE}; {@code get-caller-identity} must see the
     * issued credentials and must <em>not</em> see {@code AWS_PROFILE}. It echoes back the session name it
     * was given for AssumeRole, so nothing about the expected name is hardcoded in the stub.
     *
     * @param correctIdentity when false, answer with the base identity instead of the assumed session —
     *     the false-positive a leaked {@code AWS_PROFILE} would produce.
     */
    private Path liveValidationStub(Path sessionFile, boolean correctIdentity) throws IOException {
        String printIdentity = correctIdentity
                ? "printf '123456789012\\tarn:aws:sts::123456789012:assumed-role/non_prod/%s"
                        + "\\tAROAEXAMPLE:%s\\n' \"$s\" \"$s\""
                : "printf '123456789012\\tarn:aws:iam::123456789012:user/base-identity\\tAIDABASE\\n'";
        return writeScript(
                "aws-live",
                "#!/bin/sh\n"
                        + "case \"$2\" in\n"
                        + "  assume-role)\n"
                        + "    if [ \"$AWS_PROFILE\" != '" + TEST_PROFILE + "' ]; then\n"
                        + "      echo \"expected AWS_PROFILE=" + TEST_PROFILE
                        + " but saw '${AWS_PROFILE}'\" >&2; exit 90\n"
                        + "    fi\n"
                        + "    prev=''\n"
                        + "    for a in \"$@\"; do\n"
                        + "      if [ \"$prev\" = '--role-session-name' ]; then printf %s \"$a\" > '"
                        + sessionFile + "'; fi\n"
                        + "      prev=\"$a\"\n"
                        + "    done\n"
                        + "    printf '" + CREDENTIAL_LINE + "'\n"
                        + "    ;;\n"
                        + "  get-caller-identity)\n"
                        + "    if [ -n \"${AWS_PROFILE+set}\" ]; then\n"
                        + "      echo 'AWS_PROFILE leaked into the identity check' >&2; exit 91\n"
                        + "    fi\n"
                        + "    if [ \"$AWS_ACCESS_KEY_ID\" != 'ASIAEXAMPLE' ]; then\n"
                        + "      echo \"temporary credentials were not passed\" >&2; exit 92\n"
                        + "    fi\n"
                        + "    s=$(cat '" + sessionFile + "')\n"
                        + "    " + printIdentity + "\n"
                        + "    ;;\n"
                        + "  *)\n"
                        + "    echo \"unexpected call: $*\" >&2; exit 93\n"
                        + "    ;;\n"
                        + "esac\n");
    }

    private Path failingStub() throws IOException {
        return writeScript(
                "aws-denied",
                "#!/bin/sh\n"
                        + "echo 'An error occurred (AccessDenied) when calling the AssumeRole operation' >&2\n"
                        + "exit 255\n");
    }

    private Path writeScript(String name, String body) throws IOException {
        Path script = tmp.resolve(name);
        Files.writeString(script, body, StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true), "stub script should be made executable");
        return script;
    }
}
