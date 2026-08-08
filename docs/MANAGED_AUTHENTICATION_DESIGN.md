# Managed AWS Authentication — implementation design (M11)

**Status: the MECHANISM is implemented and locally validated. The CONTENT is
superseded by M12 — Universal Attribution (2026-08-06).**

> **Read this first.** This document describes generating an AWS configuration
> file *from the Jenkins profile mapping*. That approach does not satisfy the
> requirement agreed after it was built, for two reasons:
>
> 1. **It requires enumeration.** It replaces `AWS_CONFIG_FILE` wholesale, so any
>    profile name not configured in Jenkins fails the build with
>    `The config profile (X) could not be found`.
> 2. **It leaves IMDS-authenticating pipelines unattributed.**
>
> **M12 keeps everything about the mechanism** — the `DynamicContext` injection
> point, `<workspace>@tmp/ck-aws/`, the lifecycle, cleanup, the feature flag, the
> off-is-invisible property, one AssumeRole per (Run, Profile) — and changes only
> what is written into the file: instead of *replacing* the agent's configuration
> with Jenkins', it *copies* the agent's configuration and injects
> `role_session_name = jk-<job>-<build>` into every profile that assumes a role,
> plus an optional `[default]` for the unprofiled path. See CLAUDE.md, "M12 —
> Universal Attribution", for the decision, the proof of what is and is not
> achievable, and the founding rule it reverses.
>
> Sections 2 (extension points), 3 (file location), 4 (execution modes),
> 8-12 (lifecycles, performance, scalability), 13 (security) and 14 (failure
> scenarios) below apply to M12 unchanged. Sections 1, 6 and 7 describe the
> superseded file content.
>
> **Two further M12 refinements, decided after this document was written:**
>
> - **The authentication mode now decides where the target role ARN comes
>   from**, not whether a role is assumed at all. `AssumeRole` -> the configured
>   ARN; `InstanceProfile` -> the agent's own role, self-assumed. Both produce
>   `jk-<job>-<build>`. §7.2 below describes `InstanceProfile` as "no credential
>   keys, unattributed" — that is the superseded M11 meaning.
> - **Job-name rules select the profile.** An ordered `pattern -> profile` list
>   (`prod/.* -> prod`, `uat/.* -> non_prod`, ...) chooses the `[default]`
>   identity for calls that name no profile. A job matching no rule is left
>   entirely alone. This replaces M11's single on/off `jobNamePattern`, and
>   removes the need for the runtime role discovery this document proposed.

Validation evidence for the implemented mechanism is in MEMORY.md Sessions 15-16.

> **Fail-open covers the whole contribution path.** The guard wraps everything, not
> only the decoration, catches `Throwable`, re-throws only `InterruptedException`,
> and performs no console I/O of its own. See CLAUDE.md, "Fail-open is the
> outermost layer".

> **One design correction found during validation, recorded here because it is the
> kind of mistake that reads as correct.** The helper script must restore the AWS
> environment the *agent process* would have had — read from
> {@code Computer#getEnvironment()}, **not** from `Run#getEnvironment()`. The
> latter is the *build's* environment (Jenkins variables, contributors, build
> parameters) and does not contain the operating-system environment a child
> process inherits. Sourcing it there produced an empty result on a machine where
> `AWS_CONFIG_FILE` was genuinely set, so the helper unset a variable it should
> have preserved and STS rejected the resulting call with
> `InvalidClientTokenId`. On a real agent, where none of these are set, both
> sources look identical — which is exactly why this would have shipped unnoticed
> and failed only where a base profile is configured.

> Renamed from "Ambient Authentication" (2026-08-06). The name describes the
> behaviour, not the mechanism: **the plugin manages AWS authentication for
> Jenkins builds.** The old term is retired — it survives only in the historical
> session log. Earlier drafts of this document used it; the design is the same
> except where §3 records a deliberate reversal and §7.2 records the explicit
> authentication mode.

## Final decisions this document implements

