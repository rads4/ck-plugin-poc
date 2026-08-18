# ck-aws — session memory

> **READ THIS BLOCK FIRST.** Everything below it is session-by-session history, oldest
> first, and it is long. This block is the current state; consult the history only when
> you need the reasoning behind a specific decision.

## Current state — 2026-08-18

| | |
|---|---|
| **On CK production (infra Jenkins)** | ck-aws **2.1**, master switch **OFF**. Nothing is audited today. Infra has never been restarted, reconfigured or installed to during this work |
| **THE INFRA RELEASE** | **2.2.0** — 239 tests, five adversarial review passes, `sha256 f2d3a59e…`, `Implementation-Build 1bf157e…`. The 2.1.x line (2.1.1–2.1.8) was POC iteration and is spent |
| **Defaults** | `managedAuthentication = false`, `observeOnly = true`. Observe-only has **no effect** until the master switch is on — verified in source, not assumed |
| **Versioning** | `major.minor.patch`. A plain `mvn verify` yields `-SNAPSHOT (private-…)`, deliberately not installable; a release needs `mvn -Dchangelist= clean verify` |
| **POC clone** | `poc-jenkins-2`, `i-0cdd407bce366be0f` — running the release binary, left at the shipping defaults: managed auth **off**, observe-only **on** |
| ⚠️ **Before installing** | Read the **PRE-INSTALL CHECKLIST** at the top of CLAUDE.md |

### Coverage, as measured

| Job type / mechanism | Evidence |
|---|---|
| SCM Pipeline (622) | `dev2/fluentd` — 35 CloudTrail events on 2.2; unprofiled `ecr get-login-password` attributed |
| Inline Pipeline (111) | `CodeArtifact-PoC` — `GetAuthorizationToken`; also proved `sh()` inside `environment{}` |
| Freestyle (69) | `ckaws-canary-freestyle-master` — cross-account `jk-` in two accounts |
| Terraform | `cln-app-terraform-pipeline` — 10 AssumeRole events, **all** `jk-` |
| AWS CLI / boto3 | both, profiled and unprofiled |
| Canaries | 14/14 green |

### The one live gap

`qa-virtuoso-resource-creation` (runs daily) assumes a role explicitly and **exports the credentials as
environment variables**, which outrank `AWS_CONFIG_FILE` in every AWS SDK. Measured exactly: calls
before the assume are attributed, the `AssumeRole` itself is attributed to the build, calls after are
not — but remain one join away. **One line in that repo fixes it**; no plugin can. Every other job
previously listed as a gap is disabled, dormant 1100+ days, or has never run.

### What 2.2 contains beyond 2.1

1. Four defect fixes — context shadowing (the rivon defect), workspace anchoring, stale
   memo after a mid-build clean, parallel write race
2. **Runtime additions-only invariant on the environment** — the layer that makes
   *unwritten* jobs safe, not just known ones
3. **Observe-only mode** — prepare, decorate, validate, report, export nothing
4. **Per-node unprofiled attribution** — each node's own instance role, resolved over
   IMDS, with a real `sts:AssumeRole` probe first so a node that may not self-assume is
   left working-and-unattributed rather than broken
5. **`AWS_ROLE_SESSION_NAME` exported** — best-effort fix for the Terraform second hop

### Proven

- 13 canaries green on the clone; 6 real production agent AMIs, 5/5 each
- Every context shape covering ~99% of 46,446 production `sh` calls, incl. all 19
  `withCredentials`+`dir` variants and `withSonarQubeEnv`
- All three `~/.aws/config` shapes; three `aws` CLI versions; two architectures
- **Audit works**: 7 real `jk-<job>-<build>` sessions in CloudTrail from POC builds
- **Fail-safe works on a real node**: an agent whose role AWS refuses to let self-assume
  got no `[default]`, stayed unattributed, and its build still passed
- Complete *static* coverage: 490 pipeline files across every branch of 20 Bitbucket
  repos contain **no** JVM-side AWS step, so nothing bypasses `AWS_CONFIG_FILE`
- **A real production job, end to end**: `dev2/fluentd #119` deployed for real from the
  clone and produced **35 CloudTrail events across two accounts, all `jk-dev2-fluentd-119`**
  — closing the SCM-backed-pipeline path (639 jobs) and exercising *both* attribution
  routes in one build (decorated profile, and profile-less ECR via `[default]` self-assume)

### Not proven / known open

- **Terraform second hop — SOLVED 2026-08-17 (addendum 6), was previously recorded as unfixable.** The
  provider ignores `AWS_ROLE_SESSION_NAME` and generates `aws-go-sdk-<nanotime>`.
  Affects **3 of 802 jobs**; their post-hop calls are traceable only transitively, via
  the `jk-` session CloudTrail records as the *caller* of that AssumeRole
- Untested job types: a **Freestyle job that really calls AWS**, and an **inline
  Pipeline that really calls AWS** (`cloud-cost-ck` ran green but makes no AWS calls at
  all, so it produced no audit evidence)
- Nodes whose role AWS will not let self-assume stay unattributed — fail-safe, by design
- **No central reporting exists.** Observe-only records to the build console only; see
  addendum 9 for the two-mechanism design (Jenkins log recorder + CloudTrail session-name
  buckets), deferred until after the infra install

### Standing constraints

Infra Jenkins is installed to exactly **once**, at the end. No repo changes, ever — the
plugin must attribute without any job being edited. No Terraform runs against real AWS
from the POC. Never print secrets.

---

# MEMORY.md

Session-by-session implementation log. Architecture lives in [CLAUDE.md](CLAUDE.md) —
do not duplicate it here.

---

## Session 1 — 2026-07-20

**Milestone: M0 (Empty plugin scaffold) — COMPLETE**

### Completed work

- Installed Maven (was absent from the machine).
- Verified current Jenkins scaffolding recommendations from primary sources
  rather than assuming them.
- Generated the plugin from the official `empty-plugin` archetype.
- Retargeted the build to CloudKeeper's actual Jenkins version.
- Confirmed the plugin loads into a running Jenkins.

### Files created / modified

| File | Note |
|---|---|
| `pom.xml` | Archetype-generated, then edited (see decisions) |
| `README.md` | Rewritten from archetype TODO stub |
| `MEMORY.md` | New (this file) |
| `.gitignore` | Archetype-generated (`target`, `work`, IDE files) |
| `src/main/resources/index.jelly` | Plugin description text |
| `src/main/java/io/github/rads4/ckaws/package-info.java` | Package placeholder |
| `src/test/java/io/github/rads4/ckaws/PluginLoadsTest.java` | M0 smoke test |
| `.mvn/` | Generated by archetype, then **deleted** (see decisions) |

`CLAUDE.md` was not modified.

### Commands executed

```bash
# Maven install (user-local; no passwordless sudo available)
curl -sSfO https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz
sha512sum -c -                      # checksum verified OK
tar -xzf ... -C ~/.local && ln -sfn ~/.local/apache-maven-3.9.16/bin/mvn ~/.local/bin/mvn

mvn archetype:generate -B \
  -DarchetypeGroupId=io.jenkins.archetypes -DarchetypeArtifactId=empty-plugin \
  -DarchetypeVersion=1.37 -DgroupId=io.github.rads4 \
  -Dpackage=io.github.rads4.ckaws -DartifactId=ck-aws -DhostOnJenkinsGitHub=false

mvn verify
mvn hpi:run -Dport=8081
```

### Build / test status

- `mvn verify` → **BUILD SUCCESS** (7m33s, mostly first-run dependency download).
- `PluginLoadsTest` → 1 test, 0 failures, 0 skipped.
- Parent-POM `InjectedTest` → 4 tests, 0 failures, 1 skipped (normal).
- `target/ck-aws.hpi` built; manifest shows `Short-Name: ck-aws`,
  `Jenkins-Version: 2.479.2`, `Group-Id: io.github.rads4`.
- Plugin API reports `"active": true`, `"enabled": true`, 0 load errors in log.

### Implementation decisions

1. **Jenkins baseline = 2.479.2**, matching CK production, rather than the
   currently recommended 2.541.3 / 2.555.3. Verified safe: the current parent
   POM's own default `jenkins.version` is `2.479`, `bom-2.479.x` is actively
   published (`5054.v620b_5d2b_d5e6`), and Jenkins 2.479 requires Java 17+ —
   all consistent. No compatibility compromise was needed.
2. **Parent POM `6.2211.v27f680c93c53`** (current release) over the archetype's
   older pin. Independent of the baseline choice.
3. **groupId `io.github.rads4`**, package `io.github.rads4.ckaws`. Neutral,
   personal-GitHub-aligned namespace; CK's namespace deliberately avoided while
   the POC is unadopted. `io.jenkins.plugins` was rejected — reserved for
   `jenkinsci`-hosted plugins.
4. **Removed `.mvn/extensions.xml` + `.mvn/maven.config`** (incrementals /
   update-center release tooling): out of scope per CLAUDE.md, and it derives
   `${changelist}` from git history this repo lacks.
5. **Dev Jenkins runs on port 8081**, not the default 8080.
6. Smoke test uses JUnit 5 `@WithJenkins`; the parent POM bans JUnit 4 imports.

### Blockers / open items

- **Port 8080 is occupied** by a pre-existing system Jenkins service
  (`/usr/share/java/jenkins.war`, pid 1563). Not modified — it is outside this
  project's scope. Always pass `-Dport=8081`.
- **Maven is user-local** (`~/.local/bin/mvn`), not on the system PATH by
  default. Add `export PATH="$HOME/.local/bin:$PATH"` to your shell profile, or
  install system-wide via `sudo apt install maven`.
- **CK Jenkins 2.479.2 is old** — below the Jenkins update-center distribution
  floor (~2.504.2) and behind 2.479.3 within its own line. Harmless for the POC
  (nothing is published), but worth raising with the Platform team separately.
- Git: no commits exist yet; no remote configured. No git operations performed.

### Recommended next milestone

**M1 — Auth core in isolation.** STS AssumeRole producing the
`jk-${JOB_NAME}-${BUILD_NUMBER}` session name, unit-tested against a
mocked/stubbed STS client, with no Jenkins integration. Per CLAUDE.md this
should be testable without `JenkinsRule`.

---

## Session 2 — 2026-07-24

**Milestone: M1 (Auth core in isolation) — COMPLETE**

### Completed work

- Built the authentication core as plain Java under a new `.auth` package, with
  no `hudson.*`/`jenkins.*` imports and no process execution.
- STS AssumeRole is abstracted behind a `StsAssumeRole` port interface
  (Approach C — approved). Only a hand-written fake backs it for now; the real
  CLI-backed implementation is deferred to a later milestone.
- Session-name generation (`jk-<job>-<build>`) implemented with STS-constraint
  sanitization/truncation that preserves the load-bearing `jk-` prefix.
- 24 new unit tests (pure JUnit 5, no `JenkinsRule`); full `mvn verify` green.

### Files created

| File | Note |
|---|---|
| `src/main/java/io/github/rads4/ckaws/auth/CkAwsAuthException.java` | Base, **unchecked** (`extends RuntimeException`) — approved decision |
| `.../auth/SessionNameException.java` | Invalid job/build → can't form session name |
| `.../auth/AssumeRoleException.java` | STS/transport failure, actionable message |
| `.../auth/SessionName.java` | Frozen `jk-<job>-<build>` generation + sanitize/truncate |
| `.../auth/AwsCredentials.java` | Immutable creds + `Instant expiration`; `Clock`-based `isExpired`/`expiresWithin`; redacting `toString` |
| `.../auth/AssumeRoleRequest.java` | roleArn + SessionName + optional durationSeconds (validated `[900,3600]`) |
| `.../auth/StsAssumeRole.java` | Port interface (single AWS seam) |
| `.../auth/CredentialsProvider.java` | Future refresh seam (unused in M1) |
| `.../auth/AuthCore.java` | Orchestration: build request → call port → normalize failures |
| `src/test/.../auth/FakeStsAssumeRole.java` | Hand-written test double (records last request) |
| `src/test/.../auth/{SessionNameTest,AwsCredentialsTest,AuthCoreTest}.java` | Unit tests |

No pom changes; no production dependencies added; `CLAUDE.md` untouched. M0
files (`PluginLoadsTest`, etc.) unchanged.

### Build / test status

- `~/.local/bin/mvn verify` → **BUILD SUCCESS** (37s). Tests run: **29**,
  Failures 0, Errors 0, Skipped 1 (parent `InjectedTest`, normal).
  New auth tests: SessionName 10, AwsCredentials 8, AuthCore 6.
- Spotless + SpotBugs clean; `target/ck-aws.hpi` still builds.

### Implementation decisions

1. **Exceptions unchecked** (`CkAwsAuthException extends RuntimeException`),
   per approval. The Jenkins layer will decide how to surface failures later.
2. **Approach C** for STS: port interface + hand-written fake now; real
   transport (planned: `aws sts assume-role` via the M2 generic executor, not
   the AWS SDK) deferred. No SDK dependency introduced.
3. **`Clock` injected** for all expiry checks — deterministic tests and a
   no-redesign path to M4/M5 refresh. `AwsCredentials.expiration` +
   `CredentialsProvider` are the only refresh hooks; no caching/scheduling.
4. **Session-name sanitization**: non-`[\w+=,.@-]` chars → `-`, dash-runs
   collapsed, edges trimmed, middle (job) segment truncated to keep total ≤64
   while preserving `jk-` prefix + trailing build. Fail-closed on blank job /
   non-positive build (`SessionNameException`).
5. **Input-shape validation** in value objects uses standard
   `IllegalArgumentException`/`NullPointerException`; only domain/auth failures
   use the `CkAwsAuthException` hierarchy.

### Environment finding (resolved by diagnosis, no machine changes made)

- **`mvn hpi:run` → "Unknown packaging: hpi"** root-caused: since M0 a system
  Maven **3.8.7** was installed at `/usr/bin/mvn` (apt). The Jenkins parent POM
  needs **Maven 3.9.6+** to load the `maven-hpi-plugin` build extension that
  registers `hpi` packaging; 3.8.7 fails to, hence the error. The working
  `~/.local/bin/mvn` (3.9.16) is unaffected — reproduced both ways.
- The login shell puts `~/.local/bin` first, so it works interactively; the
  error appears whenever `mvn` resolves to `/usr/bin/mvn` (non-login shell,
  IDE terminal, cron). **Not a pom/M1 problem** — M1 is plain Java and never
  needs `hpi:run`. Fix left to the user (per instruction not to modify PATH):
  prefer `~/.local/bin/mvn`, or `sudo apt remove maven`. All Maven commands
  this session used `~/.local/bin/mvn` explicitly.
- `/etc/maven/settings.xml` has a `<localRepository>/path/to/local/repo</...>`
  line but it is inert (inside the shipped comment block); irrelevant here.

### Blockers / open items

- Port-8080 conflict from M0 persists (system Jenkins on 8080); still pass
  `-Dport=8081` for any future `hpi:run`.
- Real STS transport for the port is intentionally not implemented yet (M2/M3).

### Recommended next milestone

