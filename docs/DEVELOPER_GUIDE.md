# CK AWS Plugin — Developer Guide

> **Read this first, before the code.** It explains *why* the plugin is shaped the
> way it is. The code is small and readable once you know the shape; it is
> confusing if you start at `AuthCore.java` and work outwards.
>
> Companion documents: [CLAUDE.md](../CLAUDE.md) is the binding architecture
> contract (constraints you must not break). [README.md](../README.md) is the
> build/run reference. This guide is the mental model that connects them.

---

## 1. Project Overview

### The problem

CloudKeeper's Platform team owns 100% of the deployment logic that runs in
Jenkins: shared Groovy libraries (`Deploy.groovy`, `AwsAuth.groovy`, …),
per-repo deployment Groovy files, and some standalone repos that skip the shared
library entirely and call the AWS CLI directly.

Today, **AWS authentication is scattered across all of that Groovy**. Each place
does its own `aws sts assume-role`. Two consequences:

| Symptom | Why it hurts |
|---|---|
| Every caller can pick its own `RoleSessionName` | CloudTrail shows auto-generated session names like `AROA…:i-0abc123`, so you cannot attribute an AWS API call back to a Jenkins job and build |
| Auth logic is duplicated in N Groovy files | Changing how auth works means editing N files across N repositories |
| There is no enforcement surface at all | Nothing prevents a pipeline from assuming a role however it likes |

### What this plugin does

It moves **authentication** out of Groovy and into a Jenkins plugin, and it makes
every AssumeRole call carry a **deterministic, standardized session name**:

```
jk-<job-name>-<build-number>        e.g.  jk-myjob-123
```

That string is the whole point. It turns an anonymous AWS API call into an
attributable one, and it is the precondition for the real enforcement mechanism
(see below).

### How this differs from just calling `aws sts assume-role`

Calling the CLI directly and calling this plugin both end at the same AWS API.
The difference is everything around it:

| | Groovy calling `sts assume-role` | This plugin |
|---|---|---|
| Session name | Whatever the caller typed, or auto-generated | Always `jk-<job>-<build>`, generated in one place |
| Job identity | Passed by hand, often wrong or missing | Read off the running `Run` object — cannot drift |
| Bad input | Cryptic CLI error mid-pipeline | Fails closed with an actionable message before any AWS call |
| Credential exposure | Lives in pipeline variables, easily echoed | Never leaves the JVM; the step returns only a session name |
| Changing auth behaviour | Edit N Groovy files in N repos | Ship one plugin version |

> **⚠️ Important framing: this plugin is not a security boundary.**
> Nothing inside Jenkins can be made non-bypassable — a raw `sh "aws ..."`
> sidesteps any plugin, step, or listener, because it all runs inside the same
> trust boundary as an unrestricted shell.
>
> The *actual* backstop is an IAM trust-policy condition on the target roles:
>
> ```json
> "Condition": { "StringLike": { "sts:RoleSessionName": "jk-*" } }
> ```
>
> AWS itself then denies any AssumeRole whose session name doesn't conform. That
> is a **future phase, not built here**. This plugin's job is to make that future
> step possible without a redesign — which is why the `jk-` shape is frozen.

### Where it sits in the ecosystem

```
Application teams          ← never write deployment logic
        │
Platform/DevOps team  ─── owns ───┬── Jenkins itself
                                  ├── shared Groovy libraries
                                  ├── deployment Groovy files
                                  └── Jenkins plugins  ← this lives here
```

Deployment Groovy keeps owning *workflow*: which AWS commands run, in what
order, with what arguments. The plugin owns *identity*: who those commands run
as. That split is deliberate — see §7.

### Current status (be precise about this)

This is a **proof of concept**, validated against CloudKeeper's **read-only** Ops AWS profile on a local `mvn hpi:run` Jenkins. What exists today:

| Capability | State |
|---|---|
| STS AssumeRole with `jk-` session naming | ✅ Implemented and validated against live AWS |
| `ckAwsAssumeRole` pipeline step | ✅ Implemented |
| Generic process executor primitive | ✅ Implemented (`ProcessRunner`) |
| Generic **AWS CLI pipeline step** (`ckAws.run([...])`) | ❌ Not implemented — the primitive exists, the step does not |
| Retry / timeout / structured logging | ❌ Not implemented |
| RunListener auto-injection, JCasC role config | ❌ Not implemented |
| Credential caching / refresh | ❌ Not implemented (seams exist — see §11) |

---

## 2. High-Level Architecture

Six layers. Each one exists to keep a specific piece of knowledge out of the
layers above and below it.

