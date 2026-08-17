# ck-aws

A Jenkins plugin that centralizes **AWS identity** for builds. It assumes an AWS
role using a deterministic, build-attributable STS session name
(`jk-<job>-<build>`) and publishes the resulting temporary credentials into a
scoped region of a pipeline as standard AWS environment variables.

It does not run AWS commands, and it contains no organization-specific,
service-specific, or deployment-specific logic. Anything that consumes AWS
credentials — the AWS CLI, boto3, Terraform, Docker — consumes them the way it
always does.

**Status: 232 tests green. 2.1 is what is installed on CK production.**

> **Versioning is `major.minor.patch`** (adopted 2026-08-17):
>
> - **major** — a breaking change to the configuration contract, or to what the
>   plugin guarantees about a build. Removing a form entry is *not* major while the
>   property still loads from existing XML.
> - **minor** — new capability, or a change in *what gets attributed*.
> - **patch** — defect fix, or a UI/doc change with no behaviour change.
>
> **Versions track installations, not builds.** A number must change before an
> artifact is *installed* on a controller, so that "the controller says 2.2.0"
> answers "which 2.2.0" unambiguously. A number that has been installed is spent
> forever; a number only ever built locally is not spent and may be re-taken.
>
> **Numbering.** POC iterations run on the **2.1.x** line (2.1.1, 2.1.2, …) — patches on
> top of what infra already runs, one patch number per POC install. The **infra** install
> claims **2.2.0**, and that number is deliberately never spent on the clone, so "the
> controller says 2.2.0" can only mean the infra release.
>
> Development builds are deliberately **not installable**: a plain `mvn verify` produces
> `2.1.1-SNAPSHOT (private-<hash>-<user>)`. A release needs `mvn -Dchangelist= clean verify`.
>
> | Number | Where it stands |
> |---|---|
> | 2.1 | On **CK production** today, master switch off. The only install that matters |
> | 2.2 — `f5150ba3…` | Test install on the POC clone. The build validated against real jobs — every canary, all 7 agent types, `dev2/fluentd #119`. **Spent** |
> | 2.3 — `bc4d59e1…` | Test install on the POC clone; static ARN form entry removed. **Spent** |
> | 2.4 | Built, never installed, superseded by the review fixes. Not spent |
> | **2.1.1** | Current POC line. Carries every code-review fix |
> | 2.2.0 | **Reserved for the infra install.** Never to be used on the clone |
>
> Every *earlier* artifact calling itself 2.2, 2.3 or 2.4 is void — including
> `b4c94c78…` (no environment invariant), `edde1e04…` (misleading observe-only
> diagnostics) and `4cc0aada…` (no per-node unprofiled resolution). See
> [MEMORY.md](MEMORY.md) for the full list of superseded hashes.

Two unmodified production pipelines ran with Managed Authentication on and were
fully attributed in CloudTrail — a prod ECS deployment (25 events) and a non-prod
one (15+ events), each under its own `jk-<job>-<build>` session. The M12i
incident (a Gradle 403) was **resolved and the plugin exonerated**: the failing
request went to an S3 *static website* endpoint over plain HTTP in a third-party
account, which cannot involve AWS credentials, and the same 403s appear in builds
that succeeded. See [docs/V2_DECISIONS.md](docs/V2_DECISIONS.md).

Pipelines never reference the plugin: with Managed Authentication enabled, an
unmodified `aws --profile non_prod ...` is authenticated and attributed as
`jk-<job>-<build>`. It is **off by default**, and with it off the plugin is
indistinguishable from not being installed. `ckAwsWithProfile` remains supported
as the explicit override.

**New in 2.2 — four defect fixes and observe-only mode**

2.1 shipped a defect that broke real jobs. `dev2/rivon` failed twice the moment it
was added to the rollout scope and passed with it removed — a controlled
experiment that identified the plugin as the cause. A census of 718 production
build logs then showed the exposure was not one job: **~89% of every `sh`
invocation in production had the affected shape**, across 371 distinct jobs.

