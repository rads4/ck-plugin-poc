package io.github.rads4.ckaws;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.PluginWrapper;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Milestone M0 smoke test: asserts the plugin is registered and active in a real Jenkins.
 *
 * <p>This is intentionally the only test that needs a running Jenkins. Auth and executor
 * logic added in later milestones must stay testable without {@link JenkinsRule}.
 */
@WithJenkins
class PluginLoadsTest {

    @Test
    void pluginIsInstalledAndActive(JenkinsRule j) {
        PluginWrapper plugin = j.jenkins.getPluginManager().getPlugin("ck-aws");
        assertNotNull(plugin, "ck-aws plugin should be installed");
        assertTrue(plugin.isActive(), "ck-aws plugin should be active");
    }
}