```
┌──────────────────────────────────────────────────────────┐
│  Jenkins Pipeline (Groovy)                               │
│  ckAwsAssumeRole(roleArn: 'arn:aws:iam::…:role/non_prod')│
└───────────────────────────┬──────────────────────────────┘
                            │  knows: Jenkins DSL
                            ▼
┌──────────────────────────────────────────────────────────┐
│  CkAwsAssumeRoleStep          [steps]                    │
│  the ONLY class that imports Jenkins types               │
└───────────────────────────┬──────────────────────────────┘
                            │  passes: roleArn, jobName, buildNumber
                            ▼
┌──────────────────────────────────────────────────────────┐
│  AuthCore  +  SessionName     [auth]                     │
│  the policy layer: naming rules, fail-closed, error shape│
└───────────────────────────┬──────────────────────────────┘
                            │  AssumeRoleRequest
                            ▼
┌──────────────────────────────────────────────────────────┐
│  StsAssumeRole (interface)    [auth]                     │
│  the seam: "something can assume a role" — nothing more  │
└───────────────────────────┬──────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────┐
│  CliStsAssumeRole             [auth.cli]                 │
│  the ONLY class that knows the shape of `sts assume-role`│
└───────────────────────────┬──────────────────────────────┘
                            │  List<String> argv
                            ▼
┌──────────────────────────────────────────────────────────┐
│  ProcessRunner / DefaultProcessRunner   [exec]           │
│  runs an argv. Knows nothing about AWS or Jenkins.       │
└───────────────────────────┬──────────────────────────────┘
                            ▼
                    aws CLI  →  AWS STS
```

**Why each layer exists:**

- **Pipeline step** — Jenkins-specific plumbing (extension registration, CPS
  threading, data binding, `AbortException`) is genuinely awkward and needs a
  running Jenkins to test. Confining it to one class means everything below it
  is a plain-Java unit test.
- **AuthCore + SessionName** — policy has to live in exactly one place or the
  `jk-` convention drifts. This layer decides *what* to ask for and *how to fail*,
  and knows neither Jenkins nor AWS transport.
- **StsAssumeRole (interface)** — a one-method port. It is what lets `AuthCore`
  be tested with a 40-line hand-written fake, and what lets the transport be
  swapped (SDK, agent-side launcher, cached wrapper) without touching policy.
- **CliStsAssumeRole** — the adapter. All knowledge of `aws sts assume-role`
  flags and output format is quarantined here, and every execution-layer failure
  is translated into `AssumeRoleException` so nothing above learns that a
  subprocess was involved.
- **ProcessRunner / DefaultProcessRunner** — a generic "run this argv" primitive.
  Kept AWS-blind on purpose so the future generic AWS CLI executor reuses the
  exact same class with no branching.

---

## 3. Runtime Flow

What actually happens when a pipeline runs
`ckAwsAssumeRole(roleArn: 'arn:aws:iam::123456789012:role/non_prod')`.

**Setup**

1. Jenkins finds the step by function name (`ckAwsAssumeRole`) via the
   `@Extension`-annotated `DescriptorImpl`, and constructs
   `CkAwsAssumeRoleStep` through its `@DataBoundConstructor`. **No validation
   happens here** — a bad value would produce an opaque data-binding error
   instead of a readable build failure.
2. `start(StepContext)` returns an `Execution`, a
   `SynchronousNonBlockingStepExecution<String>`. "NonBlocking" means it runs on
   a separate thread, not the CPS VM thread — blocking the CPS thread on a
   subprocess would stall the whole build's flow execution.

**Validate and identify (no AWS calls yet)**

3. `run()` trims `roleArn`. Blank ⇒ `AbortException` with a usage example.
4. Pulls `Run` and `TaskListener` out of the step context. Missing ⇒
   `AbortException` (the descriptor's `getRequiredContext()` normally makes
   Jenkins catch this earlier with a clearer message).
5. Extracts identity: `jobName = build.getParent().getFullName()` and
   `buildNumber = build.getNumber()`. **`getFullName()`, not `getName()`** — for
   a job inside a folder the full name includes the folder path (`team/myjob`),
   which is more precise and which `SessionName` knows how to sanitize.

**Session name generation**

6. `SessionName.forBuild(jobName, buildNumber)` is called *by the step, up
   front*. It is deterministic and side-effect-free, so computing it early costs
   nothing and buys two things: the build fails closed **before** any process is
   spawned, and the step has the value it needs to return.
7. Inside `SessionName`: reject blank job / non-positive build → replace every
   character outside AWS's `[\w+=,.@-]` set with `-` → collapse `--` runs → trim
   edge dashes → truncate the **middle (job) segment** if the result would
   exceed 64 chars, so the `jk-` prefix and the trailing build number always
   survive → re-validate the assembled string defensively.

   ```
   "my awkward job", 7   →  jk-my-awkward-job-7
   "team/deploy/api", 12 →  jk-team-deploy-api-12
   ```

8. The step prints `[ck-aws] Assuming role <arn> as session <name>` to the build
   log — visible before the slow part, so a hung build shows what it's waiting on.

**AssumeRole**

9. The step constructs the stack per invocation:
   `new AuthCore(new CliStsAssumeRole(new DefaultProcessRunner(), awsExecutable()))`.
   (`awsExecutable()` reads the `io.github.rads4.ckaws.awsExecutable` system
   property, defaulting to `aws`; it exists so tests can substitute a stub
   script, since a child process inherits the JVM's `PATH`.)
10. `AuthCore.authenticate(roleArn, jobName, buildNumber)` regenerates the same
    session name (deterministic, so identical), wraps it in an immutable
    `AssumeRoleRequest`, and delegates to the `StsAssumeRole` port. No duration
    is requested — role chaining caps the session at 1 hour regardless.
11. `CliStsAssumeRole` builds the argument list:

    ```
    aws sts assume-role
        --role-arn            arn:aws:iam::123456789012:role/non_prod
        --role-session-name   jk-myjob-123
        --query   Credentials.[AccessKeyId,SecretAccessKey,SessionToken,Expiration]
        --output  text
    ```

    The `--query … --output text` projection is why the plugin needs **no JSON
    parser dependency**: the CLI returns one tab-separated line.

**Process execution**

12. `DefaultProcessRunner.run(command)` starts a `ProcessBuilder`. The child
    inherits this JVM's environment, so ambient AWS config (`AWS_PROFILE`,
    `AWS_DEFAULT_REGION`, instance-metadata role) reaches the CLI **without the
    plugin ever reading `~/.aws/config`**.
