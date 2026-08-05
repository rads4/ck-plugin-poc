# Project: CK AWS Plugin

## What this is

A Jenkins plugin that centralizes **AWS identity** for Jenkins builds. It assumes
an AWS role on the build's behalf, using a deterministic, build-attributable STS
session name, and exposes the resulting temporary credentials to a scoped region
of a pipeline through the standard AWS environment-variable contract.

It does **not** run AWS commands. It does not know what a deployment is, what
ECS is, or that CloudKeeper exists. Anything that consumes AWS credentials —
the AWS CLI, boto3, Terraform, Docker, a future SDK — consumes them the same way
any AWS tool anywhere does.

Originally a proof of concept (M0–M5, complete and validated against real AWS).
As of the M6 architecture review it has an agreed long-term direction, recorded
below. **This document is the authoritative architecture document for the
project.**

## Status

| Milestone | State |
|---|---|
| M0 — plugin scaffold | Complete |
| M1 — auth core (AssumeRole + `jk-` session naming, unit-tested) | Complete |
| M2 — first pipeline step wired to the auth core | Complete (as `ckAwsAssumeRole`) |
| M3 — real pipeline on local Jenkins, real AWS, CloudTrail verified | Complete |
| M4 — live AWS validation of the AssumeRole flow | Complete |
| M5 — production packaging, installed on Infra Jenkins | Complete |
| M6 — layered architecture: block-scoped auth, JCasC mapping, agent-side execution | Complete |
| M7 — deployment-library integration (`cln-deployment-scripts` @ `ck-aws-plugin`) | Complete, untested end to end |
| **M7v — validation: local, STS and CloudTrail verified; `.hpi` uploaded to Infra Jenkins** | **Current — pending Jenkins restart, then Backend dev2 deployment** |
| M8 — optional `ckAws.run([...])` convenience executor | Planned, optional |
| M9 — RunListener default injection | Planned |
| M10 — IAM trust-policy enforcement | Future, outside Jenkins |

---

# Architecture

## The layered architecture

The system is four layers. Each layer is independently adoptable, independently
revertable, and depends only on the layer below it.

```
Layer 3   IAM trust policy: "sts:RoleSessionName" StringLike "jk-*"
          (AWS-side, non-bypassable, outside Jenkins entirely)
              ^ made possible by Layer 1's session naming

Layer 2   OPTIONAL execution conveniences
          ckAws.run([...]) — retry/timeout/typed result for callers that
          genuinely are a plain AWS CLI invocation. Never required.

Layer 1   THE CONTRACT (mandatory, the only thing consumers must adopt)
          ckAwsWithProfile('non_prod') { ... }
          AssumeRole with jk-<job>-<build>, executed on the agent,
          credentials exported into the block as AWS_* environment variables,
          masked in the console, expiring at block exit.

Layer 0   CONFIGURATION
          profile name -> role ARN (+ region) in JCasC.
          Jenkins-admin-owned, version-controlled, reviewable.
```

**Layer 1 is the product.** Layers 0 and 2 exist to serve it. Layer 3 is the
only real enforcement and is not ours to deploy.

## Principle 1 — the plugin owns identity, and only identity

The plugin's entire responsibility is answering *"who is this build allowed to
be, and for how long?"*:

- Resolve a profile name to a role ARN through Jenkins-owned configuration.
- Generate the `jk-<job>-<build>` session name.
- Perform STS AssumeRole, on the machine where the work will run.
- Publish the resulting credentials into a bounded scope.
- Withdraw them when that scope ends.

Everything else is out of scope, permanently and by design.

## Principle 2 — execution stays outside the plugin

The plugin does not execute AWS commands on behalf of consumers. Consumers keep
running whatever they already run — `sh "aws ..."`, `terraform apply`,
`python3 script.py`, `docker login` — inside the authenticated block.

This is not a concession. It is the property that makes the plugin reusable.
There are at least four distinct ways an AWS consumer obtains credentials, and
only one of them is "hand an argument list to something":

| Consumer shape | How it takes credentials |
|---|---|
| `sh "aws ecs update-service ..."` | env vars, or `--profile` |
| `aws ecr get-login-password \| docker login ...` | env vars (it is a **pipeline**, not an argv) |
| boto3 `Session()` | env vars, or `profile_name` |
| `terraform apply` | env vars only |

The exported-environment contract serves all four. An argument-list executor
serves one. Since the plugin's goal is to be consumable by *any* Jenkins shared
library or Jenkinsfile, the contract must be the environment.

