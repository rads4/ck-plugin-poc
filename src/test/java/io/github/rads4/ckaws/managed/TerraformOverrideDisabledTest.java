package io.github.rads4.ckaws.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rads4.ckaws.config.CkAwsGlobalConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * With no pattern configured, the Terraform feature must be completely inert.
 *
 * <p>This is the property the rollout depends on. The feature ships with {@code
 * terraformOverridePattern} blank, so the claim "installing this changes nothing for any existing job"
 * rests entirely on a blank pattern meaning <em>no directory is scanned and no file is written</em> —
 * not merely "nothing is usually written". Asserting it in a test is cheaper than trusting it.
 */
@WithJenkins
class TerraformOverrideDisabledTest {

    @Test
    void blankPatternMatchesNothing(JenkinsRule r) {
        CkAwsGlobalConfiguration config = CkAwsGlobalConfiguration.get();
        config.setTerraformOverridePattern(null);

        assertFalse(config.appliesTerraformOverride("anything"), "blank pattern must never apply");
        assertFalse(config.appliesTerraformOverride("cln-infra-terraform-pipelines/cln-app-terraform-pipeline"));
        assertFalse(config.appliesTerraformOverride("ck-kong-terraform"));
        assertFalse(config.appliesTerraformOverride(null), "a null job name must not match either");
    }

    @Test
    void anUnparseablePatternDisablesRatherThanWidens(JenkinsRule r) {
        CkAwsGlobalConfiguration config = CkAwsGlobalConfiguration.get();
        config.setTerraformOverridePattern("[unclosed");

        assertFalse(
                config.appliesTerraformOverride("ck-kong-terraform"),
                "a typo must switch the feature off, never turn it on for everything");
    }

    /** A configured pattern still selects only what it names. */
    @Test
    void aPatternAppliesOnlyToWhatItMatches(JenkinsRule r) {
        CkAwsGlobalConfiguration config = CkAwsGlobalConfiguration.get();
        config.setTerraformOverridePattern("ck-kong-terraform");

        assertTrue(config.appliesTerraformOverride("ck-kong-terraform"));
        assertFalse(config.appliesTerraformOverride("dev2/fluentd"));
        assertFalse(config.appliesTerraformOverride("ck-kong-terraform-other"), "full-string match, not prefix");
    }

    /**
     * The generator itself writes nothing for a directory it does not understand — the second half of
     * "inert", independent of the pattern.
     */
    @Test
    void theGeneratorWritesNothingForUnrecognisedConfiguration(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("main.tf"), "resource \"aws_s3_bucket\" \"b\" {}\n");

        assertEquals(
                0,
                Files.list(tmp)
                        .filter(p -> p.getFileName().toString().equals(TerraformOverride.FILE_NAME))
                        .count(),
                "no override file should exist before or after");
        assertEquals(
                null,
                TerraformOverride.overrideFor(Files.readString(tmp.resolve("main.tf")), "jk-x-1"),
                "a file with no provider block yields no override");
    }
}
