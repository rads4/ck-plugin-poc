# Project: CK AWS Plugin (POC)

## What this is

A Jenkins plugin proof of concept for CloudKeeper (CK), a cloud cost management
SaaS company. The plugin centralizes AWS authentication and generic AWS CLI
execution for Jenkins deployment pipelines, so deployment Groovy code no
longer performs STS calls directly and no longer knows how authentication
happens.

This is a POC being built locally against a local Jenkins instance
(`mvn hpi:run`), validated using the organization's existing **read-only**
NonProd AWS profile. There is no dedicated AWS account for this POC — do not
assume write/deploy permissions are available or needed for validation.

## Repository Strategy (POC)

The POC does **not** begin inside the existing CK deployment repository or
any existing CK GitLab repository. Instead:

- A brand new, independent repository, tentatively named `ck-aws-plugin`.
- Pushed to the developer's **personal GitHub** initially, not CK GitLab.
- Kept completely independent from `cln-deployment-scripts`,
  `cln-infra-main`, and the shared Groovy libraries — no dependency in
  either direction during the POC.
- This isolation is deliberate: the POC is still experimental, and keeping
  it out of CK's existing repositories avoids entangling unproven code with
  production deployment infrastructure.

**Where this repository ends up long-term is intentionally out of scope for
the POC.** See "Post-POC Repository Decision" at the end of this document.
Do not treat any particular end-state repository location as decided.

## Development Flow (POC)

```
New Local Repository (ck-aws-plugin)
        -> GitHub (personal, initial)
        -> Claude Code
        -> Local Jenkins (localhost, via mvn hpi:run)
        -> Plugin Development
        -> Read-only CK NonProd AWS Profile
        -> CloudTrail Validation
        -> POC Complete
```

No deployment repositories and no production Jenkins instance are involved
at any point during the initial POC. Migration into CK GitLab, and any
integration with real deployment pipelines, is only discussed **after** the
POC succeeds — not as part of it.

## Organizational context

- CloudKeeper's Platform/DevOps team owns Jenkins, shared Groovy libraries,
  deployment Groovy files, and Jenkins plugins. Application teams do not
  write deployment logic — the Platform team owns 100% of it.
- Existing shared library classes: `Deploy.groovy`, `Build.groovy`,
  `Utilities.groovy`, `Audit.groovy`, and an `AwsAuth.groovy` class that
  currently performs STS AssumeRole explicitly inside the shared library.
- Some standalone repositories exist that do not use the shared library and
  execute AWS CLI directly.
- An infrastructure repository runs Terraform, which currently authenticates
  independently of this plugin.

## Current (pre-plugin) authentication flow

```
Jenkins Pipeline
    -> Deployment Groovy
    -> AwsAuth.groovy
    -> aws sts assume-role (explicit, with custom RoleSessionName)
    -> temporary credentials
    -> AWS CLI
    -> AWS
```

A working POC already validated that explicit `sts assume-role` calls with a
deterministic session name of the form:

```
jk-${JOB_NAME}-${BUILD_NUMBER}
```

produce CloudTrail sessions like `jk-myjob-123` instead of generic
auto-generated assumed-role session names. This session-naming convention is
**load-bearing** — do not change its shape without discussion, since it is
also the basis for a future IAM trust-policy enforcement mechanism (see
below).

## What we're building now: architecture decisions already made

We deliberately rejected two simpler designs before landing here. Do not
re-propose them without a very good reason:

1. **A pipeline step that does everything** (`ckAws.execute(profile, command)`
   owning both auth AND execution as the only interface). Rejected because
   it's still opt-in — a raw `sh "aws ..."` call bypasses it exactly as
   easily as it bypassed the old Groovy wrapper. Compiling the wrapper into
   Java does not make it less optional.
2. **Auth-only plugin, execution fully back in Groovy `sh` calls.** Rejected
   because it loses centralized retry/timeout/structured logging unless
   reimplemented redundantly across Groovy files over time.

### The design we landed on

**The plugin owns exactly two things, both intentionally generic and
rarely-changing:**