- **Context shadowing (the rivon defect).** The plugin's `DynamicContext` answered
  unconditionally, which shadowed the `EnvironmentExpander` published by an
  enclosing `withCredentials`. `ContextVariableSet.get` consults every
  `DynamicContext` at the current level *before* recursing to the parent, so a
  non-null answer hides the enclosing level entirely — Nexus credentials expanded
  to empty and Gradle fell through to a public mirror that 403s. The plugin now
  merges with, rather than replaces, whatever is already in context. Note
  `EnvironmentExpander.merge(a, b)` null-checks only its *first* argument, so the
  order and the guard both matter.
- **Workspace anchoring.** The generated file was anchored to the *current*
  directory, so inside `dir('x')` it landed somewhere else. It is now anchored to
  the build's workspace via `node.getWorkspaceFor(...)`, falling back to the
  current path when that is outside the workspace.
- **Stale memo.** The path was memoized for the whole build, so a mid-build
  `cleanWs()` / `deleteDir()` / `git clean -fdx` left every later step exporting
  `AWS_CONFIG_FILE=<deleted file>`. An AWS SDK reads a missing config file as an
  *empty* one, so `--profile x` then fails with "The config profile could not be
  found" — nothing thrown, nothing logged. Existence is now re-checked before the
  memo is reused.
- **Parallel race.** Concurrent `parallel` branches could prepare the same key at
  once. Preparation is now under a per-key lock.
- **Runtime additions-only invariant on the environment.** Before contributing, the
  plugin expands the enclosing environment and the merged one it proposes, and
  compares them. If any variable an enclosing block set would be dropped or
  changed, it contributes nothing and the build keeps its own environment. The
  config file has had this check since M12; the environment had none, and that
  asymmetry is why rivon shipped — the guard caught exceptions, and nothing threw.
  This is the only layer that covers **shapes nobody has written yet**, so it is
  what makes future jobs safe without enumerating them.
- **Unprofiled attribution resolved per node.** *Attribute unprofiled calls as the
  node's OWN instance role* asks each node for its instance role over IMDS when a build
  prepares, instead of using one ARN for the whole controller. A single ARN is only
  correct while every agent shares one role; hand a node a `role_arn` it may not assume
  and its unprofiled `aws` calls **fail** rather than merely going unattributed — the one
  way this feature can break a build, and it would surface first on a node nobody tested.
  Per-node resolution removes that failure mode for agents that do not exist yet. Nodes
  that report no instance role get no `[default]` at all, so they are left exactly as
  they are today. This matters because **~98% of production AWS calls name no profile**.

- **`AWS_ROLE_SESSION_NAME` is exported too.** The generated config names the session for
  every role the *shared config* assumes — the AWS CLI, boto3, Terraform's default
  resolution. It does not cover a **second hop** performed by the tool itself: a Terraform
  provider with its own `assume_role` block assumes a further role and, with no
  `session_name` there, picks the name itself. Every AWS SDK reads
  `AWS_ROLE_SESSION_NAME`, so exporting it names that hop **without editing a single
  `.tf` file, Jenkinsfile or shared library** — which matters, because the plugin's whole
  premise is attribution with no repository changes. *Status: measured — the Terraform AWS provider **ignores it** and generates
  `aws-go-sdk-<nanotime>`. There is no plugin-side fix for that hop; CloudTrail still
  records the caller as `jk-<job>-<build>`, so those calls are traceable with one join
  rather than directly labelled. Affects 3 of 802 jobs.*

  Naming the right role is not enough — AWS must also *permit* the role to assume
  itself. If it does not, writing `role_arn` would turn "not audited" into a build that
  **fails**, on a node nobody tested. So the plugin proves it first: one
  `sts:AssumeRole` probe per node, cached. A refusal means no `[default]` is written and
  that node's unprofiled calls are left working and unattributed. There is no
  configuration in which enabling this can break a build.