**M2 — One explicit pipeline step** (`ckAws.run([...])`) wiring `AuthCore` (via
a real `StsAssumeRole` implementation) to the generic `ProcessBuilder`
executor. This is where the CLI-backed `StsAssumeRole` and the executor first
appear. Still no RunListener (that's M5).

---

## Session 3 — 2026-07-24

**Milestone: M2 (real CLI-backed STS transport + generic executor) — COMPLETE**

> Scope note: the user deliberately narrowed this milestone to the CLI transport
> and the generic process executor, **excluding** the Jenkins pipeline step. So
> the `ckAws.run([...])` step from CLAUDE.md's M2 is still deferred — what landed
> here is the real `StsAssumeRole` implementation plus the reusable executor it
> runs on. No Jenkins integration yet.

### Completed work

- Added a generic, AWS-unaware process-execution layer (`.exec`) and a
  CLI-backed `StsAssumeRole` (`.auth.cli`) that swaps in for the M1 fake.
- **`AuthCore` and every other M1 file are byte-for-byte unchanged** — the only
  change is which `StsAssumeRole` you construct `AuthCore` with. The M1
  `FakeStsAssumeRole` and its tests remain in place.
- No new dependencies (no JSON parser, no AWS SDK); no `pom.xml` change.

### Files created

| File | Note |
|---|---|
| `src/main/java/io/github/rads4/ckaws/exec/ProcessRunner.java` | Generic port: run an arbitrary command → result |
| `.../exec/ProcessResult.java` | Immutable (command, exitCode, stdout, stderr); `toString` omits stream **content** (may hold secrets) |
| `.../exec/ProcessExecutionException.java` | Execution-layer failure (couldn't start/complete); **stays inside the exec + auth.cli boundary** |
| `.../exec/DefaultProcessRunner.java` | `ProcessBuilder`-based; inherits env; drains stderr on a helper thread to avoid pipe deadlock; no timeout |
| `.../auth/cli/CliStsAssumeRole.java` | Builds `aws sts assume-role … --query Credentials.[…] --output text`, parses tab-separated line, maps all failures → `AssumeRoleException` |
| `src/test/.../exec/FakeProcessRunner.java` | Public hand-written double; `failingExecution(...)` raises the exec exception *inside* exec so adapter tests never import it |
| `src/test/.../exec/DefaultProcessRunnerTest.java` | Real-process tests (POSIX `sh`; `@DisabledOnOs(WINDOWS)`) |
| `src/test/.../auth/cli/CliStsAssumeRoleTest.java` | Command construction, parsing, error mapping, + AuthCore-swap test |

### Build / test status

- `~/.local/bin/mvn verify` → **BUILD SUCCESS** (~19s). Tests run: **44**,
  Failures 0, Errors 0, Skipped 1 (parent `InjectedTest`). New this milestone:
  CliStsAssumeRole 11, DefaultProcessRunner 4.
- Spotless + SpotBugs clean (0 bugs); `target/ck-aws.hpi` still builds.

### Implementation decisions

1. **Dependency-free credential extraction** (approved): the CLI's own
   `--query Credentials.[AccessKeyId,SecretAccessKey,SessionToken,Expiration]
   --output text` yields one tab-separated line; split on `\t`. No JSON library.
   Expiration parsed via `OffsetDateTime.parse(...).toInstant()` (CLI emits
   `+00:00`, which `Instant.parse` rejects); falls back to `Instant.parse`.
2. **Executor is strictly generic** (approved): `ProcessRunner` executes any
   argument list and has zero AWS/Jenkins awareness — it is the reusable seed of
   the future `ckAws.run([...])` executor. STS specifics live *only* in
   `CliStsAssumeRole`. No per-service branching anywhere.
3. **`ProcessExecutionException` does not leak** (approved): it is referenced
   only in `.exec` (defined + thrown) and caught/mapped inside `CliStsAssumeRole`.
   The rest of the project sees only `AssumeRoleException`, so authentication's
   transport (process vs SDK vs anything) is invisible upstream. It is retained
   as the exception *cause* for diagnostics — visible only if a caller unwraps
   `getCause()`, not in the thrown type or any signature.
4. **`AuthCore` unchanged** (approved): swap is construction-time only —
   `new AuthCore(new CliStsAssumeRole(new DefaultProcessRunner()))`.
5. **No timeout** (deferred to M4): `DefaultProcessRunner` blocks on
   `waitFor()`. Acceptable for M2/M3 (`get-caller-identity`/`assume-role` are
   fast); a hung `aws` would block — revisit with retry/timeout in M4.
6. **Base identity / region** come from the inherited process environment
   (`AWS_PROFILE`, `AWS_DEFAULT_REGION`, instance metadata). The plugin still
   does **not** read `~/.aws/config`; the CLI resolves ambient creds itself.

### Blockers / open items

- Still no live STS call exercised — `CliStsAssumeRole` is unit-tested only
  (faked runner). First real `aws` invocation happens at **M3** against the
  read-only NonProd profile, with CloudTrail verification of the `jk-<job>-<build>`
  session name. That is also the first time the `aws` CLI binary + real AWS
  credentials/region are required in the environment.
- Port-8080 conflict and the Maven-3.8.7 `hpi:run` trap (Session 2) still stand.

### Recommended next milestone

**M3 — POC success criterion.** Wire a real pipeline job on local Jenkins to
`AuthCore(new CliStsAssumeRole(new DefaultProcessRunner()))`, run
`ckAws.run(["sts", "get-caller-identity"])` against the NonProd read-only
profile, and confirm CloudTrail shows the session as `jk-<job>-<build>`. This
milestone finally introduces the Jenkins pipeline step (the piece deferred out
of this session's M2) and the generic-executor path for arbitrary AWS CLI
commands.

---

## Session 4 — 2026-07-28

**Milestone: M3 (first Jenkins integration point) — COMPLETE**

> **Scope note — read this before comparing against CLAUDE.md.** The user
> narrowed M3 to *Jenkins integration only*: **no live AWS, no CloudTrail, no
> generic AWS command execution**. So what landed is the explicit pipeline step
> CLAUDE.md assigns to M2 (deferred out of Session 3), not CLAUDE.md's M3
> success criterion. **Live AssumeRole + CloudTrail validation of
> `jk-<job>-<build>` is still outstanding** and is the next session's work.
> `CLAUDE.md` was deliberately not modified.

### Completed work

- Added `ckAwsAssumeRole`, the plugin's first Pipeline step, in a new `.steps`
  package. It bridges Jenkins to the existing stack and owns nothing else.
- **M1 and M2 are byte-for-byte unchanged** (verified via `git diff`: among
  tracked files only `pom.xml` changed). The step composes them at
  construction time exactly as Session 3 predicted.
- 8 new `JenkinsRule` tests driving the *real* path Step → AuthCore →
  CliStsAssumeRole → DefaultProcessRunner → a real subprocess, using stub
  `aws` shell scripts. No AWS account, credentials, or AWS CLI required.
- Verified on a live `hpi:run` Jenkins: plugin `active=true`, 0 load errors,
  and `ckAwsAssumeRole` / "Assume an AWS role for this build" both present in
  the Snippet Generator.

### Files created / modified

| File | Note |
|---|---|
| `src/main/java/io/github/rads4/ckaws/steps/CkAwsAssumeRoleStep.java` | The step, its `Execution`, and `DescriptorImpl` |
| `src/test/java/io/github/rads4/ckaws/steps/CkAwsAssumeRoleStepTest.java` | 8 `@WithJenkins` tests, `@DisabledOnOs(WINDOWS)` |
| `pom.xml` | 3 workflow dependencies + **BOM downgrade** (see decision 6) |

### Public API

```groovy
def session = ckAwsAssumeRole(roleArn: 'arn:aws:iam::123456789012:role/non_prod')
// session == 'jk-<job>-<build>'
```

### Build / test status

- `~/.local/bin/mvn verify` → **BUILD SUCCESS**. Tests run: **52**, Failures 0,
  Errors 0, Skipped 1 (parent `InjectedTest`). New this milestone: 8.
- Spotless clean; SpotBugs `BugInstance size is 0`.

### Implementation decisions

1. **Returns only the session name** (approved). No credential material — not
   even `AccessKeyId` — reaches the Pipeline DSL, because anything returned is
   persisted in CPS program state and trivially printable. The credentials from
   `authenticate(...)` are deliberately discarded; exporting them is a future
   `withProfile`-block concern.
2. **`SynchronousNonBlockingStepExecution`**: the AssumeRole call blocks on a
   subprocess, and blocking the CPS VM thread would stall the whole flow.
   `AuthCore` is built inside `run()` and never held in a field, so the
   execution stays serializable for pipeline durability.
3. **Session name computed in the step** via `SessionName.forBuild(...)` before
   calling `AuthCore` (which derives the same value internally). Deterministic
   and side-effect-free, so this fails closed *before* spawning a process and
   supplies the return value **without changing AuthCore's M1 signature**.
4. **`CkAwsAuthException` → `AbortException`**: Jenkins' idiom for an expected,
   user-actionable failure — message only, no stack trace. Anything else
   propagates untouched (a real bug deserves its trace). The **root cause's
   message** is logged, never the exception object: printing it leaked
   `ProcessExecutionException` into the build log and broke M2's decision 3
   (transport invisible upstream). A test now asserts that specifically.
5. **`io.github.rads4.ckaws.awsExecutable` system property** (approved)
   overrides the `aws` binary. Required for testing: the child process inherits
   the JVM environment, so a test cannot prepend to `PATH` in-process. Uses
   `CliStsAssumeRole`'s existing 2-arg constructor — no M2 change.
6. **BOM downgraded `5054.v620b_5d2b_d5e6` → `4488.v7fe26526366e`.** *Not*
   cosmetic — see blockers below.
7. **No `workflow-basic-steps`** (so no `echo`): it transitively drags
   `instance-identity` 203, which also requires 2.479.3. Tests assert the
   return value with plain Groovy `assert` inside the pipeline instead. This
   was verified not to be a vacuous check by deliberately breaking the expected
   value and confirming the build fails.
8. **Runs on the controller JVM**, not the agent (approved, POC-only).
   `node {}` is ignored. Future fix is a `LauncherProcessRunner implements
   ProcessRunner` — purely additive, zero auth-layer change.
9. `getFullName()` not `getName()`, so folder paths enter the session name and
   get sanitized. Jenkins rejects most punctuation in job names but allows
   spaces, so the sanitization test uses `"my awkward job"` → `jk-my-awkward-job-1`.

### Blockers / open items

- **The 2.479 LTS line has moved past CK production.** Every plugin BOM release
  after `4488` pins `workflow-cps >= 4050`, whose manifest requires **Jenkins
  2.479.3**; `validate-hpi` then refuses to build against our 2.479.2 baseline.
  Newest workflow-cps still on 2.479.1 is `4046.v90b_1b_9edec67`, pinned by BOM
  `4488`. Keeping the M0 guarantee ("loadable on CK's real instance") therefore
  costs a **several-months-old tested-together dependency set**, and every new
  plugin dependency risks dragging in another 2.479.3 requirement (this already
  happened twice this session). **Decision to raise with the Platform team:
  either CK takes the 2.479.3 patch, or this plugin stays on an ageing BOM.**
- **Still no live STS call.** `aws` CLI *is* installed locally
  (`/usr/local/bin/aws`), so the next session can attempt it — but it was
  deliberately not exercised here per the milestone's no-live-AWS constraint.
- `mvn hpi:run` serves Jenkins under the **`/jenkins` context path**
  (`http://localhost:8081/jenkins`), and anonymous **POST is 403** — creating
  or triggering jobs via `curl` needs authentication. UI use is unaffected.
- Port-8080 conflict and the Maven-3.8.7 `hpi:run` trap (Sessions 1–2) stand.

### Recommended next milestone

**CLAUDE.md's original M3 — live validation.** Point `ckAwsAssumeRole` at the
real NonProd read-only role on local Jenkins, confirm AssumeRole succeeds, and
verify CloudTrail Event History shows the session as `jk-<job>-<build>`. That
closes the POC success criterion. The generic `ckAws.run([...])` execution step
and `sts get-caller-identity` remain unimplemented and are a separate decision.

---

## Session 5 — 2026-07-30

**Milestone: M4 (live STS validation against real AWS) — COMPLETE**
(CloudTrail confirmation is the one item left, and is deliberately manual.)

> **Scope note — read this before comparing against CLAUDE.md.** The user
> redefined M4: CLAUDE.md lists M4 as the retry/timeout/structured-logging
> stretch goal, but this session's M4 was **live validation** — i.e. CLAUDE.md's
> *original M3 success criterion*, which Session 4 deliberately deferred. **No
> retry, timeout, caching, refresh or logging work was done.** The target account
> is also not NonProd: a dedicated least-privilege validation role in the **Ops**
> account was used instead (see below). `CLAUDE.md` was deliberately not modified.

### Completed work

- Ran the full stack against **real AWS** from local Jenkins, twice, and
  confirmed the `jk-<job>-<build>` convention survives real STS.
- Added the one capability the milestone genuinely required: per-invocation
  **environment overrides** on `DefaultProcessRunner`. Everything else is
  temporary scaffolding.
- **The entire `.auth` layer is byte-for-byte unchanged** — `git diff` touches
  zero files under `src/main/java/io/github/rads4/ckaws/auth/`. `AuthCore`,
  `SessionName`, `CliStsAssumeRole` and the `ProcessRunner` port were not
  modified. **The Pipeline DSL is unchanged from M3.**
- 6 new tests (52 → 58); `mvn verify` green, SpotBugs 0, Spotless clean.

### Live validation evidence (real AWS, account 685502069032)

Base identity (`ops-admin`, CK SAML):
`arn:aws:sts::685502069032:assumed-role/CKPrism-AdministratorAccess/radhika.awasthi@cloudkeeper.com`
— matches the principal in `trust-policy.json`.

Build log of job `ck-aws-live` #2, verbatim:

```
[ck-aws] AWS CLI profile: ops-admin (temporary M4 override)
[ck-aws] Assuming role arn:aws:iam::685502069032:role/ck-jenkins-plugin-validation-role as session jk-ck-aws-live-2
[ck-aws] Assumed role  ... as session jk-ck-aws-live-2
[ck-aws] Caller identity: 685502069032	arn:aws:sts::685502069032:assumed-role/ck-jenkins-plugin-validation-role/jk-ck-aws-live-2	AROAZ7GY3PEUKA3CDG6XM:jk-ck-aws-live-2
[ck-aws] Session name confirmed in caller identity: jk-ck-aws-live-2
Finished: SUCCESS
```

Build #1 produced the identical shape with `jk-ck-aws-live-1`, so the session
name is confirmed **build-scoped**, not merely well-formed.

| Claim | Evidence |
|---|---|
| Pipeline step, AuthCore, CliStsAssumeRole, DefaultProcessRunner all work | build SUCCESS with both AWS calls answering |
| AssumeRole succeeds against a real role | "Assumed role" line |
| Temporary credentials are valid | `get-caller-identity` answered as the **assumed role**, with `AWS_PROFILE` stripped |
| Session name is correct | `.../ck-jenkins-plugin-validation-role/jk-ck-aws-live-2` |
| Profile selection works | AssumeRole succeeded only via the `ops-admin` base identity |
| Exactly two AWS APIs called | `sts:AssumeRole`, `sts:GetCallerIdentity` — nothing else exists in the code path |

### Files created / modified

| File | Note |
|---|---|
| `.../exec/DefaultProcessRunner.java` | **PERMANENT.** New `run(command, Map)` overload; `null` value **removes** a variable. `run(command)` delegates with an empty map — behaviour unchanged. Still zero AWS awareness. |
| `.../steps/CkAwsAssumeRoleStep.java` | **TEMPORARY parts only** (see removal list): 2 property constants, `verifyIdentity`, `temporaryProfileEnvironment`, and the runner-decorating lambda. |
| `src/test/.../exec/DefaultProcessRunnerTest.java` | +3 tests (override applies / inheritance survives / null removes). Field retyped to `DefaultProcessRunner`. |
| `src/test/.../steps/CkAwsAssumeRoleStepTest.java` | +3 tests and a two-mode stub `aws`. |
| `README.md` | Status line refreshed; new **"Live AWS validation (M4)"** section (temporary); project layout updated. |

No `pom.xml` change and **no new dependencies**. No AWS CLI config, IAM policy,
role or trust policy was created or modified. No existing deployment library was
touched.

### Build / test status

- `~/.local/bin/mvn verify` → **BUILD SUCCESS**. Tests run: **58**, Failures 0,
  Errors 0, Skipped 1 (parent `InjectedTest`, normal). SpotBugs `BugInstance
  size is 0`; Spotless clean.
- New: `DefaultProcessRunnerTest` 4 → 7, `CkAwsAssumeRoleStepTest` 8 → 11.

### Implementation decisions

1. **No new Pipeline parameter** (per instruction). Validation is enabled by the
   system property `io.github.rads4.ckaws.validateIdentity`, so the step's public
   API is identical to M3. A test asserts no `get-caller-identity` call happens
   when the property is unset.
2. **No new package or adapter class.** The identity check is ~25 lines of
   private methods inside the step. Putting a `CliStsGetCallerIdentity` in
   `.auth.cli` would have created a permanent-looking class for temporary work;
   duplicating the executor would have meant the milestone validated a *copy* of
   `DefaultProcessRunner` rather than the real one.
3. **`ProcessRunner` (the port) was not modified** — the env overload lives only
   on the concrete class. Because `ProcessRunner` is a `@FunctionalInterface`,
   the profile override is injected as a one-line decorating lambda in the step,
   which is why `CliStsAssumeRole` and `AuthCore` needed no change at all.
4. **`AWS_PROFILE`/`AWS_DEFAULT_PROFILE` are removed for the identity check**,
   and the check runs with the *same base environment* the AssumeRole call got.
   This is the milestone's most important detail: with the profile left in place
   the AWS CLI can answer from the **base** identity, so `get-caller-identity`
   would succeed and print a plausible ARN while proving nothing. The
   session-name assertion is what makes the check real.
5. **Non-vacuity verified, not assumed.** Deleting the `AWS_PROFILE` strip was
   confirmed to fail 2 tests (`exit 91: AWS_PROFILE leaked into the identity
   check`) before the line was restored. An earlier version of the same test
   *did* pass without the strip — the identity check simply never had the
   variable set — which is why the base environment is now threaded through.
6. **No region default in code** (per instruction). If no region resolves, the
   AWS CLI's own error is surfaced verbatim and the build fails.
7. **Credentials travel by environment, never argv.** Process arguments are
   world-readable via `ps` and `/proc/<pid>/cmdline`; environment is not. This is
   also why the `env VAR=... aws ...` trick — which would have avoided the
   executor change entirely — was rejected.
8. **Credentials still never reach the Pipeline DSL.** `authenticate(...)`'s
   result is held in a local for the length of one method and is the sole
   consumer of the temporary check; the step still returns only the session name
   (M3 decision 1 intact). Nothing credential-bearing is logged.
9. **Log line order** was corrected after the first live run, then the plugin was
   rebuilt and **re-validated live** (build #2), so the committed code is exactly
   what was validated.

### Environment findings

- **`ops-admin` is `us-east-1` but the `default` profile is `eu-west-2`.**
  Because the identity check strips `AWS_PROFILE`, the CLI falls back to the
  `default` profile for *config*, so the two calls would otherwise land in
  **different CloudTrail regions** (Event History is per-region). Both runs were
  therefore launched with `AWS_DEFAULT_REGION=us-east-1`, which is inherited by
  both children and is not a profile, so the strip does not remove it.
- **Session 4's "anonymous POST is 403" is resolved:** it was CSRF, not auth.
  With a cookie jar plus a crumb from `/crumbIssuer/api/xml`, `createItem` and
  `build` both work over `curl` (HTTP 200 / 201). Useful for scripted validation.
- **`pkill -f "hpi:run"` does not stop Jenkins** — it kills the Maven wrapper
  while the forked Jenkins JVM keeps holding port 8081, so the next `hpi:run`
  dies with `Failed to start Jetty` *and* you may unknowingly keep testing the
  **old** plugin build. Kill the PID listening on 8081 (`ss -ltnp`) instead.
- Port-8080 conflict and the Maven-3.8.7 `hpi:run` trap (Sessions 1–2) stand.

### Outstanding: CloudTrail (manual, deliberately not automated)

In account **685502069032**, region **us-east-1**, CloudTrail → Event history
(allow ~15 min for delivery):

1. Event name `AssumeRole` → confirm `requestParameters.roleSessionName` is
   **`jk-ck-aws-live-1`** and **`jk-ck-aws-live-2`** (not an auto-generated
   name), `requestParameters.roleArn` is the validation role, and
   `userIdentity.arn` contains `CKPrism-AdministratorAccess`.
2. Event name `GetCallerIdentity` → confirm `userIdentity.arn` ends in
   `assumed-role/ck-jenkins-plugin-validation-role/jk-ck-aws-live-<n>`. This is
   the independent proof the temporary credentials, not the base profile, made
   the call.
3. Confirm no other event names appear from those sessions.

Until step 1 is eyeballed, the POC's headline claim is evidenced only by
`get-caller-identity` (which already embeds the session name in its ARN), not by
CloudTrail itself.

### To delete in M5 (the whole temporary surface)

1. In `CkAwsAssumeRoleStep.java`: the `AWS_PROFILE_PROPERTY` and
   `VALIDATE_IDENTITY_PROPERTY` constants, the `verifyIdentity` and
   `temporaryProfileEnvironment` methods, the `if (SystemProperties.getBoolean
   (...))` block, and the runner lambda — reverting to
   `new CliStsAssumeRole(processRunner, awsExecutable())`. Also the
   `AwsCredentials`/`ProcessResult`/`ProcessRunner`/`HashMap`/`List`/`Map`
   imports and the M4 paragraph in the class javadoc.
2. The 3 M4 tests plus `liveValidationStub` and `TEST_PROFILE` in
   `CkAwsAssumeRoleStepTest`.
3. The README "Live AWS validation (M4)" section.
4. **Retained deliberately:** the `DefaultProcessRunner` env overload and its 3
   tests (approved as a permanent generic executor improvement — it is exactly
   what a `withProfile` block needs to export credentials).

After that, `git diff` against Session 4 is one added method in
`DefaultProcessRunner`.

### Recommended next milestone

Two candidates, and they are independent:

- **The generic execution step** (`ckAws.run([...])`, CLAUDE.md's M2/DoD item).
  This is the last unmet Definition-of-Done line: `get-caller-identity` has now
  run *through* the generic executor, but only from temporary internal code — no
  pipeline-callable generic AWS CLI step exists yet.
- **M5 as CLAUDE.md defines it** — JCasC-backed profile→role config, which is
  the real replacement for this session's temporary `awsProfile` property, plus
  the RunListener path.

Also still open from Session 4: the **BOM/2.479.3 decision** for the Platform
team, unchanged by this session.

---

## Session 6 — 2026-07-30

**Milestone: M5 (production packaging and cleanup) — COMPLETE**

> **Scope note.** CLAUDE.md's M5 is "RunListener automatic default-profile
> injection, JCasC-backed profile/role config". This session's M5 was
> **different**: removing the temporary M4 validation surface and getting the
> plugin into an installable state. **No RunListener and no JCasC work was
> done** — both remain unimplemented. `CLAUDE.md` was deliberately not modified.

### Completed work

- Deleted the entire temporary M4 validation surface listed in Session 5's
  "To delete in M5" checklist. Verified complete: no `awsProfile`,
  `validateIdentity`, `verifyIdentity` or `temporaryProfileEnvironment` symbol
  remains anywhere in `src/`.
- Refreshed the `package-info.java` javadoc, which still described the plugin as
  "Scaffold only (milestone M0)".
- Added [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md) — a full codebase
  walkthrough (architecture, runtime flow, per-file reference, design decisions,
  extension points, reading order) for a new maintainer.
- `CLAUDE.md` gained a **Documentation Maintenance** section (process, not
  architecture): update MEMORY.md and review README.md at the end of every
  session; never modify CLAUDE.md automatically.

### Files modified

| File | Note |
|---|---|
| `.../steps/CkAwsAssumeRoleStep.java` | −134/+15: temporary constants, `verifyIdentity`, `temporaryProfileEnvironment`, the runner-decorating lambda, the M4 javadoc paragraph and the now-unused imports all removed. Reverted to `new CliStsAssumeRole(new DefaultProcessRunner(), awsExecutable())` |
| `.../steps/CkAwsAssumeRoleStepTest.java` | −113/+4: the 3 M4 tests, `liveValidationStub`, `TEST_PROFILE` and the property-clearing `@AfterEach` removed. Back to 8 tests |
| `.../package-info.java` | "Scaffold only (M0)" wording dropped |
| `docs/DEVELOPER_GUIDE.md` | New |
| `CLAUDE.md` | Documentation Maintenance section (explicitly requested) |

**Retained deliberately** (approved in Session 5 as a permanent generic-executor
improvement): `DefaultProcessRunner.run(List, Map)` and its 3 tests. It is
exactly what a future `withProfile` block needs to export credentials, and it
carries no AWS awareness.

### Net effect

Against Session 4, the only production-code difference is the one added overload
in `DefaultProcessRunner`. `AuthCore`, `SessionName`, `AssumeRoleRequest`,
`AwsCredentials`, `CliStsAssumeRole`, the `ProcessRunner` port and the Pipeline
DSL are all unchanged. The step's only remaining system property is
`io.github.rads4.ckaws.awsExecutable` (the test hook from M3).

---

## Session 7 — 2026-08-03

**Task: production release verification (no code changes) — COMPLETE**

Verification pass ahead of the first installation on Infra Jenkins. No feature
work, no refactoring, no architecture or authentication changes.

### Repository review

| Check | Result |
|---|---|
| Temporary M4 validation code remaining | None — grep for `awsProfile`/`validateIdentity`/`verifyIdentity`/`temporaryProfileEnvironment` across `src/` returns nothing |
| Experimental code | None |
| TODO / FIXME / XXX / HACK affecting production | None (the only "temporary" hits are javadoc prose about *temporary credentials*) |
| Unused imports | None — every import in all 12 production and 10 test files is referenced; Spotless clean |
| Dead code | None introduced during development. `CredentialsProvider` is unused but is a deliberate M1 seam documented as such, not a leftover |
| `pom.xml` | Clean; no experimental dependencies. 3 workflow deps (1 compile, 2 test) + the pinned BOM |

### Release verification — `mvn clean verify`

**BUILD SUCCESS** (2m40s, finished 2026-08-03T16:14:55+05:30).

- Tests run **55**, Failures 0, Errors 0, Skipped 1 (parent `InjectedTest`, normal).
  AuthCore 6, AwsCredentials 8, SessionName 10, CliStsAssumeRole 11,
  DefaultProcessRunner 7, CkAwsAssumeRoleStep 8, PluginLoads 1, InjectedTest 4.
- SpotBugs: `BugInstance size is 0`, `Error size is 0`.
- Spotless: 25 Java files clean, pom clean.
- `target/ck-aws.hpi` generated.

### Local boot verification — `mvn hpi:run -Dport=8081`

- "Jenkins is fully up and running" (Jenkins 2.479.2); startup log free of
  SEVERE/exception/load-failure entries.
- `ck-aws` → `active: true`, `enabled: true`,
  `requiredCoreVersion: 2.479.2`, version `1.0-SNAPSHOT (private-16c86596-radhika)`.
- All **13** plugins active and enabled; zero inactive/disabled.
- Snippet Generator (`/jenkins/pipeline-syntax/`) lists
  **`ckAwsAssumeRole: Assume an AWS role for this build`** — step still registered.
- Stopped cleanly by `kill -TERM` on the JVM holding port 8081 (per Session 5's
  finding that `pkill -f "hpi:run"` leaves the forked JVM running).

### Final artifact

| | |
|---|---|
| Path | `target/ck-aws.hpi` |
| Size | 27,712 bytes (27 KB) |
| Built | 2026-08-03 16:14:26 +0530 |
| Short-Name / Long-Name | `ck-aws` / CK AWS Plugin |
| Plugin-Version | `1.0-SNAPSHOT (private-16c86596-radhika)` |
| Jenkins-Version | 2.479.2 |
| Build-Jdk-Spec | 21 |

### Documentation changes made this session

- **README.md — updated.** It was the one genuinely stale document: it still
  advertised the M4 `awsProfile` / `validateIdentity` system properties and the
  `verifyIdentity` / `temporaryProfileEnvironment` methods that M5 deleted, and
  its status line still read M4. Now: status M5, a Usage section (step, log
  output, runtime requirements, known limitations, the one surviving system
  property), an install section, a corrected live-validation summary, and the
  `docs/` entry in the project layout.
- **docs/DEVELOPER_GUIDE.md — one paragraph updated.** Its §12 note flagged the
  README as stale; that note now records the correction instead of pointing at a
  problem that no longer exists.
- **MEMORY.md — updated.** Sessions 6 and 7 added (M5 had never been recorded).
- **CLAUDE.md — not modified.** No architectural decision changed: the auth
  design, the AWS-service-agnostic executor rule, the `jk-<job>-<build>`
  convention, the no-`~/.aws/config` rule and the JCasC/RunListener plans are all
  exactly as documented. This session only removed nothing and added nothing.

### Open items carried forward (unchanged by this session)

- **Artifact version is `1.0-SNAPSHOT`, a private build.** Fine for a first
  manual install; a real release version is a decision for whoever owns the
  release process (still out of POC scope).
- **BOM/2.479.3 decision** for the Platform team (Session 4).
- **CloudTrail eyeballing** from Session 5 is still the one manual confirmation
  step, if it has not been done.
- Controller-JVM execution, no retry/timeout, no credential refresh, no generic
  `ckAws.run([...])` step, no RunListener, no JCasC mapping.

---

## Session 8 — 2026-08-04

**Architecture review + M6 (layered architecture). The project's direction
changed this session.** This is the first session where a previously agreed
design decision was overturned rather than extended.

### What triggered the review

The plugin was complete through M5 and installed on Infra Jenkins. Before
integrating it with the CloudKeeper deployment library, we reviewed three
codebases together for the first time:

1. this plugin,
2. `cln-deployment-scripts` (the deployment shared library),
3. `cln-infra-terraform` (the Terraform pipelines).

### Findings that changed the direction

**1. The premise recorded in CLAUDE.md was wrong.** CLAUDE.md described an
`AwsAuth.groovy` in the shared library performing an explicit `sts assume-role`,
and framed the plugin as replacing that call. **That file does not exist.**
Neither does `Audit.groovy`. The library is `Utilities.groovy`, `Build.groovy`,
`Deploy.groovy` and 12 `vars/*.groovy`, and it performs **no explicit STS call
anywhere**. Authentication is entirely `aws ... --profile ${prof}`, where `prof`
is a plain string (`envName == 'prod' ? 'prod' : 'non_prod'`) set in 12
`vars/*.groovy` entry points and threaded through 9 function signatures.

*(Counted precisely while writing the M7 plan later in this session: **13 AWS CLI
invocations across 4 files**, 12 with `--profile` and 1 without. An earlier
estimate in this session of "~50 call sites across 15 files" was wrong and has
been corrected everywhere. The direction of the argument is unaffected — the
executor was rejected mainly because three of four consumer shapes cannot use an
argument list at all — but the adoption-cost argument against it is weaker than
first stated, and the docs now say so.)*

So the plugin had been designed to replace a call site that was not there.

**2. There is no seam in the library to integrate against.** `prof` is a
parameter, not an abstraction. Any change to how authentication works touches
every file.

**3. The `--profile` path produces no build attribution, and will be denied by
the planned trust policy.** When the AWS CLI resolves a profile with a
`role_arn`, it performs the AssumeRole itself and generates its own session name
(`botocore-session-<epoch>`) unless `role_session_name` is pinned in config — and
even pinned it is static per profile, so it can never carry a job name or build
number. Consequences:
   - CloudTrail today has **zero** build attribution for every existing
     deployment.
   - The planned Layer 3 trust policy
     (`"StringLike": {"sts:RoleSessionName": "jk-*"}`) would **deny every
     existing deployment on day one**.

   This reframed the migration from "a tidy-up" to "a prerequisite for the
   enforcement phase the project exists to enable".

**4. There are four consumer shapes, not one.** This is the finding that killed
the generic-executor design:

| Consumer | How it gets credentials |
|---|---|
| deployment library Groovy | `sh "aws ... --profile ${prof}"`, ~50 sites |
| `Utilities.dockerLoginEcr` | `aws ecr get-login-password \| docker login` — a **shell pipeline**, and it takes `prof` and ignores it |
| `code/dr_sync.py` | boto3 `Session(profile_name=...)` |
| `cln-infra-terraform/jenkins/*.groovy` | nothing explicit — ambient instance role + `AWS_REGION` |

   Only the first can call an argument-list executor. Three of four consume
   credentials through the **environment**. An AWS-CLI-executor API is generic
   across AWS *services* but narrow across *consumers* — the opposite of what
   the project needs.

**5. The plugin's own step could not be consumed by anything.**
`ckAwsAssumeRole` performs the AssumeRole and then **discards the credentials**,
returning only the session name. It also runs on the **controller**, while every
consumer runs on an agent — so it authenticated with the wrong identity and
produced credentials on the wrong machine.

### Architectural decisions made

**Decision 1 — layered architecture.** Layer 0 config (JCasC) → Layer 1 the
mandatory contract (block-scoped auth) → Layer 2 optional execution conveniences
→ Layer 3 IAM trust policy. Each layer independently adoptable and revertable.

**Decision 2 — the plugin owns identity only.** Execution stays with consumers,
permanently.

**Decision 3 — the generic AWS CLI executor is demoted, not deleted.** It was
previously going to be *the* interface (`ckAws.run([...])`). It is now optional
Layer 2. Reasons recorded in CLAUDE.md: it is the least generic option in
practice, has the highest coupling and largest blast radius, the worst adoption
cost (~50 call sites across 15 files in one change), and unbounded scope creep
toward reimplementing `sh`. The original argument *for* it — centralized
retry/timeout/logging — is still valid, which is why it survives as an optional
surface rather than being removed.

**Decision 4 — the wrapper is block-scoped, not value-returning.** Credentials
must never be a step return value: a returned value is serialized into CPS
program state (`program.dat`) and is trivially printable from a pipeline. The
block holds them in an `EnvironmentExpander` as `hudson.util.Secret`, masks them
in the console, and withdraws them at block exit. The block is also the only
place a future credential refresh can live.

**Decision 5 — execution moves to the agent.** The base identity is the agent's
instance role; the controller's identity is both wrong and broader. This is a
correctness fix, not a preference.

**Decision 6 — profile→role mapping is JCasC-owned.** Consumers name an
environment, never an ARN. Unknown profiles fail closed. An explicit `roleArn:`
escape hatch exists and is documented as *not* a security boundary — the
security boundary is Layer 3.

**Decision 7 — reversed nothing about M1.** `SessionName`, `AuthCore`,
`CliStsAssumeRole`, the `jk-` convention, the no-`~/.aws/config` rule and the
no-per-service-branching rule are all unchanged and were re-affirmed.

### Deviation from the original plan

CLAUDE.md previously listed two explicitly rejected designs, the second being
"auth-only plugin, execution back in Groovy `sh` calls", rejected because it
loses centralized retry/timeout/logging. **That rejection was partially
overturned.** The resolution is that auth-only is correct as the *mandatory
boundary*, and the executor survives as an *optional surface* — so the original
objection is answered by keeping Layer 2 rather than by forcing every consumer
through it. CLAUDE.md was rewritten this session to record this.

### Documentation rewritten this session

- **CLAUDE.md — substantially rewritten** and is now the authoritative
  architecture document. New: layered architecture, the six principles, the
  rejected-designs section with reasons, the configuration reference, the
  seven-stage migration strategy and the rollback table, and corrected
  organizational context. The stale `AwsAuth.groovy` claim was corrected
  explicitly rather than silently deleted.
- **README.md — rewritten** for the new public API (`ckAwsWithProfile`, JCasC
  configuration, `node` requirement, the `--profile` override warning), with
  `ckAwsAssumeRole` marked deprecated.
- **MEMORY.md — this entry.**

### M6 implementation (same session, after the documentation rewrite)

**Files added**

| File | Layer | Purpose |
|---|---|---|
| `config/AwsProfile.java` | 0 | One `name -> roleArn (+ region)` entry; `@Symbol("awsProfile")` |
| `config/CkAwsGlobalConfiguration.java` | 0 | `GlobalConfiguration`, `@Symbol("ckAws")`, exact-match `resolve()` |
| `config/.../config.jelly` (x2) | 0 | Global config UI |
| `exec/LauncherProcessRunner.java` | — | `ProcessRunner` backed by `Launcher`, so execution happens on the agent |
| `steps/CkAwsWithProfileStep.java` | 1 | The block-scoped contract |
| `steps/CredentialsEnvironmentExpander.java` | 1 | Publishes credentials into the block's `EnvVars`, held as `Secret` |
| `steps/SecretMaskingConsoleLogFilter.java` | 1 | Masks credential material in the block's console output |

**Unchanged, as required:** `AuthCore`, `CliStsAssumeRole`, `SessionName`,
`AwsCredentials`, `ProcessRunner`, `DefaultProcessRunner`, `ProcessResult`, and
all their tests. `CkAwsAssumeRoleStep` is untouched apart from a javadoc note
marking it superseded — deliberately, so the live-AWS/CloudTrail evidence from
Session 5 still describes code that exists.

**Notable implementation decisions**

1. **`ProcessRunner` was not changed.** The earlier review listed "add env/cwd to
   `ProcessRunner`" as a required step. It turned out not to be: credentials
   reach child processes through Jenkins' `EnvironmentExpander`, not through the
   runner, so the interface (and therefore `CliStsAssumeRole`) stayed as-is.
   `DefaultProcessRunner` already had an env overload; `LauncherProcessRunner`
   mirrors it.
2. **`workspace.mkdirs()` in the step.** `node` allocates a workspace path but
   creates the directory lazily. This step can be the first thing in a build to
   touch it, and a `Launcher` refuses to start a process in a directory that does
   not exist — this surfaced as 11 test failures on the first run. Creating it in
   the step rather than in `LauncherProcessRunner` keeps that class free of
   filesystem side effects.
3. **`GeneralNonBlockingStepExecution`**, so the blocking AssumeRole subprocess
   does not stall the CPS VM thread.
4. **Credentials are held as `hudson.util.Secret`** in both the expander and the
   log filter, because both are serialized into CPS program state.
5. **Region is exported as both `AWS_REGION` and `AWS_DEFAULT_REGION`**, and only
   when configured — exporting `AWS_REGION=""` would override whatever the agent
   would otherwise resolve, which is worse than exporting nothing.

**Deviation: no JCasC test dependency.** The intent was a test that loads real
JCasC YAML. Every available `io.jenkins:configuration-as-code` release fails the
2.479.2 baseline: 1947+ declares `Jenkins-Version: 2.479.3` directly, and the one
older release that declares 2.479.1 (`1932.v75cb_b_f1b_698d`) drags in
`instance-identity:203.x`, which declares 2.479.3 itself. Raising the baseline
would break the M0 guarantee that the plugin loads on CK's actual Jenkins.
`workflow-basic-steps` was dropped for the same reason (same transitive), and the
masking test uses `sh` instead of `echo` — a stronger test anyway, since it
exercises real subprocess output. JCasC compatibility is instead asserted through
its three actual mechanisms (`@Symbol` lookup, structs `DescribableModel`
instantiation, `@DataBoundSetter` push). **Still untested end-to-end: JCasC's own
YAML parsing and `unclassified` routing.** Revisit when CK moves to 2.479.3+.

**Verification — `mvn clean verify`: BUILD SUCCESS.**

| | |
|---|---|
| Tests run | 97 (was 63) |
| Failures / Errors | 0 / 0 |
| Skipped | 1 (pre-existing `InjectedTest` skip) |
| New tests | `CkAwsGlobalConfigurationTest` (14), `LauncherProcessRunnerTest` (7), `CkAwsWithProfileStepTest` (19) |
| Pre-existing tests | all still passing, unmodified |
| Artifact | `target/ck-aws.hpi` |

What the new tests actually prove, beyond registration: credentials reach a real
subprocess inside the block; they are gone after the block; they are masked in
the console even when a shell deliberately echoes them; the body does not run at
all when authentication fails; the `jk-<job>-<build>` session name reaches the
CLI's `--role-session-name`; an unknown profile fails closed listing what is
configured; and `Launcher`/`FilePath` are required context, so the step cannot
run on the controller.

**Not implemented in M6, deliberately:** `ckAws.run([...])` (Layer 2), retry and
timeout, credential refresh, RunListener injection, IAM trust policy.

### Open items carried forward

- **Credential refresh vs the 1-hour chained-session cap** is now the highest
  open risk: real deployment job durations have not been measured, and a block
  that runs past the hour will fail on expiry.
- **`dockerLoginEcr` identity change.** It currently runs on the ambient instance
  role; inside a Layer 1 block it becomes the assumed role, which must therefore
  hold `ecr:GetAuthorizationToken`. Must be verified before rollout.
- **The `--profile` override problem** is the single blocking detail for
  deployment-library migration (M7): an explicit `--profile` beats exported
  environment credentials. Two approaches recorded in CLAUDE.md; the choice is an
  M7 decision.
- **Agent base-identity shape is unconfirmed** — whether `~/.aws/config` on the
  agents uses `credential_source = Ec2InstanceMetadata` or a `source_profile`
  chain determines what the plugin must reproduce.
- **Region is not a constant** across the estate (`us-east-1` in the library,
  `us-east-2` for DR, `AWS_REGION` for Terraform). Always an input.
- BOM / 2.479.3 decision (Session 4) — unchanged.

---

## Session 9 — 2026-08-05

**Plugin validation and Infra Jenkins deployment.** The first session after M7
(deployment-library integration, done in `cln-deployment-scripts` on branch
`ck-aws-plugin`, commit `b36c7925`). No plugin source, tests, `pom.xml`, version
numbers or manifests were changed in this session — the implementation was
treated as frozen throughout.

### Local validation completed

- Plugin built successfully.
- Local Jenkins (`mvn hpi:run`) validation completed successfully.
- Verified `ckAwsWithProfile` execution.
- Verified session naming format: `jk-<job>-<build>`.
- Verified temporary credentials are injected **only inside the block**.
- Verified cleanup after the block.
- Verified STS AssumeRole succeeds.
- Verified `aws sts get-caller-identity` returns the assumed role.
- Verified CloudTrail attribution using the validation role.

Evidence — job `ckaws-sts-validation`, builds #7 and #8, both SUCCESS:

```
[ck-aws] Assuming role arn:aws:iam::685502069032:role/ck-jenkins-plugin-validation-role as session jk-ckaws-sts-validation-8
[ck-aws] Credentials available as session jk-ckaws-sts-validation-8 (expires 2026-08-05T06:54:42Z)
+ aws sts get-caller-identity
    "UserId": "AROAZ7GY3PEUKA3CDG6XM:jk-ckaws-sts-validation-8",
    "Account": "685502069032",
    "Arn": "arn:aws:sts::685502069032:assumed-role/ck-jenkins-plugin-validation-role/jk-ckaws-sts-validation-8"
[ck-aws] Released credentials for session jk-ckaws-sts-validation-8
+ echo after: token=[<unset>] session=[<unset>]
```

Two consecutive builds produced `jk-ckaws-sts-validation-7` and `-8`, confirming
the session name is **build-scoped**, not merely well-formed. The
`after: token=[<unset>] session=[<unset>]` line is the scope-exit proof: the
credentials do not survive the block. Session expiry ≈ 1 hour, consistent with
the known role-chaining cap.

Exactly two AWS APIs were exercised: `sts:AssumeRole` and
`sts:GetCallerIdentity`. No ECS, ECR or SSM calls were made, and no AWS resource
was created or modified.

### CloudTrail verification

Verified that CloudTrail records `GetCallerIdentity` under:

```
arn:aws:sts::<account>:assumed-role/ck-jenkins-plugin-validation-role/jk-<job>-<build>
```

concretely, in account `685502069032` (the same ops account used in Session 5).

The standardized session name appears correctly in CloudTrail.

This confirms that the plugin is generating deterministic build-scoped session
names and that AWS API calls are attributed to the **assumed role** instead of
the base Jenkins identity.

The correlation that matters: the `requestParameters.roleSessionName` on the
`AssumeRole` event equals the session suffix in the `userIdentity.arn` of the
`GetCallerIdentity` event, and that event's
`sessionContext.sessionIssuer.arn` is the validation role rather than
`CKPrism-AdministratorAccess` — proving the identity actually switched rather
than falling through to the base credentials.

### Issues encountered and resolved (both environmental, neither a plugin defect)

**1. Local Jenkins silently executed nothing.** A Declarative pipeline
(`pipeline { agent any … }`) produced only `Start of Pipeline` → `End of
Pipeline` → SUCCESS, with no `node`, no `stage`, no body.

Root cause: `pipeline-model-definition` is not installed in the `hpi:run`
instance, so `pipeline` is **not a step** — but it *is* a registered `@Symbol`,
owned by `WorkflowJob$DescriptorImpl` (workflow-job), the marker JCasC/Job DSL
use to declare a Pipeline *job type*. The call therefore never reaches the
"No such DSL method" path that a genuinely unknown name hits; it resolves as a
describable symbol, the closure is consumed as configuration rather than executed,
nothing runs and nothing throws. A control job using `someUndefinedStep { }`
failed loudly with `NoSuchMethodError`, which is what made the distinction
visible.

Resolution: use **scripted** syntax locally. Declarative cannot be added at the
2.479.2 baseline — installing it via the update centre upgraded the whole
workflow stack past the baseline (`workflow-cps` → needs 2.504.3, `workflow-api`
→ 2.504.1, `structs`/`script-security` → 2.479.3) and knocked out every plugin
including `ck-aws`. The instance was restored by deleting the gitignored
`work/plugins` and letting `hpi:run` re-provision its own 15 dependencies.
`pipeline-stage-step 322.vecffa_99f371c` (manifest verified as requiring core
2.479.1) was then added by file copy to provide `stage`. This is the same
2.479.2-baseline trap recorded in Session 8 for JCasC — it is now confirmed to
apply to the local dev environment as well.

**2. `aws sts assume-role` failed with "Your session has expired."** while
`ck-prism credential-process --profile ops-admin` succeeded standalone (exit 0,
no stderr).

Root cause: `[profile ops-admin]` in `~/.aws/config` carries **both**
`login_session` and `credential_process`. `aws-cli/2.35.1` supports the newer
`aws login` command and resolves `login_session` **first**, treating the profile
as an `aws login` session profile, finding no valid session in
`~/.aws/login/cache/`, and failing — `credential_process` is never invoked. The
error text is the AWS CLI's own (note it says `aws login`, not `ck-prism login`).

Proven by single-variable control: the identical profile with `login_session`
removed returns the correct base identity; with it present, it fails. Ruled out
by evidence: all AWS credential/config environment variables were **unset**;
`aws` is the real CLI binary, not a wrapper; `~/.aws/credentials` has no
`[ops-admin]` section; and the plugin's `LauncherProcessRunner` subprocess was
verified to inherit its environment correctly. The failure reproduces in a plain
shell with no Jenkins involved.

`~/.aws/config` was rewritten at 10:28 on 2026-08-05, matching the ck-prism token
cache mtime — a ck-prism-written annotation has collided with a newer AWS CLI
feature. `ops-admin` is the only profile carrying both keys; `ops-read` and
`prod-read` are unaffected. **This will affect every ck-prism user once their CLI
reaches ≥ 2.3x and is worth raising with whoever owns ck-prism.**

Resolution for validation only: `AWS_CONFIG_FILE` pointed at
`work/aws-config-validation` (gitignored), a copy of the profile without
`login_session`. `~/.aws/config`, `~/.aws/credentials` and `~/.ck-prism/` were
**not** modified.

### Release build

`mvn clean verify` from a clean working tree, `main` @ `5c1b5bb`:

| | |
|---|---|
| Result | **BUILD SUCCESS** |
| Tests | 97 run, 0 failures, 0 errors, 1 skipped (pre-existing archetype `InjectedTest`) |
| Artifact | `target/ck-aws.hpi` |
| Size | 49,690 bytes (M5 was 27,712) |
| SHA256 | `9f6dcf3038d43dee429ee6f8ebf6701e278717588f9852320d456502afd0a63b` |
| Plugin-Version | `1.0-SNAPSHOT (private-5c1b5bbe-radhika)` |
| Short-Name / Long-Name | `ck-aws` / CK AWS Plugin |
| Jenkins-Version | 2.479.2 |
| Build-Jdk-Spec | 21 |
| Plugin-Dependencies | `workflow-step-api:700.v6e45cb_a_5a_a_21` |

Plugin identity confirmed **unchanged** from the installed M5 build (commit
`16c8659`, version marker `private-16c86596-radhika`): `groupId`, `artifactId`,
`packaging` and `name` are byte-identical, so this installs as an in-place
upgrade of `$JENKINS_HOME/plugins/ck-aws.jpi` rather than as a second plugin.
The only pom difference is a **test-scoped** `workflow-durable-task-step`
dependency, which is not packaged.

### Infra Jenkins

The updated plugin (`.hpi`) has been uploaded to Infrastructure Jenkins.

It is currently **pending Jenkins restart** before becoming active.

No further plugin code changes are planned.

### Remaining validation

Next milestone is deployment-library integration testing. After Jenkins restart:

- execute one Backend deployment in **dev2**
- validate ECR authentication
- validate SSM access
- validate ECS deployment
- verify CloudTrail attribution for **all** deployment AWS API calls
- if successful, continue rollout across remaining deployment types

Carry forward into that test (from Sessions 7–8, still unverified):

- `dockerLoginEcr` changes identity under a Layer 1 block — the assumed role must
  hold `ecr:GetAuthorizationToken`. Most likely cause of a first-run failure.
- The 1-hour chained-session cap with no refresh; real deployment duration has
  still not been measured.
- The M7 `AwsAuth.profileGuard` shell prelude fails **safe**, so a run that only
  shows success cannot distinguish "plugin worked" from "silently took the legacy
  `--profile` path". The `set -x` trace must show `CK_AWS_PROFILE=` **empty**
  inside the block, and `--profile <prof>` outside it, or the test proves nothing.

### Current status

| Item | State |
|---|---|
| Plugin implementation | complete |
| Local validation | complete |
| STS validation | complete |
| CloudTrail validation | complete |
| Plugin uploaded to Infra Jenkins | complete |
| Infra Jenkins restart | pending |
| Backend deployment validation | pending |
| Deployment library rollout | pending |

---

## Session 10 — 2026-08-06

**Architecture review only. No code was written, no plugin source, test, `pom.xml`,
version number or manifest was touched.** Documentation was updated at the end, on
explicit instruction.

> **Session 9's closing line — "No further plugin code changes are planned" — is
> superseded.** The wrapper is validated and correct; the *requirement* changed.

### What changed

Between Sessions 9 and 10 the wrapper completed its validation: Infra Jenkins
deployment, Backend UAT deployment, and CloudTrail attribution across ECS, SSM,
KMS, ECR, Docker login, Docker push, `RegisterTaskDefinition` and `UpdateService`.
Technically finished.

The requirement then changed to: **deployment repositories, Jenkinsfiles, shared
libraries, shell scripts, Python, Terraform and `aws` CLI usage must all remain
exactly as they are today.** The plugin must adapt to the ecosystem, not the other
way round. `ckAwsWithProfile` cannot satisfy that — it is opt-in by construction,
so it can be forgotten.

"Non-bypassable" was also clarified by the user to mean **no accidental bypass**
(nothing a developer must remember), *not* protection against hostile scripts or
administrators. That materially narrowed the problem and removed OIDC from the
critical path.

### Decision: M11 — ambient authentication

> *Renamed to **Managed Authentication** in Session 11, and the file location was
> reversed. This entry is kept as written; see Session 11 for what changed.*

Jenkins generates a **per-build AWS config file** and injects it into every
Pipeline step via `DynamicContext.Typed<EnvironmentExpander>`. The file is the one
the agents already have, plus a single line:
`role_session_name = jk-<job>-<build>`. Every AWS tool then performs its own
AssumeRole, natively, under the Jenkins-chosen identity.

Full design: [docs/MANAGED_AUTHENTICATION_DESIGN.md](docs/MANAGED_AUTHENTICATION_DESIGN.md)
(18 sections — lifecycles, failure modes, class design, compatibility matrix,
proofs). Decision and rationale: CLAUDE.md, "M11 — ambient authentication".

### Evidence gathered this session

Everything below was measured or read from bytecode. Nothing was assumed. No AWS
API call was made at any point; the credential tests used fabricated values in a
scratch directory, and `~/.aws/config`, `~/.aws/credentials` and `~/.ck-prism/`
were not read or modified.

**1. An explicitly passed profile deletes the environment provider.**
`botocore/credentials.py:95` — `disable_env_vars = session.instance_variables()
.get('profile') is not None`, then `providers.remove(env_provider)`. Measured
against `aws-cli 2.35.1` / `botocore 1.42.65`:

| Invocation | Credentials resolved from |
|---|---|
| `aws --profile X …` | **config file** — environment ignored |
| `aws …` | environment |
| `AWS_PROFILE=X aws …` | environment |
| `boto3.Session(profile_name="X")` | **config file** |
| `boto3.Session()` | environment |

This is the fact the whole design turns on: 12 of the 13 AWS invocations in the
deployment library pass `--profile`, so exported environment credentials can never
reach them — which is precisely why M7 needed `profileGuard`, i.e. a repository
change.

**2. `AWS_ROLE_SESSION_NAME` cannot substitute for the file.** The tempting
minimal design ("leave `~/.aws/config` alone, inject one variable") is dead: that
variable belongs only to `AssumeRoleWithWebIdentityProvider._CONFIG_TO_ENV_VAR`
(`credentials.py:1879-1886`), the OIDC path. The provider the agents use reads
`role_session_name` from the config file only (`:1643`, `:1693`, `:1956`), and
without it botocore generates `botocore-session-<epoch>` (`:824`).

*Corollary worth keeping:* under a future OIDC design the variable **is**
honoured, so the file-generation layer disappears entirely.

**3. `role_session_name` in a generated file is not "static per profile".**
Earlier documentation stated that pinning `role_session_name` can never carry a
job name or build number. True of an admin-written file; false of a per-build
generated one. That single observation is what makes the architecture possible.

**4. Other measurements**, all with fabricated credentials:

- One `credential_process` invocation **per `aws` process** (3 commands → 3
  invocations) — which is why the native `role_arn` form was chosen over it; the
  AWS CLI caches assume-role results in `~/.aws/cli/cache` instead, giving one
  AssumeRole per (build, profile).
- A `credential_process` helper **inherits `AWS_CONFIG_FILE`** — a real recursion
  hazard for that design, and another reason it lost.
- An unknown profile fails **loudly**: exit 253,
  `The config profile (X) could not be found`.
- With **no `[default]`** in the generated file, an unprofiled command falls
  through to IMDS and behaves exactly as today — no hard error. This is what makes
  "start without a default" a provable no-op for `dockerLoginEcr` and Terraform.
- `credential_source` accepts `Ec2InstanceMetadata`, `EcsContainer` and
  `Environment` (`credentials.py:1157`, `:2070`, `:1187`), so it can be a
  configurable setting rather than a hardcoded EC2 assumption.

**5. Jenkins internals, from bytecode at the pinned versions:**

- `ContextVariableSet.get` (workflow-cps 4046) walks block-scoped `values` first,
  then `ExtensionList.lookup(DynamicContext.class)` — so an explicit
  `ckAwsWithProfile` still wins, and core already holds a `ThreadLocal`
  re-entrancy guard. It does **not** cache `DynamicContext` results, so the
  implementation must memoise.
- `DynamicContext.Typed` is in `workflow-step-api:700.v6e45cb_a_5a_a_21` — already
  a dependency. **No new dependency, no BOM movement, no 2.479.3 trap.**
- `DefaultStepContext` (workflow-support 968) derives `Run`, `Job`, `Node`,
  `Computer`, `Launcher`, `FilePath`, `EnvVars`, `TaskListener` and
  `EnvironmentExpander`. `Node` is what makes the agent-root file location viable.
- `WorkspaceList.tempDir(FilePath)` and `RunListener.onFinalized(R)` both exist in
  core 2.479.2.
- **`PluginManager.dynamicLoad` throws `RestartRequiredException` on the
  "plugin is already installed" branch.** `PluginWrapper.supportsDynamicLoad()`
  returns `MAYBE` when the `Support-Dynamic-Loading` manifest attribute is absent,
  which it is in `target/ck-aws.hpi`. So: a *new* plugin id can be dynamically
  loaded once, on first install; **every upgrade of any plugin requires a
  restart**, and uninstalling requires one too.

### Sub-decisions and why

**Evolve `ck-aws`; do not create a second `ck-aws-platform` plugin.** A second
plugin buys exactly one restart-free event (its first install), then guarantees a
restart for every iteration, and adds one more at retirement — it postpones
nothing. What actually removes restarts from the rollout is the **enable flag**:
with ambient code shipped inert inside `ck-aws`, enabling and disabling it is a
restart-free configuration change, which is faster rollback than uninstalling a
second plugin could ever be. A fork would also mean either duplicating
`CkAwsGlobalConfiguration` (two sources of truth for profiles, the exact thing
Layer 0 exists to prevent) or duplicating `SessionName` (whose `jk-` shape is
load-bearing for the future trust policy and must not be allowed to drift). For
iteration speed the answer is not a second plugin — it is not deploying to Infra
Jenkins until the design is stable; `mvn hpi:run` gives a full loop with zero
production restarts.

**Global configuration only.** Folder-scoped configuration
(`AbstractFolderProperty`) was seriously considered — because a Pipeline's own
Jenkinsfile rewrites its job property list, and all 12 CloudKeeper entry points
declare `options { buildDiscarder(...) }`, so a UI-set *job* property can be
silently deleted on the first build. A folder property is structurally immune.
It was **dropped anyway**: the global mapping is sufficient and the user's
requirement is explicitly "keep it simple, one configuration location".

**File location: the agent root path**, not the workspace and not `@tmp`. An
earlier draft proposed `WorkspaceList.tempDir(workspace)`; that was reversed
because `cleanWs()` deletes `@tmp` siblings by default and `deleteDir()` /
`git clean -fdx` reach the workspace — losing the file mid-build would silently
return the build to the unattributed `--profile` path. The cost is that AWS calls
inside containers are not covered.

**Native `role_arn` + `credential_source`**, not `credential_process` and not
static credentials. Reasons in evidence item 4 and the design document §2.2.

### Corrections to earlier documentation, made this session

- **The `hudson.util.Secret` / CPS-program-state claim was wrong and is now
  corrected in CLAUDE.md.** `Secret` declares no `writeObject`/`writeReplace`, so
  it is *not* encrypted under plain Java serialization; the wrapper's
  `EnvironmentExpander` therefore does hold credential material in CPS program
  state. This was found during the Session 8/9-era freeze review and had remained
  uncorrected. M11 removes the exposure rather than restating the claim — the
  ambient path puts no credentials in the environment at all.
- The `credential_process`-on-the-agent direction and the folder-property
  direction, both recorded in CLAUDE.md's earlier "Direction under investigation"
  section, are now recorded as rejected with reasons rather than as preferred.
  History was preserved, not deleted.

### Limitations accepted before implementation

1. **A profile name used by a repository but absent from the Jenkins mapping fails
   the build.** Loud, never silently wrong, but a failure. Mitigation is a
   pre-rollout inventory of every `--profile` string. Largest operational risk.
2. AWS calls inside containers are not covered (file lives outside the workspace).
3. Freestyle jobs are not covered — `DynamicContext` is Pipeline-only.
4. Unprofiled calls are unattributed unless a `[default]` is configured;
   provably a no-op until then.
5. Controller-side AWS calls by other Jenkins plugins remain unattributed.
6. AWS SDK for Java v1 `credential_source` support is limited — flagged, not
   assumed.

### Documentation changed this session

| File | Change |
|---|---|
| `docs/MANAGED_AUTHENTICATION_DESIGN.md` | **New.** The 18-section implementation design |
| `CLAUDE.md` | Status table (M11); the "Direction under investigation" section replaced by "M11 — ambient authentication"; Layer 1 supersession note refined to Layer 1A/1B; configuration reference gained the three new settings; migration Stages 1–5 marked historical and Stage 6 rewritten; 1-hour cap marked resolved for the ambient path; Definition of Done — M11 added; M6 DoD corrected re `Secret`; five new "What NOT to do" rules |
| `README.md` | Status, a "Planned: ambient authentication" section, and the limitations list |
| `MEMORY.md` | This entry |

### Open questions blocking implementation

1. Does Infra Jenkins run any **freestyle** job that calls AWS?
2. Are the three new settings on the existing CK AWS page acceptable?
3. Does any consumer run `aws`/boto3 **inside a container**?
4. `[default]` profile — configure one now, or leave unset until Stage 5?
   (Recommendation: leave unset.)

### Current status

| Item | State |
|---|---|
| Wrapper implementation (M6/M7) | complete and validated |
| Backend UAT deployment via wrapper | complete |
| M11 design | complete, awaiting approval |
| M11 implementation | **not started — no code written** |
| Pre-rollout profile-name inventory | not started |

---

## Session 11 — 2026-08-06

**Final architecture review before implementation. No code written; no plugin
source, test, `pom.xml`, version or manifest touched.** The task was explicitly to
*try to disprove* the Session 10 design. It survived, with one reversal, four new
findings and one rename.

### Rename

**"Ambient Authentication" → "Managed Authentication."** The name now describes
behaviour rather than mechanism: the plugin *manages* AWS authentication for
Jenkins builds. `docs/AMBIENT_AUTHENTICATION_DESIGN.md` was replaced by
`docs/MANAGED_AUTHENTICATION_DESIGN.md`; Session 10 above is kept as written.

*(The old file had also acquired a pasted copy of the previous prompt in its
header; writing the renamed file resolved that.)*

### Reversal — where the generated file lives

Session 10 chose `<agent root>/ck-aws/<run>/`. **Reversed to
`<workspace>@tmp/ck-aws/`** on three counts:

1. **It eliminates the container limitation.** `docker.image().inside { }`,
   Declarative `agent { docker }` and Kubernetes agents make the workspace and its
   `@tmp` sibling visible inside the container; the agent root is not visible.
   Session 10 listed containers as an accepted limitation; the user asked whether
   it could be eliminated cleanly, and this is the clean elimination.
2. **It bounds storage without relying on cleanup.** The path is stable per
   workspace and overwritten every build, so the footprint is
   `workspaces × ~1 KB` rather than `builds × 1 KB`. The orphan-sweeper
   (`ComputerListener`) proposed in Session 10 was **deleted from the design** —
   cleanup is now hygiene, not correctness.
3. **Losing the file fails loudly.** Measured: `AWS_CONFIG_FILE` pointing at a
   missing path plus `--profile X` exits **253**,
   `The config profile (X) could not be found`. There is no silent fallback to an
   unattributed identity, which is what makes the residual `cleanWs()` exposure
   tolerable.

`@tmp` is a *sibling* of the workspace, so `deleteDir()`, `git clean -fdx` and
`stash` (workspace-rooted) cannot reach it. `cleanWs()` can — handled by verifying
existence **once per `node` block**, keyed on the enclosing `ExecutorStep`
FlowNode id, never per step.

Also established: **no regression for containers either way.** A container has no
`~/.aws/config` today, so no existing pipeline can be using `--profile` inside
one; and an unprofiled call with `AWS_CONFIG_FILE` pointing at a path the
container cannot see falls through to IMDS exactly as today (measured).

### Execution-mode audit (the disproof attempt)

The decisive enabling fact, from `ContextVariableSet` bytecode: `values` is
`final`, `get()` **never writes to it**, and the only static state is a
`ThreadLocal` re-entrancy guard. **Core never caches `DynamicContext` results** —
they are recomputed on every query. That is why node changes, parallel branches
and multi-agent builds resolve correctly rather than freezing a stale path.

| Mode | Verdict |
|---|---|
| Shared libraries, `parallel`, `matrix`, `retry`, `timeout` | ✅ |
| Multiple `node` blocks / multiple agents | ✅ — each gets its own file and its own exported path |
| `input` (incl. across a controller restart) | ✅ — the memo is in-memory only, so a restart causes a rewrite |
| `stash`/`unstash` | ✅ — `stash` is workspace-rooted; the config is a sibling and is never shipped |
| `ws()`, `dir()` | ✅ |
| Multibranch | ⚠️ — works, but see the truncation finding below |
| `docker.image().inside{}`, `agent { docker }`, Kubernetes | ⚠️→✅ expected after the location change; mount behaviour is the one unverified claim |
| Freestyle jobs | ❌ — `DynamicContext` is Pipeline-only |

### New findings

**1. Session-name collision in deep multibranch hierarchies.** `SessionName`
truncates the middle (job) segment to stay within STS's 64 characters, so two
branches sharing a long prefix produce the same `jk-<truncated>-<build>`. It is an
attribution defect, not a security one, and it is **pre-existing since M1** —
Managed Authentication merely makes it fleet-wide. Fixing it means appending a
short deterministic hash on truncation, which changes a load-bearing convention,
so it is recorded as **a separate decision, deliberately not folded into M11**.

**2. Memo leak.** Because core does not cache `DynamicContext` results, the plugin
must memoise — and that memo must be **evicted in `onFinalized`**, or it grows one
entry per node block forever. Recorded as a hard implementation constraint, with a
`WeakHashMap` keyed on `Run` as belt and braces.

**3. INI injection.** The renderer interpolates admin-supplied profile names and
role ARNs into an INI file; a value containing `\n`, `[` or `]` could inject
arbitrary keys, including `credential_process`. Only administrators can configure
profiles, so severity is low, but the fix is trivial and must be applied in **both**
the form validation and the renderer, because JCasC bypasses form validation.

**4. Profile names cannot contain whitespace.** Measured round trip through a
generated file: `non_prod`, `prod`, `ops`, `sandbox`, `finance`, `engineering`,
`qa`, `with-dash`, `with_underscore`, `with.dot`, `Mixed_Case9`, `with:colon` and
`with/slash` all resolve; `with space` is **rejected by botocore's config parser**.
Form validation must reject it. Genericity is otherwise confirmed — arbitrary
organisation-chosen names work without plugin changes.

### Performance and scalability, quantified

Per build (1 node block, ~50 steps, 13 `aws` invocations, 1 profile):

| | |
|---|---|
| `get()` on a memo hit | ~1 µs — one map lookup, no remoting |
| `get()` on a memo miss | 3 remoting round trips (`exists`, `mkdirs`, `write`), once per node block |
| Cleanup | 1 remoting round trip at finalize |
| `sts:AssumeRole` | **1 per (build, profile)** — cached in `~/.aws/cli/cache`; boto3 does 1 per Python process |
| Jenkins-side overhead | **≈ 5 ms per build** |
| AWS-side overhead | one AssumeRole, ~150–400 ms, once |

At 5,000 builds/day: **0.06 STS req/s** average (orders of magnitude below any AWS
limit); +5,000 CloudTrail management events/day (free in the first trail);
~200 live memo entries at 200 concurrent builds; agent disk bounded by workspace
count. No timers, no thread pools, no sweeper.

The one thing that would not scale — an `exists()` or a write **per step** — is
explicitly designed out and recorded as implementation constraint #1.

### Criteria tension surfaced

"Existing infrastructure repositories require zero modifications" and "CloudTrail
continues producing `jk-<job>-<build>`" **conflict for `cln-infra-terraform`**,
which passes no profile at all. Attributing it without repository changes requires
configuring a `[default]` profile, which simultaneously changes
`Utilities.dockerLoginEcr`'s identity (needs `ecr:GetAuthorizationToken`). Both
criteria cannot be met for that repository until that permission question is
settled. Deferred to its own rollout stage; starting without a default is a
provable no-op.

### Unverified claims, recorded before implementation

1. `docker.image().inside { }` mounts `<workspace>@tmp` and propagates the build
   environment — docker-workflow is not a dependency, so its bytecode was not
   inspected.
2. Kubernetes agents share the workspace volume across pod containers.
3. Terraform's AWS provider honours `AWS_CONFIG_FILE` + `role_arn` +
   `credential_source`.
4. `cleanWs()` deletes `@tmp` siblings (assumed as the worst case).
5. JCasC end-to-end YAML load — a pre-existing M6 gap at the 2.479.2 baseline.

### Documentation changed this session

| File | Change |
|---|---|
| `docs/MANAGED_AUTHENTICATION_DESIGN.md` | **New**, replacing `AMBIENT_AUTHENTICATION_DESIGN.md`. 21 sections: adds the execution-mode audit, the container analysis, the location reversal, quantified performance and scalability, implementation constraints, unverified claims, and a Definition of Done |
| `docs/AMBIENT_AUTHENTICATION_DESIGN.md` | **Deleted** (renamed) |
| `CLAUDE.md` | M11 renamed to Managed Authentication; naming note; file-location reversal recorded with reasons; `ContextVariableSet`-never-caches fact added; limitations extended from 5 to 7; config field renamed `managedAuthentication`; pointers updated |
| `README.md` | Section renamed; new file location; container coverage and bounded-footprint properties; limitations refreshed |
| `MEMORY.md` | This entry, plus a forward pointer on Session 10 |

### Current status

| Item | State |
|---|---|
| M11 design | **finalized — implementation approved to begin** |
| M11 implementation | not started — no code written |
| Pre-rollout profile-name inventory (Stage 0) | not started |
| Freestyle-job question | open |
| Container-mount verification | open |
| Session-name truncation decision | open, deliberately separate from M11 |

---

## Session 12 — 2026-08-06

**M11 Managed Authentication implemented and validated locally against real AWS.**
First session in which M11 code exists. Nothing was uploaded to Infrastructure
Jenkins, committed or pushed.

### Final decisions incorporated before coding

Nine decisions were fixed before implementation and the design document was
updated first, as instructed. Eight were already satisfied by the finalized
design; **one changed the code**: a profile now declares an explicit
**authentication mode** — `AssumeRole` (cross-account, attributed) or
`InstanceProfile` (same-account, the agent's own identity) — rather than the
plugin inferring it from whether a role ARN happened to be blank. Inference would
turn a mistyped or cleared ARN into a silent downgrade to "no authentication",
which is the exact failure the plugin exists to prevent. Declared, the same
mistake is a form error.

### What was built

| File | Role |
|---|---|
| `managed/ManagedAwsFiles.java` | Pure renderer: the generated `config`, the empty `credentials`, and one helper script per assume-role profile. No Jenkins imports, so the file format is unit-tested without `JenkinsRule` |
| `managed/ManagedAwsContext.java` | `DynamicContext.Typed<EnvironmentExpander>` — the injection point. Memoised per `(run, workspace)`; hot path is a map lookup with no I/O |
| `managed/ManagedAwsAction.java` | Persisted bookkeeping of generated directories, so cleanup survives a controller restart |
| `managed/ManagedCleanupListener.java` | `RunListener.onFinalized` — deletes the directory and evicts the memo, for every terminal build state |
| `config/AwsProfile.java` | Gained `mode`; `hasRole()`/`isUsable()` are mode-driven; INI-injection validation on names and ARNs |
| `config/CkAwsGlobalConfiguration.java` | Gained `managedAuthentication`, `jobNamePattern`, `credentialSource`; `configure()` override so a cleared field can actually be cleared |
| `steps/CkAwsWithProfileStep.java` | Fails closed on an instance-profile-mode profile: there is nothing to assume |

Version bumped `1.0` → `1.1`. Plugin id, short name and artifact identity
unchanged, so this installs as an in-place upgrade.

### How one AssumeRole per (Run, Profile) is achieved

The generated `config` points each assume-role profile at a plugin-written helper
via `credential_process`. The helper checks a workspace-private cache before
calling STS, so the first AWS consumer in the build pays for the AssumeRole and
every later one — any `aws` command, boto3 session, Terraform run or
`docker login` — reuses the same session. The helper emits the exact
`credential_process` JSON shape using the AWS CLI's own `--query`, so no `jq` is
needed on the agent.

It is also **lazy**: only profiles a build actually uses are ever assumed.
Validated below — three profiles were configured in assume-role mode and only the
one the pipeline referenced produced a session cache.

### Build and test results

`mvn clean verify` — **BUILD SUCCESS**. Tests **145**, failures 0, errors 0,
skipped 1 (pre-existing archetype `InjectedTest`). SpotBugs `BugInstance size is
0`; Spotless clean across 40 files. New: `ManagedAwsFilesTest` (21, plain JUnit),
`ManagedAwsContextTest` (19, `@WithJenkins`); `CkAwsGlobalConfigurationTest` grew
14 → 22.

Two pre-existing assertions changed deliberately, each with a comment saying why:
a blank role ARN is no longer unconditionally an error (it depends on the declared
mode), and a role-less profile is now usable in instance-profile mode.

### Local validation against real AWS

Local Jenkins (`mvn hpi:run -Dport=8081`), plugin `1.1-SNAPSHOT` active. Four
profiles configured through Manage Jenkins only: `ckvalidation`, `non_prod`,
`prod` (assume-role) and `ops` (instance-profile). The pipeline was an **ordinary
Jenkinsfile** — `node { sh 'aws sts get-caller-identity --profile ckvalidation' }`
×3 — with no wrapper, no plugin step and no import.

| Build | Flag | Session observed |
|---|---|---|
| #2 | **OFF** | `botocore-session-1786026256` — the zero-attribution status quo |
| #4 | **ON** | `jk-ckaws-managed-validation-4` on all three calls |
| #5 | **OFF** again | `botocore-session-1786026256` — identical to #2 |

Verified:

- **One AssumeRole per build**: the session cache mtime was identical after call 1
  and after call 3 (`1786026666`), while all three calls returned the same
  assumed-role ARN.
- **Laziness**: only `.session-ckvalidation.json` was created. `non_prod` and
  `prod` were configured and rendered but never assumed.
- **Generated directory**: `<workspace>@tmp/ck-aws/`, mode `0700`, files `0600`.
- **Cleanup**: the directory was **gone** after the build; the `@tmp` parent
  remained empty. No sweeper, no timer.
- **Instance-profile mode**: `[profile ops]` rendered with no credential keys, so
  the AWS SDKs fall through to the agent's identity.
- **Feature flag**: toggled ON and OFF **without restarting Jenkins**, and the
  setting survived a restart.
- **Backward compatibility**: with the flag off, `AWS_CONFIG_FILE` was the build's
  own value, `CK_AWS_SESSION_NAME` was unset, no `[ck-aws]` line appeared in the
  log, and no directory was generated.

Only `sts:AssumeRole` and `sts:GetCallerIdentity` were exercised. No resource was
created, updated or deleted; no ECS, SSM, ECR or IAM call was made.

### Defect found and fixed during validation

The first ON run failed with
`InvalidClientTokenId ... The security token included in the request is invalid`.

Root cause, established by dumping the generated helper rather than guessing: the
helper restores the AWS environment so its inner `sts assume-role` resolves the
agent's real base identity, and the plugin was reading that environment from
`Run#getEnvironment(listener)`. **That is the build's environment — Jenkins
variables, contributors, build parameters — not the operating-system environment a
child process inherits.** It returned nothing, so the helper emitted `unset` for
variables that were genuinely set, and the inner call authenticated as the wrong
identity.

Fixed by reading `Computer#getEnvironment()`, which is the agent process's own
environment. Worth recording because on a real agent — where none of these
variables are set — both sources look identical, so this would have shipped
unnoticed and failed only on a machine with a base profile configured.

### Open items carried forward

- Container mounting (`docker.image().inside{}` mounting `@tmp`) is still
  **unverified** — the design's one load-bearing unverified claim.
- Freestyle jobs remain uncovered; whether Infra Jenkins has any that call AWS is
  still an open question.
- The pre-rollout inventory of every `--profile` name used across repositories has
  **not** been done. It is the gating task before enabling the flag in production.
- `[default]` profile still deliberately unset, so unprofiled calls
  (`dockerLoginEcr`, Terraform) stay on today's behaviour.
- Session-name truncation collision in deep multibranch hierarchies — a separate
  decision, deliberately not folded into M11.

### Current status

| Item | State |
|---|---|
| M11 implementation | complete |
| Unit + integration tests | 145 pass, 0 failures |
| Local validation, real AWS, ON and OFF | complete |
| One-AssumeRole-per-build proof | complete |
| Cleanup proof | complete |
| Upload to Infra Jenkins | **not done — awaiting approval** |
| CloudTrail verification in Ops account | pending manual check |

---

## Session 13 — 2026-08-06

**Architecture review only. No code written; no plugin source, test or `pom.xml`
touched.** The requirement changed again after M11 was implemented and locally
validated, and the review concluded that **M11 as built does not satisfy it.**

### The requirement, restated

Every AWS API call originating from Jenkins must become attributable in CloudTrail
to the build — AWS CLI, boto3, Terraform, Docker ECR login, Java/Go/Python SDKs,
shell scripts, shared libraries, existing repositories and future ones. The only
intended behavioural difference is the session name. And explicitly: **the
administrator will not enumerate or configure each repository's authentication
path.** Some use profiles, some IMDS, some Terraform, some raw SDKs. Whatever a
pipeline does today must keep working untouched and simply become attributed.

### Answer: not fully achievable with a plugin alone, and the limitation is AWS

Three facts, verified against the STS service model shipped with the AWS CLI and
the botocore provider chain:

1. **Only three AWS operations accept a caller-chosen session name:**
   `AssumeRole` and `AssumeRoleWithWebIdentity` (`RoleSessionName`), and
   `GetFederationToken` (`Name` — IAM users only, so unavailable to an instance
   role). `GetSessionToken` has no name parameter. **Attribution is only ever a
   by-product of assuming a role.**
2. **An instance-role session's name is assigned by EC2 and is immutable**
   (`assumed-role/<role>/i-<instance-id>`). There is no request in which a caller
   could influence it.
3. **So a build authenticating through IMDS cannot be attributed unless something
   assumes a role for it — which requires a trust policy permitting it.** Trust
   policies live in IAM, which a plugin cannot change.

The limitation is **AWS (STS + EC2)** — not Jenkins, not the SDKs, not Terraform,
not Docker.

### Why M11 as implemented fails the requirement

- **It requires enumeration.** It replaces `AWS_CONFIG_FILE` with a file built
  only from the Jenkins mapping, so any profile name not configured in Jenkins
  fails the build (`The config profile (X) could not be found`).
- **It leaves IMDS pipelines unattributed** — they fall through to the instance
  role exactly as today.

Both were known and documented as limitations when M11 was designed; the change is
that they are now disqualifying rather than acceptable.

Nothing is wasted: the injection point, `<workspace>@tmp/ck-aws/`, the lifecycle,
cleanup, the feature flag, the off-is-invisible property and one-AssumeRole-per-
(Run, Profile) all carry forward. **Only the content of the generated file
changes.**

### The decision: M12 — overlay, do not replace

- **Layer A — attribute what already exists, without naming any of it.** Copy the
  agent's own AWS configuration into the build's private temp directory and inject
  one line into every profile that assumes a role:
  `role_session_name = jk-<job>-<build>`. `role_arn`, `source_profile`,
  `credential_source`, region and MFA settings are copied verbatim — the
  mechanism is preserved, only the label is added. No enumeration; a profile added
  to an agent tomorrow is attributed on its next build.
- **Layer B — attribute the unprofiled path.** A `[default]` that assumes the
  agent's own role, discovered at runtime by one `sts:GetCallerIdentity` per agent
  and cached. This is the layer that needs **one IAM trust-policy edit per
  account** (self-assumption, or a same-permission companion role). Until that
  edit exists the layer stays off and unprofiled calls behave exactly as today.
- **Layer C — optional, later.** `AWS_CONTAINER_CREDENTIALS_FULL_URI`. Verified
  from the botocore chain (`post_profile = [..., container_provider,
  instance_metadata_provider]`): it outranks IMDS but loses to an explicit
  profile. Catches clients that ignore shared config, at the cost of a local
  credential endpoint. Not needed for v1.

The Jenkins `profile → roleArn` mapping demotes from **source of truth** to
**optional override**.

### A founding rule is reversed, and it needs explicit agreement

Since M6 the architecture has said **"the plugin must never read
`~/.aws/config`"**, because the identity *decision* had to be Jenkins-owned. The
M12 requirement inverts that premise: the agent's existing decision must be
**preserved**, with Jenkins contributing only attribution — which cannot be done
without reading the file.

Recorded as a deliberate reversal with the trade-off stated: **Jenkins stops being
the authority on which role a build assumes, and becomes the authority on how that
assumption is labelled.** The old rule still governs the override path. This was
flagged to the user rather than applied silently, and implementation is blocked on
their agreement.

### What remains unreachable, whatever is built

1. Clients that pin a credential provider in code (e.g. an explicit
   `InstanceProfileCredentialsProvider`).
2. AWS calls inside containers with nothing mounted.
3. Controller-side AWS calls by other Jenkins plugins.
4. Deliberate bypass by someone with `sh` on an agent.

Truly universal attribution needs per-build federated identity (OIDC /
`AssumeRoleWithWebIdentity`) plus removing the instance-profile principal from
those trust policies — an infrastructure programme, not a plugin.

### Open items before M12 implementation

- **Explicit agreement to reverse the no-read rule.** Blocking.
- The IAM trust-policy edit for Layer B.
- Role **path** is not recoverable from an assumed-role ARN
  (`assumed-role/<name>/<session>` omits it), so runtime discovery of the agent's
  role ARN needs `iam:GetRole`, or an administrator override where roles use
  paths.
- Whether AWS SDK for Java **v1** honours `role_session_name` from shared config.
- Carried forward from Session 12, unchanged: container mounting unverified;
  freestyle jobs uncovered.

### Documentation updated this session

`CLAUDE.md` (M12 section, status table, the rule reversal),
`docs/MANAGED_AUTHENTICATION_DESIGN.md` (superseded-content banner naming which
sections still apply), `README.md` (status and the M12 note), and this entry.
Session 12 and earlier were left exactly as written.

### Current status

| Item | State |
|---|---|
| M11 implementation | complete, locally validated, **insufficient for M12** |
| M11 artifact `ck-aws.hpi` 1.1-SNAPSHOT | built, **not uploaded** |
| M12 design | agreed in principle |
| M12 implementation | **not started — blocked on the no-read rule reversal** |
| Infra Jenkins upload | not done |

---

## Session 14 — 2026-08-06

**Architecture review only, refining M12. No code written.** Two questions were
asked and both are answered affirmatively, with one AWS limitation restated
precisely.

### Question 1 — can both modes produce `jk-<job>-<build>`?

Yes, but the mechanism is narrower than "obtain base credentials from IMDS, then
create a managed session". Verified from the STS service model shipped with the
AWS CLI:

| Operation | Required input | Can carry a chosen name |
|---|---|---|
| `AssumeRole` | `RoleArn` **and** `RoleSessionName` | yes — a target role ARN is mandatory |
| `GetFederationToken` | `Name` | **no** — AWS documents it as requiring *"the long-term security credentials of an IAM user"*, so an instance role cannot call it |
| `GetSessionToken` | *(none)* | no name parameter exists |

So **there is no way to mint a named session without assuming a role.** The two
modes therefore differ in exactly one thing — where the *target* role ARN comes
from. Base credentials are IMDS either way, because that is how the agent
authenticates.

| Mode | Target role | Permissions vs today | IAM change |
|---|---|---|---|
| `AssumeRole` | the configured ARN | those of the target role | none, if the trust already exists |
| `InstanceProfile` | **the agent's own role** (self-assumption) | **identical** | **one trust-policy edit** |

**The exact limitation is IAM, not Jenkins:** a role does not trust itself by
default — an EC2 instance role's trust policy names `ec2.amazonaws.com` only — so
self-assumption is denied until an administrator adds the role's own ARN as a
principal. Whether a same-account self-assumption *also* needs `sts:AssumeRole` in
the role's identity policy is recorded as must-verify rather than asserted.

**This redefines `InstanceProfile` mode**, which M11 implemented as "render a
profile with no credential keys, fall through, stay unattributed". From M12 it
means "self-assume the agent's own role for attribution" — turning the mode from a
documented gap into a solution.

### Question 2 — can the plugin select the profile from job-name patterns?

Yes, entirely Jenkins-side, and it is better than the runtime role discovery
Session 13 proposed. An ordered `pattern -> profile` list matched against a job's
full name, first match wins (`prod/.* -> prod`, `uat/.* -> non_prod`,
`dev.* -> non_prod`, `ops/.* -> ops`).

The selected profile becomes the `[default]` in the generated config — the
identity for every AWS call that does **not** name a profile, which is exactly the
gap (Terraform, `dockerLoginEcr`, raw SDKs, bare shell scripts). An explicit
`--profile X` still resolves to X: a command-line flag is the pipeline stating an
intent, and overriding it would change behaviour rather than label it.

This replaces M11's single on/off `jobNamePattern`, which only gated; the rules
both gate and select. It also removes the need for `iam:GetRole` and role-path
reconstruction, since the administrator states the mapping once per folder.

### Question 3 — jobs matching no rule

Confirmed, and already the implemented behaviour: the plugin returns before
touching anything — no environment variable, no file, no AWS call, no log line.
Identical to the "switch off" and "no profiles configured" paths, which are
covered by tests.

### Does this give full attribution while preserving behaviour?

| Job shape | Attributed | Behaviour preserved |
|---|---|---|
| Matched, `--profile X` where X assumes a role | yes | yes — identical role and permissions |
| Matched, no profile, rule → `InstanceProfile` | yes | **yes — identical permissions** |
| Matched, no profile, rule → `AssumeRole` | yes | **no — permissions become the target role's** |
| Unmatched | no, unchanged | yes — untouched |
| Client pinning a credential provider in code | no | yes |
| AWS call in a container with nothing mounted | no | yes |

The third row is the decision point. Mapping a folder that today runs on the bare
instance role to `AssumeRole -> non_prod` attributes it with no IAM change, but its
effective permissions change and it breaks if the instance role holds anything the
target role does not. **`InstanceProfile` mode is the one that satisfies "exactly
as they do today", at the cost of the trust-policy edit** — the irreducible price
of attributing an IMDS identity.

### Documentation updated

`CLAUDE.md` (new "two authentication modes" section, Layer B rewritten around job
rules, configuration reference, open items),
`docs/MANAGED_AUTHENTICATION_DESIGN.md` (banner extended with the two
refinements), and this entry. No source, tests or `pom.xml` touched.

### Still blocking M12 implementation

- Agreement to reverse the "never read `~/.aws/config`" rule (Session 13).
- The IAM trust-policy edit for `InstanceProfile` mode, and confirmation of
  whether an identity-policy grant is needed alongside it.
- Which folders map to which profile, in which mode.

---

## Session 15 — 2026-08-06

**M12 Managed Authentication implemented and validated against real AWS.** The
plugin is now a *decorator*: it reads the executing node's own AWS configuration
and adds a session name to it. It never decides identity.

### Three defects found before or during implementation

All three were found by testing, not by reasoning, and all three would have
reached production.

**1. Blanking `AWS_SHARED_CREDENTIALS_FILE` breaks `source_profile` chains.**
Measured: exit 253, *"The source_profile 'base' referenced in the profile
'chained' does not exist."* M11 did exactly this. M12 never touches the variable —
under a decorator there is nothing to shadow, and leaving it alone also avoids
copying static keys into the workspace.

**2. A `[default]` profile with a `role_arn` is authoritative.** Measured: when its
AssumeRole fails, the call exits non-zero rather than falling through to IMDS.
**So a speculative self-assuming `[default]` would break every unprofiled call
whose trust policy does not permit it** — the opposite of fail-safe. This is the
technical reason M12 ships without IMDS self-assumption rather than attempting it
optimistically.

**3. A literal NUL byte in `ManagedAwsAction`'s separator.** Jenkins persists
actions as XML, where `0x00` is not a legal character; every build logged
*"Failed to serialize … #locations"* and no cleanup location survived. Fixed by
modelling the pair as a nested `Location` type — removing the need to choose a
safe delimiter rather than choosing one more carefully.

### Implementation

| File | Role |
|---|---|
| `managed/AwsConfigOverlay.java` | **New.** Pure line-based decoration. Never parses and regenerates |
| `managed/ManagedAwsContext.java` | **Rewritten.** Discovers the node's config, decorates, writes, exports two non-secret variables |
| `managed/ManagedAwsRecord.java` | **New.** Cleanup bookkeeping, split out of the hot path |
| `managed/ManagedAwsAction.java` | Rewritten around a nested `Location` type |
| `managed/ManagedAwsFiles.java` | **Deleted** — the credential_process helper approach is gone |

The helper-script/`credential_process` design was removed deliberately. It
*replaced* the node's authentication mechanism; the requirement is to *preserve*
it. Native `role_arn` also works with AWS SDK for Java v1, which does not support
`credential_process`. The trade-off accepted: several AssumeRole calls per build
(one per process family) instead of exactly one — all under the same session name,
so CloudTrail attribution is unaffected.

Version `1.1` → `1.2`.

### Validation

`mvn clean verify` — **BUILD SUCCESS**, 138 tests, 0 failures, 1 skipped
(pre-existing), SpotBugs 0, Spotless clean. New: `AwsConfigOverlayTest` (17, plain
JUnit), `ManagedAwsContextTest` (16, rewritten).

Local Jenkins, plugin `1.2-SNAPSHOT`, **zero profiles configured in Jenkins** — the
node's config was the only source. Ordinary Jenkinsfile, no plugin references.

| Build | Flag | Session observed |
|---|---|---|
| #1 | OFF | `botocore-session-1786036150`, node config untouched |
| #2 | ON | **`jk-ckaws-m12-2`** on all three calls |
| #3 | OFF again | `botocore-session-1786036150` — identical to #1 |

The decorated file showed the node's config copied verbatim — comments, the
`[profile ops-admin]` `credential_process` line, and `[profile ckvalidation]`'s
`role_arn` / `source_profile` / `region` — **plus exactly one added line**. This
run also exercised a `source_profile` chain, a different code path from the
`credential_source` form the deployment agents use.

Also verified: cleanup removed the directory; an unknown profile failed exactly as
it does today (`The config profile (nosuch_profile) could not be found`) while
decoration itself succeeded; ON/OFF toggled with no restart; no credentials
exported; `AWS_SHARED_CREDENTIALS_FILE` left unset.

### Not validated, and why

**IMDS self-assumption is not implemented and could not be honestly validated.**
There is no IMDS on a development machine, and `ck-jenkins-plugin-validation-role`
trusts `CKPrism-AdministratorAccess` only (read-only `iam:GetRole`) — it cannot
assume itself, and this project will not modify it. Combined with defect 2 above,
attempting it speculatively would risk breaking builds. Documented as an AWS/IAM
limitation.

### Current status

| Item | State |
|---|---|
| M12 implementation | complete |
| Tests | 138 pass |
| Local validation, real AWS, ON and OFF | complete |
| IMDS attribution | **not implemented — AWS/IAM limitation** |
| Upload to Infra Jenkins | **not done — awaiting approval** |

---

## Session 16 — 2026-08-06

**Final production build.** One correctness defect found by review and fixed; the
deployment-agent authentication shape reproduced faithfully and validated. No new
features.

### Defect: fail-open did not cover the whole contribution path

Asked to confirm that *any* unexpected exception falls back without failing the
build, the honest answer was no. The guard wrapped only `prepare()`, which left
outside it:

| Statement | Throws |
|---|---|
| `CkAwsGlobalConfiguration.get()` | `IllegalStateException` if Jenkins is unavailable |
| `context.get(Run.class)` | `IOException` |
| `context.get(FilePath.class)` | `IOException` |
| `context.get(TaskListener.class)` | `IOException` |
| `catch (Exception e)` | does not catch `Error` — `LinkageError`, `NoClassDefFoundError` |

`DelegatedContext.get` is declared `throws IOException, InterruptedException` —
confirmed from the interface bytecode, so these were real checked exceptions, not
theoretical. Anything escaping propagates through `ContextVariableSet` into the
step and **fails the build** — the one outcome the design forbids.

**Fixed:** the entire contribution path now runs inside `ManagedAwsContext.guarded`,
which catches `Throwable` and re-throws only `InterruptedException` (so an aborted
build stays aborted). The outermost handler performs **no console I/O**, since it
is reached in states where attempting it could itself throw; expected, diagnosable
failures are still reported to the build log one level in.

`Throwable` rather than `Exception` is deliberate: a classloading failure after an
upgrade would otherwise break every step on the controller rather than one build.

Six regression tests added (`ManagedAwsGuardTest`), including the two that matter:
an `Error` contributes nothing, and an `InterruptedException` still propagates.

### The deployment-agent shape, reproduced rather than assumed

Session 15 validated a `source_profile` chain; the deployment agents use
`credential_source = Ec2InstanceMetadata`, a different botocore code path. That gap
was closed rather than argued away.

`AWS_EC2_METADATA_SERVICE_ENDPOINT` is honoured by the SDK
(`botocore/configprovider.py:85-87`), so a local IMDS stand-in and a local STS
stand-in were used to exercise the real path with the agent's exact configuration.
No real AWS calls; the stand-ins serve obviously fake values.

| State | `uat-deploy2` | `prod-deploy2` |
|---|---|---|
| Flag OFF (post-upgrade state) | `botocore-session-1786037672` | `botocore-session-1786037672` |
| Flag ON, pattern `uat-.*` | **`jk-uat-deploy2-2`** | `botocore-session-1786037672` — untouched |
| Flag OFF again | `botocore-session-1786037672` | — |

Matched and unmatched jobs, in the same instance at the same moment: one gains
attribution, the other is byte-identical to today.

### Two harness defects found and fixed along the way

Worth recording because both initially looked like product defects:

1. **The first fake STS answered `GetCallerIdentity` from the most recent
   `AssumeRole` it had seen**, so a build that hit the AWS CLI's credential cache
   reported someone else's session. Corrected to issue a unique access key per
   assumption and answer from the credential actually presented.
2. **A stale CLI cache entry masked a run.** Chasing it found
   `botocore/credentials.py:833-838`: an *auto-generated* session name is stripped
   from the assume-role cache key, while an *explicitly configured* one is kept.
   This is a **good** property — it means each build's session name produces its own
   cache entry and builds cannot inherit one another's session. Confirmed in
   practice: consecutive builds produced `jk-agent-shape-5` and `-6`.

### Build

`mvn clean verify` — **BUILD SUCCESS**. Tests **144**, failures 0, errors 0,
skipped 1 (pre-existing archetype `InjectedTest`). SpotBugs `BugInstance size is
0`; Spotless clean across 42 files.

| | |
|---|---|
| Artifact | `target/ck-aws.hpi`, 68,785 bytes |
| Version | `1.2-SNAPSHOT (private-2288bd59-radhika)` |
| SHA256 | `4372a254e2748341328aa1966766df771c4ca67544bbee7c4bec0b2f69f7d419` |
| Plugin id | `ck-aws` — unchanged, in-place upgrade |

### Still true, and carried forward

- **IMDS-only pipelines gain nothing.** AWS limitation: a session name exists only
  as a by-product of `AssumeRole`, and self-assumption needs a trust-policy edit.
  Measured additionally: a `[default]` with a `role_arn` is authoritative — a failed
  assumption is a hard error, not a fallback — so speculative self-assumption would
  break builds. This is why the mode is absent rather than experimental.
- Terraform declaring its own `assume_role {}` block, hardcoded credentials, pinned
  credential providers, containers with nothing mounted, and freestyle jobs are all
  out of reach.
- **Not validated against a real multi-agent Infrastructure Jenkins** — all local
  validation used a single-node instance.

### Current status

| Item | State |
|---|---|
| M12 implementation | complete |
| Tests | 144 pass |
| Fail-open across the whole path | complete, regression-tested |
| Deployment-agent shape (`credential_source`) | validated |
| Matched / unmatched behaviour | validated |
| Upload to Infra Jenkins | **not done — awaiting approval** |

---

## Session 17 — 2026-08-07

**Production incident investigation. Root cause NOT proven. No architecture
change made.**

### The report

`uat/batchprocessor` #182 on `jenkins-slave-non-prod-multitenant`: with Managed
Authentication ON, Gradle fails dependency resolution with **403 Forbidden** from
the internal S3-backed Maven repository, and no `jk-uat-batchprocessor-182`
appears in CloudTrail. The plugin logged a successful decoration from
`/home/ubuntu/.aws/config`. With the flag OFF the same job succeeds.

### What was proven locally, against the exact agent shape

A local IMDS stand-in plus the documented slave configuration (`role_arn` +
`credential_source = Ec2InstanceMetadata`, no `[default]`):

| Consumer | OFF | ON |
|---|---|---|
| Unprofiled | `iam-role` (IMDS) | **`iam-role` (IMDS) — identical** |
| `--profile non_prod` | `botocore-session-…` | `jk-uat-batchprocessor-182` |

The diff between the node's file and the plugin's export is **two added
`role_session_name` lines**. So for this shape the plugin **cannot** change what
an unprofiled consumer receives — the leading hypothesis is eliminated.

Also confirmed via the new diagnostics: `sections found = [profile non_prod,
profile prod]`, both decorated, none appended, and
`aws sts get-caller-identity --profile non_prod` returns `jk-diag3-1`. So the
plugin is not decorating one profile while the build uses another.

An incidental observation worth keeping: with no profile named, `aws configure
list` reported `shared-credentials-file` as the source on a machine that has
`~/.aws/credentials`. The plugin deliberately does not touch
`AWS_SHARED_CREDENTIALS_FILE`, so that file remains fully in play — by design, and
the reason `source_profile` chains keep working.

### What could not be established

Neither the AWS SDK for Java nor Gradle is installed on the development machine.
**No claim is made about how Gradle resolves credentials.** Stating SDK v1
behaviour from memory would be recall presented as evidence, which is precisely
what the investigation was asked to avoid.

### Open questions, each with a decisive discriminator

1. Does `/home/ubuntu/.aws/config` contain a `[default]` section or any profile
   beyond `non_prod`/`prod`? → the diagnostics line `sections found`.
2. Is `AWS_PROFILE` set anywhere? → diagnostics prints the node's value; a
   pipeline-set value needs `sh 'env | grep -i aws'` inside the failing job.
3. How is Gradle's S3 repository authenticated? → the repository block in the
   build script.
4. **Which account's CloudTrail was searched?** `terraform-assume-role` for
   non_prod lives in the non-prod account, and `AssumeRole` is recorded in the
   account owning the role — not in ops. Searching ops would prove nothing.
   (Account IDs are deliberately not in this repository; it is public.)

### Change made this session

Diagnostics only, behind `-Dio.github.rads4.ckaws.diagnostics=true` (off by
default): `AwsConfigOverlay.describe()` now reports sections found, decorated and
appended, and `ManagedAwsContext` prints those plus the node's AWS environment and
the exported values. Non-sensitive throughout. 144 tests still pass.

**No final HPI produced.** A diagnostic build exists at
`sha256 359db9fdf489c653b5b9943bb2e022f4a4cfd6c4ce87f5f32e323a51585dc38d`.

### Immediate mitigation available without any code change

Set **Apply to jobs matching** to a pattern that excludes the affected job, or
turn Managed Authentication OFF. Both are runtime configuration changes requiring
no restart, and both restore today's behaviour exactly.

---

## Session 18 — 2026-08-07

**Infra Jenkins fleet measured directly over SSM; plugin exonerated for the M12i incident.
No code change.**

Full write-up is **not tracked** — it contains AWS account IDs, role ARNs and a node
inventory, and this repository is public. See `.session-archive/MEMORY-session-18.md`,
`.session-archive/INFRA_JENKINS_FLEET_FACTS.md` and
`.session-archive/M12i_RCA_AND_ROLLOUT.md`.

Sanitized outcome and the agreed v2.0.0 scope: [docs/V2_DECISIONS.md](docs/V2_DECISIONS.md).

---

## Session 19 — 2026-08-07

**v2.0 implemented. 180 tests, all green. `ck-aws 2.0` built.**

### Shipped

| Item | Where |
|---|---|
| Verify the generated file before exporting it | `AwsConfigOverlay.validate` |
| `[default]` self-assume, attributing unprofiled calls | `AwsConfigOverlay`, new `unprofiledRoleArn` |
| Diagnostics as a checkbox (system property kept as an override) | `CkAwsGlobalConfiguration` |
| Exclude pattern; node-label scoping | `appliesTo`, new `appliesToNode` |
| Both real agent shapes as fixtures | `ProductionShapeFixturesTest` |
| Session name truncates the tail, not the head | `SessionName` |
| Scope selection and the OFF-by-default guarantee | `ScopeSelectionTest` |

### Two bugs found in this session's own work

1. **An invalid include pattern briefly returned `true`** — widening scope to
   every job on a typo, the one direction that must never fail. Introduced while
   refactoring `appliesTo` to add exclude, caught by writing the test for it.
   Fixed by separating "pattern absent" from "pattern unparseable" as distinct
   fallbacks; both now pinned.
2. A local variable named `jenkins` shadowed the `jenkins.model` package.

### Decisions taken during implementation

- **No IAM change is required.** Self-assume was measured working on a live host.
- **Not a dedicated audit role**: resource policies grant by *principal ARN*, and
  sampling found two buckets granting to the instance role by name, one
  cross-account. A new role would be denied by all of them.
- **`sts:SourceIdentity` deferred**: it requires `sts:SetSourceIdentity` on every
  downstream role's trust policy, and would break the jobs that assume roles
  directly — the ones it was meant to trace.
- **No `credential_process`, no Layer C**: neither is needed once source identity
  is deferred and the chaining cap is left to the SDK's own refresh.
- Real account IDs replaced with placeholders in all test fixtures: the
  repository is public.

Rationale in full: [docs/V2_DECISIONS.md](docs/V2_DECISIONS.md) and CLAUDE.md
"v2.0". Infrastructure evidence is in `.session-archive/` and is **not tracked**.

### Not done, deliberately

Rollout itself. The plan is one restart to install with the flag OFF, then a
canary job scoped by include pattern, then widening by folder — all
configuration, no further restarts.

---

## Session 20 — 2026-08-08

**v2.0 installed, canaries run, three defects found and fixed. 199 tests.**

### Installed, and what the canaries proved

The slave canary passed exactly as designed: seven sections found, five
decorated, none appended, `--profile` attributed, unprofiled untouched (the
unprofiled role was deliberately left blank to isolate one variable).

**The controller canary failed** — the safety check refused the generated file
and the build ran unattributed, exactly as fail-open promises. Root cause: the
controller's configuration **ends with a blank line**, and joining lines with
`"\n"` turns a final empty element into a single terminating newline, so the
trailing blank was lost whenever the last section was decorated. The check
correctly saw a line present in the input and absent from the output. A real
defect, for a difference that does not matter — trailing blank lines are not
content. Now normalised on both sides, with a regression test for the shape.

**This was the validate-before-export feature earning its place on its first
real run.**

### Three defects fixed

1. **Trailing blank line** — above. Blocked the controller entirely.
2. **A named profile with no `role_arn` was not attributed.** Same
   unattributable path as `[default]`, reached by name instead of by omission;
   otherwise a caller could opt out of the audit by naming it. Extended to any
   profile section, with an explicit guard so non-profile sections
   (`[sso-session x]`, `[services x]`) are never given a role.
3. **Freestyle builds were not covered at all** — 35 of 775 buildable jobs,
   including production S3 and Route 53. See CLAUDE.md "Freestyle builds"; the
   important part is that `RunListener#setUpEnvironment` runs before the
   workspace exists and fails *silently*.

### Verification done without touching production

The new overlay was run against **all eight real configurations** captured from
the fleet — seven agents plus the controller, with and without unprofiled
attribution. All pass, including the 3473-byte controller file that failed live
and the agent that has no `[default]` at all. Only the controller has a trailing
blank line, which is precisely why one canary passed and the other did not.

New tests exercise a **real agent** rather than the built-in node, proving the
generated file is written on the agent and not the controller — a distinction
invisible on the built-in node, where a remote path is a local file.

### Production left untouched

Verified after the fact: no generated directories left in any workspace (the
plugin's own cleanup), no temporary files, and `~/.aws/config` still carries its
original mtime. Nothing was edited on any host at any point.

---

## Session 21 — 2026-08-08

**`ck-aws 2.1` built. Six canary baselines captured before upgrading.**

### Why 2.1 and not another 2.0

Three different artifacts were built reporting `Plugin-Version: 2.0` as defects
were found. "The controller says 2.0" therefore identified nothing. **Raise
`<revision>` before producing any artifact that leaves the build machine** —
recorded in `pom.xml` beside the property and in CLAUDE.md.

### The canary set: every distinct variable in the fleet

| Canary | Type | Node | What only it covers |
|---|---|---|---|
| `ckaws-canary` | Pipeline | multitenant | standard shape on a slave |
| `ckaws-canary-master` | Pipeline | controller | 28 profiles; the trailing-blank file |
| `ckaws-canary-freestyle` | Freestyle | multitenant | Freestyle on a slave |
| `ckaws-canary-freestyle-master` | Freestyle | controller | Freestyle on the controller |
| `ckaws-canary-drupal` | Freestyle | drupal | **no `[default]`** — the append path |
| `ckaws-canary-pdf` | Freestyle | pdf-to-csv | **AWS CLI 2.9.5**, the oldest in the fleet |

The four agents not covered share a byte-identical configuration with
multitenant, so a canary on each would re-test the same shape.

### Baselines, all six, before the upgrade

Every one: `CK_AWS_SESSION_NAME` and `AWS_CONFIG_FILE` **unset**, unprofiled
calls resolving to `…/ck-ops-jenkins-master-instance-iam-role/i-<instance>`, and
profiled calls carrying an SDK-generated `botocore-session-…` name.

The two Freestyle baselines are the important ones: **in scope, plugin enabled,
and still unset** — evidence that Freestyle was genuinely uncovered rather than
merely excluded by a pattern.

Also confirmed by the baselines: drupal *does* have an instance role, so
unprofiled attribution can work there; and pdf-to-csv resolves the standard
configuration on CLI 2.9.5.

### A note on canary scripts

Probes should end in `exit 0`. `set +e` stops a script aborting early, but the
shell's exit status is still the last command's, so a failed probe marks the
build red — and after an upgrade a failing probe is something to *read*, not
something to be told about by a red icon.

## Session 22 — 2026-08-09

**Trigger.** `dev2/rivon` was added to the include pattern and failed; removed
from scope, it passed; added back, it failed again. Three builds, same commit,
same agent. I had initially attributed the failure to the pre-existing Gradle 403
noise and was **wrong** — the alternation is a controlled experiment and the
plugin was the cause.

**Mechanism.** `ContextVariableSet.get` (verified by decompiling
workflow-cps 4046) scans the current context level, consults every
`DynamicContext`, and only then recurses to the parent. `ManagedAwsContext`
answered unconditionally, so inside `withCredentials { dir { sh } }` the lookup
never reached the level holding the merged credentials expander. `NEXUS_*`
expanded to empty, Gradle got `-P…Repository=` with no value, never contacted
Nexus, and the internal `com.ttn.ck:*` artifacts fell through to a public mirror
that 403s. The pre-existing 403 lines were real but were a *symptom*, not the
cause.

**Deep-dive RCA.** Enumerated the whole always-on surface — three hooks
(`DynamicContext`, `EnvironmentContributor`, `RunListener`); the two `ckAws*`
steps are opt-in and no Jenkinsfile calls them. Built
`ProductionFailureModesTest`, which runs real Pipeline builds. `dir`/`withEnv`
cannot be test dependencies (`validate-hpi` rejects `workflow-basic-steps` even
at test scope — measured), so `FakeBindingStep` and `FakeDirStep` reproduce
verbatim what `CredentialsBindingStep` and `PushdStep` do to the context.

Six probes, **three confirmed defects**, all fixed in 2.3:

1. Context shadowing (critical) — merge instead of replace, ours first.
2. File anchored to the current directory, so `dir` wrote `ck-aws/` into the
   source tree — anchor to `node.getWorkspaceFor(job)`.
3. Stale memo after a mid-build `cleanWs()`/`deleteDir()` — re-check existence
   before reusing. **CLAUDE.md had forbidden this placement before the code was
   written; the implementation had drifted from its own design doc.**
4. Race between parallel branches — per-key lock (not a reproduced failure; the
   window is real in code and the fix is cheap).

209 tests green.

**Blast radius, measured on the controller (read-only SSM, `ops-admin`).** 806
job configs: 740 Pipeline, 39 Freestyle, 27 folders — **no matrix, maven,
multibranch or external jobs, so coverage is structurally complete**. 637 jobs
define their pipeline from SCM, so their shapes are invisible from the
controller. Of what *is* visible, 12 inline jobs combine `withCredentials` with a
nested block — all the `Stormus-*-Build-Publish-Nexus-Job` family across
dev2–dev5/qa1–qa3/uat/prod, plus `nexus-gradle-poc` and two Cost-report jobs.
Every one would have failed exactly like rivon. In the shared library, **8 of 9
deployment vars** (`stormusDeployment`, `frontEndDeployment`, `nodeDeployment`,
`kongDeployment`, …) have the same shape — essentially the whole ECS estate.

The six jobs that reference `AWS_CONFIG_FILE` are the six canaries, not
production. `docker.inside`/`container()`: **zero uses anywhere**, so container
path visibility is not a live risk.

**Terraform (`cln-infra-terraform`).** Seven pipelines, **no `withCredentials`
and no `withEnv` at all**, so they were never exposed to the shadowing defect.
`cleanWs()` is in `post { always }`, not mid-build. Provider auth resolves from
`config_<workspace>.yml`: **416 of 432 workspaces set `profile: "<name>"` with
`role: ""`** — resolved from the shared config file, therefore decorated and
**fully audited**. The other 55 use the provider's own `assume_role` block, and
`session_name` appears in **no `.tf` file anywhere** — that second hop gets an
SDK-generated name, so it is attributable only transitively (CloudTrail records
the `jk-*` session that called `AssumeRole`). Closing that gap is a Terraform-repo
change, not a plugin one: set `session_name` from the `CK_AWS_SESSION_NAME` the
plugin already exports.

**Live state at end of session.** Controller runs 2.1, managed auth on, scope
`(ckaws-canary(-.*)?)`, unprofiled ARN blank. 2.3 built and ready; not installed.

### Session 22 addendum — the build-log census

The 637 SCM-defined jobs cannot be read from the controller, but **their build
logs record every step they executed**. Scanning the last 3 builds of every job
(690 logs, 230 MB, `nice`/`ionice` throttled) gives runtime truth rather than
static guesswork.

Note for anyone repeating this: Jenkins prefixes each console line with a binary
console note, so `grep '^\[Pipeline\]'` matches **nothing**. Drop the anchor.

**Complete executed step vocabulary — 44 distinct steps.** The ones that matter:

| Contributes an `EnvironmentExpander` (shadowable) | Publishes context, so may trigger shadowing |
|---|---|
| `withEnv` 1917 · `withCredentials` 562 · `tool`/`envVarsForTool` 534/515 · `withSonarQubeEnv` 250 · `withBuildUser` 13 · `wrap` 8 · `withFileParameter` 1 | **verified:** `dir` **2512**, `node` 654 · **near-certain:** `timestamps` 91, `ansiColor` 32 (a `ConsoleLogFilter` is their whole purpose) · **unverified:** `catchError` 453, `parallel` 11, `timeout` 6 |

Only `dir` was reproduced directly; the rest of the right-hand column is inferred
from what each step exists to do, and was deliberately **not** relied on. That is
the point: a fix built by enumerating trigger steps would have missed one —
`timestamps` and `ansiColor` were not on the original hypothesis list at all. The
fix sits at the context-resolution layer instead, so it holds for every step in
this table and every step not yet written, which is why the unverified entries
never needed verifying.

**Blast radius, measured: 441 build logs across 371 distinct jobs exhibit the
shadowing shape** — more than half of every job that has ever run, spanning
`ck-analytics-*`, `auto-analytics-*`, `ck-uat-new-*`, `ck-demo-*`,
`ck-fluentd-deployment-prod`, `ck-drupal-*-terraform-pipeline`,
`cln-infra-terraform-pipelines/*`, the `Library_Build_Job*` family and more.

**Zero uses** of `container`, `ws`, `docker.inside`, `withAWS` or `withKubeConfig`
anywhere in executed history — so container path-visibility of `@tmp` and
`withAWS` precedence are not live risks today, only future ones.

`input` (45) explains the 74-minute Terraform build from Session 21: it was
parked awaiting approval, not holding credentials. Chained-credential expiry
remains theoretical — the configs use `credential_source = Ec2InstanceMetadata`,
not `source_profile` chaining, and every `sh` re-resolves credentials per process.

### Session 22 addendum 2 — closing the last risks, and the safety net

**Session names are settled.** All 27 role ARNs in the controller's config were
probed with `sts assume-role --role-session-name jk-probe-secops-1` from the
controller's own IMDS identity. 26 succeeded. The single denial,
`275595855473/SecOpsAdminRole`, was re-probed with an SDK-style name and a neutral
name and denied identically — a pre-existing permission gap, not a session-name
restriction. No trust policy anywhere constrains `sts:RoleSessionName`.

**No production job was ever damaged.** `Unable to parse config file` appears in 7
builds, **all canaries** — the duplicate-key defect never escaped the canary set.
`config profile could not be found`: zero. Two `ExpiredToken` and one AssumeRole
denial exist in `prod/marketplace`, `qa1/marketplace` and
`slack-messages-monitoring`, all out of scope and pre-existing.

**The answer to "is the master switch the only safety net": no.** Rivon proved a
guard that only catches exceptions cannot catch a contribution that succeeds and
still removes something. 2.4 adds **observe-only mode** — prepare, decorate,
validate, write, report, and export *nothing*. Scope can be widened to every job
under real traffic with zero possibility of affecting one, which is the only
honest way to survey the 637 SCM-defined jobs. Order of escalation is now:
structural invariant, then per-job exclude, then observe-only, then the master
switch last.

**2.4** — `sha256 47b8f3ae192c3d8742cd66e1e1e3751c179bf28807426605b461725f3474805e`.
210 tests. 2.3 was built and its hash shared but never installed; it lacks
observe-only. Install 2.4.

### Session 23 — 2026-08-14 — version renumbered to 2.2

**Versioning rule refined by Rads: versions track INSTALLATIONS, not builds.**
A number must change before an artifact is installed on a controller, so
"the controller says 2.2" is unambiguous. A number that has been installed is
spent forever. A number only ever built locally is *not* spent and may be
re-taken by the build that actually ships.

Applying that: 2.1 is what is installed on CK production. The builds numbered
2.2, 2.3 and 2.4 during the August 2026 defect work were never installed, so
those numbers were never spent. The build that ships is therefore **2.2**.

**ck-aws 2.2 (shipping)** — `sha256 b4c94c784efc697662d9578b4f0d1bad1c3398d7c2a67744bc7ae7d92e558f45`
Contains all four defect fixes (context shadowing, workspace anchoring, stale
memo, parallel race) plus observe-only mode. 210 tests.

⚠️ **Any artifact claiming to be an earlier 2.2, 2.3 or 2.4 is void.** The first
of those still contained the DynamicContext shadowing defect — the one that would
break 371 jobs. Superseded hashes, recorded so they are recognisable and not
mistaken for the shipping build:

| void build | sha256 |
|---|---|
| old 2.2 | `fa854a3d4f0ffeea8d6250942801b2939e4d64e4dec8021e9f051fe2f98bafdd` |
| old 2.3 | `728d1eb411b1c01462cea4e618ccf993671fe31348090e0d568b3282d0469856` |
| old 2.4 | `47b8f3ae192c3d8742cd66e1e1e3751c179bf28807426605b461725f3474805e` |

Only `b4c94c78…` may be installed.

### Session 24 — 2026-08-14 — POC clone verified; 2.2 NOT yet installed

The POC controller clone (`poc-jenkins`, `i-0ce520741740bf2f6`, `10.20.94.122`,
ops account, same VPC as infra by design) is built, neutralised and **verified
isolated**. 2.2 is deliberately **not installed yet** — the clone is being held in
a known-good, fully preserved state first.

**Two defects found in the POC setup, both mine, both now understood.**

**1. `init.groovy.d` hooks never ran — a Groovy filename defect.**

```
java.lang.ClassFormatError: Illegal class name "00-poc-admin$imds"
    at 00-poc-admin.run(00-poc-admin.groovy:38)
```

Groovy derives a script's class name from its **filename**. `00-poc-admin.groovy`
becomes class `00-poc-admin`; when the script calls a method it defines itself
(`imds(...)`), Groovy generates a call-site helper class `00-poc-admin$imds`. A
JVM class name may not begin with a digit or contain a hyphen, so it fails at the
first self-call. Because `GroovyInitScript.init` propagates this as an `Error`,
**the entire init task aborted** — so `01-` and `02-` never executed either.

Consequences, all still outstanding: triggers were never stripped (37 job
`config.xml` files still carry `TimerTrigger`/`SCMTrigger`/`GitLabPushTrigger`),
notification credentials were never removed, and `poc-admin` was never created.
None of it can act while executors are 0 and no clouds are defined.

Fix (not yet applied): rename to identifiers that are legal JVM class names —
`poc00Admin.groovy`, `poc01DisableTriggers.groovy`,
`poc02StripNotifyCredentials.groovy`. Alphabetical ordering, which is what
Jenkins uses, is preserved. **A numeric or hyphenated `init.groovy.d` filename is
only safe if the script never calls a method it defines itself** — which is why
this convention is used everywhere and looked fine.

**2. Cloned `JENKINS_HOME` resumes in-flight Pipeline builds.**

The AMI was taken from a live controller, so four builds were mid-flight inside
it: `ecr-replication#485`, `uat/batchprocessor#152`, `qa1/azure-insights#248`,
`prod/tuner-mcp#5`. Deleting `queue.xml` does **not** stop these — resumable
executions are restored from `org.jenkinsci.plugins.workflow.flow.FlowExecutionList.xml`,
independently of the queue, and `program.dat` per build holds the CPS state. On
first boot the clone resumed all four. They could not proceed (0 executors, no
clouds) and parked at their `node` steps, logging
`ExecutorStepExecution$AnomalousStatus` every 5 minutes. **Zero AWS API calls were
ever made from the clone** — CloudTrail, whole history.

Fixed by a `#cloud-boothook` in the instance user-data, which runs as root on
**every** boot, before `jenkins.service`: it masks Jenkins by symlinking
`/etc/systemd/system/jenkins.service → /dev/null`, then moves `FlowExecutionList.xml`,
`queue.xml` and `queue.xml.bak` to `/var/lib/poc-quarantine/<timestamp>/` — moved,
not deleted, so it is reversible. It carries the same guard as everything else:
refuses on `i-0924a915a1c76f33e`, requires `ckaws-poc=true` from IMDS.
Confirmed working: Jenkins did not start at all on the following boot. A **new**
`queue.xml` had appeared by then (07:54), so re-queueing was live and this was not
theoretical.

**Verified state of the clone** (Jenkins masked and down at time of measurement):

| | |
|---|---|
| job `config.xml` | 810 · 305 top-level dirs · 1,739 build dirs |
| `credentials.xml` | 80,503 bytes · 460 users · 206 plugins |
| installed plugin | `ck-aws` **2.1** — untouched, from the AMI |
| executors | 0 · `<clouds/>` empty · `nodes/` empty |
| `jenkinsUrl` | `http://10.20.94.122:8080/` (infra: `https://jenkins.ck.tothenew.net/`) |
| realm | `HudsonPrivateSecurityRealm` (SAML replaced, `saml.jpi` still on disk) |
| resume state | quarantined; 4 `program.dat` remain but nothing references them |
| AWS calls from clone | **zero**, entire CloudTrail history |
| infra `jenkins-17` | `LaunchTime 2026-03-31`, unchanged; 3 agents running, none adopted |
| clone SG `sg-03b1f1d96664aea1a` | **0 ingress**, 1 egress |

Zero ingress is why the clone's URL does not open over the Pritunl split tunnel —
by design. Access is SSM port-forward, which needs no SG change.

**Still to do before 2.2 can be tested here:** rename and re-run the hooks; strip
the 37 remaining triggers; create `poc-admin`; remove `saml.jpi` only *after*
login is confirmed; re-create agent cloud templates with **distinct tags** (the
EC2 plugin can adopt instances it believes are its own orphans) and only then
raise executors above 0.

### Session 24 addendum — correction: ALL testing happens on the clone

Rads corrected a planning error of mine, and it is important enough to record so it
is not repeated.

I proposed running the observe-only survey **on infra Jenkins** to cover the job
types that are unsafe to run on the clone. That defeats the entire purpose of
building the clone. The rule is:

> **No further tests, upgrades, restarts or scope changes on infra Jenkins.**
> Everything — including the broad survey — happens on the POC clone. Infra
> Jenkins is touched exactly once more, at the end: the final 2.2 install.

The reason the wrong plan was tempting: the clone carries the **same instance
profile as infra**, so `prod`/`dr`/`qa`/`uat` jobs would really succeed against
production. That made those job types look untestable on the clone, and infra
looked like the only place to observe them. Both halves of that are wrong.

**Wrong half 1 — the plugin's failure modes do not need a successful AWS call.**
Every defect found so far is about what the plugin does to the *environment and
the config file*, not about whether AssumeRole succeeds. The rivon defect was
Nexus credentials expanding to empty — no AWS call involved at all. So a job whose
AWS access is denied still exercises the entire contribution surface: config
generation, environment merge, workspace anchoring, mid-build clean, parallel,
Freestyle vs Pipeline.

**Wrong half 2 — job *shapes*, not job *identities*, are what need covering.**
The census already produced the exact context stacks and their frequencies across
718 real builds. Those stacks can be replayed as canary pipelines on the clone,
including stacks that only occur in prod/qa/uat jobs. That gives structural
coverage of all job types — present and future — with zero risk and nothing
running on infra.

Consequences recorded for the plan:

1. Observe-only is a control for use **on the clone**, and later as the first
   rollout stage on infra *after* the install — never as a reason to experiment on
   infra beforehand.
2. To run prod/qa/uat-shaped real jobs on the clone, swap the clone's instance
   profile for a **POC-only role that cannot assume prod/dr/qa/uat**. Those jobs
   then fail closed at the AWS boundary while still exercising the plugin. This is
   an IAM change and needs Rads' explicit approval; it also does **not** bound
   non-AWS effects (a stored SSH credential, a Nexus publish), so it is not on its
   own a licence to run production jobs.
3. The **structural additions-only invariant** matters more under this rule than it
   did before: enumeration cannot cover jobs whose definitions live in SCM, so the
   plugin must refuse to contribute when any variable it does not own would change.

### Session 24 addendum 2 — the additions-only environment invariant is IN 2.2

Rads' decision, and the reasoning that forced it: the goal is that **every job,
including ones not yet written, is audited without any job failing**, and that infra
Jenkins is restarted exactly once. Those two together mean the structural invariant
cannot be deferred to a later version — deferring it buys a second restart.

**What was added.** `ManagedAwsContext.wouldRemoveSomething(existing, merged)`
expands the enclosing `EnvironmentExpander` and the merged one the plugin proposes,
from an empty `EnvVars` each, and compares. If any variable the enclosing block set
would be dropped or altered, `contribute()` returns `null` — resolution continues to
the enclosing level and the build keeps its own environment untouched. Variable
*names* are reported; *values* never are, because an enclosing `withCredentials`
expands secrets into that map.

**Why the previous approach was not enough.** The merge order (`merge(ours,
existing)`, so the enclosing value always wins) is correct — but "correct by
construction" is precisely the claim that failed for rivon. More concretely, the
ordering argument assumes `DelegatedContext.get` returns the enclosing expander
faithfully. If it ever returns null, a partial view, or a different level — in a
nesting shape nobody has written — the merge is built from the wrong base and the
argument silently stops holding, with nothing thrown and nothing logged. Comparing
actual expansions makes no such assumption.

There is deliberately **no exemption for the plugin's own two variables**. Since
`merge(ours, existing)` expands ours first, the enclosing value wins for any
overlapping key, so a job that sets its own `AWS_CONFIG_FILE` keeps it. Checking
every key is both simpler and stricter.

**Also fixed while building:** SpotBugs flagged `buildWorkspace(...)` as a possibly
null return (it can return its own `@CheckForNull` argument) feeding `prepareOnce`.
Bound to a local and re-checked rather than suppressed.

**215 tests, 0 failures, 1 skipped.** Five new in `AdditionsOnlyEnvironmentTest`,
including a reduction of the rivon defect to its essence — an expander that answers
with only the plugin's own variables is now rejected.

⚠️ **The shipping hash has changed.**

| build | sha256 | status |
|---|---|---|
| **2.2 (shipping)** | `edde1e04d5e5415b1ea05d73e5f29e283f9b70072cbb6194f4aa7c1959817ef9` | **the only installable artifact** |
| old 2.2 | `b4c94c784efc697662d9578b4f0d1bad1c3398d7c2a67744bc7ae7d92e558f45` | void — no environment invariant |
| older 2.2 | `fa854a3d4f0ffeea8d6250942801b2939e4d64e4dec8021e9f051fe2f98bafdd` | void — shadowing defect |
| old 2.3 | `728d1eb411b1c01462cea4e618ccf993671fe31348090e0d568b3282d0469856` | void |
| old 2.4 | `47b8f3ae192c3d8742cd66e1e1e3751c179bf28807426605b461725f3474805e` | void |

The number stays **2.2** because no 2.2 has ever been *installed* on a controller,
and versions track installations, not builds. 2.1 remains what is on infra.

### Session 24 addendum 3 — observe-only reporting defect, found by using it

The observe-only sweep on the POC clone passed functionally: scope widened to `.*`
(all 782 jobs), `observeOnly=true`, and both a canary and a real job
(`Cost-report-ck-Devops-Infra-MAV` #118, SUCCESS 3m09s) confirmed **nothing was
exported** and the build's own environment was untouched.

But the diagnostics block still printed:

```
[ck-aws] OBSERVE ONLY, nothing exported: would decorate as session jk-...
[ck-aws]   exported AWS_CONFIG_FILE      : /home/pocagent/workspace/...@tmp/ck-aws/config
[ck-aws]   exported CK_AWS_SESSION_NAME  : jk-...
```

The headline says nothing was exported; the next two lines say it was. Functionally
harmless — the canary proved nothing is exported — but observe-only exists **precisely
so its output can be read and trusted** while surveying every job before enforcing.
Output that contradicts itself defeats the feature. `diagnose()` now takes the
observe-only flag and prints "would export" instead.

Found by using the feature rather than by reading the code, which is the same lesson
as rivon.

⚠️ **Shipping hash changed again.**

| build | sha256 | status |
|---|---|---|
| **2.2 (shipping)** | `4cc0aadafddd4b0f274617eb0bc0169bcd9167309439885a5712aa8b427ae798` | **the only installable artifact** |
| edde1e04… | `edde1e04d5e5415b1ea05d73e5f29e283f9b70072cbb6194f4aa7c1959817ef9` | void — misleading observe-only diagnostics |
| b4c94c78… | `b4c94c784efc697662d9578b4f0d1bad1c3398d7c2a67744bc7ae7d92e558f45` | void — no environment invariant |
| fa854a3d… / 728d1eb4… / 47b8f3ae… | (earlier 2.2 / 2.3 / 2.4) | void |

215 tests, 0 failures. Still version 2.2 — no 2.2 has ever been installed on a
controller, and versions track installations, not builds.

### Session 24 addendum 4 — the audit gap, and per-node unprofiled resolution

**The gap.** A census of 684 build logs: `aws` with **no profile** appears **16,378
times across 398 jobs**; `aws --profile X` only **370 times across 26 jobs**. So ~98% of
AWS calls resolved to the node's instance role, whose session name EC2 fixes to the
instance id — unattributable. The plugin was auditing ~2% of AWS activity.

This was **not a missing feature**. `unprofiledRoleArn` has existed since v2.0. It was
blank because nobody had verified the instance role may assume *itself* — and if it may
not, enabling it makes every bare `aws` call fail. Verified read-only before touching
anything: trust policy includes `685502069032:root`, and
`iam simulate-principal-policy` for `sts:AssumeRole` on its own ARN returns **allowed**.

**Proven** with a real bare `aws sts get-caller-identity` on a real EC2 agent:
`.../i-0a237b1d7c86f57a5` → `.../jk-poc-canary-audit-real-2`. The principal ARN is
unchanged, only the session name differs, so resource policies keep working.

**The long-term fix.** A single ARN is right only while every agent shares one instance
role — true today (all 25 EC2 templates) but not guaranteed. A new agent with a
different role would be handed a `role_arn` it cannot assume and its unprofiled calls
would **fail**. New setting `attributeUnprofiledAsNodeRole` resolves each node's own
role over IMDS at prepare time, via a `MasterToSlaveFileCallable` that runs **on the
node**. Fail-safe: unresolvable node → `null` → no `[default]` written → today's
behaviour. Cached per node name.

Verified with a **negative control**: the static ARN was set to
`arn:aws:iam::999999999999:role/this-role-does-not-exist` and the checkbox turned on.
If the fixed value had been used, every bare call would have failed. Both agents instead
resolved their own role and produced `jk-*` sessions. **20/20 canaries passed.**

What this still cannot check: whether a node's role is *permitted* to self-assume. The
help text documents the `simulate-principal-policy` check for that.

⚠️ **Shipping hash changed.** Only
`b4ec751e2f36bc5b8b7d4f81e5b44a0bb77043df7a810cf4382f5faef3119b07` may be installed.
Void: `4cc0aada…`, `edde1e04…`, `b4c94c78…`, `fa854a3d…`, `728d1eb4…`, `47b8f3ae…`.
220 tests, 0 failures. Still version 2.2 — no 2.2 has ever been installed.

### Session 24 addendum 5 — self-assume probe, and the limits of enumeration

**The fallback.** Per-node resolution names the right role, but AWS must also *permit*
the role to assume itself. If it does not, writing `role_arn` turns "unattributed" into
"the build fails" — the one remaining way this feature could break something, on a node
nobody had tested.

Two designs were considered. A `credential_process` wrapper that self-assumes and falls
back to raw IMDS credentials is the most robust, but it would rewrite the `[default]`
emission inside `AwsConfigOverlay` — the most heavily validated code in the plugin — and
needs JSON reshaping in POSIX sh. Chosen instead: **prove it before claiming it.** The
node-side callable runs `aws sts assume-role` against its own ARN once per node; on
refusal it returns `null`, so no `[default]` is written at all. Same safety outcome,
contained entirely within the new code, `AwsConfigOverlay` untouched.

Session name for the probe is `ck-aws-selfassume-probe` — deliberately not a `jk-` name,
because it is not a build. It appears once per node in CloudTrail.

**Enumeration limits, measured.** 802 jobs: **602 have build history, 200 do not**, so
the build-log census saw 75%. Static scanning of job configs closes most of it:

- **Freestyle (41 jobs): every build step is `hudson.tasks.Shell`.** Nothing else.
- **Inline Pipelines (118 jobs): statically scanned** — no `withAWS`, `s3Upload`,
  `ecrLogin`, `cfnUpdate` or `invokeLambda` anywhere.
- **All 45 distinct Pipeline steps used in production: none calls AWS directly.** Every
  AWS call goes through a shell, which is exactly what `AWS_CONFIG_FILE` covers. Had any
  job used a JVM-side AWS step, the plugin would neither audit nor affect it — a silent
  hole. There are none.

Remaining gap: **165 SCM pipelines with no build history**, whose Jenkinsfiles live in
Bitbucket and have never been seen. A repo scan closes it.

**Two agent templates have NO instance profile** — `jenkins-slave-non-prod-aispl` and
`non-prod-rds-restore-slave`. Their AWS calls are unattributable and **no plugin change
can fix that**; it needs an instance profile attached. With per-node resolution they are
left alone rather than broken.

⚠️ Shipping hash: `7f02b6542c378777f9273cf1d565f4724bba4d140172c361121d8741994c2a1e`.
220 tests. 20/20 canaries pass with the probe in place.

### Session 24 addendum 6 — complete static coverage via Bitbucket

The build-log census could only see jobs that had run (602 of 802). The remaining
**165 SCM pipelines had no build history**, and their Jenkinsfiles had never been read
by anything. Closed by scanning Bitbucket directly.

**Method.** A read-only Jenkins job on the POC clone (`poc-scan-bitbucket`) using the
existing `CKBitbucket` credential natively — no key extraction, no push. Bare, blobless
clones of all 20 referenced repos, then `git ls-tree` across **every branch** and
`git show` for each Jenkinsfile and `vars/*.groovy`, deduplicated by blob sha.

First attempt found only 7 files: a shallow default-branch clone. CloudKeeper's
Jenkinsfiles live on per-environment branches — `cln-deployment-scripts` alone has
**337 branches**, `stormus` has 5,371. Scanning all branches found **490 unique
pipeline files**.

**Result 1 — no AWS call bypasses the plugin.** Across all 490 files there is **not one**
`withAWS`, `s3Upload`, `ecrLogin`, `cfnUpdate`, `invokeLambda` or any other JVM-side AWS
step. Every AWS call goes through a shell, which is exactly what `AWS_CONFIG_FILE`
covers. Had even one existed, the plugin would neither audit nor affect it — a silent
hole. This is the strongest evidence yet that the mechanism is complete.

**Result 2 — every context block in use has a canary.**

| block | files | | block | files |
|---|---|---|---|---|
| stage | 295 | | withCredentials | 84 |
| script | 287 | | catchError | 59 |
| cleanWs | 253 | | withEnv | 37 |
| dir | 245 | | deleteDir | 15 |
| **withSonarQubeEnv** | **142** | | ansiColor / timeout / parallel / timestamps | 9 / 2 / 2 / 2 |

`withSonarQubeEnv` at 142 files confirms how consequential that catch was — it was in no
canary until the corrected census found it.

The names the scan flagged as "uncovered" — `Utilities`, `Build`, `Deploy`, `call`,
`extractVariable`, `stormusDeployment`, `kongDeployment` … — are **shared-library global
vars**, i.e. Groovy functions, not context-publishing block steps. Their bodies live in
`vars/*.groovy`, which are themselves among the 490 files scanned, so the blocks they use
internally are already counted.

**Conclusion: coverage is now static and complete, not sampled.** Enumeration no longer
depends on a job having run, and the additions-only invariant covers whatever is written
next.

### Session 24 addendum 7 — the Terraform second hop: measured, and unfixable plugin-side

Hypothesis: exporting `AWS_ROLE_SESSION_NAME` would make Terraform's provider-level
`assume_role` name its session after the build, closing the gap with no repo change.

**Tested, and false.** A build with the variable set ran `terraform plan` against a
provider carrying its own `assume_role`. CloudTrail (ops account, found via
`AttributeKey=Username,AttributeValue=jk-...`):

```
CALLER:    .../ck-ops-jenkins-master-instance-iam-role/jk-poc-canary-terraform-secondhop-2
requested: roleSessionName = aws-go-sdk-1786899555220461151
RESULT:    .../terraform-assume-role/aws-go-sdk-1786899555220461151
```

The provider constructs the second AssumeRole from the `assume_role` block alone and
generates `aws-go-sdk-<nanotime>` when `session_name` is absent. No environment variable
reaches it. **No plugin-side fix exists**; the only direct fix is `session_name` in the
provider block, which is a repository change and therefore excluded.

**What holds instead:** CloudTrail records the *caller* of that AssumeRole as the `jk-`
session, so every subsequent Terraform call is one join from the build. Affects **3 of
802 jobs** (`cln-app-terraform-pipeline`, `ck-analytics-app-services-terraform`,
`ck-ecs-terraform`). The other 18 Terraform jobs have no provider `assume_role` and are
directly attributed.

The export is retained — additive, free, may help a tool that does read it — but must
not be described as covering Terraform.

**Method note worth keeping:** cross-account AssumeRole is logged in the *calling*
account too, and `lookup-events` cannot find it by role name. The query that works is
`AttributeKey=Username,AttributeValue=<the jk- session>`, because CloudTrail indexes the
caller's session name as Username.

### Session 24 addendum 8 — dev2/fluentd: end-to-end proof on a real deployment

`dev2/fluentd #119` ran from poc-2 with 2.2 enforcing and **SUCCEEDED** in 3 minutes:
Bitbucket checkout → shared library → SSM → Docker build → ECR push → ECS deploy →
`ecs wait services-stable`. This closed the last untested execution path, the
**SCM-backed pipeline** (639 jobs).

**35 CloudTrail events across two accounts, every one labelled `jk-dev2-fluentd-119`** —
including the mutating `ecs:RegisterTaskDefinition`, `ecs:UpdateService` and ECR
`PutImage`. Critically it exercised **both** attribution paths in one build: profile-based
calls through the decorated profile, and **profile-less ECR calls** through the
`[default]` self-assume — the ~98% case that was previously unattributable.

Chosen over `dev2/profitability` because it takes **no parameters**. `profitability` has a
`branch` param defaulting to **`uat`**; running it with defaults would have deployed uat
code into dev2, and only one build is retained on either controller so there was no
history to learn the right branch from. **Check parameter defaults before running a job,
not just its stages.**

### Session 24 addendum 9 — configuration-surface review and the reporting gap

Two questions asked before the infra install, both answered without a code change.

**1. What observe-only actually does.** It is not a partial dry run — it runs the
*whole* path and withholds exactly one step. The node's config is read, decorated,
validated, and **the file is genuinely written** to `<workspace>@tmp/ck-aws/config`.
Only the export is skipped, so `AWS_CONFIG_FILE` and `CK_AWS_SESSION_NAME` stay unset,
nothing reads the file, and no build behaviour changes. Both halves were confirmed on
the canary: file present on disk, variables unset inside the build.

**The record exists only in the build's console log.** No aggregation, no central
report, nothing outside each individual build. This is the honest limitation — it
answers "what would this build do", one build at a time, and cannot answer "is
anything slipping through".

**2. The UI has ten fields and they are not equally useful.** Reviewed field by field:

*Load-bearing:* managed authentication (master switch / rollback), except-jobs-matching
(the per-job incident switch), attribute-as-node's-own-role (the ~98%), observe-only
(the rollout mechanism), diagnostics (the source of every piece of POC evidence).

*Dead weight:* node-label pattern (no demonstrated use case — the fail-safe paths made
it unnecessary), the AWS profiles list (**`sections appended: []` in every single
diagnostic block ever printed** — it has never added a profile), agent base identity
(only affects `[default]`, always `Ec2InstanceMetadata` on EC2).

*A genuine footgun:* **the static `unprofiledRoleArn` free-text field.** During testing
it was deliberately pointed at `arn:aws:iam::999999999999:role/this-role-does-not-exist`
as a poison pill — which is precisely what a typo would do in production, breaking every
bare `aws` call in every build. It is fully superseded by the per-node checkbox, which
resolves each node's real role *and probes that the assume succeeds* before using it.
Shipping both, where the older is strictly worse, is a design defect. **Decision: leave
blank and documented as deprecated for the infra install; delete it in the next version
that touches that file** — removing it now would cost another build, hash and reinstall
for no safety gain, since blank is already correct.

**3. Automatic notification when a job isn't audited.** Neither the plugin nor
observe-only reports centrally. The answer is two mechanisms, because they catch
different things:

- **Jenkins side:** a log recorder on `io.github.rads4.ckaws` at WARNING (*Manage
  Jenkins → System Log*). Zero code, zero build impact, collects every decline with its
  reason. **Blind to anything the plugin thinks succeeded** — it will never surface the
  Terraform second hop.
- **AWS side, and this is the real one:** CloudTrail bucketed by session-name shape.
  `jk-<job>-<build>` = audited; `i-0…` = uncovered unprofiled call; `aws-go-sdk-…` /
  `botocore-session-…` / 32-hex = a tool that assumed a role itself. It is
  **outcome-based** — it measures what reached AWS rather than what the plugin intended
  — so it catches gaps in jobs nobody has written yet. Deliverable as a Logs Insights
  query, a metric filter + alarm, or an EventBridge rule; all AWS-side only and
  structurally incapable of affecting a build.

Blind spot in both: a job authenticating some entirely other way (static keys in a
Jenkins credential). The census found none; neither mechanism would see one appear.

**Deferred by decision:** the reporting implementation itself. Rads will consider it
after the infra install rather than before.

### Session 24 addendum 10 — `.hpi` builds are not byte-reproducible

Re-running `mvn clean verify` on **unchanged source** (only `.md` files were edited)
produced `sha256 c0507c9a8d195f60e3ad0c8bea0725ad48567982e519ca8aa8ccd39d1ad3344e`, not
the recorded `f5150ba3…`. Jar entries carry build timestamps, so **every rebuild yields a
new hash even when nothing in `src/` changed.**

Consequence for the "one hash is the installable artifact" convention: it identifies a
*build*, not a *source state*. Two implications, both mattering right now:

1. `mvn clean` **deleted the f5150ba3 artifact** — the only surviving copy is the one
   installed on `poc-jenkins-2` at `/var/lib/jenkins/plugins/ck-aws.hpi`. That is the
   binary every canary, every agent test and `dev2/fluentd #119` actually validated.
2. For the infra install, choose deliberately: either **retrieve f5150ba3 from poc-2**
   (the exact validated binary — preferred, since "tested" should mean that binary), or
   ship a fresh build and accept that its hash was never the one under test, even though
   the source is identical and 220 tests pass.

**Never run `mvn clean` again while a validated artifact is the only copy.** Fresh builds
are now archived outside the repo at
`poc-jenkins-setup/artifacts/ck-aws-<version>-<short-sha>.hpi` (never committed — the
repo is public).

### Session 25 — 2026-08-17 — configuration surface trimmed (2.3)

The UI review from addendum 9 was implemented. **Two form entries removed; no behaviour change.**

- ***Attribute unprofiled calls as* (static ARN)** — the footgun. Superseded by the per-node
  checkbox, which resolves each node's real role and probes the assume first.
- ***Apply on nodes labelled*** — a second scoping axis no run ever needed.

Both properties are **retained `@Deprecated`**, not deleted, for two reasons: existing configuration
XML still loads without an XStream warning, and six test classes need a settable ARN to exercise the
`[default]` emission — per-node resolution needs real IMDS and cannot run under `JenkinsRule`.
Removing the *form entry* removes the hazard; removing the *property* would cost test coverage.

**Two fields reviewed as dead weight were kept after tracing their callers.** Worth not
re-litigating:

- **`profiles` is not dead** — it is the configuration source for `CkAwsWithProfileStep`, the M11
  override layer. `sections appended: []` in every diagnostic block means it has never *appended a
  profile*, which is a different claim from "nothing reads it".
- **`credentialSource` is not dead** — it is written into every generated `[default]` as
  `credential_source = Ec2InstanceMetadata`. Its value has never varied, but it is functional output,
  and the ECS/EKS agent case is real.

Form went 10 → 9 (2.3) → **8 fields** (2.4).

**Two versions, because the rule bit mid-session.** 2.3 (`bc4d59e1…`) removed the static ARN and was
**installed on `poc-jenkins-2`** — manifest confirmed `Plugin-Version: 2.3`, and `grep
unprofiledRoleArn` against the saved configuration XML returns **0**, so the `999999999999` poison
pill from testing is not lurking behind the removed field. That spent the number. The node-label
removal that followed therefore had to become **2.4** (`e3ad90b0…`, 220 tests), which is built and
archived but **not installed anywhere**.

The lesson is procedural: *finish the whole UI change before installing anything.* Installing an
intermediate build spends a version number for a state nobody keeps.

**Build releases with `mvn -Dchangelist= clean verify`.** Without it the manifest says
`2.3-SNAPSHOT (private-<sha>-<user>)`, which is not installable as a release.

**The installed plugin file is `ck-aws.jpi`, not `.hpi`** — Jenkins renames what it installs. The
pre-install checklist said `.hpi` and the first backup attempt found nothing.

### Session 25 addendum — code review, six fixes, and semantic versioning

A full review of the repo produced nine findings. Six were fixed; three are recorded as known
limitations. **One of the six was a bug introduced earlier the same session**, which is the argument
for reviewing your own work rather than trusting that a green suite means correct.

**Fixed:**

1. **`AwsConfigOverlay.emissionFor` treated "no `role_arn`" as "uses the agent's base identity".**
   That is false for five shapes, and they fail differently. An **SSO** profile given the assume-role
   triple authenticates as the agent's instance role in the wrong account — it *succeeds*, as the
   wrong principal, which is worse than failing. A **`source_profile`** profile gains
   `credential_source`, which botocore rejects outright (`InvalidConfigError`), failing every call.
   **`credential_process`**, static keys and web identity all have their identity silently replaced.
   None was caught by the duplicate-key guard, because the keys differ from the ones being written.
   Now guarded by `establishesIdentity(sectionKeys)`. **8 new tests** in `IdentityBearingProfilesTest`,
   including a regression guard that a genuinely plain profile *is* still attributed — otherwise
   "leave identity-bearing profiles alone" could be satisfied by leaving everything alone.
2. **The `ASSIGNMENT` regex matched indented continuation lines.** AWS nested configuration
   (`[services local]` with indented `endpoint_url` under `dynamodb =` and `s3 =`) made the
   duplicate-key check see `endpoint_url` twice and reject a file botocore parses happily — costing
   that node **all** attribution. Leading whitespace is no longer allowed, matching configparser.
3. **Removing the two form entries made the first UI Save erase them.** `configure()` still reset
   `nodeLabelPattern` and `unprofiledRoleArn` to null before `bindJSON`, but the form no longer
   submits them, so nothing restored them. For `nodeLabelPattern` the erasure *widens* scope from one
   agent to every node — the dangerous direction. **Rule: only fields the form actually submits may be
   reset from the form.**
4. **Failed per-node role resolution was never cached**, so a node that is not EC2, has no instance
   profile, or may not self-assume paid two IMDS timeouts plus up to 30 s of `sts assume-role` on
   *every build* — while holding the preparation lock. Negatives now cache via an `UNRESOLVABLE`
   sentinel. The warning text was also wrong for the self-assume-denied case and now covers both.
5. **The Freestyle path used `envs.putAll`, overwriting the build's own variables** — contradicting
   the additions-only invariant the Pipeline path enforces by comparing expansions. A Freestyle job
   setting its own `AWS_CONFIG_FILE` had it silently replaced. Now `putIfAbsent`.

**Recorded, not fixed:**

- `NODE_ROLE_ARNS` is keyed on node name with no invalidation. Exact for ephemeral cloud agents
  (fresh name per instance); **stale for a permanent agent re-provisioned with a different role under
  the same name**. No such agent exists on this controller and a restart clears the cache.
- `stillOnDisk` does a remote `FilePath.exists()` per step, and `wouldRemoveSomething` expands two
  environments per step. Measurable on a high-latency agent with a several-hundred-step pipeline.
- `buildWorkspace` does not fix the `dir()` case for concurrent builds (`job@2` is not `inside`
  `getWorkspaceFor`), so those fall back to the current path.

**Versioning moved to `major.minor.patch`.** Development builds are `2.2.0-SNAPSHOT (private-…)` and
are deliberately **not installable**; the release number is claimed at install time. Infra Jenkins is
on **2.1** and is the only controller whose number matters — 2.2 and 2.3 were POC test installs and
are spent. The superseded `ck-aws-2.4-e3ad90b0.hpi` artifact was deleted rather than kept, because a
numbered artifact matching no current source state is exactly the confusion the rule exists to prevent.

### Session 25 addendum 2 — the second review found two of the six fixes were WRONG

A verification pass over the six fixes rejected two of them. Both were mine, both were made the same
day, and **both would have caused the exact production outage the code they touched exists to
prevent.** A green suite proved nothing, because no test covered the shapes that break.

**The root cause of both: one regex was doing INI parsing it was not equipped for.**

The `ASSIGNMENT` pattern was changed to forbid leading whitespace, on the reasoning that configparser
treats indented lines as continuations. That reasoning is half right. **An indented line is a
continuation only when its indent exceeds the previous option's** — a *uniformly* indented profile is
completely valid. Verified against Python's `RawConfigParser`, which is what botocore uses:

```
[profile ops]
    role_arn = arn:aws:iam::2:role/ops     -> parses to a real role_arn
```

Under the change, that `role_arn` was invisible, so the section looked like it assumed nothing, got
the assume-role triple appended, and the file then genuinely declared `role_arn` twice →
`DuplicateOptionError` → **every AWS call in the build fails, not just that profile's.** And because
`duplicateKey()` used the same regex, `validate()` was blind to the corruption it had just written.
Both guards failed in the same direction, which is the failure mode this plugin most needs to avoid.

Second defect in the same area: **`ASSIGNMENT` only matched `=`.** configparser's default delimiters
are `('=', ':')` and botocore does not override them, so `sso_session: ck` is a real key — and a
colon-delimited SSO profile was invisible to the new identity guard, defeating the whole point of it.

**Fixed properly:** one `optionKeysOf(...)` helper implementing configparser's actual rule
(indent-relative continuation, both delimiters), used by *both* `describe()` and `duplicateKey()` so
writer and guard can never disagree again. `emissionForSlice(...)` now derives `role_arn` /
`role_session_name` presence from that helper instead of accumulating it line by line.

**Verified end-to-end, not just by unit test:** generated output for six risky shapes was fed to real
`configparser`. All parse; indented and tab-indented profiles keep their own `role_arn`; colon-SSO and
`source_profile` are untouched; a genuinely plain profile is still attributed.

**Fix 5 (`putIfAbsent` on the Freestyle path) was reverted to `putAll`.** It looked like the
additions-only invariant applied to Freestyle. It is not: `Job#getEnvironment` fills that map with the
**agent's OS environment and node properties** before any `EnvironmentContributor` runs, whereas the
Pipeline invariant compares only against the enclosing *context-level* expander. So `putIfAbsent`
would defer to a node that merely exports `AWS_CONFIG_FILE` in its systemd unit — a setup
`locateNodeConfig` explicitly prefers — and **silently drop attribution while the console still
printed "decorated as session jk-…"**. Silent loss of attribution is worse than overriding a variable,
and `putAll` is what every Freestyle canary was validated against.

**Also fixed:** the unresolvable-node warning fired once per node *ever* (it sat inside the cache-miss
branch), so most builds on such a node said nothing; it now warns every build. Added
`forgetNodeRoles()` plus a `@BeforeEach` — the static cache meant one test recording an unresolvable
node short-circuited every later test in the JVM, so those tests could pass without running the path
they claim to cover. And one of the eight new tests was **vacuous**: it included `role_session_name`,
which sets `pinned` and returns before the identity guard is consulted, so it passed with the guard
reverted.

**231 tests.** The lesson worth carrying: *verify a fix against the real parser, not against the
reasoning that motivated it.* Two independent reviews were needed, and the second one caught what the
first one's fixes broke.

**Known, recorded, not fixed** (pre-existing; each needs a design decision, none is a regression):
`DefaultProcessRunner` leaves the child's stdin an open pipe with no timeout and runs on the
controller JVM; `configure()` blanks `profiles` in place while build threads read it unsynchronised;
`NODE_ROLE_ARNS` never invalidates for a permanent agent re-provisioned under the same node name;
per-step remote I/O in `stillOnDisk`; `buildWorkspace` does not cover `job@2` concurrent builds;
failed-assume stderr is echoed unmasked.

### Session 25 addendum 3 — why these issues surfaced now, and what was fixed

**Why now.** Almost none of this was new code going wrong. Three things converged:

1. **This was the first systematic review of the whole repo.** Everything before it was
   incident-driven (rivon) or milestone-driven, so review attention followed the defect rather than
   the codebase. `DefaultProcessRunner`, `CkAwsWithProfileStep` and `CliStsAssumeRole` date from M5/M6
   and had never been read adversarially.
2. **The blast radius of the config writer grew enormously in 2.2.** Before per-node unprofiled
   attribution, the plugin added `role_session_name` to profiles that *already* assumed a role — a
   tiny, almost unfalsifiable edit. Now it writes a full `role_arn` + `credential_source` +
   `role_session_name` triple into sections. A latent weakness in *parsing* became a
   build-breaking one in *writing*. The production `DuplicateOptionError` incident was the first
   symptom; the SSO, `source_profile`, indentation and delimiter defects are the same root cause.
3. **The root cause is one design decision:** the plugin approximates, with regexes, a file format
   whose real semantics live in Python's `configparser`. Every defect in this class comes from the
   approximation diverging from the parser. That is now contained in a single `optionKeysOf(...)`
   helper — if it diverges again, it diverges in one place, and both the writer and the guard move
   together.

**Fixed in this pass** (beyond the six already recorded):

- **`buildWorkspace` ignored concurrent builds.** `getWorkspaceFor` always answers `…/job`, but a
  second simultaneous build runs in `…/job@2`, which is not "inside" that root — so **every concurrent
  build fell back to the current directory**, reintroducing exactly the three problems the method
  exists to prevent. Now recognises a `job@N` root (digits, then a separator, so a job whose name
  contains `@` cannot be mistaken for one).
- **Session-name collisions.** Keeping the tail distinguishes jobs in the *same* folder but not jobs
  in *different* folders sharing a trailing segment — both truncated to one identical session name,
  and CloudTrail then attributes two builds to a single identity. A 6-hex digest of the full original
  name is appended whenever the name is lossy. Names that fit are untouched, which is nearly all.
- **`DefaultProcessRunner`**: child now gets no stdin (the default PIPE meant any prompting child
  blocked forever — and a blocking pipe read ignores `Thread.interrupt()`, so the step could not be
  aborted and the thread was lost permanently); bounded `waitFor`; both captures capped at 4 MB (this
  runs in the **controller** JVM, so a runaway child is a controller-wide problem); stderr reader is a
  daemon, catches `Throwable`, and is interrupted and joined on every exit path.
- **`profiles` is `volatile` and only ever replaced wholesale.** `configure()` emptied it in place
  before `bindJSON`, so an admin pressing Save while a build was inside `ckAwsWithProfile` could make
  that build abort with "No AWS profile named 'prod' is configured" — a phantom failure that would
  never reproduce. The volatile write also gives readers a happens-before edge to the `AwsProfile`
  objects, which are populated by setters after construction.
- **Console corruption**: the masking filter re-encoded *every* line through the build charset, so any
  byte invalid in it became U+FFFD permanently. Original bytes are now written through untouched when
  nothing matched.
- **Unmasked credentials on the failure path**: the masking filter is attached only to the step's
  *body*, so a failed assume-role echoed raw stderr into the console — and with `AWS_DEBUG` that
  includes the signed `Authorization` header and the source session token. Now masked and truncated.
- **`AWS_SESSION_TOKEN=None`**: `--output text` renders a null field as the literal `None`, which is
  not blank and passed every check; the build proceeded and failed opaquely much later inside the tool
  that used the credentials. Now rejected at construction.
- **Blank `awsExecutable` guard** added to `CkAwsWithProfileStep`, matching `CkAwsAssumeRoleStep`.

**232 tests.** Still recorded, not fixed: `NODE_ROLE_ARNS` does not invalidate for a permanent agent
re-provisioned under the same node name (all templates here are ephemeral, and a restart clears it),
and `stillOnDisk` does a remote stat per step. Both need a design decision — a `ComputerListener` and
a throttle respectively — rather than a patch.

**Exposure note worth keeping:** the `DefaultProcessRunner` / `CliStsAssumeRole` /
`CkAwsWithProfileStep` findings are all in the **M11 explicit-step layer**, which the census showed
**no production job uses**. They were worth fixing, but none of them gated the install. The findings
that did matter for the 802 real jobs were the ones in `AwsConfigOverlay`, `ManagedAwsContext`,
`ManagedAwsFreestyleEnvironment`, `CkAwsGlobalConfiguration` and `SessionName`.

### Session 25 addendum 4 — third review, and validation of 2.1.1 / 2.1.2 on the clone

**Versioning changed again, deliberately.** POC iterations now run on the **2.1.x** line (patches on
what infra already runs); **2.2.0 is reserved for the infra install** and must never be spent on the
clone, so "the controller says 2.2.0" can only ever mean the infra release.

**The third review verified 5 of 8 changes correct** (against Jenkins core bytecode and the real
`configparser`, not intuition) and found three real problems:

1. **`safeStderr` missed the format it was written for.** botocore prints headers as a Python dict —
   `'X-Amz-Security-Token': b'IQoJ…'` — and the regex required `name` followed directly by `[=:]`, so
   the apostrophe meant **nothing matched** and the session token was echoed verbatim. Widened to
   tolerate quoted keys and `b'` prefixes, and to consume to a structural delimiter rather than the
   first space (which had left the access key ID visible inside `Authorization`).
2. **The 120 s timeout could never fire.** `readCapped(stdout)` is an unbounded blocking read placed
   *before* `waitFor`, so a child that stays alive holding stdout open never reached the timeout at
   all — exactly the wedge the change was added for. The kill is now armed with
   `process.onExit().orTimeout(...)` *before* the read; killing the child is what unblocks it. (The
   reviewer separately proved `readCapped` does **not** deadlock: closing at the cap gives the child
   SIGPIPE and `waitFor` returns in ~22 ms.)
3. **A literal NUL byte** in `UNRESOLVABLE = "\0unresolvable"` made `ManagedAwsContext.java` read as
   binary, so **`grep` and `rg` silently skipped the largest source file on the managed path** unless
   given `-a`. Every grep-based search of this repo had an invisible hole. Now plain ASCII.

**Also fixed: the fail-open window in `configure()`.** Seven fields were still reset *before*
`bindJSON`, and two of those defaults fail **open** — `jobNamePattern = null` means *every job in
scope*, `jobNameExcludePattern = null` means *the exclusion containing an incident is dropped*. An
admin saving an unrelated setting could briefly put the whole controller in scope. Defaults are now
applied *after* the bind, from recorded key presence, so each field moves old-value → new-value with
no observable gap. All scope-critical fields are `volatile`, and `appliesToNode` snapshots the pattern
into a local (SpotBugs correctly flagged that check-then-use on a volatile field is a race).

**Documented rather than changed:** `SessionName` appends its digest only when the name is truncated
or empties — `a/b`, `a-b` and `a b` still collide as `jk-a-b-<n>`, as do `platform/deploy` and
`platform-deploy`. Closing it would re-name almost every build on the controller and break CloudTrail
name continuity: a rollout decision, not a patch. The javadoc now states the real guarantee.

**Validated on the clone, 2026-08-17:**

| Build | Result |
|---|---|
| 12 Pipeline canaries on 2.1.2 | all SUCCESS with correct `jk-<job>-<build>` |
| `poc-canary-freestyle-agent` #2 | CANARY_PASS on a real agent |
| `ckaws-canary-freestyle-master` #5 | real cross-account calls: `…/ck-ops-jenkins-master-instance-iam-role/jk-…-5` **and** `…/terraform-assume-role/jk-…-5` |
| `poc-canary-observeonly` #4 (2.1.1) | SUCCESS — `AWS_CONFIG_FILE=<unset>`, `ENCLOSING_ENV=kept` |
| `poc-canary-terraform-secondhop` #5 | SUCCESS — second hop still `aws-go-sdk-…`, exactly as documented |

**Two process lessons worth keeping.** A canary "passing" was reported from a build number that had
not actually re-run — always check the build's **start time**, not just its result. And
`POST /job/<name>/build` returns **400** for these Freestyle jobs; `scheduleBuild2(0)` via the script
console works.

**236 tests.** Untested still: the delete-last-profile path in `configure()` (verified correct by
reading core bytecode, but no test), and the runner's stdin/cap/timeout paths.

### Session 25 addendum 5 — boto3 closed with evidence; Terraform needs no further testing

**boto3 was the last untested execution mechanism**, and it mattered: most Freestyle jobs on this
controller call AWS through Python scripts, not the CLI, and *every* canary until now used the CLI.
"botocore honours `AWS_CONFIG_FILE`" was reasoning, not evidence — and reasoning-instead-of-evidence
is exactly what shipped the rivon defect.

`poc-canary-boto3` (Freestyle on the controller, created for this, matches the existing
`poc-canary-*` scope so no config change was needed):

```
BOTO3_VERSION=1.34.46
BOTO3_DEFAULT_ARN     = …/ck-ops-jenkins-master-instance-iam-role/jk-poc-canary-boto3-1
BOTO3_PROFILE_ops_ARN = …/ck-ops-jenkins-master-instance-iam-role/jk-poc-canary-boto3-1
```

**Both paths attributed** — the unprofiled `[default]` self-assume *and* an explicitly named profile.
The ARN is returned *by* `sts:GetCallerIdentity`, so AWS itself confirms the session name; CloudTrail
then indexed two `GetCallerIdentity` events under `jk-poc-canary-boto3-1`. Proven twice over.

**Terraform needs no further testing.** `poc-canary-terraform-secondhop` #5 ran on 2.1.2 and
reproduced the limitation exactly (`aws-go-sdk-1786965174359929854`). The other 18 Terraform jobs
carry no provider `assume_role` block — they are ordinary profile-based shell and are covered by the
CLI evidence. A real `terraform plan` would add nothing.

**Coverage of execution mechanisms is now complete:** AWS CLI (unprofiled and profiled), boto3
(unprofiled and profiled), Terraform first hop. The only gap is the Terraform/CLI **second hop**,
10 of 802 jobs, traceable transitively and unfixable without repo changes.

**Still resting on 2.2 evidence:** SCM-backed Pipeline (622 jobs, `dev2/fluentd #119`). The changes
since then are confined to the config writer, `configure()`, the process runner and session-name
truncation — all exercised by canaries on 2.1.2 — so this is a confidence gap, not a coverage gap.

**Note on `/opt/scripts` and the clone:** those scripts live on the instance filesystem, not in git,
so an edit made on infra master does **not** reach the clone. `UptimeReport_ecs.py` on poc-2 is dated
10 August and still carries the original six recipients. Its mail path is blocked four ways
regardless (SMTP host → `::1`, nothing listening on 25/465/587/2525, no local MTA, no Jenkins
publishers), and it uses **smtplib**, not the SES API — which is why it is safe where
`UptimeReport.py` (SES API, not blackholed) is not.

### Session 25 addendum 6 — the Terraform second hop IS solvable without repo changes (proven)

The second hop was recorded as unfixable. **That was wrong**, and the correction came from being pushed
to test rather than reason. Terraform has a native **override file** mechanism: any `*_override.tf` in
the working directory is merged over the configuration. The file is created at build time, so the
repository is never modified.

**Proven on a faithful canary** replicating the real `_setting.tf` — yaml-driven locals,
`terraform.workspace` selection, computed `role_arn` — against the real cross-account role:

```
A. baseline   275595855473:assumed-role/terraform-assume-role/aws-go-sdk-1786981874145529146
B. override   275595855473:assumed-role/terraform-assume-role/jk-cln-app-terraform-pipeline-9001
C. control    275595855473:assumed-role/terraform-assume-role/aws-go-sdk-1786981881777387740
```

Same role, same account, `region` preserved in all three; removing the file restores baseline exactly.

**The override MUST carry `role_arn`, copied verbatim.** This is the critical design constraint, found
by testing the obvious shortcut first:

```
override with ONLY session_name  ->  .../i-0cdd407bce366be0f
```

Terraform **replaces** the nested `assume_role` block rather than merging its attributes, so omitting
`role_arn` silently drops the assume entirely and Terraform runs as the **raw instance role** — wrong
principal, different permissions, no error, only a "this will be an error in a future release"
warning. That is the single most dangerous failure mode found in this entire POC, and it is why this
must be HCL-aware rather than a template.

Provider-level attributes (`region`) *do* merge; only the nested block is replaced.

**Fails loudly when mis-targeted**, which is the good direction: an override naming a provider alias
that does not exist gives `Error: Missing base provider configuration for override`.

**Real repo facts that make this tractable:** `infra-cloudkeeper-app-services` has exactly two
`provider "aws"` blocks and **no aliases anywhere**, and both use the identical expression
`arn:aws:iam::${local.workspace["aws"]["account_id"]}:role/${local.workspace["aws"]["role"]}`.

**Design for 2.3.0** — deliberately narrow, opt-in, fail-safe by skipping:
1. Scoped by an explicit job-name pattern; off by default.
2. Only touches a directory whose `provider "aws"` block has an `assume_role` with **no**
   `session_name`.
3. Copies the `role_arn` expression **textually** — never reconstructs or hardcodes it.
4. Skips (contributing nothing) on anything unrecognised: aliases, multiple providers, an existing
   `session_name`, or an expression it cannot extract cleanly.
5. Removes the file afterwards.

**Scope:** this solves the **3 provider-`assume_role` jobs**. It does **not** help the 7 jobs that run
`aws sts assume-role --role-session-name TestSessionName` in shell — proven separately that an
explicit CLI argument cannot be overridden by env, config, or CLI alias (all three tested).

### Session 25 addendum 7 — TerraformOverride implemented and proven end to end (2.1.4)

`TerraformOverride` generates the `*_override.tf` that names a Terraform provider's own
`assume_role`. **It is a pure function with no callers in any runtime path** — `grep` across
`src/main/java` returns only its own file — so it cannot affect a single existing build. That is
deliberate: the extraction logic is the part worth proving first, and it can be proven without
touching the contribution path at all.

**End-to-end proof.** The real `_setting.tf` was fed to the compiled class, and the file it produced
was dropped into a canary replicating the real repo (yaml-driven locals, `terraform.workspace`,
computed `role_arn`), planning against the real cross-account role:

```
with the plugin-generated override:
  275595855473:assumed-role/terraform-assume-role/jk-cln-app-terraform-pipeline-5620
after removing it (control):
  275595855473:assumed-role/terraform-assume-role/aws-go-sdk-1786983449497858871
```

`region` preserved, same role, same account, fully reversible. The generated `role_arn` is copied
character-for-character:
`"arn:aws:iam::${local.workspace["aws"]["account_id"]}:role/${local.workspace["aws"]["role"]}"`.

**9 tests, all asserting refusal rather than action** — because the failure mode here is not lost
attribution, it is a build silently running as a different principal:

| Input | Result |
|---|---|
| Real production shape | override written, expression verbatim |
| `assume_role` without `role_arn` | **nothing written** (this is the dangerous one) |
| `session_name` already pinned | nothing — a deliberate choice is not ours to change |
| Aliased provider | nothing — an override without the alias hits the wrong provider |
| Two `provider "aws"` blocks | nothing — picking one is a guess that could re-point an account |
| `provider "aws"` inside a comment or string | nothing — not a declaration |
| Interpolation braces inside strings | handled; block matching is brace-counted, string- and comment-aware |

**Still to build for 2.3.0: the runtime wiring**, which is the genuinely delicate part and was
deliberately not rushed. Design constraints already known:

1. The plugin prepares at the *first* step, but `.tf` files only exist *after* checkout — so the write
   must be lazy, not part of `prepareOnce`.
2. It must be scoped by an explicit job-name pattern and off by default.
3. The file must be removed at build end; leaving generated files in a workspace is the kind of
   residue this plugin has avoided everywhere else.
4. A workspace scan needs bounding (depth, file count, skip `.terraform/`) so it cannot become a
   per-step cost on large checkouts.

**Scope, unchanged:** solves the 3 provider-`assume_role` jobs. The 7 shell jobs run
`aws sts assume-role --role-session-name …` explicitly, and an explicit CLI argument was proven
unbeatable by environment, config, and CLI alias.

### Session 25 addendum 8 — Terraform second hop WIRED and validated end to end (2.1.8)

Working on the POC clone. `poc-canary-tfoverride` #4, a Freestyle job on a real agent, planning against
the real cross-account role:

```
[ck-aws] named the Terraform provider's own assume_role as jk-poc-canary-tfoverride-4 in 1 directory
OVERRIDE PRESENT:  session_name = "jk-poc-canary-tfoverride-4"
who = "arn:aws:sts::275595855473:assumed-role/terraform-assume-role/jk-poc-canary-tfoverride-4"
```

CloudTrail in 275595855473 confirms `GetCallerIdentity` under that session. **The second hop is now
attributed.** Full regression: **14/14 canaries pass, 0 fail.**

**Three defects were found by running it, not by reading it.** Each produced a build that looked
correct — the console said the override had been written — while Terraform still used the invented
name. That combination, a truthful-looking log over a wrong outcome, is the worst kind, and none of
the three would have been caught without a canary:

1. **Wired into the Pipeline path only.** The canary was Freestyle, which goes through
   `ManagedAwsFreestyleEnvironment` and never reached the new code. Both paths now call it. (The real
   Terraform jobs are Pipeline, so this would have shipped looking fine and silently skipped every
   Freestyle job.)
2. **A "done, stop scanning" marker.** The job did `rm -rf` on its Terraform directory between steps,
   which deleted the override the plugin had already written — and the marker meant it was never
   replaced. **Exactly the stale-memo defect this plugin fixed in 2.2, in new clothes:** any state
   cached across a step must survive the workspace being wiped underneath it.
3. **The throttle itself.** Reduced from 15 s to 2 s, and still too long — these steps run under a
   second apart, so the scan that mattered was skipped anyway. Removed entirely. The feature runs only
   for jobs explicitly opted in by pattern (3 of 802) and the walk is bounded, so correctness is worth
   more than the saving.

**Design as shipped:** opt-in via `terraformOverridePattern`, blank by default, separate from the main
scope — writing into a job's own source tree is a bigger intrusion than anything else this plugin
does, so it applies to the handful of jobs that need it and nothing else. Verified isolation:
`appliesTerraformOverride("poc-canary-tfoverride")` is true and `("dev2/fluentd")` is false. Written
paths are registered through `ManagedAwsRecord`, so the existing cleanup listener removes them at
build end. The whole call is inside its own guard: any failure contributes nothing and the job behaves
exactly as it does today.

**Scope unchanged:** solves the 3 provider-`assume_role` jobs. The 7 shell jobs that pass
`--role-session-name` explicitly remain unreachable — proven against env, config and CLI alias.

### Session 25 addendum 9 — the two remaining gaps closed (Pipeline path, multi-directory scan)

Two gaps were named and then closed rather than waved at as "needs more mileage".

**Gap 1 — the Pipeline path had no end-to-end proof.** The first canary was Freestyle, but all three
target jobs are Pipeline; the path that actually matters had never been run. Closed by
`poc-canary-tfoverride-pipeline`, a declarative Pipeline on a real agent.

**Gap 2 — the scanner had never walked a realistic tree.** The first canary had one directory with one
`.tf`. Closed with two working directories plus a vendored module planted inside
`.terraform/modules/vendored/` carrying a provider block pointing at
`arn:aws:iam::999999999999:role/must-not-be-touched`.

`poc-canary-tfoverride-pipeline` #1, SUCCESS:

```
[ck-aws] named the Terraform provider's own assume_role as jk-poc-canary-tfoverride-pipeline-1 in 2 directories
tfa/zz_ckaws_session_override.tf
tfb/zz_ckaws_session_override.tf
VENDORED-UNTOUCHED-GOOD
=== tfa ===  who = ".../terraform-assume-role/jk-poc-canary-tfoverride-pipeline-1"
=== tfb ===  who = ".../terraform-assume-role/jk-poc-canary-tfoverride-pipeline-1"
```

Both directories named, **both plans attributed**, and the vendored module inside `.terraform/`
correctly skipped — the decoy role was never written to. Scope isolation re-verified with a wildcard
pattern: `poc-canary-tfoverride.*` matches the canaries and **not**
`cln-infra-terraform-pipelines/cln-app-terraform-pipeline`.

**Confirmed from a real run:** `cln-app-terraform-pipeline` #5621 plans with
`find . -type d -name .terraform -prune -execdir terraform plan`, i.e. it really does plan in several
directories at once — so multi-directory support was a genuine requirement, not a hypothetical.

**Also confirmed: the real job gates apply behind a human `input`** ("Click proceed to approve all
Terraform Plans"), so a plan run cannot become an apply without someone clicking. That is worth
knowing before anyone runs one again.

**Operational note, cost of the day's iterations:** each plugin restart reset the controller's
`numExecutors` to 0, which silently left a real queued build waiting forever with
"Waiting for next available executor on 'Built-In Node'". Restore executors after any restart, or
queued work stalls with no error anywhere.

### Session 25 addendum 10 — the override logic run against 962 REAL .tf files

The compiled `TerraformOverride` was pushed to the clone and run against every `.tf` on the controller
carrying a top-level `provider "aws"` declaration — real repositories, not fixtures:

```
TOTAL FILES : 962
WOULD WRITE : 3
WOULD SKIP  : 959
   26  aliased provider
   13  no assume_role
  920  other (multi-provider / no role_arn / not a declaration)

sample writes:
  infra-cloudkeeper-app-services/application-setup/kong/_setting.tf
  infra-cloudkeeper-app-services/common/_setting.tf
  test/infra-cloudkeeper-app-services/common/_setting.tf
```

**Three writes out of 962** — and all three are exactly the `_setting.tf` shape the feature targets.
The other 959 are left untouched, which is the property that matters: this writes into a job's own
source tree, so being wrong in the permissive direction is the expensive kind of wrong. Combined with
the vendored-module canary (a provider block planted under `.terraform/modules/` pointing at
`role/must-not-be-touched`, confirmed untouched), the scanner is demonstrably conservative on real
input rather than only on fixtures.

Method note for repeating this: the box runs **Java 17**, so a driver compiled on a newer JDK fails
with `UnsupportedClassVersionError` — use `javac --release 17`. Loading `TerraformOverride` also needs
`jenkins-core` and `remoting` on the classpath, both present under
`/var/cache/jenkins/war/WEB-INF/lib/`, because of its `FileCallable` inner class.

### Session 25 addendum 11 — cln-app-terraform-pipeline was never a second-hop job

The override did not fire on the real job. Diagnosed to the end rather than assumed, and the finding
inverts an earlier claim.

`cln-infra-terraform` — the repo this job actually plans — does not use a static `assume_role` block:

```hcl
locals {
  role_enable = local.workspace["aws"]["role"] == "" ? [] : ["arn:aws:iam::…:role/…"]
}
provider "aws" {
  region  = local.workspace_aws["region"]
  profile = local.profile_enable
  dynamic "assume_role" {
    for_each = local.role_enable
    content { role_arn = assume_role.value }
  }
}
```

**All 74 workspace configs in that repo set `role: ""`.** So `role_enable` is `[]`, the `dynamic` block
renders zero times, and the provider authenticates **only** through `profile = "non_prod"` / `"prod"` —
a shared-config profile the plugin already decorates. **There is no second hop.**

This is confirmed by CloudTrail for #5620: **10 AssumeRole events, all carrying
`jk-cln-infra-terraform-pipelines-cln-app-terraform-pipeline-5620`**, including the cross-account hop
into 275595855473. That was the entire chain, not merely its first link — an earlier note read it as
"first hop attributed, second hop lost", which was wrong.

**So the plugin behaved correctly by writing nothing**, and this job is already fully attributed.

**Correction to the record:** the "3 jobs with provider `assume_role`" figure is not reliable. It was
derived from scanning for the string `assume_role`, which matches a `dynamic "assume_role"` block that
may render zero times, and matches `assume_role_policy` on IAM resources. Of the 962 real `.tf` files
scanned, the only genuine static `assume_role` providers live in `infra-cloudkeeper-app-services`
(3 files) — a different repository, reached by different jobs.

**What this means for the feature:** `TerraformOverride` remains correct and useful for the static
shape it targets, and is proven end to end on canaries. It cannot name a `dynamic "assume_role"` block,
because `role_arn` there comes from a `for_each` iterator that an override file cannot reproduce — and
it correctly declines rather than guessing.

**Before enabling it anywhere, confirm the target job actually renders a static `assume_role`.** Run
the bundled `Scan` against that job's checked-out repository; `WOULD WRITE 0` means there is nothing to
fix, not that the feature is broken.

### Session 25 addendum 12 — the attribution gap is ONE live job, not ten

Last-run dates for all nine jobs previously listed as unattributable. Eight are effectively dead:

| Job | Last run | Age | State |
|---|---|---|---|
| non-prod-ck-java-backend-deployment | 2022-12-16 | 1340d | dormant |
| ck-network-services-poc-terraform | 2023-01-18 | 1307d | **disabled** |
| non-prod-poc-ck-eks-base-setup | 2023-01-18 | 1307d | **disabled** |
| ck-route53-terraform | 2023-03-24 | 1242d | **disabled** |
| ck-ecs-terraform | 2023-04-18 | 1217d | dormant |
| ck-analytics-app-services-terraform | 2023-04-24 | 1211d | dormant |
| cognito-backup-dev | 2023-07-20 | 1124d | dormant |
| gradle-test-job | — | — | **never run** |
| **qa-virtuoso-resource-creation** | **2026-08-16** | **1d** | **ACTIVE, runs daily** |

**So the live attribution gap is one job.** `qa-virtuoso-resource-creation` runs daily (#477, #478 on
consecutive days), has no notifications, and does:

```
aws sts assume-role --role-arn ${roleArn} --role-session-name jenkins-session --output json
  -> "AWS_ACCESS_KEY_ID=${creds1.AccessKeyId}", ...
```

It exports the resulting credentials as environment variables, so every later AWS call in that build
uses them directly and **never consults `AWS_CONFIG_FILE`**. The plugin cannot reach it by any
mechanism — this is the strongest form of the explicit-argument case.

**One-line fix, with a fallback that is safe even if the plugin is off:**

```
--role-session-name "${CK_AWS_SESSION_NAME:-jenkins-session}"
```

**Consequence for the rollout:** the two jobs the `TerraformOverride` feature was built for
(`ck-analytics-app-services-terraform`, `ck-ecs-terraform`) have not run in over three years. The
feature is correct and proven on canaries, but it is **not urgent**, which further supports shipping
with `terraformOverridePattern` blank and enabling it only if either job is revived.

**Method note:** always check *last-run dates* before sizing an attribution gap. A grep over job
configs counts jobs that exist; it says nothing about jobs anyone runs. Sizing this by config count
produced "10 jobs" and three sessions of concern; sizing it by activity produced "one job, one line".

### Session 25 addendum 13 — the one live gap, measured exactly

`qa-virtuoso-resource-creation` could not be run directly (it does `ec2 allocate-address` and
`create-volume`), so its shape was replicated in `poc-canary-explicit-assume`: a normal call, then an
explicit `aws sts assume-role --role-session-name jenkins-session`, then exporting the returned
credentials as `AWS_ACCESS_KEY_ID`/`SECRET`/`SESSION_TOKEN`, then another call.

```
STEP 1  before the explicit assume : .../jk-poc-canary-explicit-assume-1   ATTRIBUTED
STEP 4  after exporting credentials: .../jenkins-session                   NOT attributed
Finished: SUCCESS                                                          build unaffected
```

CloudTrail under `jk-poc-canary-explicit-assume-1`: `AssumeRole` plus two `GetCallerIdentity`. **The
AssumeRole that minted `jenkins-session` is itself recorded under the build's session name**, so the
later calls are one join from the build rather than invisible.

**The precise boundary, for this and any job of this shape:**

| Phase | Attributed? |
|---|---|
| Everything before the explicit assume | ✅ `jk-<job>-<build>` |
| The `AssumeRole` call itself | ✅ caller recorded as `jk-<job>-<build>` |
| Everything after the credentials are exported | ❌ carries the hardcoded name; traceable by join |

Exporting `AWS_ACCESS_KEY_ID` into the environment takes precedence over `AWS_CONFIG_FILE` in every
AWS SDK, so the plugin is out of the loop by construction — not by defect. **No mechanism inside a
Jenkins plugin can change this**, which is why the fix is one line in that job's repository.

**Nothing failed.** The canary succeeded, which is the property that matters most: a job of this shape
is unaffected by the plugin.

### Session 25 addendum 14 — 2.2.0: the infra release

**Version 2.2.0.** The 2.1.x line (2.1.1 – 2.1.8) was POC iteration and is spent; 2.2.0 was reserved
for infra from the start and is now claimed.

**The two switches, and how they relate.** Verified in source rather than assumed:
`isManagedAuthentication()` is checked **first** on both paths — `ManagedAwsContext` line 247,
`ManagedAwsFreestyleEnvironment` line 81 — and `isObserveOnly()` only later (287 / 116). So
**observe-only does nothing unless managed authentication is on**; with the master switch off the
plugin returns immediately and is indistinguishable from not being installed.

Defaults therefore ship as **managedAuthentication = false, observeOnly = true**. That pair is
deliberate: a fresh install does nothing at all, and the moment an administrator turns the master
switch on, the safe mode is *already selected* — the first action reports what would happen and
exports nothing, rather than changing the environment of every in-scope build at once. Enforcing
becomes a second, deliberate click.

**The plugin is not customised for the POC — verified, not assumed.** Grepping all of `src/main` for
`poc`, agent hostnames, instance IDs, account numbers, `cloudkeeper`, `ck-ops-jenkins`,
`jenkins-slave`, `bitbucket`, absolute paths and hardcoded ARNs/regions found **7 hits, all of them
javadoc or comments**. No hardcoded path, ARN, account, region or hostname exists in shipped code. The
only environment-specific values are the ones an administrator configures.

**Observe-only: what it records and where.** It runs the whole path — reads the node's config,
decorates, validates, and *writes* the file to `<workspace>@tmp/ck-aws/config` — and withholds only
the export. Its record lives **in the build console log and nowhere else**; there is no database and
no index.

⚠️ **Retention caveat for anyone planning to read that evidence later:** on this controller **544 of
806 jobs have a build discarder** and 262 keep everything. For a frequently-run job keeping only a few
builds, a day-old observe-only record may already have rotated away. Sweep the logs periodically
during the observation window rather than once at the end.

**Canaries on infra.** Six `ckaws-canary-*` jobs came across in the AMI and therefore exist on infra
today: `ckaws-canary`, `-master`, `-drupal`, `-pdf`, `-freestyle`, `-freestyle-master`. They only run
`aws sts get-caller-identity`, so they are harmless and useful as post-install smoke tests. The ~20
`poc-*` canaries were created on the clone and exist **only** there — nothing on the clone propagates
to infra, which has a separate instance, EBS volume and `JENKINS_HOME`.

### Session 25 addendum 15 — fourth review: the default change broke 23 tests

Changing `observeOnly` to default `true` **broke 23 tests**, and the fourth review caught it before any
artifact was built. The failure was not the default itself but what it exposed: nearly every test
enabled managed authentication through a helper that never touched `observeOnly`, so with the new
default they all silently ran in **observe-only** — asserting nothing about what is exported.

**The regression net for the enforcing path — the only path that can change a build — had switched
itself off, while still reporting 249 tests.** A green suite that tests the wrong mode is worse than a
red one.

Fixed by making the intent explicit in every enforce-path helper (`ManagedAwsContextTest`,
`ManagedAwsFreestyleEnvironmentTest`, `FleetSimulationTest`, `AgentCoverageTest`,
`ProductionFailureModesTest`) plus `ObserveOnlyDefaultTest`, which pins the shipped pair so a future
accidental flip fails loudly. **250 tests, 0 failures.**

**Upgrade semantics, verified rather than reasoned.** Field initialisers run before the constructor
body, and XStream never clears a field whose element is absent, so:

- v2.1 has no `observeOnly` element in its XML → **infra will get the new default `true`** — intended
- a controller whose XML *does* carry `observeOnly=false` keeps false — the admin's saved choice wins

Confirmed empirically on the clone: after upgrading to 2.2.0 its saved `observeOnly=false` survived.
The wrinkle worth remembering: on such a controller, later enabling the master switch goes straight to
enforcing with no observe-only phase.

**`managedAuthentication` cannot be turned on by an upgrade** — no initialiser, set only from saved XML
or a deliberate admin action, and defaulted to false when absent from a form submission.

**The OFF state is inert, traced on both paths.** Pipeline `contribute` does an extension lookup and one
volatile read before returning; the first call that could do I/O comes after the check. Freestyle is
the same shape. Blank `terraformOverridePattern` returns false before `TerraformOverride` is touched.

### 2.2.0 — the artifact

`sha256 966d1500a0ad43c2b3085cc40372d8d0820cf5f78c48b5a33339b589a8813742`, `Plugin-Version: 2.2.0`,
250 tests, four adversarial review passes. Installed on the clone and **14/14 canaries pass**.

### ⚠️ Do NOT enable `terraformOverridePattern` until two defects are fixed

Both found by the fourth review, both able to **fail a build**. Harmless today only because the pattern
ships blank.

1. **Malformed HCL when `role_arn` spans multiple lines.** `attributeLine` extracts a single line, so a
   `role_arn = format("arn:aws:iam::%s:role/%s",` continuation is copied truncated →
   `Error: Missing argument separator` → `init`/`plan` fail.
2. **Sibling attributes silently dropped.** Only `role_arn` is copied, and Terraform replaces the nested
   block wholesale — so `external_id`, `duration`, `policy`, `policy_arns`, `source_identity` vanish.
   A role requiring an ExternalId then fails with AccessDenied; dropping `policy_arns` instead
   *widens* the session's permissions silently.

**One fix closes both: copy the entire original `assume_role` body verbatim and append a single
`session_name` line**, rather than reconstructing from `role_arn`. Also outstanding before enabling:
`terraform fmt -check` fails on the generated file (alignment); the writer half (`applyTo`,
`WriteOverrides`) has no tests; the walk follows symlinks; observe-only does not suppress the write;
and any admin Save silently clears the pattern because it is in no jelly.

### Session 25 addendum 16 — TerraformOverride removed from the release

The feature is **gone from the shipped plugin**. It was correct research and it worked, but it did not
belong in an artifact going to a controller running 802 production jobs:

- **Nobody needed it.** Its only two beneficiaries, `ck-analytics-app-services-terraform` and
  `ck-ecs-terraform`, last ran in **April 2023**. The Terraform job that actually runs is already fully
  attributed without it.
- **It carried two build-breaking defects** (multi-line `role_arn` → malformed HCL; sibling attributes
  such as `external_id` / `policy_arns` silently dropped because Terraform replaces the nested block).
- **It was the largest unvalidated surface in the release** — canaries only, and its writer half had no
  tests at all, while writing into a job's own checked-out source tree.

Removed: `TerraformOverride.java`, both call sites, `terraformOverridePattern` and all its config
plumbing, and the two test classes. `grep` across `src/` returns nothing, and the running plugin
confirms it: `java.lang.ClassNotFoundException: io.github.rads4.ckaws.managed.TerraformOverride`.

**Nothing durable was lost.** The mechanism, the end-to-end proof, and the trap that omitting
`role_arn` silently runs as the wrong principal are all in addenda 6–11; the code is in git at
`ef6a0e0`, `3f511e5`, `b3388b4`. If either job is revived it is one `git show` away, with the two
defects already identified and the fix written down: copy the whole `assume_role` body verbatim and
append `session_name`.

**Final artifact: `sha256 40f24324eb6240cb96ce95ddb4cfa109b68ca4a6de0d8f0cdb1a8d7e76dbea54`,
Plugin-Version 2.2.0, 237 tests, 0 failures.** Installed on the clone; **14/14 canaries pass**. The UI
is unchanged at eight fields.

### Session 25 addendum 17 — correction: the executor reset is a POC artefact

Earlier notes told the reader to "check `numExecutors` after the restart" because it reset to 0 on
every restart of the clone and silently stalled a real queued build. **That advice was wrong for
infra, and the correction matters more than the original observation.**

The cause is `init.groovy.d/pocInit06KillResumedBuilds.groovy:103`, which calls
`Jenkins.get().setNumExecutors(0)` on every start. It is one of six `pocInit*.groovy` neutralisation
hooks **pushed onto the clone after it was built** — deliberately, to stop resumed production builds
running there. They were never in the AMI, so **infra has no such hook and executors will not reset
there.**

Generalising a POC artefact into the infra runbook is precisely the failure mode this whole exercise
was meant to avoid. When something on the clone behaves oddly, check whether a `pocInit*` hook causes
it before writing it down as Jenkins behaviour.

### Session 25 addendum 18 — `profiles` removed from the UI; the M11 layer deliberately kept

**Removed from the form, not from the code.** `AWS profiles` no longer appears in the configuration
page. `AwsProfile`, `usableProfiles()` and `AwsConfigOverlay.appendOverrides` are untouched — this was
a change to one jelly file and nothing else. **UI is now seven fields.**

**Why it was safe, verified four ways rather than assumed:**

```
job configs using ckAwsWithProfile / ckAwsAssumeRole : 0   (all 802 jobs)
build logs mentioning ckAwsWithProfile               : 0   (covers SCM Jenkinsfiles that ran)
Jenkinsfiles / .groovy in workspaces                 : 0
<profiles/> in the plugin config                     : EMPTY
```

The decisive one: **with an empty list, `ckAwsWithProfile` cannot work anyway** — it aborts with "No AWS
profile named 'x' is configured". Any job using it would already be failing, and none is.

**A real defect was caught by doing it.** Two form round-trip tests failed immediately: removing a field
means `configure()` clears it on the next Save, so an operator's JCasC-managed profile list would be
silently discarded the first time anyone pressed Save for an unrelated reason. Fixed the same way as
`nodeLabelPattern` and `unprofiledRoleArn` — **`configure()` no longer resets `profiles`**. Only fields
the form actually submits may be reset from the form.

**Why the M11 layer itself was NOT removed**, despite being unused. Measured before deciding:

```
M11 explicit-step layer : ~2,065 lines
Total main code         :  4,720 lines   -> ~44% of the plugin
Test classes to rewrite :  9
SessionName             : SHARED with the managed path, cannot be deleted
```

Removing it means changing four public signatures on **`AwsConfigOverlay`** and deleting
`appendOverrides` — inside the class that writes the AWS config file, the one that caused the
production `DuplicateOptionError`, needed the SSO identity guard, and needed the configparser parser
rewrite. **Five review passes examined the plugin in its current shape; a 44% change invalidates that
coverage** for zero functional gain, since the code is inert and cannot fail a build or leave a job
unaudited.

The rule that decided every removal this session: **remove what is dangerous or free to remove; keep
what is merely unused when removing it touches the safety-critical writer.** The static ARN field and
`TerraformOverride` went because they could break builds. This stays. Scheduled as its own cleanup
release, with its own review cycle.

**Release artifact: `sha256 f2d3a59eb808ccf4ffb0a9166f21eef43edf9d95b0b6b6ce72691ec46dbbbaa6`,
Plugin-Version 2.2.0, 237 tests, 14/14 canaries.**

---

# ===== CHECKPOINT 2026-08-18 — READ THIS FIRST IN A NEW SESSION =====

## Where things stand

**ck-aws 2.2.0 is built, validated, committed, and ready to install on infra Jenkins.**

```
Plugin-Version : 2.2.0
sha256         : f2d3a59eb808ccf4ffb0a9166f21eef43edf9d95b0b6b6ce72691ec46dbbbaa6
artifact       : poc-jenkins-setup/artifacts/ck-aws-2.2.0-final.hpi
tests          : 239, 0 failures, 1 skipped
canaries       : 14/14 on the release build
UI             : 7 fields
reviews        : 5 adversarial passes, ALL COMPLETE. Fifth verdict: SAFE TO INSTALL
```

| Where | What |
|---|---|
| **infra Jenkins** | ck-aws **2.1**, master switch OFF. **Never touched during any of this work** |
| **poc-jenkins-2** (`i-0cdd407bce366be0f`) | 2.2.0 installed, clean state, scope `poc-canary-*`, observeOnly=false, managedAuth=true |

## OPEN AT CHECKPOINT

1. **~20 commits are LOCAL ONLY.** `git push` is blocked by Rads' mutation hook; she must push or approve.
2. Infra Jenkins has **not** been inspected — standing no-contact rule. Needs explicit go-ahead.
3. ⚠️ **Before installing, check infra's `$JENKINS_HOME/io.github.rads4.ckaws.config.CkAwsGlobalConfiguration.xml`
   for `<managedAuthentication>`.** The clone's copy says `true`. If infra's does too, the plugin becomes
   active in observe-only mode on restart rather than dormant — safe (nothing exported) but every
   in-scope build then reads its agent config and writes a temp file. If it is `false` as expected, the
   install is a complete no-op until someone deliberately opts in.

## The install procedure

1. Install `ck-aws-2.2.0-final.hpi` — master switch is **off** by default, so this changes nothing
2. One restart
3. Verify: `managedAuthentication=false`, `observeOnly=true` (both are the shipped defaults; 2.1's XML
   has no `observeOnly` element so the upgrade takes `true`)
4. Tick **Managed authentication**. Observe-only is already on → it reports and exports nothing
5. Add a log recorder: *Manage Jenkins → System Log* → logger `io.github.rads4.ckaws` at **WARNING**
6. Read a day of build consoles. ⚠️ **544 of 806 jobs discard old builds — sweep periodically, not
   once at the end**, or evidence rotates away
7. Untick **Observe only** → attribution goes live, `jk-<job>-<build>` appears in CloudTrail

**Do NOT carry `numExecutors` into the runbook** — that reset was a POC-only hook
(`pocInit06KillResumedBuilds.groovy`), not Jenkins behaviour. Infra has no `pocInit*` hooks.

## What is proven

| Job type | Count | Evidence |
|---|---|---|
| SCM Pipeline | 622 | `dev2/fluentd` — 35 CloudTrail events |
| Inline Pipeline | 111 | `CodeArtifact-PoC` — `GetAuthorizationToken`; also `sh()` in `environment{}` |
| Freestyle | 69 | `ckaws-canary-freestyle-master` — cross-account `jk-` |
| Terraform | 21 | `cln-app-terraform-pipeline` — 10 AssumeRole events, **all** `jk-` |

AWS CLI and boto3, both profiled and unprofiled. 7 agent AMIs + controller. Fail-safe proven on a real
node whose role AWS refused to let self-assume — no `[default]` written, **build passed**.

**No POC-specific code:** grepping all of `src/main` for poc/hostnames/instance-ids/accounts/paths/ARNs
found 7 hits, **all javadoc**.

## The ONE live attribution gap

`qa-virtuoso-resource-creation` (runs daily) does
`aws sts assume-role --role-session-name jenkins-session` then **exports the credentials as environment
variables**, which outrank `AWS_CONFIG_FILE` in every AWS SDK. Calls before the assume are attributed;
the AssumeRole itself is attributed; calls after are not, but are one join away.

**Fix is one line in that repo:** `--role-session-name "${CK_AWS_SESSION_NAME:-jenkins-session}"`.
No plugin can do this. Every other job once listed as a gap is disabled, dormant 1100+ days, or has
never run.

## POC cleanup — do it AFTER the rollout

**There is no connection between the clone and infra** — separate instance, EBS and JENKINS_HOME.
Keep `poc-jenkins-2` until infra is enforcing and satisfactory; it is the only place to reproduce
anything without touching production. Then: terminate `i-0cdd407bce366be0f`, delete SGs
`sg-00a1f09ce5d7b073d`, `sg-08694bd76e66abaf7`, `sg-0703eb4187bf65df3`, `sg-07ab9ae8ee8e473aa`, and
restore `~/.claude/hooks/block-mutations.py` from `.PRE-POC-BACKUP`.

Six `ckaws-canary-*` jobs **exist on infra** (they came in the AMI) — harmless, only run
`sts get-caller-identity`, useful as post-install smoke tests. The ~20 `poc-*` canaries exist only on
the clone.

## Planned next work

- **Delete the M11 explicit-step layer** as its own release with its own review cycle (~2,065 of 4,720
  lines, 9 test classes, and it touches `AwsConfigOverlay` — that is why it was NOT done here)
- Set up gap detection: the log recorder above, plus CloudTrail bucketed by session-name shape
  (`jk-*` audited; `i-0*` / `aws-go-sdk-*` / 32-hex = gap)
- The one-line fix to `qa-virtuoso-resource-creation`

## Fifth review — verdict and what it changed

**SAFE TO INSTALL. No critical, high or medium finding.** The reviewer independently verified the
build (237 tests at the time, SpotBugs 0), traced the `TerraformOverride` deletion as clean with zero
dangling references, and confirmed the off state is inert on both paths — including a bytecode check
that a `null` from a `DynamicContext` continues the search rather than shadowing, so the rivon defect
class cannot recur while off.

Most valuable confirmation: it proved **two independent ways** — a live probe through Jenkins' real
`XmlFile`/`XStream2`, and a bytecode trace of `Descriptor.load` — that unmarshalling writes only the
elements actually present, so **a 2.1 config lacking `<observeOnly>` yields `true`**. That is now also
pinned by `UpgradeFromOlderVersionTest`.

**Three LOW findings fixed rather than shipped:**

1. **`isBenignRace` could hang a build thread.** Its `c.getCause() == c` guard was **dead code**
   (`getCause()` returns null, never `this`), and a cyclic cause chain — constructible via the public
   API — made the loop non-terminating. Inside the fail-open guard a hang is worse than an exception:
   the build would neither fail nor proceed. Now depth-bounded at 20.
2. **`NODE_ROLE_ARNS` grew without bound** — one permanent entry per ephemeral agent ever provisioned.
   Now capped at 500 entries.
3. **`configure()` defaulted `observeOnly` to `false` when absent**, the one field whose inert value is
   ON. Unreachable from the real UI (a checkbox always submits its key) but wrong in principle. Now
   defaults to `true`, matching the field initialiser.

**Left as accepted:** assorted dead code (`preparedCount`/`preparedKeys`, two `doCheck*` methods with no
matching form field), and a doc nit — the `credentialSource` help text says it applies only to
`[default]`, while `appendOverrides` also writes `credential_source` for appended profiles. True on a
fresh install (empty list), inaccurate on an upgrade carrying profiles.

**Final: 239 tests, 0 failures, 14/14 canaries on the shipping artifact.**

## Final artifact — traceability corrected

A last check caught that the artifact's manifest recorded `Implementation-Build: 6a5323b` while HEAD
was `1bf157e`: it had been built *before* the review fixes were committed, so anyone checking out the
recorded commit would not have got this artifact's source. Cosmetic to the running plugin, but it
defeats the one thing this whole exercise cared about — knowing exactly what is installed.

Rebuilt from the committed tree and re-validated. **Install this and nothing else:**

```
Plugin-Version      : 2.2.0
Implementation-Build: 1bf157ee74259afe5ff28734c401357fcfa91d06   (matches HEAD)
sha256              : f2d3a59eb808ccf4ffb0a9166f21eef43edf9d95b0b6b6ce72691ec46dbbbaa6
artifact            : poc-jenkins-setup/artifacts/ck-aws-2.2.0-final.hpi
tests               : 239, 0 failures
canaries            : 14/14 on this exact artifact
```

Every earlier 2.2.0 hash (`40f24324`, `19db49cc`, `98fb48b2`) is superseded — same version number,
different builds during the day. **Check `Implementation-Build` matches the commit you expect before
installing**; `.hpi` builds are not reproducible, so the sha alone cannot tell you which source it came
from.