## Principle 3 — profile → role resolution is Jenkins-owned

Consumers name an environment; they never name an ARN.

```groovy
ckAwsWithProfile('non_prod') { ... }
```

The `profile → roleArn (+ region)` mapping lives in **Jenkins Configuration as
Code**, under `unclassified.ckAws.profiles`. Consequences, all intended:

- Changing which role an environment maps to requires Jenkins admin permission.
- The mapping is version-controlled and reviewable like the rest of platform
  config.
- Consumers are portable: the same Jenkinsfile works in another Jenkins with a
  different account, because it contains no ARNs.
- Unknown profile names **fail closed**, with an error listing the configured
  profiles.

The plugin must **never read `~/.aws/config`**. That file lives on the agent
filesystem, outside Jenkins' permission model, editable by anyone with agent or
SSH access. Depending on it would place the identity decision outside the system
that is supposed to be making it. This rule predates M6 and is unchanged.

Role ARNs are **not secrets**. They must not be routed through the Jenkins
Credentials plugin, which is the wrong tool for non-secret configuration.

An explicit `roleArn:` parameter exists as a documented escape hatch for
pipelines whose profile has not been added to JCasC yet (e.g. an initial
Terraform-repo migration). It is not a security boundary — a pipeline author who
wants an arbitrary ARN can already call `sh "aws sts assume-role"` directly. The
security boundary is Layer 3.

## Principle 4 — the block-scoped authentication wrapper

Layer 1's shape is a **block**, not a value-returning step:

```groovy
node {
    ckAwsWithProfile('non_prod') {
        sh "aws ecs update-service ..."          // inherits the session
        sh "terraform apply -auto-approve"       // inherits the session
        sh "python3 code/dr_sync.py"             // inherits the session
    }
}
```

Why a block and not a returned value:

1. **Authentication is inherently scoped.** "For the duration of this work, be
   this identity" is a region of a program, not a point in it. A returned value
   cannot express a scope, cannot clean up, and cannot host credential refresh.
2. **Credentials must not enter CPS program state.** A value returned to a
   pipeline variable is serialized into the flow's `program.dat` on disk and is
   trivially printable from a pipeline. Block-scoped credentials live in the
   body's `EnvVars` and in an `EnvironmentExpander` that stores them as
   `hudson.util.Secret`, never as plaintext in program state.
3. **The block is the only place refresh can live** when the 1-hour chained
   session cap becomes a problem.
4. **It matches what Jenkins users already know** — `withCredentials`,
   `withAWS`, `withEnv`. Adoption cost is one wrapping line.

Exported into the block:

| Variable | Source |
|---|---|
| `AWS_ACCESS_KEY_ID` | AssumeRole result (masked) |
| `AWS_SECRET_ACCESS_KEY` | AssumeRole result (masked) |
| `AWS_SESSION_TOKEN` | AssumeRole result (masked) |
| `AWS_REGION`, `AWS_DEFAULT_REGION` | profile mapping or step parameter, if configured |
| `CK_AWS_SESSION_NAME` | the `jk-<job>-<build>` session name — non-secret, for logging and debugging |

All three credential variables are declared sensitive and are masked in the
console for the duration of the block.

## Principle 5 — execution happens where the work happens

The AssumeRole subprocess runs **on the agent selected by the enclosing `node`
block**, via the step context's `Launcher` — not on the Jenkins controller.

This is a correctness requirement, not a preference:

- The base identity the plugin chains from (EC2 instance role) is the **agent's**
  identity. Assuming from the controller would use the wrong base identity and,
  in most topologies, would simply be denied.
- Credentials produced on the controller are useless to `sh` steps that execute
  on an agent.
- The controller's identity is broader than an agent's. Authenticating there
  would be a privilege escalation relative to the status quo.

Consequently `ckAwsWithProfile` requires `Launcher` and `FilePath` context and
therefore must be used inside a `node { }` block. Outside one, it fails closed
with an actionable message.

## Principle 6 — the plugin is generic; genericity is a hard constraint

No layer of the plugin may contain:

- Per-AWS-service logic. The process execution primitive must never branch on
  `args[0]`. If you find yourself writing `if (args[0] == "ecs")`, stop.
- CloudKeeper-specific names, conventions, account IDs, or role names. `prod`
  and `non_prod` are *data in someone's JCasC file*, not identifiers in the
  source tree.
