package io.github.rads4.ckaws.managed;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.WorkspaceList;
import io.github.rads4.ckaws.auth.SessionName;
import io.github.rads4.ckaws.config.AwsProfile;
import io.github.rads4.ckaws.config.CkAwsGlobalConfiguration;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.SystemProperties;
import org.jenkinsci.plugins.workflow.steps.DynamicContext;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;

/**
 * Managed Authentication: the plugin decorates the authentication a build was already going to use.
 *
 * <p>Jenkins consults a {@link DynamicContext} whenever a step needs a context value it has not been
 * given, and step environment is computed through {@link EnvironmentExpander}. Contributing one here
 * therefore reaches <em>every</em> step of every Pipeline — shared libraries, {@code parallel}
 * branches, {@code retry} attempts, second {@code node} blocks on other agents — with nothing to add to
 * a Jenkinsfile and nothing for anyone to remember.
 *
 * <p><b>What it does.</b> It reads the executing node's own AWS configuration, copies it into the
 * build's private temporary directory with {@code role_session_name = jk-<job>-<build>} added to
 * profiles that assume a role, and points {@code AWS_CONFIG_FILE} at the copy. Role ARNs, base
 * identities, chained profiles and regions are the node's; the plugin contributes a label and nothing
 * else.
 *
 * <p><b>What it deliberately does not do.</b> It does not set
 * {@code AWS_SHARED_CREDENTIALS_FILE} — measured: pointing that at a plugin-owned file breaks any
 * profile that chains through {@code source_profile} to a credentials-file profile, with
 * <em>"The source_profile … does not exist"</em>. It exports no credentials. It never authenticates on
 * the build's behalf.
 *
 * <p><b>Fail-safe is the governing property.</b> Missing configuration, an unreadable file, no
 * {@code HOME}, a workspace that cannot be written, an unexpected exception — every one of them
 * results in contributing nothing, and the build authenticates exactly as it does today. Losing
 * attribution is acceptable; failing a deployment is not.
 *
 * <p><b>The hot path does no I/O.</b> Jenkins recomputes dynamic context on every query and never
 * caches the result, so everything after the first step of a {@code node} block is a map lookup.
 */
@Extension
public final class ManagedAwsContext extends DynamicContext.Typed<EnvironmentExpander> {

    private static final Logger LOGGER = Logger.getLogger(ManagedAwsContext.class.getName());

    /** Name of the generated directory inside the build's private temporary directory. */
    static final String DIRECTORY_NAME = "ck-aws";

    static final String CONFIG_FILE = "config";

    /** Where a node keeps its AWS configuration, unless {@code AWS_CONFIG_FILE} says otherwise. */
    private static final String DEFAULT_CONFIG_RELATIVE_PATH = ".aws/config";

    /**
     * Overrides the node configuration path. A test hook, in the same spirit as the existing
     * {@code io.github.rads4.ckaws.awsExecutable} property: {@link Computer#getEnvironment()} reports the
     * agent process's real operating-system environment, which is exactly what production needs and
     * exactly what a test cannot fake from inside the same JVM.
     */
    private static final String CONFIG_PATH_PROPERTY = "io.github.rads4.ckaws.nodeConfigFile";

    /**
     * Prints what the plugin discovered to the build console.
     *
     * <p>Set {@code -Dio.github.rads4.ckaws.diagnostics=true} on the controller when a build
     * authenticates as something unexpected. Everything printed is non-sensitive: environment variable
     * names and values that are file paths and profile names, section headers, and the session name. No
     * file content, no credentials.
     */
    private static final String DIAGNOSTICS_PROPERTY = "io.github.rads4.ckaws.diagnostics";

    /**
     * Keyed by {@code run id + workspace}, so a second {@code node} block on another agent prepares its
     * own copy while every later step in the same workspace is a lookup. Evicted when the build
     * finalizes; leaving entries would leak one per node block, forever.
     */
    private static final Map<String, Map<String, String>> PREPARED = new ConcurrentHashMap<>();

