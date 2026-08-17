package io.github.rads4.ckaws.managed;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Names the session for a Terraform provider's <em>own</em> {@code assume_role} — the "second hop".
 *
 * <p><b>The problem.</b> The generated AWS configuration names every role the shared config assumes,
 * which covers the CLI, boto3 and Terraform's default resolution. It does not reach a provider that
 * carries its own {@code assume_role} block: that block is a second assume, performed in-process from
 * the already-assumed session, and with no {@code session_name} in it the AWS Go SDK invents one —
 * {@code aws-go-sdk-<nanotime>}. Every call after that hop is recorded under the invented name, so a
 * CloudTrail lookup by the build's session finds the first hop and stops. Measured: no environment
 * variable reaches it, {@code AWS_ROLE_SESSION_NAME} included.
 *
 * <p><b>The mechanism.</b> Terraform merges any file matching {@code *_override.tf} in a working
 * directory over the configuration already there. Writing one at build time therefore fills in the
 * missing {@code session_name} <em>without modifying the repository</em> — the file lands in the
 * checked-out workspace, not in git.
 *
 * <p><b>The constraint that shapes every line below.</b> Terraform <em>replaces</em> a nested block
 * rather than merging its attributes. An override containing only {@code session_name} therefore
 * removes {@code role_arn} — and Terraform then skips the assume altogether and runs as the agent's
 * raw instance role: a different principal with different permissions, no error, only a deprecation
 * warning. Measured. So the override must carry {@code role_arn}, and it is copied <b>textually</b>
 * from the original: the real configurations compute it
 * ({@code "arn:aws:iam::${local.workspace["aws"]["account_id"]}:role/…"}) and any attempt to
 * reconstruct that value would eventually diverge from it silently.
 *
 * <p><b>Everything unrecognised is skipped, not guessed.</b> An aliased provider, a block that already
 * pins {@code session_name}, a {@code role_arn} that cannot be extracted cleanly — each yields no
 * file, and that directory behaves exactly as it does today. Losing attribution is acceptable;
 * changing which identity a build runs as is not.
 */
final class TerraformOverride {

    /** Written into a Terraform working directory. The {@code _override.tf} suffix is what makes Terraform merge it. */
    static final String FILE_NAME = "zz_ckaws_session_override.tf";

    /**
     * How deep below the workspace to look. Terraform working directories in these repositories sit at
     * the root or one or two levels down ({@code common/}, {@code application-setup/kong/}).
     */
    private static final int MAX_DEPTH = 4;

    /** Upper bound on directories examined, so a large checkout cannot turn this into a per-step cost. */
    private static final int MAX_DIRECTORIES = 400;

    private TerraformOverride() {}

    /**
     * Writes an override into every Terraform working directory below {@code workspace} that has an
     * unnamed second hop, and returns what was written.
     *
     * <p>Runs on the agent, because the workspace is the agent's filesystem. Called <em>lazily</em> —
     * repeatedly, as the build proceeds — rather than once at preparation time, because the {@code .tf}
     * files do not exist until the job has checked its repository out, which happens well after the
     * first step. Rewriting an identical file is harmless; not writing it at all would be silent.
     *
     * @return the absolute paths written, for the caller to register for cleanup
     */
    @NonNull
    static List<String> applyTo(@NonNull hudson.FilePath workspace, @NonNull String sessionName)
            throws java.io.IOException, InterruptedException {
        return workspace.act(new WriteOverrides(sessionName));
    }

    /** Scans and writes on the node that owns the workspace. */
    private static final class WriteOverrides extends jenkins.MasterToSlaveFileCallable<List<String>> {

        private static final long serialVersionUID = 1L;

        private final String sessionName;

        WriteOverrides(String sessionName) {
            this.sessionName = sessionName;
        }

        @Override
        public List<String> invoke(java.io.File base, hudson.remoting.VirtualChannel channel) {
            List<String> written = new ArrayList<>();
            List<java.io.File> directories = new ArrayList<>();
            collect(base, 0, directories);
            for (java.io.File dir : directories) {
                java.io.File[] tf = dir.listFiles((d, n) -> n.endsWith(".tf") && !n.equals(FILE_NAME));
                if (tf == null) {
                    continue;
                }
                for (java.io.File file : tf) {
                    String content;
                    try {
                        content = new String(
                                java.nio.file.Files.readAllBytes(file.toPath()),
                                java.nio.charset.StandardCharsets.UTF_8);
                    } catch (java.io.IOException e) {
                        continue; // unreadable: leave this directory exactly as it is
                    }
                    String override = overrideFor(content, sessionName);
                    if (override == null) {
                        continue;
                    }
                    java.io.File target = new java.io.File(dir, FILE_NAME);
                    try {
                        java.nio.file.Files.write(
                                target.toPath(), override.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        written.add(target.getAbsolutePath());
                    } catch (java.io.IOException e) {
                        // A read-only or vanished directory is not worth failing a build over.
                    }
                    break; // one provider block per directory is the shape these repositories use
                }
            }
            return written;
        }

        /** Bounded walk. {@code .terraform/} holds downloaded modules, which are not ours to touch. */
        private void collect(java.io.File dir, int depth, List<java.io.File> out) {
            if (depth > MAX_DEPTH || out.size() >= MAX_DIRECTORIES) {
                return;
            }
            out.add(dir);
            java.io.File[] children = dir.listFiles();
            if (children == null) {
                return;
            }
            for (java.io.File child : children) {
                String name = child.getName();
                if (child.isDirectory() && !name.equals(".terraform") && !name.equals(".git")) {
                    collect(child, depth + 1, out);
                }
            }
        }
    }