- **Observe-only mode.** Prepare, decorate, validate, write and report — and
  export *nothing*. Rivon proved that a guard which only catches exceptions cannot
  catch a contribution that succeeds and still removes something. Observe-only is
  the control that closes that gap: scope can be widened to every job under real
  traffic with zero possibility of affecting one, which is the only honest way to
  survey the 637 jobs whose definitions live in SCM and cannot be read ahead of
  time. The escalation order is now **structural invariant → per-job exclude →
  observe-only → master switch last**.

**New in 2.0**

- **Freestyle builds are covered.** Previously only Pipeline builds were, which
  left production S3 and Route 53 jobs unattributed. Both build types now share
  one implementation, so they cannot drift.
- **Calls naming a profile that assumes no role are attributed**, not just calls
  naming no profile at all — otherwise a caller could opt out of the audit by
  naming such a profile.

- **Calls that name no profile can be attributed too.** Set *Attribute unprofiled
  calls as* to the agent's own instance role and the generated `[default]`
  assumes it under the build's session name. Self-assume keeps the principal ARN
  unchanged, so resource-based policies that grant to that role keep working.
- **The generated file is verified before it is used.** Fail-open catches
  exceptions; it cannot catch "produced a wrong file successfully". The output is
  now checked against the input — additions only — and anything unexpected means
  contributing nothing.
- **Diagnostics is a checkbox**, not a system property: no restart to investigate.
- **An exclude pattern**, so removing one job from scope during an incident does
  not mean writing a negative-lookahead over every job on the controller.
- **Node-label scoping**, for agents that differ or make no AWS calls at all.
- **Toggling the switch never disturbs a running build** — builds already under
  way finish under their existing session. Verified in production.

> **M12 — Universal Attribution** changes what the plugin writes. Today it
> *replaces* the agent's AWS configuration with one built from the Jenkins
> mapping, which means every profile a pipeline uses must be configured in
> Jenkins, and pipelines that authenticate through IMDS stay unattributed. M12
> instead *copies* the agent's own configuration and adds one line —
> `role_session_name = jk-<job>-<build>` — to every profile that assumes a role,
> so nothing has to be enumerated. See [CLAUDE.md](CLAUDE.md), "M12 — Universal
> Attribution", including the proof of what a plugin can and cannot attribute.

See [CLAUDE.md](CLAUDE.md) for the architecture,
[docs/MANAGED_AUTHENTICATION_DESIGN.md](docs/MANAGED_AUTHENTICATION_DESIGN.md)
for the M11 design, [MEMORY.md](MEMORY.md) for session-by-session history, and
[docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md) for a codebase walkthrough.

## Requirements

| | Version | Why |
|---|---|---|
| JDK | 17+ (21 recommended) | Parent POM enforces `[17,)` |
| Maven | 3.9.6+ | Enforced by the plugin parent POM |
| Jenkins core | 2.479.2 | Matches CloudKeeper's production Jenkins version |

At run time, on each **agent** that uses the plugin:

- The `aws` CLI must be on the agent's `PATH`.
- A base identity the agent can use to call `sts:AssumeRole` (instance role,
  ambient environment, etc.) must be resolvable. The plugin does **not** read
  `~/.aws/config`; the AWS CLI resolves the base identity itself.
- The target role's trust policy must permit that base identity.

## Configuration

Profile names map to role ARNs in Jenkins-owned configuration — never in
pipeline code, and never in `~/.aws/config`.

**Via JCasC** (`unclassified.ckAws`):

```yaml
unclassified:
  ckAws:
    managedAuthentication: true            # off by default
    jobNamePattern: "uat/.*"               # optional; blank means EVERY job
    jobNameExcludePattern: ""              # optional; wins over the include pattern
    attributeUnprofiledAsNodeRole: true    # resolve each node's OWN role over IMDS
    observeOnly: false
    diagnostics: false
    credentialSource: "Ec2InstanceMetadata"
    profiles:                              # optional overrides only — see below
      - name: "dr"
        mode: "AssumeRole"
        roleArn: "arn:aws:iam::123456789012:role/dr"
        region: "us-east-2"
```