    /** The variable a build's AWS SDKs read the generated configuration from. */
    static final String CONFIG_VARIABLE = "AWS_CONFIG_FILE";

    /** The build's session name, exported so a script can log or tag with it. */
    static final String SESSION_VARIABLE = "CK_AWS_SESSION_NAME";

    /**
     * One monitor per prepared key, so concurrent branches sharing a workspace prepare it once between
     * them rather than racing to write the same file. Evicted with {@link #PREPARED}.
     */
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    @Override
    protected Class<EnvironmentExpander> type() {
        return EnvironmentExpander.class;
    }

    /**
     * The whole contribution path, guarded.
     *
     * <p>Everything is inside {@link #guarded}: the configuration lookup, every
     * {@link DelegatedContext#get} — each of which is declared to throw {@link IOException} — and the
     * decoration itself. An earlier version guarded only the decoration, which left four statements and
     * the whole of {@link Error} outside the net; anything escaping here propagates through
     * {@code ContextVariableSet} into the step and fails the build, which is the one outcome this
     * feature must never cause.
     */
    @Override
    @CheckForNull
    protected EnvironmentExpander get(DelegatedContext context) throws IOException, InterruptedException {
        return guarded(() -> contribute(context));
    }

    /**
     * Runs a contribution attempt and swallows everything except an interruption.
     *
     * <p>{@link Throwable}, not {@link Exception}: a {@link LinkageError} or {@link NoClassDefFoundError}
     * after an upgrade would otherwise take out every step on the controller, not one build. Catching
     * {@link Error} is normally poor practice — here the alternative is failing deployments to protect a
     * JVM that is already unwell, and the plugin's contribution is optional by definition.
     *
     * <p>{@link InterruptedException} is re-thrown deliberately: an aborted build must stay aborted.
     *
     * <p>This path writes <b>nothing</b> to the build console. It is the last resort, and it is reached
     * in states where attempting console I/O could itself throw. Expected, diagnosable failures are
     * reported to the console one level in, by {@link #contribute}.
     */
    @CheckForNull
    static <T> T guarded(Attempt<T> attempt) throws InterruptedException {
        try {
            return attempt.run();
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            LOGGER.log(
                    Level.WARNING,
                    t,
                    () -> "ck-aws: contributing nothing; this build authenticates exactly as it would "
                            + "have without the plugin");
            return null;
        }
    }

    /**
     * One attempt to contribute. Package-private so the guard can be tested directly.
     *
     * <p>Generic because Pipeline and Freestyle builds need different wrappers around the same
     * variables, and the fail-open guard is the last thing that should be duplicated between them.
     */
    @FunctionalInterface
    interface Attempt<T> {
        @CheckForNull
        T run() throws Exception;
    }