- Deployment-specific logic. The plugin has no concept of a deployment, a task
  definition, a cluster, or a rollback.

The plugin is validated against the CloudKeeper deployment library because that
is the first consumer available — not because it is the only intended consumer.
Terraform pipelines, standalone Jenkinsfiles, and future shared libraries are
first-class consumers of the same contract.

---

# Rejected designs, and why

These were evaluated seriously. Do not re-propose them without new information.

## Rejected: the AWS Java SDK as the execution mechanism

Would force a plugin release for every new AWS service or API surface a
deployment wants to use. Contradicts a hard requirement that new AWS CLI
commands never require plugin changes. The CLI subprocess stays.

## Rejected: a single step that owns both auth and execution as the only interface

`ckAws.execute(profile, command)` as the sole entry point was rejected because
it is still opt-in — a raw `sh "aws ..."` bypasses it exactly as easily as it
bypassed the old Groovy wrapper. Compiling a wrapper into Java does not make it
less optional. Bypassability is solved at Layer 3 or not at all.

## Rejected: the generic authenticated AWS CLI executor as the mandatory interface

This is the one that changed at M6. Previously the plan was that
`ckAws.run(["ecs", "update-service", ...])` would be *the* way consumers reached
AWS, with the plugin providing centralized retry, timeout, and structured
logging. It was rejected as the **boundary** for five reasons:

1. **It is the least generic option, despite appearing the most generic.** It is
   generic across AWS *services* but narrow across *consumers*. It cannot
   express `aws ecr get-login-password | docker login` (a shell pipeline, not an
   argv). It cannot help boto3. It cannot run Terraform. Three of the four
   consumer shapes we actually have are unreachable through it.
2. **Highest coupling.** It makes plugin availability and plugin version a hard
   runtime dependency of every deployment. A plugin rollback becomes a
   deployment outage.
3. **Largest blast radius.** Every AWS call in the organization would route
   through one Java method. A defect in argument handling, stream draining,
   exit-code mapping, or retry semantics breaks every pipeline simultaneously.
4. **Higher adoption cost, with no incremental path.** In the CloudKeeper
   deployment library it means rewriting all 13 AWS invocations, each with its
   own `returnStdout` / `jq` / output-parsing behaviour to re-verify — and one of
   them (the ECR login pipeline) cannot be rewritten that way at all. A block
   wrapper is one line per entry point and can be adopted one pipeline at a time.
5. **Unbounded scope creep.** An executor immediately needs environment,
   working directory, stdin, pipes, masking, streaming, and timeouts — at which
   point it has reimplemented `sh`, worse.

**It was not deleted — it was demoted.** The centralized retry/timeout/logging
argument that originally motivated it is still valid, so it survives as
**Layer 2**: an optional convenience for the subset of call sites that genuinely
are a plain AWS CLI invocation and want a typed result. Optional, adopted per
call site, never a dependency. The rule that it must never branch on AWS service
still applies to it.

## Rejected: auth-only plugin with nothing else

The earlier objection — "auth-only loses centralized retry/timeout/structured
logging unless reimplemented redundantly across Groovy files" — was correct, and
is answered by keeping Layer 2 rather than by forcing everyone through it.

---

# The session-naming convention (unchanged, load-bearing)

```
jk-${JOB_NAME}-${BUILD_NUMBER}
```

produces CloudTrail sessions like `jk-myjob-123` instead of generic
auto-generated names. **Do not change this shape without discussion.** It is the
basis for the Layer 3 IAM trust-policy condition:

```json
"Condition": {
  "StringLike": { "sts:RoleSessionName": "jk-*" }
}
```

Any AssumeRole with a non-conforming session name is denied by AWS itself. This
is the only truly non-bypassable control in the system; nothing inside Jenkins
can be, because it all runs inside the same trust boundary as an unrestricted
shell.

**Why this now matters more than it did at M0.** When the AWS CLI resolves a
profile that has a `role_arn`, it performs the AssumeRole itself and generates
its *own* session name (`botocore-session-<epoch>`) unless `role_session_name`
is pinned in the config file — and even pinned, it is static per profile and can
never carry a job name or build number. So any consumer authenticating via
`--profile` today has **zero build attribution in CloudTrail**, and would be
**denied outright** the day the Layer 3 trust policy is applied. Migrating
consumers to Layer 1 is a prerequisite for Layer 3, not an optional tidy-up.

# Known constraint: chained AssumeRole session duration