| Setting | Default | Purpose |
|---|---|---|
| `managedAuthentication` | `false` | Master switch. No restart to change, and changing it never disturbs a running build |
| `jobNamePattern` | blank = **all jobs** | Phased rollout. Full-string match against a job's *full* name. An unparseable pattern matches nothing, so a typo narrows rather than widens |
| `jobNameExcludePattern` | blank = exclude nothing | Evaluated after include and wins. The incident switch. An unparseable pattern excludes nothing |
| `attributeUnprofiledAsNodeRole` | `false` | Resolves each node's own instance role over IMDS and verifies it can assume itself. **This is what attributes the ~98% of calls that name no profile** |
| `observeOnly` | `false` | Prepares and logs everything, exports nothing. See below |
| `diagnostics` | `false` | Prints what was found and changed. Non-sensitive; no restart |
| `credentialSource` | `Ec2InstanceMetadata` | How agents obtain their base identity, for profiles the plugin *writes*. Also `EcsContainer`, `Environment` |
| `profiles` | empty | Appended **only** for profiles the agent does not define, and the configuration source for the `ckAwsWithProfile` step |

### Two properties are no longer in the form

`unprofiledRoleArn` and `nodeLabelPattern` still load from existing XML but have no UI entry.

`unprofiledRoleArn` was a single static ARN for the whole controller. That is correct only while
every agent shares one instance role, and a wrong value does not merely go unattributed — it makes
every bare `aws` call **fail** on whichever node differs. It is superseded by
`attributeUnprofiledAsNodeRole`, which resolves each node's real role and proves the assume
succeeds first. `nodeLabelPattern` was a second scoping axis that no run ever needed; the cases it
was meant for are handled structurally. **Leave both unset.**

### What observe-only actually does

It runs the **whole** contribution path and withholds only the final step. The
node's config is read, decorated, validated, and **written** to
`<workspace>@tmp/ck-aws/config`; the console records what would have happened. What
does *not* happen is the export — `AWS_CONFIG_FILE` and `CK_AWS_SESSION_NAME` are
never set, so nothing reads the file and no build behaviour changes.

The record exists **only in each build's console log**. There is no aggregation and
no central report, so observe-only tells you what the plugin *would* do, one build
at a time. Measuring what actually reached AWS is a CloudTrail question, not a
plugin one.

**You do not enumerate profiles here.** The agent's own `~/.aws/config` is the
source of truth: it is copied and decorated, so a profile added to an agent
tomorrow is attributed on its next build with no Jenkins configuration at all.
The `profiles` list is for the exceptions — a profile you want to add to agents
that do not define it. The agent always wins.

### Why the unprofiled role must be the agent's own

Calls that name no profile fall through to the agent's base identity, whose
session name is assigned by the platform and cannot be changed — so they are
unattributable unless a role is assumed on their behalf. Assuming a *different*
role would change the principal ARN, and bucket, key and repository policies
grant access **by principal ARN**: a new role with identical permissions is still
denied by every policy naming the original. Self-assume keeps the ARN, so every
such grant keeps working, and it needs no IAM change if the role can already
assume itself.

**Via the UI:** Manage Jenkins → System → **CK AWS**.

`region` is optional. Profile names are free-form; the plugin attaches no meaning
to any particular value.

## Usage

### `ckAwsWithProfile` — the block-scoped authentication wrapper

```groovy
node {
    ckAwsWithProfile('non_prod') {
        sh 'aws sts get-caller-identity'
        sh 'aws ecs update-service --cluster my-cluster --service my-svc ...'
        sh 'terraform apply -auto-approve'
        sh 'python3 code/dr_sync.py'
    }
}
```

