package io.github.rads4.ckaws.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.FormValidation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Tests for Layer 0, the Jenkins-owned profile mapping.
 *
 * <p>{@link JenkinsRule} is genuinely required: what is under test is extension registration, form
 * data binding and configuration persistence.
 *
 * <p><b>On JCasC coverage.</b> There is no dependency on the configuration-as-code plugin here — every
 * available release transitively requires Jenkins 2.479.3, above this plugin's 2.479.2 baseline (see
 * the note in {@code pom.xml}). JCasC does not use a private API, so its three moving parts can be
 * asserted directly: it finds the configuration by its {@code @Symbol}, builds each list entry with
 * structs' {@link DescribableModel} from the YAML's nested maps, and pushes the result onto the
 * existing singleton through its {@code @DataBoundSetter}. All three are covered below.
 *
 * <p>What this therefore does <em>not</em> cover is JCasC's own YAML parsing and its
 * {@code unclassified} routing. Those are JCasC's code, not this plugin's, but it does mean a real
 * end-to-end YAML load remains untested until the baseline moves to 2.479.3+.
 */
@WithJenkins
class CkAwsGlobalConfigurationTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/non_prod";

    @Test
    void isRegisteredAndStartsEmpty(JenkinsRule j) {
        CkAwsGlobalConfiguration configuration = CkAwsGlobalConfiguration.get();

        assertNotNull(configuration, "global configuration should be registered as an extension");
        assertTrue(configuration.getProfiles().isEmpty(), "no profiles should be configured by default");
        assertTrue(configuration.configuredProfileNames().isEmpty());
    }

    @Test
    void resolvesAConfiguredProfile(JenkinsRule j) {
        CkAwsGlobalConfiguration configuration = configureWith(profile("non_prod", ROLE, "us-east-1"));

        Optional<AwsProfile> resolved = configuration.resolve("non_prod");

        assertTrue(resolved.isPresent());
        assertEquals(ROLE, resolved.get().getRoleArn());
        assertEquals("us-east-1", resolved.get().getRegion());
    }

    @Test
    void resolutionIsExactAndCaseSensitive(JenkinsRule j) {
        CkAwsGlobalConfiguration configuration = configureWith(profile("non_prod", ROLE, null));

        // Fail closed: a near-miss must not resolve to something. Assuming the wrong identity because a
        // name was nearly right is the failure mode this whole layer exists to prevent.
        assertTrue(configuration.resolve("NON_PROD").isEmpty(), "resolution should be case-sensitive");
        assertTrue(configuration.resolve("non_pro").isEmpty());
        assertTrue(configuration.resolve("prod").isEmpty());
        assertTrue(configuration.resolve(null).isEmpty());
        assertTrue(configuration.resolve("   ").isEmpty());
    }

    @Test
    void surroundingWhitespaceInALookupIsTolerated(JenkinsRule j) {
        CkAwsGlobalConfiguration configuration = configureWith(profile("non_prod", ROLE, null));

        assertTrue(configuration.resolve("  non_prod  ").isPresent());
    }

    @Test
    void halfConfiguredProfilesAreTreatedAsAbsent(JenkinsRule j) {
        CkAwsGlobalConfiguration configuration = configureWith(profile("no_arn", "", null), profile("", ROLE, null));

        assertTrue(configuration.resolve("no_arn").isEmpty(), "a profile without a role ARN must not resolve");
        assertTrue(configuration.configuredProfileNames().isEmpty());
    }

    @Test
    void listsConfiguredNamesForErrorMessages(JenkinsRule j) {
        CkAwsGlobalConfiguration configuration =
                configureWith(profile("non_prod", ROLE, null), profile("prod", ROLE, null));

        assertEquals(List.of("non_prod", "prod"), configuration.configuredProfileNames());
    }

    @Test
    void configurationSurvivesAFormRoundTrip(JenkinsRule j) throws Exception {
        configureWith(profile("non_prod", ROLE, "us-east-1"));

        // Exercises the jelly and the data binding the UI actually uses.
        j.configRoundtrip();

        CkAwsGlobalConfiguration reloaded = CkAwsGlobalConfiguration.get();
        assertNotNull(reloaded);
        assertEquals(1, reloaded.getProfiles().size());
        AwsProfile profile = reloaded.getProfiles().get(0);
        assertEquals("non_prod", profile.getName());
        assertEquals(ROLE, profile.getRoleArn());
        assertEquals("us-east-1", profile.getRegion());
    }

    @Test
    void anEmptyRegionIsStoredAsAbsentRatherThanBlank(JenkinsRule j) {
        // A blank region must not be exported as AWS_REGION="" - that is worse than not setting it,
        // because it overrides whatever the agent would otherwise have resolved.
        AwsProfile profile = new AwsProfile("non_prod", ROLE);
        profile.setRegion("   ");

        assertNull(profile.getRegion());
    }

    @Test
    void profileNamesAndArnsAreTrimmed(JenkinsRule j) {
        AwsProfile profile = new AwsProfile("  non_prod  ", "  " + ROLE + "  ");

        assertEquals("non_prod", profile.getName());
        assertEquals(ROLE, profile.getRoleArn());
        assertTrue(profile.isComplete());
    }

    // --- the JCasC-facing contract -------------------------------------------

    @Test
    void exposesTheSymbolsJcascAddressesItBy(JenkinsRule j) {
        // unclassified.ckAws.profiles in YAML resolves through these two symbols. Renaming either is a
        // breaking change to every JCasC file in the field, so it is pinned by a test.
        Symbol configurationSymbol = CkAwsGlobalConfiguration.class.getAnnotation(Symbol.class);
        assertNotNull(configurationSymbol, "the global configuration must carry a @Symbol for JCasC");
        assertEquals("ckAws", configurationSymbol.value()[0]);

        Symbol profileSymbol = AwsProfile.DescriptorImpl.class.getAnnotation(Symbol.class);
        assertNotNull(profileSymbol, "the profile descriptor must carry a @Symbol");
        assertEquals("awsProfile", profileSymbol.value()[0]);
    }

    @Test
    void bindsFromTheNestedMapsJcascProducesFromYaml(JenkinsRule j) throws Exception {
        // This is the shape JCasC hands to structs for:
        //   unclassified:
        //     ckAws:
        //       profiles:
        //         - name: "non_prod"
        //           roleArn: "arn:aws:iam::123456789012:role/non_prod"
        //           region: "us-east-1"
        Map<String, Object> yamlShapedProfile = new LinkedHashMap<>();
        yamlShapedProfile.put("name", "non_prod");
        yamlShapedProfile.put("roleArn", ROLE);
        yamlShapedProfile.put("region", "us-east-1");

        AwsProfile bound = DescribableModel.of(AwsProfile.class).instantiate(yamlShapedProfile);

        assertEquals("non_prod", bound.getName());
        assertEquals(ROLE, bound.getRoleArn());
        assertEquals("us-east-1", bound.getRegion());

        // A GlobalConfiguration is a singleton, so JCasC does not instantiate one: it looks up the
        // existing instance by symbol and pushes each attribute onto it through the @DataBoundSetter.
        // That setter is therefore the actual JCasC entry point, and it is what the rest of this test
        // exercises - with a value built by structs from the YAML shape above, so the whole chain from
        // 'what the YAML says' to 'what resolve() returns' is covered.
        Method setter = CkAwsGlobalConfiguration.class.getMethod("setProfiles", List.class);
        assertNotNull(
                setter.getAnnotation(DataBoundSetter.class),
                "setProfiles must be a @DataBoundSetter - it is how JCasC writes unclassified.ckAws.profiles");

        CkAwsGlobalConfiguration configuration = CkAwsGlobalConfiguration.get();
        assertNotNull(configuration);
        setter.invoke(configuration, List.of(bound));

        assertTrue(
                CkAwsGlobalConfiguration.get().resolve("non_prod").isPresent(),
                "configuring the way JCasC does should populate the mapping");
        assertEquals(
                ROLE, CkAwsGlobalConfiguration.get().resolve("non_prod").get().getRoleArn());
    }

    @Test
    void regionIsOptionalInTheBoundForm(JenkinsRule j) throws Exception {
        Map<String, Object> withoutRegion = new LinkedHashMap<>();
        withoutRegion.put("name", "sandbox");
        withoutRegion.put("roleArn", ROLE);

        AwsProfile bound = DescribableModel.of(AwsProfile.class).instantiate(withoutRegion);

        assertEquals("sandbox", bound.getName());
        assertNull(bound.getRegion(), "an omitted region must stay absent, not become empty string");
    }

    // --- form validation ------------------------------------------------------

    @Test
    void formValidation(JenkinsRule j) {
        AwsProfile.DescriptorImpl descriptor = j.jenkins.getDescriptorByType(AwsProfile.DescriptorImpl.class);
        assertNotNull(descriptor);

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckName("").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckName("non_prod").kind);

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckRoleArn("  ").kind);
        // Advisory, not an error: unanticipated ARN shapes must not be rejected by this plugin.
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckRoleArn("non_prod").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckRoleArn(ROLE).kind);
    }

    @Test
    void profileToStringCarriesNothingSensitive(JenkinsRule j) {
        // A role ARN is not a secret and there is nothing else here - but assert it, so that anyone
        // later adding a field has to think about this line.
        String rendered = profile("non_prod", ROLE, "us-east-1").toString();

        assertTrue(rendered.contains("non_prod"));
        assertFalse(rendered.toLowerCase().contains("secret"));
        assertFalse(rendered.toLowerCase().contains("token"));
    }

    // --- helpers --------------------------------------------------------------

    private static CkAwsGlobalConfiguration configureWith(AwsProfile... profiles) {
        CkAwsGlobalConfiguration configuration = CkAwsGlobalConfiguration.get();
        assertNotNull(configuration);
        configuration.setProfiles(List.of(profiles));
        return configuration;
    }

    private static AwsProfile profile(String name, String roleArn, String region) {
        AwsProfile profile = new AwsProfile(name, roleArn);
        profile.setRegion(region);
        return profile;
    }
}