| # | Decision |
|---|---|
| 1 | The architecture is called **Managed Authentication**. "Ambient" is retired |
| 2 | Deployment repositories, Jenkinsfiles and shared libraries change **not at all** — no wrapper, no `profileGuard`, no plugin-specific pipeline code, now or in future |
| 3 | **Manage Jenkins → System → CK AWS is the only configuration surface.** No folder property, no job property, no Configure-page UI |
| 4 | A profile declares an explicit **authentication mode**: `AssumeRole` (cross-account, carries `jk-<job>-<build>`) or `InstanceProfile` (same-account, uses the agent's own identity). Adding a profile is a row in System configuration, never a plugin release |
| 5 | Generated configuration lives in `<workspace>@tmp/ck-aws/`, is created once, reused for the build, and deleted at completion. No sweeper, no timer, no background service |
| 6 | **One AssumeRole per (Run, Profile)**, shared by the AWS CLI, boto3, Terraform, `docker login` and every SDK, under one `jk-<job>-<build>` session |
| 7 | The feature is **disabled by default**; enabling and disabling are administrator configuration changes requiring **no restart**. A job-name pattern allows phased rollout |
| 8 | **Backward compatibility is mandatory.** Disabled, the plugin must be indistinguishable from not being installed |
| 9 | The long-term objective is a platform capability: install once, restart once, configure once, and migrate repositories by enabling a checkbox |

This is the complete design for making the plugin authenticate Pipeline builds
transparently — no wrapper, no pipeline step, no change to any consumer
repository. Architecture rationale lives in [CLAUDE.md](../CLAUDE.md); this
document is the engineering detail.

Every factual claim about AWS credential resolution and Jenkins internals was
verified against `aws-cli 2.35.1` / `botocore 1.42.65`, Jenkins core `2.479.2`,
`workflow-step-api 700.v6e45cb_a_5a_a_21`, `workflow-cps 4046.v90b_1b_9edec67`
and `workflow-support 968.v8f17397e87b_8` — the exact versions in play. Claims
that are **unverified** say so explicitly and are listed together in §19.

---

## 0. The requirement

| | |
|---|---|
| Consumer repositories change | **zero** |
| Jenkinsfiles / shared libraries / infra repos change | **zero** |
| Developer knowledge required | **none** |
| Configuration location | Manage Jenkins → System → CK AWS (unchanged) |
| CloudTrail attribution | `jk-<job>-<build>` wherever technically possible |
| CloudKeeper-specific content in the plugin | **none** |

"Non-bypassable" means **no accidental bypass** — nothing for a developer to
forget. Deliberate bypass by someone with `sh` access is explicitly out of scope.

---

## 1. High-level architecture

The plugin stops handing credentials to the build. It **rewrites the build's view
of AWS configuration**, so the profile names the build already uses resolve to
Jenkins-owned roles under a Jenkins-owned session name.

```
Manage Jenkins → System → CK AWS                     Layer 0, unchanged
   profile → roleArn (+ region)  +  enable flag, job pattern, credential source
              |
              v
   DynamicContext.Typed<EnvironmentExpander>          Layer 1A — MANAGED (new)
   consulted by Jenkins for every Pipeline step
              |
              |  writes, once per (build, workspace):
              v
   <workspace>@tmp/ck-aws/config                      generated AWS config file
              |
              |  and exports (all non-secret):
              |     AWS_CONFIG_FILE, AWS_SHARED_CREDENTIALS_FILE,
              |     CK_AWS_SESSION_NAME
              v
   sh 'aws ecs update-service --profile non_prod ...'  UNCHANGED build code
   sh 'terraform apply'
   python3 dr_sync.py            (boto3, profile_name=...)
              |
              v
   The AWS tool performs its own AssumeRole, natively,
   using role_session_name = jk-<job>-<build>
              |
              v
   CloudTrail: jk-<job>-<build>
```

The generated file is the file the agent already has, plus **one line**:

```ini
# Generated by the ck-aws Jenkins plugin. Regenerated every build. Do not edit.
[profile non_prod]
role_arn          = arn:aws:iam::…:role/…      # from Jenkins configuration
credential_source = Ec2InstanceMetadata        # configurable, see §7.3
role_session_name = jk-uat-Backend-deploy-412  # <-- the only thing Jenkins adds
region            = us-east-1                  # only if configured
```

### 1.1 Why a generated config file is forced, not chosen

**(a) An explicitly passed profile deletes the environment provider.**
`botocore/credentials.py:95` sets
`disable_env_vars = session.instance_variables().get('profile') is not None`, then
`providers.remove(env_provider)`. Measured with fabricated credentials, zero AWS
calls:

| Invocation | Credentials resolved from |
|---|---|
| `aws --profile X …` | **config file** — environment ignored |
| `aws …` | environment |
| `AWS_PROFILE=X aws …` | environment |
| `boto3.Session(profile_name="X")` | **config file** |
| `boto3.Session()` | environment |

12 of the 13 AWS invocations in the deployment library pass `--profile`. Exported
environment credentials therefore cannot reach them — which is exactly why the
wrapper needed M7's `profileGuard`, i.e. a repository change.

**(b) `AWS_ROLE_SESSION_NAME` cannot substitute for the file.** It is read only by
`AssumeRoleWithWebIdentityProvider._CONFIG_TO_ENV_VAR`
(`credentials.py:1879-1886`) — the OIDC path. The provider the agents use
(`role_arn` + `credential_source`) reads `role_session_name` from the **config
file only** (`:1643`, `:1693`, `:1956`); absent it, botocore generates
`botocore-session-<epoch>` (`:824`).

**(c) A pinned `role_session_name` is only static in a *static* file.** Earlier
documentation said it "can never carry a job name or build number". True of an
admin-written file, false of one generated per build. That is the unlock.

> Under a future OIDC design, `AWS_ROLE_SESSION_NAME` *is* honoured and this whole
> file layer disappears.

---

## 2. Jenkins extension points

### 2.1 Chosen

| Extension point | Role | Why |
|---|---|---|
| `DynamicContext.Typed<EnvironmentExpander>` | Injects the environment into **every** Pipeline step | Consulted per step by `ContextVariableSet`; `DelegatedContext` reaches `Run`, `Node`, `Computer`, `FilePath`, `Launcher`, `FlowNode`. Already in `workflow-step-api:700.v6e45cb_a_5a_a_21` — **no new dependency, no BOM movement, no 2.479.3 trap** |
| `RunListener.onFinalized(Run)` | Deletes generated files; evicts the memo | Fires for every terminal state including `ABORTED` |
| `GlobalConfiguration` (existing `CkAwsGlobalConfiguration`) | Configuration | Already the source of truth; gains three fields |

Verified from `ContextVariableSet` bytecode (workflow-cps 4046):

- `get()` walks its own block-scoped `values` list **first**, then
  `ExtensionList.lookup(DynamicContext.class)` → an explicit `ckAwsWithProfile`
  still wins, no double AssumeRole.
- `values` is `final` and `get()` **never writes to it**. The only static state is
  a `ThreadLocal<Set<DynamicContextQuery>>` re-entrancy guard. **DynamicContext
  results are therefore recomputed on every query, never cached by core** — which
  is what makes node changes, parallel branches and multi-agent builds resolve
  correctly (§4), and why the plugin must do its own memoisation (§6, §11).

Verified from `DefaultStepContext` bytecode (workflow-support 968): it derives
`Run`, `Job`, `Node`, `Computer`, `Launcher`, `FilePath`, `EnvVars`,
`TaskListener`, `FlowNode` and `EnvironmentExpander`, and computes step
environment through `EnvironmentExpander.getEffectiveEnvironment(...)`.

### 2.2 Rejected

| Extension point | Why not |
|---|---|
| `SimpleBuildWrapper` / `BuildWrapper` | `WorkflowJob extends hudson.model.Job`, not `AbstractProject`, and does not implement `BuildableItemWithBuildWrappers`. No "Build Environment" section on a Pipeline job. Reachable only via `wrap([$class:…])` from a Jenkinsfile — a repository change. **Does** work for freestyle jobs (§18.3) |
| `EnvironmentContributor` | Has `Run`, so it could compute the session name, but has **no `FilePath`** — it cannot write the file. Also fires on build-page rendering |
| `LauncherDecorator` | `decorate(Launcher, Node)` has no `Run`, so it cannot derive `jk-<job>-<build>`. And environment injection alone loses to `--profile` (§1.1a). Dead twice |
| `StepListener` | `notifyOfNewStep(Step, StepContext)` returns `void` — detect and abort only, never inject |
| `JobProperty` | A Pipeline's own Jenkinsfile rewrites its job property list; all 12 CloudKeeper entry points declare `options { buildDiscarder(…) }`, so a UI-set property can be silently deleted on the first build |
| `AbstractFolderProperty` | Immune to the above, but a second configuration location. Ruled out as unnecessary complexity |
| `CredentialsProvider` / job Credentials dropdown | Every public method on `CredentialsProvider` is a *query*; there is no push/bind/inject API. The only two routes into a build (`BindingStep`, `SecretBuildWrapper`) are wrappers. That dropdown is also the SCM-checkout credential and is never exported to the build |
| Agent-installed `credential_process` helper | Moves the control plane onto the agent filesystem and the role mapping out of Jenkins; its session name would come from `$JOB_NAME` — forgeable, and wrong for concurrent builds on one agent. Measured at **one helper invocation per `aws` process** (3 commands → 3) versus one AssumeRole per build natively |
| Static credentials in an ephemeral credentials file | Forces eager assumption of every configured role, writes real credential material to disk, reintroduces the 1-hour cap with no refresh |

---

## 3. Where the file lives — decision reversed after challenge

**Chosen: `<workspace>@tmp/ck-aws/` via `WorkspaceList.tempDir(FilePath)`.**

An earlier draft chose `<agent root>/ck-aws/<run>/`. That was reversed on three
counts:

**(a) It eliminates the container limitation.** `docker.image().inside { }`,
Declarative `agent { docker { … } }` and Kubernetes agents all make the workspace
and its `@tmp` sibling visible to the container; the agent root is not visible.
Since the user requirement is to attribute every call *wherever technically
possible*, coverage of the standard container mechanisms outweighs the
`cleanWs()` exposure (§4.2). **This is the single most important change in this
revision.**

**(b) It bounds storage without relying on cleanup.** The path is *stable per
workspace* and overwritten every build, so total footprint is
`workspaces × ~1 KB` — not `builds × 1 KB`. With `<run>` in the path, a run of
cleanup failures accumulates without bound. This directly satisfies "minimal
filesystem management": **cleanup becomes a nicety, not a correctness
requirement.**

**(c) Losing the file fails loudly, not silently.** Measured: with
`AWS_CONFIG_FILE` pointing at a missing path, `aws --profile X` exits **253** with
`The config profile (X) could not be found`. There is no silent fallback to an
unattributed identity. That is what makes the `cleanWs()` exposure tolerable.

Alternatives and why not:

| Candidate | Verdict |
|---|---|
| `$WORKSPACE/.ck-aws/` | **Rejected.** Inside the SCM checkout: `git clean -fdx`, `deleteDir()` and a careless `git add -A` all reach it, and `stash '**'` would ship role ARNs to the controller |
| `<agent root>/ck-aws/<run>/` | **Rejected** (previous draft). Safe from every build-initiated deletion, but invisible to containers and unbounded if cleanup fails |
| Agent system `/tmp` | Rejected. Not build-scoped; cleanup relies on OS policy |
| **`<workspace>@tmp/ck-aws/`** | **Chosen.** Not in the SCM checkout, not reached by `deleteDir()` (a sibling of the workspace, not a child), not reached by `git clean`, not collected by `stash` (which is workspace-rooted), visible to containers, stable and self-overwriting |

`@tmp` may not exist yet — the implementation must `mkdirs()`, the same lesson
M6 learned with `workspace.mkdirs()`.

---

## 4. Challenge: does every Pipeline execution mode observe it?

Because core recomputes `DynamicContext` on every query (§2.1), the expander is
derived from the *current* step's context each time. That is what makes most of
this table trivially correct rather than accidentally correct.

### 4.1 Verdicts

| Mode | Verdict | Reasoning |
|---|---|---|
| **Shared libraries** (`vars/*.groovy`, `src/**`) | ✅ | Library code runs in the same `CpsFlowExecution`; its `sh` steps resolve context identically. There is no separate environment path |
| **`parallel` stages** | ✅ | Each branch is its own body with its own context chain; each resolves independently. Branches on one agent share one file and one session name — correct, they are one build |
| **`matrix`** | ✅ | Declarative `matrix` expands to `parallel`. Same path |
| **`retry`** | ✅ | Each attempt re-resolves context. Memo stays valid; if the workspace changed, it misses and rewrites |
| **`timeout`** | ✅ | Adds context, removes none |
| **Multiple `node` blocks / multiple agents** | ✅ | Different workspace → memo miss → its own file with its own path. The exported `AWS_CONFIG_FILE` value differs per agent and is correct per step, because nothing is cached across queries |
| **Multibranch / organisation folders** | ⚠️ | Works, but see §4.3 — long branch paths can collide after session-name truncation |
| **`input`** | ✅ | If the build is paused across a controller restart, the in-memory memo is lost, so the next step rewrites the file. Correct by construction — the memo must be in-memory only |
| **`stash` / `unstash`** | ✅ | `stash` is workspace-rooted; `@tmp` is a sibling, so the config is never stashed and never lands on another agent with a wrong path |
| **`ws('/custom')`, `dir()`** | ✅ | `ws()` gets its own `@tmp`; `dir()` changes only cwd |
| **`withEnv` / `environment { }` setting AWS vars** | ✅ by design | The build wins. Deliberate escape hatch |
| **`agent { docker }`, `docker.image().inside { }`** | ⚠️ | Covered **if** `@tmp` is mounted — see §5 and §19.1 |
| **`agent { kubernetes }`** | ⚠️ | Workspace volume is shared across pod containers, so `@tmp` should be visible. Unverified — §19.2 |
| **Steps outside `node`** | ✅ | No `FilePath` → inject nothing. `sh` cannot run there anyway |
| **Freestyle jobs** | ❌ | `DynamicContext` is Pipeline-only. §18.3 |

### 4.2 The `cleanWs()` exposure, and the fix

`deleteDir()` deletes the current directory (the workspace); `@tmp` is a sibling
and survives. `git clean -fdx` runs inside the workspace. **`cleanWs()` from the
ws-cleanup plugin is the one step that deletes `@tmp` siblings.**

Worst case: a pipeline calls `cleanWs()` *after* the file is written and *before*
an AWS call. The result is a loud build failure (exit 253), never a silent loss of
attribution.

Mitigation, and it is cheap: **verify existence once per `node` block, not once
per build and not once per step.** The memo key is
`(runId, enclosing ExecutorStep FlowNode id)`, obtained from
`DelegatedContext.get(FlowNode.class)`. That costs one `FilePath.exists()` per
node block — one remoting round trip — and makes a mid-build `cleanWs()`
self-healing on the next node block. Within a single node block the exposure
remains and is accepted.

### 4.3 New finding — session-name collision in deep multibranch hierarchies

`SessionName` truncates the **middle** (job) segment to keep the total ≤ 64 chars,
preserving `jk-` and the build number. Two different branches whose full names
share a long prefix therefore truncate to the same value:

```
jk-org-platform-services-payments-api-feature-add-retry-logic-412
jk-org-platform-services-payments-api-feature-add-retry-limit-412
                          ↓ both truncate to
jk-org-platform-services-payments-api-feature-add-retr-412
```

Consequence: CloudTrail attribution becomes ambiguous between two branches of the
same build number. It is an **attribution** defect, not a security one — both are
legitimate builds of the same repository.

This is pre-existing (it dates from M1) and is *not* introduced by managed
authentication — but managed authentication is what makes it fleet-wide, so it
must be decided now. Options:

- **(a) Accept and document.** Zero risk, ambiguity remains.
- **(b) Append a short deterministic hash** of the full job name when truncation
  occurs, e.g. `jk-<truncated>-<6 hex>-<build>`. Still matches `jk-*`, so Layer 3
  is unaffected, but it changes the shape — which CLAUDE.md marks as requiring
  discussion.

**Recommendation: (b), but as a separate decision, not folded into M11 silently.**
It changes a load-bearing convention.

---

## 5. Challenge: can the container limitation be eliminated?

Three distinct cases; the answer differs for each.

| Case | Under the previous (`agent root`) design | Under the chosen (`@tmp`) design |
|---|---|---|
| `docker.image().inside { }` and Declarative `agent { docker }` | Not covered | **Covered** — the docker-workflow plugin mounts the workspace and its `@tmp`, and passes the build environment to the container. §19.1 |
| `agent { kubernetes }` | Not covered | **Probably covered** — the workspace volume is shared between containers in the pod. §19.2 |
| Hand-rolled `sh 'docker run …'` | Not covered | Not covered — nothing is mounted unless the pipeline mounts it. Genuinely unsolvable without a repository change |

**Crucially, none of these is a regression.** Today a container has no
`~/.aws/config` of its own, so `aws --profile non_prod` inside a container already
fails; no existing CloudKeeper pipeline can be doing it. And an *unprofiled* call
inside a container, with `AWS_CONFIG_FILE` pointing at a path the container cannot
see, falls through to IMDS exactly as it does today — measured: a missing config
file plus no profile produces chain fall-through, not a hard error.

So the change from "not covered" to "covered" for the two standard mechanisms is
a pure gain, and the remaining gap (hand-rolled `docker run`) leaves behaviour
identical to today.

---

## 6. Class diagram

```
                       ┌──────────────────────────────────────────┐
                       │ config.AwsProfile                        │  EXISTING
                       │   String name                            │  (+1 field)
                       │   AuthenticationMode mode         [NEW]  │
                       │     AssumeRole | InstanceProfile         │
                       │   String roleArn   (AssumeRole only)     │
                       │   String region    (optional)            │
                       └──────────────┬───────────────────────────┘
                                      │ held by
                                      v
                       ┌──────────────────────────────────────────┐
                       │ config.CkAwsGlobalConfiguration          │  EXISTING
                       │   List<AwsProfile> profiles              │  (+3 fields)
                       │   boolean managedAuthentication   [NEW]  │
                       │   String  jobNamePattern          [NEW]  │
                       │   String  credentialSource        [NEW]  │
                       │   Optional<AwsProfile> resolve(String)   │
                       │   doCheckName(): reject INI-unsafe names │
                       └──────────────┬───────────────────────────┘
                                      │ reads
                                      v
  ┌───────────────────────────────────────────────────────────────────────┐
  │ managed.ManagedAwsContext extends DynamicContext.Typed<Environment…>  │  NEW
  │   @Extension                                                          │
  │   type() -> EnvironmentExpander.class                                 │
  │   get(DelegatedContext) -> EnvironmentExpander | null                 │
  │                                                                       │
  │   1. enabled? profiles configured? job matches pattern?   else null   │
  │   2. Run + FilePath + FlowNode from the delegate;         else null   │
  │   3. key = (runId, enclosing ExecutorStep FlowNode id)                │
  │   4. memo hit -> return cached expander (no I/O)                      │
  │   5. memo miss -> SessionName.forBuild(job.getFullName(), number)     │
  │                   render, mkdirs, write, record, memoise              │
  │   6. return EnvironmentExpander.constant({AWS_CONFIG_FILE, …})        │
  └───────────┬───────────────────────────────┬───────────────────────────┘
              │ uses                          │ records on
              v                               v
  ┌──────────────────────────────┐   ┌────────────────────────────────────┐
  │ managed.ManagedAwsConfigFile │   │ managed.ManagedAwsAction           │  NEW
  │   NEW — Jenkins-aware        │   │   implements InvisibleAction       │
  │   write(FilePath dir, String)│   │   List<String> node + remote path  │
  │   delete(FilePath dir)       │   │   (persisted with the build, so    │
  │   0700 dir / 0600 file       │   │    cleanup survives a restart)     │
  └──────────┬───────────────────┘   └──────────────┬─────────────────────┘
             │ renders                              │ read by
             v                                      v
  ┌───────────────────────────────┐   ┌────────────────────────────────────┐
  │ managed.AwsConfigRenderer     │   │ managed.ManagedCleanupListener     │  NEW
  │   NEW — pure, no Jenkins      │   │   extends RunListener<Run<?,?>>    │
  │   render(List<AwsProfile>,    │   │   @Extension                       │
  │          SessionName,         │   │   onFinalized(run):                │
  │          credentialSource,    │   │     - best-effort delete           │
  │          defaultProfile)      │   │     - EVICT the memo entry         │
  │     -> String                 │   └────────────────────────────────────┘
  │   escapes/rejects INI-unsafe  │
  │   values. Unit-testable       │
  │   without JenkinsRule         │
  └───────────────────────────────┘

  UNCHANGED, reused:      auth.SessionName, config.AwsProfile
  UNCHANGED, override:    auth.AuthCore, auth.cli.CliStsAssumeRole, exec.*,
                          steps.CkAwsWithProfileStep + expander + log filter
```

`AwsConfigRenderer` is deliberately Jenkins-free, so the file format — the part
most likely to be got wrong — is covered by plain JUnit tests, consistent with the
project's standing principle of reserving `JenkinsRule` for what genuinely needs a
running Jenkins.

**`AuthCore` is not on the managed path.** The plugin still *decides* the identity;
the AWS tool *performs* the AssumeRole. `AuthCore` and `CliStsAssumeRole` stay in
the tree, unchanged and still tested, serving the explicit override. Stated
plainly rather than glossed over.

---

## 7. Configuration

### 7.1 Unchanged

The mapping itself, its `@Symbol`s (`ckAws`, `awsProfile`) and its JCasC path
`unclassified.ckAws.profiles`. **No mapping moves into Java code, ever.** Adding
`sandbox`, `qa`, `finance` or anything else is a row in System configuration, not
a plugin release.

### 7.2 Added — an explicit authentication mode per profile

A profile now declares **how** a build authenticates under it, rather than the
plugin inferring it from whether a role ARN happens to be blank:

| Mode | Meaning | Role ARN | CloudTrail |
|---|---|---|---|
| `AssumeRole` | Assume the configured role under this build's session name. The cross-account case | required | `jk-<job>-<build>` |
| `InstanceProfile` | Use the agent's own identity. The same-account case, where there is nothing to assume | not used | the agent's instance-role session, as today |

```yaml
unclassified:
  ckAws:
    managedAuthentication: true
    profiles:
      - name: "non_prod"
        mode: "AssumeRole"
        roleArn: "arn:aws:iam::…:role/…"
        region: "us-east-1"
      - name: "ops"
        mode: "InstanceProfile"
```

Making the mode explicit matters for three reasons. It removes an inference that
would silently turn a mistyped ARN into "no authentication at all". It lets form
validation demand a role ARN exactly when one is needed. And it makes the
same-account case a first-class, documented configuration rather than a hack —
which is what allows any organisation to describe its accounts without touching
the plugin.

`InstanceProfile` renders a profile section with **no credential keys**. Verified
against botocore 1.42.65: an *existing* profile carrying no credentials continues
down the provider chain to the agent's identity, whereas an *unknown* profile is a
hard error. So `aws --profile ops` keeps working, unchanged, and remains
unattributed — which is exactly today's behaviour for that account.

### 7.3 Added — three global fields, one screen

| Setting | Default | Purpose |
|---|---|---|
| `managedAuthentication` | `false` | Master switch. Ships off, so the upgrade is inert; toggling is a **restart-free** rollback |
| `jobNamePattern` | empty (= all jobs) | Staged rollout by job full name |
| `credentialSource` | `Ec2InstanceMetadata` | Base identity of the agent |

`defaultProfile` is deliberately **not** added initially — see §18.4.

### 7.4 Genericity — verified, not asserted

`credential_source` accepts the three canonical values botocore validates:
`Ec2InstanceMetadata`, `EcsContainer`, `Environment` (`credentials.py:1157`,
`:2070`, `:1187`). Nothing ties the plugin to EC2 agents, to AWS account layouts,
or to CloudKeeper.

Profile names are arbitrary admin-chosen strings. Measured round-trip through a
generated file and `aws --profile`:

| Name | Result |
|---|---|
| `non_prod`, `prod`, `ops`, `sandbox`, `finance`, `engineering`, `qa` | ✅ |
| `with-dash`, `with_underscore`, `with.dot`, `Mixed_Case9` | ✅ |
| `with:colon`, `with/slash` | ✅ |
| `with space` | ❌ **rejected by botocore's config parser** |

### 7.5 New finding — INI safety

The renderer interpolates admin-supplied strings into an INI file. A profile name
or role ARN containing `\n`, `\r`, `[` or `]` could inject arbitrary configuration
keys — including `credential_process`. Only Jenkins administrators can configure
profiles, so severity is low, but it is a real injection surface and the fix is
trivial:

- **Form validation** rejects profile names containing whitespace (which botocore
  cannot parse anyway), `[`, `]`, `\n`, `\r`.
- **The renderer** re-validates and refuses to emit a file containing any value
  with a newline or bracket, rather than trusting the UI.

Belt and braces, because JCasC bypasses form validation.

---

## 8. Build lifecycle

```
 Build starts (controller)          nothing happens — no AssumeRole, no file
    v
 node { … }                         agent + workspace allocated
    v
 First step in that node block that resolves its environment
    ├─ DefaultStepContext.get(EnvVars.class)
    │    └─ get(EnvironmentExpander.class)
    │         └─ ContextVariableSet.get()
    │              ├─ block-scoped values first (empty here)
    │              └─ ExtensionList.lookup(DynamicContext.class)
    │                   └─ ManagedAwsContext.get(delegate)
    │                        ├─ config + pattern checks
    │                        ├─ memo miss for this node block
    │                        ├─ SessionName.forBuild(job.getFullName(), n)
    │                        ├─ mkdirs + write <ws>@tmp/ck-aws/config
    │                        ├─ record on ManagedAwsAction, memoise
    │                        └─ EnvironmentExpander.constant({…})
    v
 sh 'aws … --profile non_prod'      resolves via AWS_CONFIG_FILE
 sh 'terraform apply'               via [default] if configured, else as today
 python3 dr_sync.py                 boto3 via AWS_CONFIG_FILE
    v
 every subsequent step in the block memo hit — no I/O, no remoting
    v
 a second node block                memo miss → its own file
    v
 Build finishes (any result)        ManagedCleanupListener.onFinalized:
                                    delete recorded dirs, evict memo entry
```

---

## 9. Authentication lifecycle

Jenkins performs **no** STS call on the managed path.

```
 t0  Jenkins writes the config file.                        0 AWS calls
 t1  First `aws --profile non_prod …`
       botocore: profile has role_arn + credential_source
         → base credentials from IMDS (the agent's instance role)
         → sts:AssumeRole(RoleArn=…, RoleSessionName=jk-<job>-<build>)
         → result cached in ~/.aws/cli/cache, keyed by role + session name
 t2  Every later `aws` command in the same build            cache hit, 0 calls
 t3  A command running past session expiry                  botocore re-assumes,
                                                            same session name
 t4  A long-lived boto3 process                             RefreshableCredentials
                                                            re-assumes in-process
```

Two consequences: **the 1-hour role-chaining cap stops being a problem** — the
highest open risk since M6, now solved by not implementing refresh at all — and
**CloudTrail volume goes down**, not up, versus a `credential_process` design.

---

## 10. Generated config lifecycle

| Phase | Behaviour |
|---|---|
| **Creation** | Lazily, on the first step of a node block that resolves its environment. `mkdirs()` + write + record + memoise |
| **Reuse** | Memoised by `(runId, enclosing ExecutorStep FlowNode id)`. Memo hit ⇒ zero I/O, zero remoting |
| **Regeneration** | New node block ⇒ new key ⇒ existence check + write. Self-heals a mid-build `cleanWs()` at the next node block |
| **Cleanup** | `RunListener.onFinalized` deletes recorded directories and evicts the memo. Best-effort |
| **Restart recovery** | The memo is in-memory only, so a controller restart causes a rewrite — correct by construction. Paths for cleanup are persisted on `ManagedAwsAction`, so cleanup survives the restart |
| **Orphan cleanup** | **Not required.** The path is stable per workspace and overwritten every build, so the footprint is bounded by workspace count, not build count. No sweeper thread, no `ComputerListener` — a deliberate simplification versus the previous draft |
| **Concurrent builds** | Jenkins allocates `ws`, `ws@2`, `ws@3` → distinct `@tmp` → distinct files. Distinct build numbers → distinct session names |
| **Agent loss** | The `node` block fails, or `retry` re-allocates: a different agent gives a new workspace (memo miss → rewrite); the same agent with an intact disk gives a memo hit and a valid file; the same agent with a wiped disk is caught by the per-node-block existence check |
| **Stale file from a previous build** | Cannot be used: `AWS_CONFIG_FILE` is exported only when *this* build's write succeeded |

**Cleanup failure is a hygiene issue, not a security incident** — there are no
credentials in these files. That property is what allows best-effort cleanup to be
a design choice rather than a compromise.

One artefact is *not* cleaned and must be named: the AWS CLI caches assumed
credentials in `~/.aws/cli/cache` on the agent, keyed by role ARN + session name.
That already happens today with the agent's existing `~/.aws/config`, so it is not
a regression — but it is the one place real credential material lands on disk.

---

## 11. Performance

Measured or derived; no assumptions.

| Cost | Magnitude | Basis |
|---|---|---|
| `ManagedAwsContext.get()` on a memo hit | ~1 µs | `ConcurrentHashMap` lookup + 2–3 local `DelegatedContext.get()` calls. No remoting: `Run`, `FilePath` and `FlowNode` are controller-side handles |
| `ManagedAwsContext.get()` on a memo miss | 2 remoting round trips (`mkdirs`, `write`) + 1 (`exists`) | ~2–5 ms on a local agent; ~15–60 ms over SSH to a remote agent |
| Misses per build | = number of node blocks | 1 for a typical deployment |
| Cleanup | 1 remoting round trip per recorded directory, at finalize | ~2 ms |
| `sts:AssumeRole` | **1 per (build, profile)** for the CLI | Cached in `~/.aws/cli/cache`. Compare `credential_process`: measured at 1 per `aws` process (3 commands → 3) |
| | 1 per Python process for boto3 | boto3 caches in-memory per session, not in the CLI's file cache |
| Added wall clock, first `aws` command | ~150–400 ms (one AssumeRole) | One-off per build |
| Controller CPU / memory | Negligible; no STS on the controller, ever | |
| Disk on agents | ~1 KB per workspace, overwritten | §10 |

**Typical CloudKeeper deployment** (1 node block, ~50 steps, 13 `aws`
invocations, 1 profile): Jenkins-side overhead ≈ **5 ms total**; AWS-side ≈ **one
extra AssumeRole, ~300 ms, once**. Against a deployment measured in minutes this
is not observable.

Versus the current wrapper this is **cheaper**: the wrapper performs an AssumeRole
per block entered and cannot cache across blocks.

---

## 12. Scalability at thousands of builds per day

Assume 5,000 builds/day, 200 concurrent executors.

| Dimension | Analysis |
|---|---|
| **STS** | 5,000 extra `AssumeRole`/day ≈ **0.06 req/s** average. Even a 20× peak is far below any AWS account-level STS limit |
| **CloudTrail** | +5,000 management events/day. Management events are free in the first trail per region; no cost impact |
| **Controller memory** | **New finding:** the memo must be evicted, or it leaks one entry per node block forever. `onFinalized` eviction plus a `WeakHashMap` keyed on `Run` as belt and braces. At 200 concurrent builds the live set is ~200 small entries |
| **Controller CPU** | `ExtensionList.lookup` is a cached list; the hot path is one map lookup per step. At 5,000 builds × 100 steps = 500 k lookups/day ≈ 6/s. Immaterial |
| **Remoting** | 3 round trips per node block, not per step. At 5,000 node blocks/day ≈ 0.06/s |
| **Agent disk** | Bounded by workspace count × 1 KB, independent of build count — a direct consequence of the §3 location decision |
| **No timers, no thread pools, no background sweeper** | The design adds no scheduled work at all |

The one thing that would not scale — an `exists()` or a write per *step* — is
explicitly designed out. This is the single most important implementation
constraint (§20).

---

## 13. Security

### 13.1 What is exposed

Role ARN, session name, region, `credential_source`. CLAUDE.md already establishes
that role ARNs are not secrets. **The plugin writes no credential material
anywhere.**

Permissions: `0700` directory, `0600` files — hygiene, not a control, since the
content is not secret and the agent process owns it either way.

### 13.2 An improvement over the wrapper

| | Wrapper (current) | Managed (proposed) |
|---|---|---|
| Live credentials in the build environment | **Yes** — printable by `sh 'env'` | No |
| Credentials in CPS program state | **Yes**, via the `EnvironmentExpander` | No |
| Console masking required | Yes | Nothing to mask |
| Credential material written by the plugin | No | No |

`hudson.util.Secret` declares no `writeObject`/`writeReplace`, so it is **not**
encrypted under plain Java serialization; the earlier claim that block-scoped
credentials never enter CPS program state as plaintext is not accurate for the
wrapper. The managed design removes the exposure rather than restating the claim.

### 13.3 Residual, unchanged from today

`~/.aws/cli/cache` on the agent (§10).

### 13.4 Injection surface

§7.4 — INI injection via admin-supplied profile names or ARNs. Mitigated by
validation in both the form and the renderer.

### 13.5 Bypass

- **Accidental: eliminated.** Nothing to remember; new repositories are covered
  from their first build.
- **Deliberate: unchanged, and out of scope.** Anyone with `sh` on an agent can
  run `aws sts assume-role` directly against a role the instance profile trusts,
  or point `AWS_CONFIG_FILE` back at `~/.aws/config`. Only OIDC plus removing the
  instance-profile principal from those trust policies closes it; the
  `sts:RoleSessionName StringLike "jk-*"` condition does not, because any caller
  can imitate the prefix.

### 13.6 Permission model

Configuration stays behind `Jenkins.ADMINISTER`. A pipeline author cannot add a
profile, change a role ARN, or point a profile at another account.

---

## 14. Failure scenarios

| # | Scenario | Behaviour | Rationale |
|---|---|---|---|
| 1 | Managed auth disabled | Nothing injected, no file, no cost | Ships inert |
| 2 | Enabled, **no profiles configured** | **Inject nothing.** Behaves exactly as today | An empty config would break every `--profile` build. Fail *open* to the status quo |
| 3 | Job outside the rollout pattern | Inject nothing | Staged rollout |
| 4 | Step outside `node` | Return `null` | `sh` cannot run there |
| 5 | Build uses a profile **not in the Jenkins mapping** | AWS CLI exits **253**, `The config profile (X) could not be found` | Loud, never silently wrong. **Largest operational risk** — §18.1 |
| 6 | File cannot be written | `[ck-aws] WARNING: could not write AWS configuration; this build will not be attributed`; inject nothing; **do not fail the build** | A fleet-wide feature must not turn a disk hiccup into a total outage. The warning plus a CloudTrail gap makes it detectable |
| 7 | `cleanWs()` deletes `@tmp` mid-build | Next AWS call in the same node block fails loudly (exit 253); the next node block self-heals | §4.2 |
| 8 | `SessionName.forBuild` throws | As #6 | Already sanitises and truncates; near-unreachable |
| 9 | Agent disconnects mid-build | Standard Jenkins failure; cleanup best-effort | §10 |
| 10 | Build sets its own `AWS_CONFIG_FILE` / `AWS_PROFILE` / keys | The build wins | Escape hatch |
| 11 | `withAWS` / `withCredentials` | Block-scoped context wins | Correct precedence |
| 12 | `ckAwsWithProfile` inside a managed build | They compose — §17.2 | No double AssumeRole |
| 13 | Jenkins configuration changed mid-build | The already-written file wins for that build | Builds see a consistent mapping |
| 14 | Controller restart mid-build | Memo lost → rewrite on the next step; cleanup paths survive on the persisted action | §10 |

---

## 15. Rollback

| Level | Action | Restart |
|---|---|---|
| Disable entirely | Untick **Enable managed AWS authentication** | **No** |
| Narrow blast radius | Edit the job-name pattern | **No** |
| Remove one profile | Delete the mapping row | **No** |
| Revert the plugin | Reinstall the previous `.hpi` (SHA `9f6dcf3038d43dee429ee6f8ebf6701e278717588f9852320d456502afd0a63b`) | Yes |
| Revert a consumer | Nothing to revert — consumers were never changed | — |

Rollback is a checkbox, not a deployment. `PluginManager.dynamicLoad` throws
`RestartRequiredException` on the "plugin is already installed" branch (Jenkins
2.479.2 bytecode), so **every** plugin upgrade needs a restart — which is exactly
why the feature ships inside `ck-aws` behind a flag rather than as a second
plugin.

---

## 16. Compatibility

| Consumer | Works? | Evidence |
|---|---|---|
| **AWS CLI v2**, `--profile X` | ✅ | Measured: config file beats environment when a profile is explicit |
| **AWS CLI v2**, no profile | ✅ unchanged | Measured: with no `[default]`, resolution falls through to IMDS — no hard error |
| **boto3**, `Session(profile_name=…)` | ✅ | Measured |
| **boto3**, `Session()` | ✅ unchanged | Measured |
| **Terraform** (AWS provider, S3 backend) | ✅ if a `[default]` is configured — the infra repo passes no profile today | Shared-config support is native; **verify at rollout** — §19.3 |
| **`aws ecr get-login-password \| docker login`** | ✅ | Same resolution path. Passes no `--profile` today, so governed by the `[default]` decision; the pre-existing `ecr:GetAuthorizationToken` question stands |
| **AWS SDK Java v2, Go, .NET, JS, Ruby, PHP** | ✅ | Native shared-config support |
| **AWS SDK Java v1** | ⚠️ `role_arn` supported, `credential_source` limited | §18.6 |
| **`docker.image().inside { }`** | ✅ expected — §19.1 | @tmp mount |
| **`agent { kubernetes }`** | ✅ expected — §19.2 | shared workspace volume |
| **Hand-rolled `docker run`** | ❌, but no regression | §5 |
| **Freestyle jobs** | ❌ | §18.3 |
| **Controller-side plugins calling AWS** | ❌ unattributed, unchanged from today | Out of scope |

---

## 17. Migration and backward compatibility

### 17.1 Migration

| Stage | Action | Rollback |
|---|---|---|
| 0 | **Inventory every `--profile` string** across `cln-deployment-scripts`, `cln-infra-terraform` and standalone Jenkinsfiles; ensure each exists in the Jenkins mapping | read-only |
| 1 | Ship managed support **disabled**; upgrade the plugin (one restart) | Reinstall previous `.hpi` |
| 2 | Enable for one non-production job pattern; verify CloudTrail shows `AssumeRole` with `requestParameters.roleSessionName = jk-<job>-<build>` and the downstream call's `sessionContext.sessionIssuer.arn` = the target role | Untick |
| 3 | **Revert the M7 deployment-library branch** (`AwsAuth.groovy`, `profileGuard`, the three `ckAwsWithProfile` wrappers) | Re-apply |
| 4 | Widen the pattern to all jobs | Narrow it |
| 5 | Decide on a `[default]` profile once ECR permissions are confirmed | Clear the field |
| 6 | *Optional:* remove `~/.aws/config` from agents | Restore |
| 7 | Layer 3 trust policy, then OIDC | Revert the condition |

Stage 3 is where the requirement is actually met. Stages 0 and 2 carry the risk.

### 17.2 The wrapper and managed auth compose

`CkAwsWithProfileStep` already builds its body context with
`EnvironmentExpander.merge(context.get(EnvironmentExpander.class), …)`. That
`get()` routes through `ContextVariableSet`, which — with no block-scoped value in
scope yet — consults `DynamicContext` and returns the **managed** expander. The
wrapper merges rather than replaces. So inside a `ckAwsWithProfile` block:

- `AWS_CONFIG_FILE` is present alongside the wrapper's credentials;
- an unprofiled command uses the wrapper's environment credentials;
- a `--profile X` command has the environment negated and resolves through the
  managed config — **same role, same session name**;
- there is no double AssumeRole.

M7's `profileGuard` therefore becomes harmless rather than required, which is what
makes reverting it at Stage 3 a non-event.

### 17.3 Disabled must be indistinguishable from not installed

This is decision 8, and it is the property that makes upgrading Infrastructure
Jenkins safe. With the master switch off, `ManagedAwsContext.get()` returns
`null` on its **first** check — before it looks at profiles, before it touches a
`Run`, before it computes a session name, before any filesystem or remoting call.
Nothing is written, nothing is exported, no AWS call is made, and no build
environment differs by a single variable from an instance where the plugin is
absent.

Three separate conditions each produce that same do-nothing outcome, so a
misconfiguration cannot accidentally activate the feature:

1. the master switch is off;
2. the switch is on but **no profiles are configured** — injecting a config file
   with no profiles would break every `--profile` command on the controller, so
   this fails *open* to the status quo;
3. the job does not match the rollout pattern, and an **unparseable** pattern
   matches nothing rather than everything, so a typo narrows a rollout instead of
   silently widening it.

Even switched on, the plugin's job is to *attribute* AWS calls, not to gate them.
It adds a session name to authentication that was already happening. A build that
fails to prepare its configuration logs a warning and proceeds unauthenticated
rather than failing (§14 #6), because a feature that applies to every job at once
must never convert a local problem into a fleet outage.

The Definition of Done requires this to be demonstrated, not argued: §21 includes
an explicit off-state test.

### 17.4 Unchanged

The mapping and its `@Symbol`s, JCasC path, `SessionName`, `AuthCore`,
`CliStsAssumeRole`, `exec.*`, `ckAwsWithProfile`, `ckAwsAssumeRole`, every
existing test, and the plugin id `ck-aws` — so this remains an in-place upgrade.
`ckAwsWithProfile` is **not** deprecated; it is reclassified from "the rollout
mechanism" to "the documented override".

The one deliberate behaviour change in existing code: a profile configured in
`InstanceProfile` mode has no role ARN, so `ckAwsWithProfile('ops')` now fails
closed with a message saying there is nothing to assume, rather than assuming an
empty ARN. Two assertions in the pre-existing configuration test changed with it,
and say why in a comment.

---

## 18. Limitations, accepted before implementation

**18.1 A profile name used by a repository but absent from the Jenkins mapping
fails the build.** Exit 253, `The config profile (X) could not be found`. Loud,
never silently wrong, but a failure. The plugin cannot mitigate by reading
`~/.aws/config` — that rule predates M6. **Mitigation is Stage 0.** Largest
operational risk in the design.

**18.2 AWS calls inside a hand-rolled `docker run` are not covered.** No
regression — §5.

**18.3 Freestyle (non-Pipeline) jobs are not covered.** `DynamicContext` is a
Pipeline concept. `SimpleBuildWrapper` works for `AbstractProject` and is the
available second path, but it is additional scope. **Must be confirmed.**

**18.4 Calls that name no profile are unattributed unless a default is
configured.** That is `Utilities.dockerLoginEcr` and all of
`cln-infra-terraform`. Measured: with no `[default]`, behaviour is identical to
today, so starting without one is provably a no-op. Note this creates a genuine
tension with the acceptance criterion "CloudTrail continues producing
`jk-<job>-<build>`" for the Terraform repository specifically: **attributing it
without repository changes requires a `[default]` profile**, which simultaneously
changes `dockerLoginEcr`'s identity. That trade-off is Stage 5's decision.

**18.5 Controller-side AWS calls by other Jenkins plugins remain unattributed.**

**18.6 AWS SDK for Java v1 `credential_source` support is limited.** Flagged, not
assumed.

**18.7 Profile names containing whitespace cannot work.** botocore's config parser
rejects `[profile with space]`. Form validation must reject them.

**18.8 Session-name truncation can collide in deep multibranch hierarchies.**
§4.3. Pre-existing, but managed authentication makes it fleet-wide.

**18.9 Windows agents are untested.** Path and INI quoting would need review.

---

## 19. Unverified claims — must be confirmed during implementation

These are the only load-bearing statements in this document that were **not**
verified against a real artefact.

1. **`docker.image().inside { }` mounts `<workspace>@tmp` and propagates the build
   environment.** The docker-workflow plugin is not a dependency of this project,
   so its bytecode was not inspected. If it turns out not to mount `@tmp`, §5's
   container coverage is lost but nothing regresses, and the §3 location decision
   should be revisited.
2. **Kubernetes agents share the workspace volume across pod containers.**
3. **Terraform's AWS provider honours `AWS_CONFIG_FILE` + `role_arn` +
   `credential_source`.** Documented SDK behaviour, not measured here.
4. **`cleanWs()` deletes `@tmp` siblings.** Assumed worst case; if it does not, the
   §4.2 mitigation becomes unnecessary rather than wrong.
5. **JCasC end-to-end YAML load** remains untested at the 2.479.2 baseline — a
   pre-existing M6 gap, unchanged.

---

## 20. Implementation constraints

Non-negotiable, derived from §11 and §12:

1. **No I/O on the hot path.** After the first write in a node block, `get()` must
   do nothing but a map lookup. No `exists()`, no `stat`, no remoting per step.
2. **Evict the memo in `onFinalized`.** Otherwise it leaks one entry per node
   block, forever.
3. **The memo is in-memory only.** Persisting it would break restart recovery.
4. **No credential material in the generated file, ever.** Every relaxed property
   in §10 and §13 depends on it.
5. **Never fail a build because the plugin could not write its file.** Warn and
   inject nothing.
6. **Validate INI-unsafe values in the renderer as well as the form.** JCasC
   bypasses form validation.
7. **Do not put the file in the workspace.** `stash`, `git clean`, `deleteDir`.
8. **No STS call from a `DynamicContext`.** It is consulted for every step; the
   AssumeRole belongs to the AWS tool.

---

## 21. Definition of Done

- Managed Authentication is off by default; enabling and disabling are
  restart-free.
- **Off is indistinguishable from not installed**, demonstrated by test: no
  environment variable, no generated file, no AWS call, no behaviour change — and
  the same for "on but no profiles configured" and "on but the job does not match
  the pattern".
- A profile in `InstanceProfile` mode renders with no credential keys, so
  `aws --profile <name>` keeps working and stays on the agent's identity.
- A profile in `AssumeRole` mode without a role ARN is rejected by form
  validation rather than rendered.
- An unmodified pipeline containing only
  `node { sh 'aws sts get-caller-identity --profile <name>' }` returns an ARN
  ending `assumed-role/<role>/jk-<job>-<build>`, with **zero** plugin references
  in the Jenkinsfile.
- CloudTrail shows `AssumeRole` with
  `requestParameters.roleSessionName = jk-<job>-<build>`, and the downstream
  call's `sessionContext.sessionIssuer.arn` is the target role.
- Enabled with no profiles configured ⇒ builds behave exactly as today.
- The generated file contains no credential material and is deleted at
  `onFinalized` for SUCCESS, FAILURE and ABORTED.
- A second `node` block on a different agent gets its own correct file.
- `parallel` branches on one agent share one file and one session name.
- A step after a memo hit performs **zero** remoting calls.
- The memo is empty after the build finalizes.
- `ckAwsWithProfile` still works, still wins in its own block, no double
  AssumeRole.
- `AuthCore`, `CliStsAssumeRole`, `SessionName`, `AwsProfile` and all existing
  tests unchanged.
- The renderer is unit-tested without `JenkinsRule`, including INI-injection
  rejection.
- Plugin id and artifact identity remain `ck-aws`.
- No CloudKeeper-specific value anywhere, `credential_source` included.