Everything inside the block runs as the assumed role. Nothing inside the block
needs to know how authentication happened.

Exported into the block only:

| Variable | Notes |
|---|---|
| `AWS_ACCESS_KEY_ID` | masked in the console |
| `AWS_SECRET_ACCESS_KEY` | masked in the console |
| `AWS_SESSION_TOKEN` | masked in the console |
| `AWS_REGION` | only when the profile or the step specifies a region |
| `AWS_DEFAULT_REGION` | same |
| `CK_AWS_SESSION_NAME` | the `jk-<job>-<build>` session name; not a secret |

The build log shows:

```
[ck-aws] Assuming role arn:aws:iam::123456789012:role/non_prod as session jk-myjob-123
[ck-aws] Credentials available as session jk-myjob-123 (expires 2026-08-04T12:00:00Z)
...
[ck-aws] Released credentials for session jk-myjob-123
```

CloudTrail in the target account records `jk-<job>-<build>` on the `AssumeRole`
event **and on every API call made inside the block** — the point of the plugin.

#### Parameters

| Parameter | Required | Notes |
|---|---|---|
| `profile` | yes* | Name of a profile configured in JCasC. Unknown names fail the build with a list of configured profiles. |
| `roleArn` | no* | Escape hatch: assume this ARN directly, bypassing the mapping. Mutually exclusive with `profile`; exactly one of the two is required. |
| `region` | no | Overrides the region from the profile mapping. |

\* Exactly one of `profile` or `roleArn` must be supplied.

#### Requires a `node` block

The AssumeRole subprocess runs **on the agent**, using the agent's base identity
— not on the controller. The step therefore requires `Launcher` and `FilePath`
context. Outside a `node { }` block it fails closed with an actionable message.

#### Interaction with `--profile`

AWS CLI resolution order is command-line flags → environment variables → config
file. An explicit `--profile foo` inside the block **overrides** the exported
credentials and silently falls back to the agent's `~/.aws/config`. Commands
inside the block must not pass `--profile`.

## Managed Authentication (M11)

**Implemented and validated locally against real AWS. Off by default.**

Today `ckAwsWithProfile` is opt-in: a pipeline that forgets it silently keeps
using the agent's own `~/.aws/config`, with no build attribution in CloudTrail.
Managed Authentication removes the need to remember anything.

Enable it under **Manage Jenkins → System → CK AWS**. No restart is needed to
enable or disable it.

With Managed Authentication enabled, Jenkins writes a **per-build AWS config
file** into the build's private temp directory (`<workspace>@tmp/ck-aws/`) and
points every step at it:

```ini
# regenerated every build; removed when the build finishes
[profile non_prod]                             # mode: AssumeRole
credential_process = /bin/sh /…@tmp/ck-aws/cred-non_prod.sh
region             = us-east-1

[profile ops]                                  # mode: InstanceProfile
region             = us-east-1                 # no credential keys: the agent's own identity
```

Each profile declares an **authentication mode** in Jenkins:

| Mode | Role ARN | Behaviour | CloudTrail |
|---|---|---|---|
| `AssumeRole` | required | assumes the role once per build | `jk-<job>-<build>` |
| `InstanceProfile` | not used | falls through to the agent's identity | as today, unattributed |

so that an **unmodified** pipeline —

```groovy
node {
    sh 'aws ecs update-service --profile non_prod ...'
    sh 'terraform apply -auto-approve'
    sh 'python3 code/dr_sync.py'
}
```

— is authenticated and attributed as `jk-<job>-<build>`, with no wrapper, no
plugin step, and nothing to add to any repository. The profile names a pipeline
already uses keep working verbatim; only the resolver changes.

Properties of the design:

- **No credentials anywhere.** The generated file holds a role ARN, a session name
  and a region. Nothing enters the build environment, the console or CPS program
  state, so there is nothing to mask.