13. stdout and stderr are captured **separately and drained concurrently** —
    stderr on a helper thread, stdout on the calling thread. Reading them
    sequentially would deadlock if the process filled the other pipe's buffer.
    Then `join()` the reader, `waitFor()` the process, and return a
    `ProcessResult(command, exitCode, stdout, stderr)`.

    > A process that *ran* and exited non-zero is **not** an exception — that's a
    > normal `ProcessResult`. `ProcessExecutionException` means the process could
    > not be run at all (missing binary, interrupted, unreadable stream).

**Parsing and error handling**

14. Back in `CliStsAssumeRole`, three failure modes all become
    `AssumeRoleException`, each carrying the role ARN and session name for
    context:
    - `ProcessExecutionException` → "could not execute 'aws sts assume-role'"
    - non-zero exit → message includes the exit code and trimmed stderr
    - empty stdout, wrong field count, or unparseable expiration → parse error
15. On success: split the line on `\t` (limit `-1`, so empty trailing fields
    still count), require exactly 4 fields, parse the expiration as
    `OffsetDateTime` with an `Instant` fallback, and build `AwsCredentials`.
16. `AuthCore` applies the final policy pass: a `null` return becomes
    `AssumeRoleException`; an existing `AssumeRoleException` passes through
    unchanged; any other `RuntimeException` is wrapped with role + session
    context.

**Return**

17. The step catches `CkAwsAuthException` and calls `abort(...)`: it logs the
    **root cause's message** as `[ck-aws] cause: …`, then throws
    `AbortException(e.getMessage())`. `AbortException` prints its message with no
    Java stack trace — the Jenkins idiom for an expected, user-actionable error.
    The exception *object* is deliberately never logged, because its class name
    would leak the transport the adapter layer works to hide.
18. On success: `[ck-aws] Assumed role … as session …` is logged, **the
    credentials are discarded**, and the step returns the session name `String`.

> **Why credentials are thrown away.** A Pipeline step's return value is
> persisted into CPS program state and is trivially printable from a pipeline
> (`echo "${x}"`). Exporting credentials to subsequent steps is the job of a
> future *block-scoped* `withProfile` step that can scope and revoke them — not
> this one.

---

## 4. Package Structure

Root package: `io.github.rads4.ckaws`

| Package | Responsibility | Why it exists separately |
|---|---|---|
| `auth` | Policy + domain model: `AuthCore`, `SessionName`, the `StsAssumeRole` port, request/credential value types, exception hierarchy | The rules that must never drift. Depends on **neither** Jenkins nor AWS transport, so it is pure-JUnit testable and survives any change to how AWS is reached |
| `auth.cli` | The single `StsAssumeRole` implementation that shells out to the AWS CLI | Isolated in its own package so "we authenticate via the CLI" is a swappable detail, not an assumption baked across the codebase. A future `auth.sdk` or `auth.cached` would sit beside it |
| `exec` | Generic process execution: `ProcessRunner`, `DefaultProcessRunner`, `ProcessResult`, `ProcessExecutionException` | Deliberately AWS-blind. Keeping it a sibling of `auth` (not a child) signals it is a general primitive that the future generic AWS CLI executor will reuse unchanged |
| `steps` | The Jenkins integration point: `CkAwsAssumeRoleStep` and its descriptor | Quarantines every Jenkins import. Anything needing `JenkinsRule` to test lives here and nowhere else |
| `src/main/resources` | `index.jelly` — the plugin description shown in **Manage Jenkins → Plugins** | Jenkins convention; the plugin needs a human-readable blurb in the UI |
| `src/test/java/…` (mirrors main) | Unit tests plus two hand-written fakes (`FakeStsAssumeRole`, `FakeProcessRunner`). Only two test classes need a running Jenkins | The layering is *verified* by the tests: if `auth` ever gains a Jenkins import, its tests stop compiling without `JenkinsRule` |

**The dependency rule, in one line:**
`steps → auth → (StsAssumeRole) ← auth.cli → exec`.
Arrows never point backwards. `auth` does not know `auth.cli` exists; `exec` does
not know anyone exists.

---

## 5. Important Files

Only the architecturally load-bearing files. Exceptions and value types are
grouped rather than listed one by one.

### Production code