    @CheckForNull
    private EnvironmentExpander contribute(DelegatedContext context) throws IOException, InterruptedException {
        CkAwsGlobalConfiguration configuration = CkAwsGlobalConfiguration.get();
        if (configuration == null || !configuration.isManagedAuthentication()) {
            return null;
        }

        Run<?, ?> run = context.get(Run.class);
        if (run == null || !configuration.appliesTo(run.getParent().getFullName())) {
            return null;
        }

        // No workspace means no agent, which means no process that could consume this.
        FilePath current = context.get(FilePath.class);
        if (current == null) {
            return null;
        }

        Node node = context.get(Node.class);
        if (!configuration.appliesToNode(labelsOf(node))) {
            return null;
        }

        Map<String, String> variables = prepareOnce(
                run,
                buildWorkspace(current, node, run),
                node,
                context.get(Computer.class),
                configuration,
                context.get(TaskListener.class));
        if (variables == null) {
            return null;
        }

        // Observe only: everything above ran - the configuration was read, decorated, safety-checked and
        // written, and the console says what would have happened - but nothing is exported. A build in
        // this mode cannot observe the plugin, so it cannot be broken by it. See
        // CkAwsGlobalConfiguration#isObserveOnly for why this exists.
        if (configuration.isObserveOnly()) {
            return null;
        }

        // Contribute ALONGSIDE what the build already has, never instead of it.
        //
        // A DynamicContext that answers unconditionally short-circuits the walk to the enclosing
        // context level: ContextVariableSet.get scans the current level, then consults every
        // DynamicContext, and only then recurses to its parent. So an EnvironmentExpander published by
        // an enclosing withCredentials/withEnv/withAWS block is never reached the moment any inner
        // block - dir, ws, container - adds a context level of its own, and its bindings vanish with
        // no error. Measured in production: dev2/rivon #942 and #944 lost their Nexus credentials to
        // exactly this, while #943 with the job out of scope passed.
        //
        // Order is load-bearing: ours expands FIRST so anything the build set deliberately wins. This
        // plugin decorates the node's default, and a value a job chose is not a default.
        EnvironmentExpander ours = EnvironmentExpander.constant(variables);
        EnvironmentExpander existing = context.get(EnvironmentExpander.class);
        // merge() null-checks only its first argument; passing null as the second NPEs on expand.
        return existing == null ? ours : EnvironmentExpander.merge(ours, existing);
    }

    /**
     * The workspace the generated file belongs to: the build's, not whatever directory a step is in.
     *
     * <p>{@link DelegatedContext#get} for a {@link FilePath} returns the <em>current</em> directory, so
     * inside {@code dir('application/service')} it is a directory in the middle of a checked-out source
     * tree. Writing there scatters {@code ck-aws/} through the source, prepares the same file once per
     * {@code dir} block, and - when a step changes into a directory outside the workspace - leaves a
     * directory on the agent that no workspace cleanup will ever reclaim.
     *
     * <p>The canonical workspace is used only when the current directory is genuinely inside it. That
     * distinction is what keeps the other shapes correct: {@code ws('other')} allocates a workspace that
     * is not under this job's, and a second concurrent build gets {@code …/job@2}, which is not under
     * {@code …/job} either. Both fall back to the path they were given, which is the right answer for
     * them.
     */
    @CheckForNull
    static FilePath buildWorkspace(@CheckForNull FilePath current, @CheckForNull Node node, Run<?, ?> run) {
        if (current == null || node == null || !(run.getParent() instanceof hudson.model.TopLevelItem)) {
            return current;
        }
        FilePath workspace = node.getWorkspaceFor((hudson.model.TopLevelItem) run.getParent());
        if (workspace == null) {
            return current;
        }
        String here = current.getRemote();
        String root = workspace.getRemote();
        boolean inside = here.equals(root) || here.startsWith(root + "/") || here.startsWith(root + "\\");
        return inside ? workspace : current;
    }

    /**
     * Prepares the build's configuration at most once per build and workspace, and returns the variables.
     *
     * <p>Memoized because both callers ask repeatedly: Jenkins recomputes dynamic context on every step,
     * and an environment contributor runs every time a build computes its environment. Without this,
     * each query would rewrite the file. A second {@code node} block on another agent has a different
     * key and prepares its own copy, which is the intended behaviour.
     *
     * <p>Shared by the Pipeline and Freestyle paths so the two cannot drift.
     */
    @CheckForNull
    static Map<String, String> prepareOnce(
            Run<?, ?> run,
            FilePath workspace,
            @CheckForNull Node node,
            @CheckForNull Computer computer,
            CkAwsGlobalConfiguration configuration,
            @CheckForNull TaskListener listener)
            throws InterruptedException {

        String key = key(run, workspace);
        // Exactly one preparation per key, even when parallel branches sharing a workspace arrive at the
        // same instant. Both would otherwise miss the memo and write the same path concurrently, and a
        // third reader can then see a half-written file - which an AWS SDK rejects as malformed, failing
        // every call in that branch. putIfAbsent alone does not prevent this: it deduplicates the memo
        // entry after the fact, long after both writes have already happened.
        Object lock = LOCKS.computeIfAbsent(key, unused -> new Object());
        synchronized (lock) {
            Map<String, String> prepared = PREPARED.get(key);
            if (prepared != null && stillOnDisk(prepared, node)) {
                return prepared;
            }
            try {
                prepared = prepareVariables(run, workspace, node, computer, configuration, listener);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                // An expected, diagnosable failure: a disk or permission problem on one node must not
                // become an outage across every job. Reported to the console so the loss is visible.
                LOGGER.log(Level.WARNING, e, () -> "ck-aws: could not decorate AWS configuration for " + run);
                warn(listener, "could not read or write AWS configuration (" + e.getMessage() + ")");
                PREPARED.remove(key);
                return null;
            }
            if (prepared == null) {
                PREPARED.remove(key);
                return null;
            }
            PREPARED.put(key, prepared);
            return prepared;
        }
    }