- **One AssumeRole per build per profile.** The first AWS consumer in the build
  pays for it; every later one — any `aws` command, boto3 session, Terraform run
  or `docker login` — reuses the same STS session, so CloudTrail shows one
  `jk-<job>-<build>` session throughout.
- **Lazy.** Only profiles a build actually uses are ever assumed. Configuring
  `prod` costs a build that never touches it nothing.
- **Refresh is automatic**, so the 1-hour role-chaining limit below stops
  applying: the cached session is renewed before it expires, under the same
  session name.
- **Off by default, and off means invisible.** With the switch off the plugin
  exports nothing, writes nothing and calls nothing — a Jenkins with it installed
  behaves identically to one without it. Same for "on, but no profiles
  configured", and "on, but the job is outside the rollout pattern".
- **It attributes, it does not gate.** The entire contribution path is wrapped in a
  guard that catches `Throwable` and re-throws only `InterruptedException`, so no
  plugin failure — missing config, unreadable file, unexpected exception, even a
  classloading error — can fail a build. The build simply authenticates as it
  would have without the plugin. Losing attribution is acceptable; failing a
  deployment is not.
- **Rollback is unticking a checkbox** — no restart.
- **Works inside containers** started by `docker.image().inside { }`,
  Declarative `agent { docker }` and Kubernetes agents, because the build's temp
  directory is mounted with the workspace. A hand-rolled `docker run` is not
  covered — but behaves exactly as it does today.
- **Bounded disk footprint.** The path is stable per workspace and overwritten
  each build, so storage is `workspaces × ~1 KB`, not `builds × 1 KB`. Cleanup is
  hygiene, not a correctness requirement.
- `ckAwsWithProfile` remains supported as the explicit override, and the two
  compose without a double AssumeRole.

Known limitations, unsupported scenarios and the full lifecycle/failure analysis
are in [docs/MANAGED_AUTHENTICATION_DESIGN.md](docs/MANAGED_AUTHENTICATION_DESIGN.md).
The most important one: **every profile name a pipeline passes must exist in the
Jenkins configuration**, or that build fails with
`The config profile (X) could not be found`.

### `ckAwsAssumeRole` — deprecated

```groovy
def session = ckAwsAssumeRole(roleArn: 'arn:aws:iam::123456789012:role/non_prod')
```

The original M2/M3 validation step. It performs the AssumeRole, returns the
session name, and **discards the credentials** — nothing downstream can use them.
It also runs on the **controller JVM**, which means it authenticates with the
controller's identity rather than the agent's.

Retained only because it is the step the live-AWS and CloudTrail validation was
performed with. Use `ckAwsWithProfile` for anything real. Scheduled for removal.

## Building

```bash
mvn verify          # compile, run tests, package target/ck-aws.hpi
```

## Running locally

```bash
mvn hpi:run -Dport=8081
```

Then browse to <http://localhost:8081/jenkins/>.

> **Note on the port:** `hpi:run` defaults to 8080. If a Jenkins service is
> already running there, startup fails with `java.net.BindException`. Pass
> `-Dport=8081` rather than stopping the other instance.

## Verifying the plugin loaded

Either check **Manage Jenkins → Plugins → Installed** for `CK AWS Plugin`, or:

```bash
curl -s "http://localhost:8081/jenkins/pluginManager/api/json?depth=1" \
  | jq '.plugins[] | select(.shortName=="ck-aws")'
```

Expect `"active": true` and `"requiredCoreVersion": "2.479.2"`. The same
assertion runs headlessly during `mvn verify` — see `PluginLoadsTest`.

## Installing the built plugin

```bash
mvn clean verify        # produces target/ck-aws.hpi
```

Install `target/ck-aws.hpi` via **Manage Jenkins → Plugins → Advanced settings →
Deploy Plugin**, then restart Jenkins. The target Jenkins must be 2.479.2 or
newer.

## Known limitations

Of what is **implemented today** (`ckAwsWithProfile`):