1. **Authentication** — STS AssumeRole, session name generation, temporary
   credential lifecycle/caching. Two entry points converge into one auth
   core:
   - An **implicit default path** (a `RunListener`/`EnvironmentContributor`
     firing at build start) that resolves the target AWS profile/role from
     Jenkins job/branch naming conventions where possible — this is the
     common single-environment-per-build case, and requires zero pipeline
     code.
   - An **explicit block-scoped step** (e.g. `ckAws.withProfile('prod') { }`,
     modeled on the existing `withAWS` step from the Pipeline: AWS Steps
     plugin) for builds that need multiple environments/roles within one
     build, where the target can't be known ahead of time.
   - **Fail closed, not open**: if the environment can't be cleanly resolved
     by convention, the build should fail with an actionable message rather
     than guessing.

2. **Generic AWS CLI execution** — a `ProcessBuilder`-based executor that
   takes an arbitrary argument list (e.g. `["ecs", "update-service", ...]`)
   and wraps it with retry/timeout/structured logging. **This executor must
   never branch on which AWS service or API is being called.** That's what
   keeps "new AWS CLI commands don't require plugin changes" true. If you
   find yourself writing an `if (args[0] == "ecs")` anywhere in the
   executor, stop — that's scope creep into what the AWS SDK approach would
   require, which we explicitly rejected for this project (it would force a
   plugin release for every new AWS service/API surface used, contradicting
   a hard requirement).

**Deployment Groovy (existing shared library) keeps owning:**
- Deployment workflow and sequencing
- Which AWS CLI commands to run, in what order, with what arguments
- This should remain fast to iterate (git push, no plugin release needed)

### Configuration

- Do **not** read `~/.aws/config` from inside the plugin. It lives on the
  agent filesystem, outside Jenkins' own permission model, and is editable
  by anyone with agent/SSH access — a real trust-boundary leak, not just
  untidy.
- Profile-name-to-role-ARN mapping should live in **Jenkins Configuration as
  Code (JCasC)**, so changing it requires Jenkins admin permission and is
  version-controlled/reviewable like the rest of platform config.
- Role ARNs are not secrets — don't route them through the Jenkins
  Credentials plugin, which is the wrong tool for non-secret config.

### Known constraint: chained AssumeRole session duration

The existing flow is EC2 instance role -> AssumeRole into a target role
(`prod`/`non_prod`/`ops`). This is role chaining, which caps the resulting
session at **1 hour regardless of the target role's configured max session
duration**. Long-running builds need a credential refresh path, not just a
single cached session for the whole build.

### The real enforcement boundary (context, not POC scope yet)

Nothing inside Jenkins — no plugin, no pipeline step, no RunListener — can
ever be made truly non-bypassable, because it all runs inside the same trust
boundary as an unrestricted shell. The actual non-bypassable backstop is an
**IAM trust-policy condition** on the target roles:

```json
"Condition": {
  "StringLike": { "sts:RoleSessionName": "jk-*" }
}
```

Any AssumeRole call with a non-conforming (e.g. auto-generated) session name
gets denied by AWS itself. This is a **future phase**, not part of the
current POC (which uses a read-only profile and shouldn't touch trust
policies), but the session-naming convention above exists specifically to
make this future step possible without redesigning anything. Keep that
convention intact.

## POC scope and milestones

We're sequencing to validate the riskiest unknown first (does AssumeRole +
custom session naming actually work from inside a plugin), not to build
full ergonomics first.

- **M0** — Empty plugin scaffold. `mvn hpi:run` boots local Jenkins with the
  plugin installed. No logic yet.
- **M1** — Auth core in isolation: STS AssumeRole with the `jk-` session
  name convention, unit-tested with a mocked/stubbed STS client. No Jenkins
  integration yet.
- **M2** — One explicit pipeline step (`ckAws.run([...])`) wiring the auth
  core to the generic executor. Start here, not with the automatic
  RunListener — it's easier to trigger and observe manually for a first
  POC.
- **M3 — POC success criterion.** Real pipeline job on local Jenkins, using
  the org's NonProd **read-only** profile, running
  `ckAws.run(["sts", "get-caller-identity"])`. Validate:
  - The AssumeRole call succeeds
  - `get-caller-identity` succeeds
  - CloudTrail Event History in the NonProd account shows the session name
    as `jk-<job>-<build>`, not a generic assumed-role session name