    /**
     * Whether the generated file is still where the memo says it is.
     *
     * <p>{@code cleanWs()} and {@code deleteDir()} in the middle of a build are ordinary idioms, and
     * either removes the generated file while the memo keeps handing out its path for every later step.
     * An AWS SDK reads a missing {@code AWS_CONFIG_FILE} as an <em>empty</em> configuration rather than
     * an error, so {@code --profile non_prod} then fails with "The config profile could not be found" -
     * nothing thrown, nothing logged, and a deployment that stops for a reason nobody can see.
     *
     * <p>One stat per step on an already-open channel, in exchange for turning a silent failure into a
     * regenerated file.
     */
    private static boolean stillOnDisk(Map<String, String> prepared, @CheckForNull Node node)
            throws InterruptedException {
        String path = prepared.get(CONFIG_VARIABLE);
        if (path == null || node == null) {
            return true;
        }
        try {
            FilePath file = node.createPath(path);
            return file != null && file.exists();
        } catch (IOException | RuntimeException e) {
            // An agent that cannot be reached cannot be repaired by regenerating. Keep what we have and
            // let the build fail on its own terms rather than losing the configuration to a transient.
            LOGGER.log(Level.FINE, e, () -> "ck-aws: could not confirm " + path + " still exists");
            return true;
        }
    }

