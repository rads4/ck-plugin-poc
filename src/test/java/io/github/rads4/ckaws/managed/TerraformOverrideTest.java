package io.github.rads4.ckaws.managed;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What may and may not be overridden.
 *
 * <p>Most of these assert that <em>nothing</em> is written. That is the point: the failure mode here is
 * not a missing attribution, it is a build silently running as a different principal. Terraform
 * replaces a nested block instead of merging it, so an override that drops {@code role_arn} makes
 * Terraform skip the assume entirely and use the agent's raw instance role — measured, and it produces
 * no error. Anything this class cannot reproduce exactly is therefore left alone.
 */
class TerraformOverrideTest {

    private static final String SESSION = "jk-cln-app-terraform-pipeline-5620";

    /** The real shape from infra-cloudkeeper-app-services: role_arn computed from yaml-driven locals. */
    private static final String REAL = "locals {\n"
            + "  workspace = local.env_space[\"workspace\"]\n"
            + "}\n"
            + "provider \"aws\" {\n"
            + "  region = local.workspace_aws[\"region\"]\n"
            + "  assume_role {\n"
            + "    role_arn = \"arn:aws:iam::${local.workspace[\"aws\"][\"account_id\"]}:role/${local.workspace[\"aws\"][\"role\"]}\"\n"
            + "  }\n"
            + "}\n";

    @Test
    void namesTheSecondHopAndCopiesTheRoleArnExpressionVerbatim() {
        String out = TerraformOverride.overrideFor(REAL, SESSION);
        assertNotNull(out, "the real production shape must be overridable");
        assertTrue(out.contains("session_name = \"" + SESSION + "\""), out);
        assertTrue(
                out.contains(
                        "role_arn = \"arn:aws:iam::${local.workspace[\"aws\"][\"account_id\"]}:role/${local.workspace[\"aws\"][\"role\"]}\""),
                "the interpolated expression must survive character for character: " + out);
    }

    /**
     * The dangerous case. Without {@code role_arn} Terraform drops the assume and runs as the instance
     * role. If extraction ever fails, the correct output is no file at all.
     */
    @Test
    void writesNothingWhenRoleArnCannotBeExtracted() {
        String noRoleArn = "provider \"aws\" {\n  assume_role {\n    duration = \"1h\"\n  }\n}\n";
        assertNull(TerraformOverride.overrideFor(noRoleArn, SESSION), "no role_arn means no override");
    }

    @Test
    void leavesAnAdministratorsOwnSessionNameAlone() {
        String pinned = "provider \"aws\" {\n  assume_role {\n"
                + "    role_arn     = \"arn:aws:iam::1:role/x\"\n"
                + "    session_name = \"deliberate\"\n  }\n}\n";
        assertNull(TerraformOverride.overrideFor(pinned, SESSION), "a pinned session name is a deliberate choice");
    }

    @Test
    void leavesProvidersWithNoAssumeRoleAlone() {
        String plain = "provider \"aws\" {\n  region = \"us-east-1\"\n}\n";
        assertNull(TerraformOverride.overrideFor(plain, SESSION), "no second hop, nothing to name");
    }

    @Test
    void skipsAliasedProviders() {
        String aliased = "provider \"aws\" {\n  alias = \"other\"\n  assume_role {\n"
                + "    role_arn = \"arn:aws:iam::1:role/x\"\n  }\n}\n";
        assertNull(
                TerraformOverride.overrideFor(aliased, SESSION),
                "an override without the alias targets the wrong provider");
    }

    /** With several providers, picking one is a guess, and a wrong guess re-points an account. */
    @Test
    void skipsFilesWithMoreThanOneAwsProvider() {
        String two = "provider \"aws\" {\n  assume_role {\n    role_arn = \"arn:aws:iam::1:role/a\"\n  }\n}\n"
                + "provider \"aws\" {\n  alias = \"b\"\n  assume_role {\n    role_arn = \"arn:aws:iam::2:role/b\"\n  }\n}\n";
        assertNull(TerraformOverride.overrideFor(two, SESSION), "ambiguous: skip rather than guess");
    }

    @Test
    void ignoresProviderMentionedInsideAStringOrComment() {
        String decoy = "# provider \"aws\" { assume_role { role_arn = \"nope\" } }\n"
                + "variable \"x\" { default = \"provider \\\"aws\\\"\" }\n";
        assertNull(TerraformOverride.overrideFor(decoy, SESSION), "a comment is not a declaration");
    }

    @Test
    void handlesBracesInsideStringsWhenMatchingTheBlock() {
        String braces = "provider \"aws\" {\n"
                + "  region = \"us-east-1\"\n"
                + "  assume_role {\n"
                + "    role_arn = \"arn:aws:iam::${local.m[\"a\"]}:role/x\"\n"
                + "  }\n"
                + "}\n";
        String out = TerraformOverride.overrideFor(braces, SESSION);
        assertNotNull(out, "interpolation braces must not confuse block matching");
        assertTrue(out.contains("${local.m[\"a\"]}"), out);
    }

    /** A non-Terraform file, or an empty one, must simply produce nothing. */
    @Test
    void writesNothingForUnrelatedContent() {
        assertNull(TerraformOverride.overrideFor("", SESSION));
        assertNull(TerraformOverride.overrideFor("resource \"aws_s3_bucket\" \"b\" {}\n", SESSION));
    }
}