| File | Purpose | Key methods | Why it matters |
|---|---|---|---|
| [CkAwsAssumeRoleStep.java](../src/main/java/io/github/rads4/ckaws/steps/CkAwsAssumeRoleStep.java) | The `ckAwsAssumeRole` Pipeline step — the only Jenkins-aware class | `start(StepContext)` — called by Jenkins, returns the `Execution`. `Execution.run()` — called on a non-CPS thread; reads `Run`/`TaskListener`, calls `SessionName.forBuild`, then `AuthCore.authenticate`. `abort(...)` — converts `CkAwsAuthException` into a stack-trace-free `AbortException`. `DescriptorImpl.getFunctionName()` — registers the DSL name | The whole Jenkins ↔ plain-Java boundary. Also the class enforcing "credentials never reach Pipeline state" |
| [AuthCore.java](../src/main/java/io/github/rads4/ckaws/auth/AuthCore.java) | The policy layer | `authenticate(roleArn, jobName, buildNumber)` — called by the step; calls `SessionName.forBuild`, builds an `AssumeRoleRequest`, delegates to the port. `assume(request)` (private) — normalizes every failure into `AssumeRoleException` | Where "which role, named how, failing how" is decided exactly once. Stateless today — no caching by design |
| [SessionName.java](../src/main/java/io/github/rads4/ckaws/auth/SessionName.java) | Generates and guards the frozen `jk-<job>-<build>` shape | `forBuild(jobName, buildNumber)` — the only way to construct one; called by both the step and `AuthCore`. `value()` — the string handed to STS | **The most load-bearing file in the repo.** The future IAM trust policy matches `jk-*`; every sanitization and truncation rule here preserves that prefix. Private constructor ⇒ an invalid `SessionName` cannot exist |
| [StsAssumeRole.java](../src/main/java/io/github/rads4/ckaws/auth/StsAssumeRole.java) | One-method port: `assumeRole(AssumeRoleRequest) → AwsCredentials` | `assumeRole(request)` — implemented by `CliStsAssumeRole`, faked in tests | The seam that keeps policy independent of transport. Everything in §7 about swappability rests on this interface |
| [CliStsAssumeRole.java](../src/main/java/io/github/rads4/ckaws/auth/cli/CliStsAssumeRole.java) | The AWS-CLI adapter | `assumeRole(request)` — called via the port; calls `ProcessRunner.run`. `buildCommand(request)` (private) — the only place `sts assume-role` flags appear. `parseCredentials(...)`, `parseExpiration(...)` — turn one tab-separated line into `AwsCredentials` | The quarantine boundary. Everything CLI-specific — flags, `--query` projection, output format, error translation — is inside this one file |
| [DefaultProcessRunner.java](../src/main/java/io/github/rads4/ckaws/exec/DefaultProcessRunner.java) | `ProcessBuilder`-backed executor | `run(List)` — the interface method. `run(List, Map)` — same, plus per-invocation environment overrides (a `null` value *removes* a variable). `readFully(...)` (private) | Owns the concurrency detail that is easy to get wrong: stderr is drained on a helper thread so a full pipe buffer can't deadlock the read. **No timeout in this milestone** |
| [ProcessRunner.java](../src/main/java/io/github/rads4/ckaws/exec/ProcessRunner.java) | The generic execution contract | `run(List<String>) → ProcessResult` | Must never become AWS-aware. That constraint is what makes "new AWS commands need no plugin change" achievable later |
| [ProcessResult.java](../src/main/java/io/github/rads4/ckaws/exec/ProcessResult.java) | Immutable command + exit code + stdout + stderr | `succeeded()`, `stdout()`, `stderr()`, `exitCode()` | Models "the process ran" as data, not as an exception. `toString()` prints only stream *sizes* — the content can be credentials |
| [AssumeRoleRequest.java](../src/main/java/io/github/rads4/ckaws/auth/AssumeRoleRequest.java) | Immutable AssumeRole inputs | `of(roleArn, sessionName)`, `of(roleArn, sessionName, durationSeconds)` | Encodes the role-chaining constraint in the type: an explicit duration is validated against `[900, 3600]`, because a chained session is capped at 1 h regardless of the role's configured maximum |
| [AwsCredentials.java](../src/main/java/io/github/rads4/ckaws/auth/AwsCredentials.java) | Immutable temporary credentials | `isExpired(Clock)`, `expiresWithin(Duration, Clock)`, `toString()` | `toString()` redacts the secret key and session token — credentials cannot leak into a log by accident. The `Clock`-based expiry checks are the pre-built hook for a future refresh path |
| Exceptions — [CkAwsAuthException](../src/main/java/io/github/rads4/ckaws/auth/CkAwsAuthException.java) → [AssumeRoleException](../src/main/java/io/github/rads4/ckaws/auth/AssumeRoleException.java), [SessionNameException](../src/main/java/io/github/rads4/ckaws/auth/SessionNameException.java); and [ProcessExecutionException](../src/main/java/io/github/rads4/ckaws/exec/ProcessExecutionException.java) | Two independent hierarchies | — | Auth exceptions are **unchecked on purpose**: the auth core doesn't dictate failure policy; the Jenkins layer decides (fail the build). `ProcessExecutionException` lives only in `exec` and never escapes past `CliStsAssumeRole` |
| [CredentialsProvider.java](../src/main/java/io/github/rads4/ckaws/auth/CredentialsProvider.java) | `get() → AwsCredentials` | `get()` | **Currently unwired.** A declared seam for a future caching/refreshing decorator around `AuthCore` |
| [pom.xml](../pom.xml) | Maven build, `hpi` packaging | — | Read the comments before touching versions: both the Jenkins baseline (2.479.2) and the BOM version are pinned deliberately to match CK production, and a newer BOM silently forces the baseline above it |

### Tests (one line each)