EC2 instance role → AssumeRole into a target role is role chaining, which caps
the session at **1 hour regardless of the target role's configured maximum**.
Builds longer than an hour need a credential refresh path. The block scope is
where that will live. Until it exists, a build whose block runs past the hour
will fail on credential expiry — this must be measured against real deployment
job durations before wide adoption.

---

# Configuration reference

```yaml
unclassified:
  ckAws:
    profiles:
      - name: "non_prod"
        roleArn: "arn:aws:iam::123456789012:role/non_prod"
        region: "us-east-1"
      - name: "prod"
        roleArn: "arn:aws:iam::210987654321:role/prod"
        region: "us-east-1"
```

`region` is optional. Profile names are free-form strings chosen by the Jenkins
admin; the plugin attaches no meaning to any particular value.

---

# Migration strategy

Migration is **staged, per-consumer, and reversible at every step**. No stage
requires the next one.

### Stage 1 — Plugin ships Layer 0 + Layer 1 (M6)

No consumer changes. Nothing in production behaves differently. The plugin is
installed and configured but unused.

### Stage 2 — One low-risk consumer adopts Layer 1

A single non-production pipeline wraps its work in `ckAwsWithProfile`. Verify in
CloudTrail that its calls now carry `jk-<job>-<build>`. Rollback is deleting one
line.

### Stage 3 — Deployment library adopts Layer 1 (M7, requires separate approval)

**The one blocking detail:** AWS CLI resolution order is command-line flags →
environment variables → config file. An explicit `--profile prod` **overrides**
the exported credentials. A library that keeps passing `--profile` inside the
block will silently continue using the old agent-config path and the session
name will never appear. Two viable approaches:

- **(a) Drop the flag.** Replace `--profile ${prof}` with a variable the block
  blanks out. Mechanical, greppable, permanent — the correct end state.
- **(b) Ephemeral config file.** The block writes a temporary profile of the
  same name into a workspace-private `AWS_CONFIG_FILE` /
  `AWS_SHARED_CREDENTIALS_FILE`, so `--profile prod` resolves to plugin-issued
  credentials with **zero library edits**. Cost: credential material transiently
  on disk, requiring strict permissions and guaranteed cleanup.

(b) is the better migration mechanism; (a) is the better end state. Doing (b)
first is defensible and keeps the first rollout at zero library risk. This is a
decision for M7, not M6.

### Stage 4 — Terraform pipelines and standalone Jenkinsfiles adopt Layer 1

These are simpler than the deployment library: they consume credentials purely
through the environment already, so wrapping is sufficient with no in-block
changes at all.

### Stage 5 — Layer 2 adopted opportunistically

Individual call sites move to `ckAws.run([...])` where a typed result and
centralized retry are worth it. Never a sweep.

### Stage 6 — RunListener default injection

Only after the explicit path is proven across multiple consumers.

### Stage 7 — Layer 3 IAM trust policy

Only after every consumer of the target roles authenticates through Layer 1.
Applying it earlier denies every unmigrated pipeline.

## Rollback strategy

| Stage | Rollback |
|---|---|
| Layer 1 adoption in a pipeline | Delete the wrapper line; `--profile` still works |
| Plugin version | Reinstall previous `.hpi`; unmigrated consumers unaffected |
| JCasC mapping | Revert the config commit |
| Layer 2 adoption | Per call site, independently |
| Layer 3 | Revert the trust-policy condition |

The agent-side `~/.aws/config` is deliberately **left in place** throughout
migration. It is the fallback path, and removing it is a separate decision taken
only once nothing depends on it.

---

# Organizational context

- CloudKeeper's Platform/DevOps team owns Jenkins, shared Groovy libraries,
  deployment Groovy files, and Jenkins plugins. Application teams do not write
  deployment logic.
- The deployment library (`cln-deployment-scripts`) is `Utilities.groovy`,
  `Build.groovy`, `Deploy.groovy` and 12 `vars/*.groovy` entry points. It
  authenticates entirely through `aws ... --profile ${prof}`, where `prof` is a
  plain string (`envName == 'prod' ? 'prod' : 'non_prod'`) set in 12
  `vars/*.groovy` entry points and threaded through 9 function signatures. It
  performs no explicit STS call anywhere. Measured, not estimated: **13 AWS CLI
  invocations across 4 files** — 12 carrying `--profile`, 1 not (see below).
  - *Correction to earlier versions of this document:* there is no
    `AwsAuth.groovy` and no `Audit.groovy` in that repository. Earlier drafts
    described an explicit `sts assume-role` inside the shared library; that call
    site does not exist.
  - `Utilities.dockerLoginEcr(ecrUrl, regionName, prof)` accepts `prof` and
    **does not use it** — its `aws ecr get-login-password` call carries no
    `--profile` and therefore runs as the ambient instance role. Under Layer 1
    its identity silently becomes the assumed role, which must therefore hold
    `ecr:GetAuthorizationToken`. Verify before rollout.