- **M4 (stretch)** — retry/timeout wrapping, structured logging.
- **M5 (stretch)** — RunListener automatic default-profile injection,
  JCasC-backed profile/role config.

Do not implement past M3 without explicit direction — the read-only
NonProd constraint means write-capable AWS CLI commands aren't validated in
this environment anyway.

## Definition of Done (POC)

The POC is complete when all of the following are true:

- Plugin loads successfully into localhost Jenkins.
- A pipeline invokes the plugin through the agreed API (the explicit
  `ckAws.run([...])`-style step from M2, not the RunListener path).
- Plugin performs STS AssumeRole using the `jk-<job>-<build>` session name
  convention.
- Plugin executes `aws sts get-caller-identity` through the generic
  executor.
- The correct identity is returned.
- CloudTrail records the standardized session name for that call.
- The generic executor correctly returns stdout, stderr, and exit code.
- All agreed Phase 1 milestones (M0-M3) are complete.
- The plugin remains AWS-service agnostic — no per-service branching was
  introduced anywhere in the executor.
- No changes were required to any existing deployment library
  (`Deploy.groovy`, `AwsAuth.groovy`, etc.) to achieve the above.

## What NOT to do (things that will look like reasonable improvements but aren't)

- Do not add per-AWS-service logic to the executor (see above).
- Do not read `~/.aws/config` (see above).
- Do not implement the full RunListener/automatic-injection path before the
  explicit step path is proven end-to-end (M3).
- Do not implement the IAM trust-policy enforcement piece as part of this
  POC — it requires access/changes this POC doesn't have and isn't the
  current milestone.
- Do not swap to the AWS Java SDK "for better typing/error handling" without
  flagging it first — this was explicitly evaluated and rejected as the
  primary execution mechanism because it breaks the AWS-service-agnostic
  requirement.

## Working Principles

This project follows an architecture-first development approach. Every
milestone must follow this workflow:

1. Explain the implementation plan.
2. Wait for approval.
3. Implement only the agreed milestone.
4. Keep changes limited to the milestone scope.
5. Run relevant tests.
6. Explain the changes.
7. Stop for review.

Do not continue into the next milestone automatically. Do not redesign
previous decisions unless explicitly requested. Prefer standard Jenkins
plugin development practices over custom abstractions. When uncertain,
explain assumptions instead of making them silently.

Also: keep auth/executor logic testable without a live Jenkins wherever
possible — reserve `JenkinsRule`-based tests for things that genuinely need
a running Jenkins (extension registration, config persistence).

## Out of Scope (POC)

The following are intentionally excluded from Phase 1:

- Production rollout
- Migration of deployment repositories
- Migration of Jenkins shared libraries
- Organization-wide adoption
- IAM trust policy enforcement
- Production Jenkins installation
- Repository ownership decisions
- CI/CD pipeline for the plugin

## Post-POC Repository Decision

Once the POC is complete, we will evaluate the appropriate long-term
repository strategy. Possible options include (not an exhaustive list, and
none of these is a recommendation):

- A dedicated Jenkins plugin repository under CK GitLab
- A repository under an existing Platform tooling project
- A branch inside an existing repository

This decision requires technical justification based on maintainability,
ownership, release process, and CK's organizational standards — it is not
being made now, and nothing in this document should be read as favoring one
option over another.

## Documentation Maintenance

At the end of every completed implementation session:

1. Update `MEMORY.md`.
   - Record the milestone or task completed.
   - Record important architectural decisions.
   - Record validation performed.
   - Record issues encountered.
   - Record any deviations from the original plan.

2. Review `README.md`.
   Update it whenever user-facing behavior changes, including:
   - new features
   - removed features
   - completed milestones
   - installation changes
   - usage changes
   - limitations
   - roadmap

3. Do NOT modify `CLAUDE.md` automatically.

`CLAUDE.md` is the project's architectural contract and should only be updated when:
- the architecture changes,
- new long-term design decisions are made,
- project scope changes,
- or I explicitly ask for it.

If no architectural decisions changed during a session, leave `CLAUDE.md` untouched and state that no update was necessary.