| File | Covers |
|---|---|
| [SessionNameTest](../src/test/java/io/github/rads4/ckaws/auth/SessionNameTest.java) | The frozen shape: sanitization, dash collapsing, unicode, truncation, blank/negative rejection |
| [AuthCoreTest](../src/test/java/io/github/rads4/ckaws/auth/AuthCoreTest.java) | Correct request built, credentials returned, failures wrapped, fail-closed before the port is touched |
| [AwsCredentialsTest](../src/test/java/io/github/rads4/ckaws/auth/AwsCredentialsTest.java) | `Clock`-driven expiry logic, `toString()` redaction, validation |
| [CliStsAssumeRoleTest](../src/test/java/io/github/rads4/ckaws/auth/cli/CliStsAssumeRoleTest.java) | Exact argv construction and every parse/failure path — with a fake runner, no real process |
| [DefaultProcessRunnerTest](../src/test/java/io/github/rads4/ckaws/exec/DefaultProcessRunnerTest.java) | Real subprocesses (POSIX-only): stream separation, exit codes, environment overrides |
| [CkAwsAssumeRoleStepTest](../src/test/java/io/github/rads4/ckaws/steps/CkAwsAssumeRoleStepTest.java) | `JenkinsRule` — the full step→`AuthCore`→CLI→subprocess path with a `/bin/sh` stub standing in for `aws`. No AWS account needed |
| [PluginLoadsTest](../src/test/java/io/github/rads4/ckaws/PluginLoadsTest.java) | `JenkinsRule` — the plugin registers and is active |
| `FakeStsAssumeRole`, `FakeProcessRunner` | Hand-written test doubles; no mocking framework is used anywhere |

---

## 6. Execution Flow Between Files

```
Pipeline (Groovy)
    │  ckAwsAssumeRole(roleArn: '…')  — Jenkins resolves the DSL name via DescriptorImpl
    ▼
CkAwsAssumeRoleStep.Execution.run()
    │  ① SessionName.forBuild(jobName, buildNumber)   ── fail closed here, pre-AWS
    │  ② new AuthCore(new CliStsAssumeRole(new DefaultProcessRunner(), awsExecutable()))
    ▼
AuthCore.authenticate(roleArn, jobName, buildNumber)
    │  builds AssumeRoleRequest, calls the port — never knows the implementation
    ▼
StsAssumeRole  (interface — the swap point)
    ▼
CliStsAssumeRole.assumeRole(request)
    │  builds argv: ["aws","sts","assume-role","--role-arn",…,"--output","text"]
    ▼
DefaultProcessRunner.run(command)
    │  ProcessBuilder + concurrent stream drain → ProcessResult
    ▼
aws CLI  →  AWS STS AssumeRole

                    … and back up …

ProcessResult ──▶ CliStsAssumeRole   parses 4 tab-separated fields → AwsCredentials
                                     (or translates ANY failure → AssumeRoleException)
AwsCredentials ─▶ AuthCore           null-check, normalize exceptions
              ──▶ Step               discards credentials, returns sessionName: String
AbortException ─▶ Pipeline           on failure: message only, no stack trace
```

**Each transition, and why it's there:**

| Transition | What crosses it | Why the boundary exists |
|---|---|---|
| Pipeline → Step | `roleArn` string | Jenkins data binding; the only place the DSL name is defined |
| Step → `AuthCore` | `roleArn`, `jobName`, `buildNumber` — **plain values** | Jenkins types stop here. `AuthCore` takes a `String` and a `long`, not a `Run`, which is exactly why it needs no `JenkinsRule` to test |
| `AuthCore` → `StsAssumeRole` | `AssumeRoleRequest` | Policy hands off to transport through an interface. Swapping the implementation changes nothing above |
| `CliStsAssumeRole` → `ProcessRunner` | `List<String>` argv | AWS knowledge stops here. The runner receives an opaque list |
| `ProcessRunner` → OS | the process | The only place the plugin touches the outside world |
| Return path | `ProcessResult` → `AwsCredentials` → `String` | Information is *narrowed* at every hop back up. By the time the pipeline sees anything, only a session name is left |

---

## 7. Design Decisions

Read these as tradeoffs, not history. Each one has a cost that was accepted.

**AWS CLI instead of the AWS Java SDK.**
The hard requirement is that *new AWS commands must not require a plugin
release*. An SDK-based executor would need typed request/response classes per
service, so every new AWS API surface used by any deployment repo would force a
plugin version bump — turning a `git push` into a release cycle. The CLI takes an
arbitrary argument list, so the plugin stays service-agnostic forever.
*Cost accepted:* string parsing instead of typed responses, and the `aws` binary
becomes a runtime dependency. Mitigated by using the CLI's own
`--query … --output text` projection so no JSON parser is needed.
**Do not swap this for the SDK without flagging it first.**

**`ProcessRunner` is generic and must stay that way.**
It executes an argv and returns stdout/stderr/exit code. It never inspects what
it is running. If you ever find yourself writing `if (args[0].equals("ecs"))`
anywhere in the exec layer, stop — that is the SDK problem reappearing in a
different shape. The genericness is what lets one class serve both the auth path
and the future generic AWS executor.

**`AuthCore` knows nothing about the AWS CLI.**
It depends only on the `StsAssumeRole` interface. This buys three concrete
things: the naming/failure policy is unit-testable in milliseconds with a
hand-written fake; the transport can be replaced (agent-side launcher, SDK,
caching decorator) without reopening policy; and CLI-specific bugs are structurally
confined to one file.
*Cost accepted:* an extra interface and a small indirection for what is currently
a single implementation.