- **Opt-in, therefore forgettable.** A pipeline that does not wrap its work keeps
  using the agent's `~/.aws/config` and produces no build attribution. This is the
  limitation M11 exists to remove.
- **`--profile` beats the block.** An explicit `--profile foo` inside the block
  overrides the exported credentials — measured behaviour, see the note above.
- **No credential refresh.** EC2 instance role → target role is role chaining,
  which caps the session at **1 hour** regardless of the role's configured
  maximum. A block that runs longer than an hour will fail on credential expiry.
  (M11 resolves this; the managed path refreshes natively.)
- **Credentials are present in the build environment** for the life of the block,
  and are held by the step's `EnvironmentExpander`, which is serialized into CPS
  program state. They are masked in the console, but `hudson.util.Secret` is not
  encrypted under plain Java serialization — so treat "masked" as "not printed",
  not as "not stored". M11 removes the exposure entirely.
- **No retry or timeout** around the AssumeRole subprocess.
- **No generic AWS CLI executor** (`ckAws.run([...])`). Deliberately optional and
  not yet implemented — see CLAUDE.md, Layer 2.
- **No IAM trust-policy enforcement.** That is an AWS-side change, and it must
  come after every consumer has migrated.

Of the **implemented** M11 managed path — note that M12 removes the first of
these, which is why it exists — see
[docs/MANAGED_AUTHENTICATION_DESIGN.md](docs/MANAGED_AUTHENTICATION_DESIGN.md)
§18 — unconfigured profile names fail the build, hand-rolled `docker run` and
freestyle jobs are not covered, unprofiled calls stay unattributed until a default
profile is configured, profile names cannot contain whitespace, and session names
can collide in very deep multibranch hierarchies.

## System properties

| Property | Effect |
|---|---|
| `io.github.rads4.ckaws.awsExecutable` | Overrides the `aws` executable used for the AssumeRole call. A test hook. Defaults to `aws`. |

## Live AWS validation

The AssumeRole flow was validated against real AWS: AssumeRole succeeded and
`sts get-caller-identity` returned an ARN ending in
`assumed-role/<role>/jk-<job>-<build>`, confirming the session-name convention
survives real STS and is build-scoped. Exactly two read-only AWS APIs were
exercised: `sts:AssumeRole` and `sts:GetCallerIdentity`. See
[MEMORY.md](MEMORY.md) (Session 5) for the evidence.

## Project layout

```
pom.xml                                          plugin POM (hpi packaging)
src/main/java/io/github/rads4/ckaws/auth/        auth core (Jenkins- and CLI-agnostic)
src/main/java/io/github/rads4/ckaws/auth/cli/    the only class that knows `sts assume-role`
src/main/java/io/github/rads4/ckaws/config/      JCasC-backed profile -> role ARN mapping
src/main/java/io/github/rads4/ckaws/exec/        generic process execution (no AWS awareness)
src/main/java/io/github/rads4/ckaws/steps/       pipeline steps
src/main/resources/index.jelly                   description shown in Manage Plugins
src/test/java/io/github/rads4/ckaws/             tests
docs/DEVELOPER_GUIDE.md                          codebase walkthrough for new maintainers
docs/DEPLOYMENT_LIBRARY_INTEGRATION_PLAN.md      M7 proposal (executed; superseded by M11)
docs/MANAGED_AUTHENTICATION_DESIGN.md            M11 design — Managed Authentication, zero-repo-change
```

## Notes on deviations from the archetype

Generated from `io.jenkins.archetypes:empty-plugin:1.37`, with three deliberate
changes:

1. **Jenkins baseline lowered to 2.479.2** (archetype default: 2.528.3) to match
   CloudKeeper's actual Jenkins.
2. **Parent POM raised to `6.2211.v27f680c93c53`** (archetype pin:
   `6.2138.v03274d462c13`).
3. **`.mvn/extensions.xml` and `.mvn/maven.config` removed** — they configure
   update-center publishing from `jenkinsci`-hosted repos, which is out of scope.