- `code/dr_sync.py` uses boto3 `Session(profile_name=...)` from environment
  variables — a non-CLI consumer.
- The infrastructure repository (`cln-infra-terraform`) runs Terraform from
  `jenkins/*.groovy` pipelines with **no profile at all**: ambient instance role
  plus an `AWS_REGION` environment variable.
- Region is not a constant across the estate: the deployment library hardcodes
  `us-east-1`, DR sync targets `us-east-2`, Terraform reads `AWS_REGION`. Region
  must always be an input, never an assumption.

---

# Repository strategy

The plugin lives in its own independent repository (`ck-aws-plugin`), currently
on personal GitHub, kept independent of `cln-deployment-scripts` and
`cln-infra-terraform` — no dependency in either direction. That isolation is
deliberate and is reinforced, not weakened, by the layered architecture: the
plugin has no CloudKeeper-specific content, so it has no reason to live inside a
CloudKeeper application repository.

Long-term repository ownership remains an open decision, to be made on
maintainability, ownership, release process, and CK standards. Nothing in this
document favours an option.

---

# Definition of Done — M6

- Block-scoped `ckAwsWithProfile` step exists, is registered, and wraps a body.
- Profile → role ARN (+ optional region) mapping is configurable via JCasC under
  `unclassified.ckAws`, and via the Jenkins global configuration UI.
- Unknown profile names fail closed with a message listing configured profiles.
- The AssumeRole subprocess executes on the agent via the step context's
  `Launcher`, not on the controller.
- Credentials are exported into the block as `AWS_ACCESS_KEY_ID`,
  `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` (+ region variables when
  configured) and are masked in the console.
- Credentials never appear in a step return value or in CPS program state as
  plaintext.
- `AuthCore`, `CliStsAssumeRole`, and `SessionName` are unchanged.
- All pre-existing tests still pass.
- No AWS-service branching and no CloudKeeper- or deployment-specific logic
  anywhere in the plugin.

---

# What NOT to do

- Do not add per-AWS-service logic anywhere.
- Do not read `~/.aws/config` from inside the plugin.
- Do not put CloudKeeper names, ARNs, account IDs, or deployment concepts in the
  source tree.
- Do not make Layer 2 (`ckAws.run`) a required interface, or a dependency of
  Layer 1.
- Do not return credentials from a step to the Pipeline DSL.
- Do not implement the IAM trust-policy enforcement as part of plugin work — it
  is an AWS-side change requiring access this project does not have, and it must
  come last.
- Do not modify the deployment library or the Terraform repository without
  explicit per-change approval.
- Do not swap to the AWS Java SDK without flagging it first.

---

# Working Principles

This project follows an architecture-first development approach. Every milestone
must follow this workflow:

1. Explain the implementation plan.
2. Wait for approval.
3. Implement only the agreed milestone.
4. Keep changes limited to the milestone scope.
5. Run relevant tests.
6. Explain the changes.
7. Stop for review.

Do not continue into the next milestone automatically. Do not redesign previous
decisions unless explicitly requested. Prefer standard Jenkins plugin development
practices over custom abstractions. When uncertain, explain assumptions instead
of making them silently.

Keep auth logic testable without a live Jenkins wherever possible — reserve
`JenkinsRule`-based tests for things that genuinely need a running Jenkins
(extension registration, config persistence, step context, agent launching).

---

# Documentation Maintenance

At the end of every completed implementation session:

1. Update `MEMORY.md` — milestone completed, architectural decisions, validation
   performed, issues encountered, deviations from plan.
2. Review `README.md` — update whenever user-facing behaviour changes.
3. Do **not** modify `CLAUDE.md` automatically.

`CLAUDE.md` is the project's architectural contract and should only be updated
when the architecture changes, new long-term design decisions are made, project
scope changes, or when explicitly requested.

If no architectural decisions changed during a session, leave `CLAUDE.md`
untouched and state that no update was necessary.