**The STS implementation is isolated in its own package.**
`auth.cli` contains exactly one class, and it is the only place the strings
`"sts"`, `"assume-role"`, and the credential query appear. Anything that changes
when AWS changes its CLI is in one file, and a future `auth.sdk` or
`auth.cached` package slots in beside it without touching anything else.

**Credentials never reach Pipeline state.**
The step returns only the session name. A step's return value is persisted in the
CPS program state and is trivially printable from a pipeline, so returning
credential material would put secrets into build records. Handing credentials to
subsequent steps requires a *block-scoped* step (`withProfile { … }`) that can
scope and revoke them — a different design, deliberately deferred.
*Cost accepted:* the current step cannot yet be used to run other AWS commands —
it proves the auth path and nothing more.

**Session name generation is centralized in one type.**
`SessionName` has a private constructor and one static factory, so an invalid
instance cannot exist anywhere in the program. This matters more than it looks:
the future IAM trust-policy condition matches `jk-*`, so a single caller that
formats its own session name would produce builds that AWS denies once that
policy is live. Centralization makes the convention a type-system guarantee
rather than a code-review convention.

**Interfaces exist only where something genuinely varies.**
There are exactly two (`StsAssumeRole`, `ProcessRunner`), plus one declared for
future use (`CredentialsProvider`). Both real ones sit on the boundary between
"logic we want to test fast" and "something slow and external" — which is also
why the test suite needs no mocking framework: a fake for a one-method interface
is a few lines of plain Java.

**Fail closed, never guess.**
Blank role ARN, blank job name, non-positive build number, unparseable
expiration — all abort the build with an actionable message. Nothing is defaulted
or inferred. A build that authenticated as the *wrong* identity is worse than a
build that failed.

**Exceptions are unchecked, and translated at each boundary.**
`ProcessExecutionException` never escapes `auth.cli`; `CkAwsAuthException` never
escapes the step. Each layer speaks its own failure vocabulary, which is what
lets the layer above stay ignorant of the layer below's mechanism. Unchecked, so
the auth core doesn't impose a handling policy on callers — the Jenkins layer
decides that failures mean "fail the build with a readable message".

**Known limitation, accepted for the POC:** the subprocess runs on the **Jenkins
controller JVM**, not on the agent selected by an enclosing `node` block. An
agent-backed `ProcessRunner` built from the step context's `Launcher` can be
added later as a new `exec` implementation, with zero changes to the auth layer —
which is itself a demonstration that the seam is in the right place.

---

## 8. Technologies Used

| Technology | Version / note | Why it's here |
|---|---|---|
| **Java** | 17+ (21 recommended) | Enforced by the Jenkins plugin parent POM. Modern-enough language features (`var`, `List.of`, `Set.of`, try-with-resources on an existing var) without needing a newer baseline |
| **Jenkins Plugin Parent POM** | `6.2211.v27f680c93c53` | Supplies the entire build: `hpi` packaging, dependency management, static analysis, formatting, test harness. Saves configuring ~10 plugins by hand |
| **Jenkins core baseline** | 2.479.2 — pinned | Matches CloudKeeper's **actual production** Jenkins. Deliberately below the archetype default so the plugin is guaranteed loadable on CK's real instance |
| **Jenkins Plugin BOM** | `4488.v7fe26526366e` — pinned | Newer BOMs pull a `workflow-cps` requiring Jenkins 2.479.3, which would force the baseline above CK production. **Read the pom comment before bumping** |
| **Pipeline Step API** (`workflow-step-api`) | BOM-managed | The only production Jenkins dependency: `Step`, `StepDescriptor`, `StepExecution`. Deliberately minimal |
| **`workflow-cps` / `workflow-job`** | test scope only | The minimum needed to define and run a real Pipeline job in tests. `workflow-basic-steps` is intentionally *not* pulled in, to keep the dependency set inside the 2.479.2 baseline |
| **AWS CLI** | runtime dependency on the agent/controller | The execution mechanism — see §7. Also resolves profiles and regions itself, so the plugin never reads `~/.aws/config` |
| **AWS STS** | AssumeRole, GetCallerIdentity | The AWS API this is all about. `RoleSessionName` is the field that carries the `jk-` convention into CloudTrail |
| **Maven** | 3.9.6+ | Jenkins plugin standard; `mvn hpi:run` boots a dev Jenkins with the plugin installed |
| **HPI packaging** | `<packaging>hpi</packaging>` | Jenkins' plugin format — a JAR plus metadata. Produces `target/ck-aws.hpi` |
| **JUnit 5** | via parent POM (JUnit 4 imports are banned in the build) | Unit tests. Note: **no mocking framework** — every double is hand-written, which is only practical because the interfaces are tiny |
| **`JenkinsRule`** (`@WithJenkins`) | jenkins-test-harness | Boots a real Jenkins in-process. Used by exactly two test classes, on purpose: it is slow, so everything testable without it is |
| **SpotBugs** | parent POM, build-failing | Static analysis. Source of the `edu.umd.cs.findbugs.annotations.NonNull` annotation on the descriptor |
| **Spotless** | `spotless.check.skip=false` | Enforces formatting at build time, so formatting never appears in a diff. Run `mvn spotless:apply` if `verify` fails on it |