    /**
     * The override file for a provider block, or {@code null} if this file should be left alone.
     *
     * @param tf contents of a {@code .tf} file
     * @param sessionName the build's {@code jk-<job>-<build>}
     * @return file content to write, or {@code null} when nothing here can be safely overridden
     */
    @CheckForNull
    static String overrideFor(@NonNull String tf, @NonNull String sessionName) {
        List<String> blocks = awsProviderBlocks(tf);
        if (blocks.size() != 1) {
            // Zero: nothing to do. More than one: which is the default provider becomes a judgement
            // call, and a wrong guess re-points a provider at another account. Skip.
            return null;
        }
        String provider = blocks.get(0);
        if (containsAttribute(provider, "alias")) {
            return null; // an aliased provider is not the default one; overriding it needs the alias too
        }
        String assumeRole = nestedBlock(provider, "assume_role");
        if (assumeRole == null || containsAttribute(assumeRole, "session_name")) {
            // No second hop, or an administrator already pinned the name. Either way, not ours to change.
            return null;
        }
        String roleArn = attributeLine(assumeRole, "role_arn");
        if (roleArn == null) {
            return null; // cannot reproduce the block faithfully, so do not write one
        }
        return "# Generated by the ck-aws Jenkins plugin. Names this provider's own assume_role so that\n"
                + "# calls made after that hop are attributed to the build. Safe to delete.\n"
                + "provider \"aws\" {\n"
                + "  assume_role {\n"
                + "    " + roleArn.trim() + "\n"
                + "    session_name = \"" + sessionName + "\"\n"
                + "  }\n"
                + "}\n";
    }

    /** Every {@code provider "aws" { … }} block in the file, brace-matched. */
    private static List<String> awsProviderBlocks(String tf) {
        List<String> found = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = indexOfProvider(tf, from);
            if (start < 0) {
                return found;
            }
            int open = tf.indexOf('{', start);
            if (open < 0) {
                return found;
            }
            int close = matchingBrace(tf, open);
            if (close < 0) {
                return found;
            }
            found.add(tf.substring(open + 1, close));
            from = close + 1;
        }
    }

    /** Start of a {@code provider "aws"} declaration, tolerating whitespace variations. */
    private static int indexOfProvider(String tf, int from) {
        for (int i = from; i >= 0; ) {
            int at = tf.indexOf("provider", i);
            if (at < 0) {
                return -1;
            }
            String rest = tf.substring(at + "provider".length());
            String trimmed = rest.stripLeading();
            if ((trimmed.startsWith("\"aws\"") || trimmed.startsWith("'aws'")) && isDeclarationStart(tf, at)) {
                return at;
            }
            i = at + 1;
        }
        return -1;
    }

    /** Only a {@code provider} at the start of a line is a declaration; elsewhere it is a word in a string. */
    private static boolean isDeclarationStart(String tf, int at) {
        for (int i = at - 1; i >= 0; i--) {
            char c = tf.charAt(i);
            if (c == '\n') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    /** Index of the {@code }} matching the {@code {} at {@code open}, or -1. Ignores braces in strings and comments. */
    private static int matchingBrace(String s, int open) {
        int depth = 0;
        boolean inString = false;
        boolean inComment = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inComment) {
                if (c == '\n') {
                    inComment = false;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '#' || (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/')) {
                inComment = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The body of a nested block such as {@code assume_role { … }}, or {@code null}. */
    @CheckForNull
    private static String nestedBlock(String body, String name) {
        for (int i = 0; i < body.length(); ) {
            int at = body.indexOf(name, i);
            if (at < 0) {
                return null;
            }
            int open = body.indexOf('{', at);
            if (open < 0) {
                return null;
            }
            // Only a block header: nothing but whitespace between the name and its brace.
            if (body.substring(at + name.length(), open).isBlank() && isWordStart(body, at)) {
                int close = matchingBrace(body, open);
                return close < 0 ? null : body.substring(open + 1, close);
            }
            i = at + 1;
        }
        return null;
    }

    private static boolean isWordStart(String s, int at) {
        return at == 0 || !(Character.isLetterOrDigit(s.charAt(at - 1)) || s.charAt(at - 1) == '_');
    }

    /** Whether {@code name} appears as an attribute assignment at this level. */
    private static boolean containsAttribute(String body, String name) {
        return attributeLine(body, name) != null;
    }

    /**
     * The whole {@code name = …} line, verbatim, or {@code null}.
     *
     * <p>Returned as written so an interpolated expression survives untouched — reproducing it by any
     * other means is what would eventually diverge from the repository.
     */
    @CheckForNull
    private static String attributeLine(String body, String name) {
        for (String line : body.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }
            if (!trimmed.startsWith(name)) {
                continue;
            }
            String after = trimmed.substring(name.length()).stripLeading();
            if (after.startsWith("=")) {
                return trimmed;
            }
        }
        return null;
    }
}