    /**
     * Generates the build's AWS configuration and returns the variables that point at it.
     *
     * <p>Shared by both build types on purpose. Pipeline contributes these through an
     * {@link EnvironmentExpander}; Freestyle through an {@link hudson.model.Environment}. Only the
     * wrapper differs — the file, the decoration, the safety check and the cleanup record are one
     * implementation, so the two can never drift apart.
     *
     * @return the variables to export, or {@code null} to contribute nothing
     */
    @CheckForNull
    static Map<String, String> prepareVariables(
            Run<?, ?> run,
            FilePath workspace,
            @CheckForNull Node node,
            @CheckForNull Computer computer,
            CkAwsGlobalConfiguration configuration,
            @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {

        FilePath nodeConfigFile = locateNodeConfig(node, computer);
        String nodeConfig = "";
        if (nodeConfigFile != null && nodeConfigFile.exists()) {
            nodeConfig = nodeConfigFile.readToString();
        }

        List<AwsProfile> overrides = configuration.usableProfiles();
        if (nodeConfig.isEmpty() && overrides.isEmpty()) {
            // Nothing to decorate and nothing to add. Contributing an empty configuration could only
            // take capability away from the build, so contribute nothing at all.
            warn(listener, "no AWS configuration found on this node and no fallback profiles configured");
            return null;
        }

        String sessionName = SessionName.forBuild(run.getParent().getFullName(), run.getNumber())
                .value();
        AwsConfigOverlay.Result result = AwsConfigOverlay.describe(
                nodeConfig,
                sessionName,
                overrides,
                configuration.getCredentialSource(),
                configuration.getUnprofiledRoleArn());
        String decorated = result.content();

        // The guard around this method catches exceptions; it cannot catch "produced a wrong file
        // successfully". Exporting such a file would fail every AWS call in the build with nothing
        // thrown. Verify the transform's own contract — additions only — and contribute nothing if it
        // does not hold.
        java.util.Optional<String> defect = AwsConfigOverlay.validate(nodeConfig, decorated);
        if (defect.isPresent()) {
            LOGGER.log(
                    Level.WARNING,
                    () -> "ck-aws: refusing to export a generated configuration for " + run + ": " + defect.get());
            warn(listener, "generated AWS configuration failed its own safety check (" + defect.get() + ")");
            return null;
        }

        FilePath temporaryDirectory = WorkspaceList.tempDir(workspace);
        if (temporaryDirectory == null) {
            throw new IOException("no temporary directory is available for " + workspace);
        }
        FilePath directory = temporaryDirectory.child(DIRECTORY_NAME);
        directory.mkdirs();
        directory.chmod(0700);
        FilePath config = directory.child(CONFIG_FILE);
        config.write(decorated, "UTF-8");
        config.chmod(0600);

        ManagedAwsRecord.record(run, node, directory);

        if (listener != null) {
            listener.getLogger()
                    .println("[ck-aws] "
                            + (configuration.isObserveOnly()
                                    ? "OBSERVE ONLY, nothing exported: would decorate as session "
                                    : "AWS configuration decorated as session ")
                            + sessionName
                            + (nodeConfigFile == null ? "" : " (from " + nodeConfigFile.getRemote() + ")")
                            + (result.unprofiledAttributed() ? "; calls naming no profile are attributed too" : ""));
            if (configuration.isDiagnostics() || SystemProperties.getBoolean(DIAGNOSTICS_PROPERTY)) {
                diagnose(listener, computer, nodeConfigFile, nodeConfig, result, config, sessionName);
            }
        }

        // AWS_SHARED_CREDENTIALS_FILE is deliberately absent: see the class javadoc.
        return Map.of(CONFIG_VARIABLE, config.getRemote(), SESSION_VARIABLE, sessionName);
    }

    /**
     * Prints what was discovered and what was changed. Diagnostics only; never enabled by default.
     *
     * <p>Deliberately prints the <em>node's</em> AWS environment rather than the build's: what matters
     * when a build authenticates unexpectedly is what the plugin saw before it changed anything.
     */
    private static void diagnose(
            TaskListener listener,
            @CheckForNull Computer computer,
            @CheckForNull FilePath nodeConfigFile,
            String nodeConfig,
            AwsConfigOverlay.Result result,
            FilePath writtenTo,
            String sessionName) {
        java.io.PrintStream out = listener.getLogger();
        out.println("[ck-aws] --- diagnostics ---");
        EnvVars environment = new EnvVars();
        if (computer != null) {
            try {
                environment = computer.getEnvironment();
            } catch (IOException | InterruptedException e) {
                out.println("[ck-aws]   node environment          : UNREADABLE (" + e.getMessage() + ")");
            }
        }
        for (String variable : List.of("HOME", "AWS_CONFIG_FILE", "AWS_SHARED_CREDENTIALS_FILE", "AWS_PROFILE")) {
            String value = environment.get(variable);
            out.println("[ck-aws]   node " + pad(variable) + ": " + (value == null ? "<unset>" : value));
        }
        out.println("[ck-aws]   " + pad("resolved config path") + ": "
                + (nodeConfigFile == null ? "<none: no HOME and no AWS_CONFIG_FILE>" : nodeConfigFile.getRemote()));
        out.println("[ck-aws]   " + pad("config read") + ": "
                + (nodeConfig.isEmpty() ? "empty or absent" : nodeConfig.length() + " bytes"));
        out.println("[ck-aws]   " + pad("sections found") + ": " + result.sectionsFound());
        out.println("[ck-aws]   " + pad("sections decorated") + ": " + result.sectionsDecorated());
        out.println("[ck-aws]   " + pad("sections appended") + ": " + result.sectionsAppended());
        out.println("[ck-aws]   " + pad("written to") + ": " + writtenTo.getRemote());
        out.println("[ck-aws]   " + pad("exported AWS_CONFIG_FILE") + ": " + writtenTo.getRemote());
        out.println("[ck-aws]   " + pad("exported CK_AWS_SESSION_NAME") + ": " + sessionName);
        out.println("[ck-aws]   " + pad("AWS_SHARED_CREDENTIALS_FILE") + ": not set by this plugin");
        out.println("[ck-aws] --- end diagnostics ---");
    }

    private static String pad(String label) {
        return label.length() >= 30 ? label : label + " ".repeat(30 - label.length());
    }

    /**
     * Finds the AWS configuration the executing node itself would use.
     *
     * <p>Portable by construction: it asks the node for its own environment rather than assuming a user,
     * a path, or a cloud. An explicit {@code AWS_CONFIG_FILE} on the node wins, because a node that has
     * been told where its configuration lives has already answered this question; otherwise
     * {@code $HOME/.aws/config}, which is what every AWS SDK would have used.
     */
    @CheckForNull
    private static FilePath locateNodeConfig(@CheckForNull Node node, @CheckForNull Computer computer) {
        String override = SystemProperties.getString(CONFIG_PATH_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            return node == null ? null : node.createPath(override.trim());
        }
        if (node == null || computer == null) {
            return null;
        }
        EnvVars environment;
        try {
            environment = computer.getEnvironment();
        } catch (IOException | InterruptedException e) {
            LOGGER.log(Level.FINE, e, () -> "ck-aws: could not read the environment of " + computer);
            return null;
        }
        String explicit = environment.get("AWS_CONFIG_FILE");
        if (explicit != null && !explicit.trim().isEmpty()) {
            return node.createPath(explicit.trim());
        }
        String home = environment.get("HOME");
        if (home == null || home.trim().isEmpty()) {
            return null;
        }
        FilePath root = node.createPath(home.trim());
        return root == null ? null : root.child(DEFAULT_CONFIG_RELATIVE_PATH);
    }

    /**
     * The executing node's label strings.
     *
     * <p>The built-in node reports an empty label set, so a controller-run build is matched on its node
     * name instead — otherwise scoping by label could never include it, and the controller runs builds.
     */
    @NonNull
    private static java.util.Set<String> labelsOf(@CheckForNull Node node) {
        if (node == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> labels = new java.util.LinkedHashSet<>();
        node.getAssignedLabels().forEach(label -> labels.add(label.getName()));
        String name = node.getNodeName();
        labels.add(name.isEmpty() ? "built-in" : name);
        return labels;
    }

    private static void warn(@CheckForNull TaskListener listener, String detail) {
        if (listener != null) {
            listener.getLogger()
                    .println("[ck-aws] " + detail
                            + "; this build will authenticate exactly as it does today, without build attribution.");
        }
    }

    /** Drops every memo entry for a finished build. Called from {@link ManagedCleanupListener}. */
    static void forget(@NonNull Run<?, ?> run) {
        String prefix = run.getExternalizableId() + ' ';
        PREPARED.keySet().removeIf(key -> key.startsWith(prefix));
        // Leaving these would leak one monitor per node block of every build the controller ever ran.
        LOCKS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Visible for tests: how many builds currently hold prepared state. */
    static int preparedCount() {
        return PREPARED.size();
    }

    /** Visible for tests: the keys currently held, for leak diagnosis. */
    static List<String> preparedKeys() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(PREPARED.keySet()));
    }

    private static String key(Run<?, ?> run, FilePath workspace) {
        return run.getExternalizableId() + ' ' + workspace.getRemote();
    }
}