---

## 9. Integration with Infrastructure Jenkins

High level — none of this is built yet, but the design assumes it.

**Where it gets installed.** `target/ck-aws.hpi` is uploaded via **Manage Jenkins
→ Plugins → Advanced → Deploy Plugin**, or dropped into
`$JENKINS_HOME/plugins/`. It is a normal plugin: install, restart, done.

**Why no deployment repository changes are needed initially.** The plugin adds a
*new* pipeline step. It does not intercept, wrap, or replace anything. Every
existing pipeline — shared-library-based or standalone `sh "aws …"` — behaves
exactly as before, because nothing calls the new step until someone writes
`ckAwsAssumeRole(...)` into a Jenkinsfile. Installation is therefore a low-risk,
reversible operation, and adoption is per-pipeline and incremental.

> This is also the honest limitation: **opt-in means bypassable.** Coverage
> becomes mandatory only when the IAM trust policy requires `jk-*` session names.
> The plugin's job is to make sure that, when that day comes, conforming
> pipelines already exist.

**How authentication actually works in production.**

```
Jenkins controller / agent runs on EC2
        │  has an instance profile (its base identity — no static keys anywhere)
        ▼
Plugin spawns the aws CLI, which INHERITS the JVM environment
        │  the CLI resolves the base identity itself (IMDS, AWS_PROFILE, …)
        ▼
sts assume-role --role-arn <target> --role-session-name jk-<job>-<build>
        │
        ▼
Temporary credentials for prod / non_prod / ops  (valid ≤ 1 hour)
```

Two things to note:

1. **The plugin has no credentials of its own.** It inherits whatever identity
   Jenkins already runs as. It stores no keys, reads no AWS config files, and
   needs nothing in the Jenkins Credentials store.
2. **This is role chaining** (instance role → target role), which caps the
   resulting session at **1 hour regardless** of the target role's configured
   max session duration. Long builds will need a credential-refresh path —
   `AwsCredentials.expiresWithin(...)` and `CredentialsProvider` exist for
   exactly that (see §11).

---

## 10. Relationship with Deployment Repositories

```
┌────────────────────────────────────────────────────────────┐
│  Infrastructure Jenkins            ← plugin installed HERE │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ ck-aws plugin: identity  (who the commands run as)   │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────────┬───────────────────────────────────┘
                         │ provides the ckAwsAssumeRole step
                         ▼
┌────────────────────────────────────────────────────────────┐
│  Shared Groovy Library                                     │
│  Deploy.groovy  Build.groovy  Utilities.groovy             │
│  Audit.groovy   AwsAuth.groovy  ← today does STS itself    │
│  → the natural first and only adoption point               │
└────────────────────────┬───────────────────────────────────┘
                         ▼
┌────────────────────────────────────────────────────────────┐
│  Deployment repos (cln-deployment-scripts, …)              │
│  Deployment Groovy: workflow, sequencing, which commands   │
│  → mostly unchanged; they call the library, not STS        │
└────────────────────────┬───────────────────────────────────┘
                         ▼
┌────────────────────────────────────────────────────────────┐
│  Service repos (application teams)                         │
│  → no changes ever. They don't write deployment logic      │
└────────────────────────────────────────────────────────────┘

Separate track: cln-infra-main / Terraform authenticates independently
                and is out of scope.
```

**The split, stated plainly:**

| Concern | Owner | Changes when? |
|---|---|---|
| *Who* the AWS calls run as (identity, session naming, credential lifecycle) | **The plugin** | Requires a plugin release — rare, by design |
| *What* AWS commands run, in what order, with what arguments | **Deployment Groovy** | `git push` — fast iteration preserved |

**What eventually changes in deployment code:** essentially one thing —
`AwsAuth.groovy`'s explicit `sts assume-role` is replaced by a call into the
plugin. Deployment Groovy files that merely *call* the shared library need no
edits at all. The standalone repos that bypass the library are the harder,
later case.

**What stays entirely inside Jenkins:** session naming, credential lifecycle,
process execution, retries, timeouts, and any future JCasC role mapping. None of
that ever appears in a deployment repo again.

> A stated POC goal, and it held: **no changes to `Deploy.groovy`,
> `AwsAuth.groovy`, or any existing library were needed** to prove this works.

---

## 11. Extension Points

Where future work goes. These are *locations*, not designs.

| Future work | Where it belongs | Note |
|---|---|---|
| **Generic AWS CLI executor** (`ckAws.run(["ecs","update-service",…])`) | A new class in `steps`, plus a small AWS-CLI-aware wrapper next to `auth.cli`. `DefaultProcessRunner` is reused **unchanged** | The most valuable next step. Must never branch on the AWS service — see §7 |
| **Additional AWS operations** | Nowhere in the plugin, once the generic executor exists | If a new AWS command requires a plugin change, the design has been violated |
| **Agent-side execution** (fix the controller-JVM limitation) | A new `ProcessRunner` implementation in `exec`, built from the step context's `Launcher` | The auth layer does not change. This is the seam paying off |
| **Credential caching / refresh** | Implement `CredentialsProvider` as a decorator over `AuthCore`; use `AwsCredentials.expiresWithin(Duration, Clock)` | The interface and the clock-based expiry checks already exist for this. Needed for builds longer than the 1 h chained-session cap |
| **Retries** | Inside `CliStsAssumeRole` (auth-specific policy) **or** a decorator implementing `ProcessRunner` (generic) — decide deliberately | A decorator keeps `DefaultProcessRunner` simple and is reusable by the future executor |
| **Timeouts** | `DefaultProcessRunner`, via `Process.waitFor(timeout, unit)` | Explicitly omitted so far. The stream-draining threading is already in place to support it |
| **Structured logging** | The step (build-log output) and `exec` (command/exit telemetry) | Keep credentials out: `ProcessResult.toString()` already prints sizes, not content — preserve that |
| **JCasC-backed profile → role ARN mapping** | A new `GlobalConfiguration` class, probably its own `config` package; the step resolves `profile` → ARN through it | Role ARNs are **not** secrets — do not route them through the Credentials plugin. And do not read `~/.aws/config` |
| **RunListener / automatic profile injection** | A new `@Extension RunListener<Run>` in `steps` (or a new `listeners` package) calling the same `AuthCore` | Deliberately deferred until the explicit path was proven. It must converge on the same auth core, not duplicate it |
| **Block-scoped `withProfile { … }` step** | A new step in `steps` extending the block-scoped step API | The correct home for exporting credentials to nested steps — the thing `ckAwsAssumeRole` intentionally does not do |
| **IAM trust-policy enforcement** (`"sts:RoleSessionName": "jk-*"`) | **Not in this repo** — it is AWS-side IAM configuration | The real enforcement boundary. `SessionName` exists to make this possible without a redesign |

---

## 12. Reading Order

Roughly 90 minutes, in this order. It goes **outside-in**, so every file you open
has already been motivated by the one before it.

| # | Read | Why here |
|---|---|---|
| 1 | [CLAUDE.md](../CLAUDE.md) | The binding constraints, including what *not* to do. Everything else makes more sense once you know which designs were rejected and why |
| 2 | [README.md](../README.md) | How to build and run. Get `mvn verify` green before reading code — a working build makes experimentation cheap |
| 3 | [CkAwsAssumeRoleStep.java](../src/main/java/io/github/rads4/ckaws/steps/CkAwsAssumeRoleStep.java) | The entry point. Start where the pipeline starts; every other class appears here as a name you'll then go look up |
| 4 | [AuthCore.java](../src/main/java/io/github/rads4/ckaws/auth/AuthCore.java) | ~60 lines and the whole policy. Short enough to hold in your head, and it tells you what the two layers below it must provide |
| 5 | [SessionName.java](../src/main/java/io/github/rads4/ckaws/auth/SessionName.java) | The one thing that must never change shape. Read the class javadoc even if you skim the regexes |
| 6 | [StsAssumeRole.java](../src/main/java/io/github/rads4/ckaws/auth/StsAssumeRole.java) | One method. Read it *before* the implementation so you see the boundary as a contract, not as a leftover abstraction |
| 7 | [CliStsAssumeRole.java](../src/main/java/io/github/rads4/ckaws/auth/cli/CliStsAssumeRole.java) | Now the CLI details land in a place you already have a slot for. Note how every failure becomes `AssumeRoleException` |
| 8 | [ProcessRunner.java](../src/main/java/io/github/rads4/ckaws/exec/ProcessRunner.java) → [DefaultProcessRunner.java](../src/main/java/io/github/rads4/ckaws/exec/DefaultProcessRunner.java) | The bottom. By now you know it is called with an opaque argv, which is exactly the point |
| 9 | [CliStsAssumeRoleTest](../src/test/java/io/github/rads4/ckaws/auth/cli/CliStsAssumeRoleTest.java) + [SessionNameTest](../src/test/java/io/github/rads4/ckaws/auth/SessionNameTest.java) | The clearest specification of intended behaviour, including the edge cases the prose glosses over |
| 10 | [CkAwsAssumeRoleStepTest](../src/test/java/io/github/rads4/ckaws/steps/CkAwsAssumeRoleStepTest.java) | The full path end-to-end with a stub `aws` script. This is also your template for testing anything new without an AWS account |

Skim on the way past (don't stop for them): `AssumeRoleRequest`, `AwsCredentials`,
`ProcessResult`, and the exception classes — they are small value types, and
their javadoc explains itself when you reach them.

> **Note on removed properties.** Earlier drafts of the README described
> `io.github.rads4.ckaws.awsProfile` and `io.github.rads4.ckaws.validateIdentity`
> system properties, and `verifyIdentity` / `temporaryProfileEnvironment`
> methods. **These were removed in M5 and no longer exist** — the README has
> been corrected. The only system property the code still reads is
> `io.github.rads4.ckaws.awsExecutable` (test hook to substitute the `aws`
> binary).

**Fastest way to see it work:**

```bash
mvn verify                    # tests + target/ck-aws.hpi
mvn hpi:run -Dport=8081       # dev Jenkins at http://localhost:8081/jenkins/
```

Then create a Pipeline job whose script is one line:

```groovy
ckAwsAssumeRole(roleArn: 'arn:aws:iam::<account>:role/<role>')
```

The build log will show `[ck-aws] Assuming role … as session jk-<job>-<build>`,
and CloudTrail in the target account will show that same session name — which is
the entire thesis of the project, in one line of Groovy.
