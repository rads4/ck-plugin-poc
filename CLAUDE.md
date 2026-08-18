# Project: CK AWS Plugin

## What this is

A Jenkins plugin that centralizes **AWS identity** for Jenkins builds. It decides
which AWS role a build is allowed to be, under a deterministic,
build-attributable STS session name (`jk-<job>-<build>`), and delivers that
decision to the build through standard AWS configuration — so that every AWS tool
the build already runs picks it up unchanged.

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
| M7 — deployment-library integration (`cln-deployment-scripts` @ `ck-aws-plugin`) | Complete |
| M7v — validation: local, STS, CloudTrail, Infra Jenkins, Backend UAT deployment | Complete |
| M8 — optional `ckAws.run([...])` convenience executor | Planned, optional |
| ~~M9 — RunListener default injection~~ | **Superseded by M11** |
| M10 — IAM trust-policy enforcement | Future, outside Jenkins |
| M11 — Managed Authentication (generated config from the Jenkins mapping) | Implemented and locally validated — **insufficient for the M12 requirement**; retained as the override layer |
| M12 — Managed Authentication: decorate the node's own configuration | Implemented, validated in production on two unmodified pipelines |
| ~~M12i — production incident investigation (Gradle 403)~~ | **Closed: the plugin was not the cause.** See below |
| v2.0 — unprofiled attribution, output verification, runtime controls | Superseded by 2.1 before wide rollout |
| v2.1 — Freestyle coverage, trailing-blank fix, no-role profiles | Implemented, 201 tests — **this is what is installed on CK production** |
| **v2.2 — context shadowing, workspace anchoring, stale memo, parallel race, observe-only, additions-only environment invariant, per-node unprofiled attribution** | Implemented, 220 tests, `ck-aws 2.2` (`sha256 f5150ba3…`). Installed and validated on the POC clone. Superseded by 2.3 |
| v2.3 — static unprofiled ARN removed from the form | Implemented. **Installed on the POC clone, so the number is spent** |
| **Current (unnumbered) — node-label entry removed, plus six code-review fixes** | **Implemented, 228 tests. Builds as `2.2.0-SNAPSHOT`; the release number is claimed at install time** |

**Versioning: `major.minor.patch`, and versions track INSTALLATIONS, not builds.**

- **major** — breaking change to the configuration contract or to what the plugin guarantees
  about a build. Removing a form entry is *not* major while the property still loads from XML.
- **minor** — new capability, or a change in what gets attributed.
- **patch** — defect fix, or a UI/doc change with no behaviour change.

The number must change before an artifact is *installed* on a controller, so that "the
controller says 2.2.0" answers "which 2.2.0" unambiguously. A number that has been installed is
**spent forever** and must never be reused. A number only ever built locally is **not** spent.

**Numbering.** POC iterations run on the **2.1.x** line — 2.1.1, 2.1.2, … — patches on top of
what infra already runs, one number per POC install. The **infra** install claims **2.2.0**, and
that number is never spent on the clone, so "the controller says 2.2.0" can only mean the infra
release. A plain `mvn verify` yields `2.1.1-SNAPSHOT (private-<hash>-<user>)`, deliberately not
installable; a release needs `mvn -Dchangelist= clean verify`.

Applying that rule, as of 2026-08-17:

| Number | Status |
|---|---|
| **2.1** | Installed on **CK production**. Master switch off. Spent |
| **2.2** (`f5150ba3…`) | Test install on the POC clone. The last build validated against real jobs. Spent |
| **2.3** (`bc4d59e1…`) | Test install on the POC clone. Spent |
| 2.4 | Built, never installed, superseded by the code-review fixes. **Not** spent |
| **2.1.1** | Current POC line, carrying every code-review fix |
| 2.2.0 | **Reserved for the infra install** — never to be used on the clone |

Several *earlier* artifacts also called themselves 2.2, 2.3 or 2.4 during the August 2026 defect
work and their hashes circulated. **All of those are void** — the first still contained the
`DynamicContext` shadowing defect. Superseded hashes are listed in MEMORY.md, Session 23.

Build the release at install time with `mvn -Dchangelist= clean verify` and record its hash then.

This rule replaces the older "raise `<revision>` before any artifact leaves the build machine",
which produced a version inflation of three unused numbers. The problem it was written for is
still real — during the v2.0 rollout three different artifacts all reported `Plugin-Version: 2.0`,
making "the controller says 2.0" meaningless — but the fix is to pin the number to the
*installation*, not to every `mvn package`.

---

# ⚠️ PRE-INSTALL CHECKLIST — read before touching infra Jenkins

Infra Jenkins is installed to **exactly once**. Work through this first; every item
below is something that was learned the hard way, not a precaution.

### 1. Install the binary that was actually tested — do not rebuild

`.hpi` jars embed build timestamps, so **`mvn clean verify` on unchanged source
produces a different `sha256` every time.** Build releases with
`mvn -Dchangelist= clean verify`, or the manifest says `2.3-SNAPSHOT (private-…)`.

**There is no current release artifact.** Development builds are `2.2.0-SNAPSHOT` and are not
installable by design. Build the release only when you install it, with
`mvn -Dchangelist= clean verify`, and record its sha256 here at that moment.

`poc-jenkins-2` currently runs **2.3** (`bc4d59e1…`) at
`/var/lib/jenkins/plugins/ck-aws.jpi` — note `.jpi`, Jenkins renames what it installs.
That build predates the six code-review fixes, so **the clone is not running current code**.

The form changes (two entries removed) carry no behaviour change, but the six code-review
fixes that followed them do — and none of it has been re-validated against a real job. The 2.2 binary that *was* (`f5150ba3…`,
every canary, all 7 agent types, `dev2/fluentd #119`) is preserved on the instance at
`/var/lib/poc-artifacts/ck-aws-2.2-VALIDATED-f5150ba3.jpi`. **Keep that until 2.3 has
its own real-job evidence**, and retrieve it before the instance is terminated.
Never run `mvn clean` while a validated artifact is the only copy.

### 2. Settings to apply at install time

Eight fields. If you see *Attribute unprofiled calls as* (a text box) or *Apply on nodes
labelled*, you are running an older build.

| Field | Value | Why |
|---|---|---|
| *Managed authentication* | **off** (the default) | Install and restart with it off; turn on afterwards without a restart. **Observe-only does nothing until this is on** — the master switch is checked first on both the Pipeline and Freestyle paths |
| *Apply to jobs matching* | blank | Observe-only makes full scope safe |
| *Except jobs matching* | blank | Reserved as the incident switch |
| *Attribute unprofiled calls as the node's own instance role* | **ticked** | This is what audits ~98% |
| *AWS profiles* | empty | Never once used |
| *Observe only* | **ticked — now the shipped default** | So that the moment someone turns the master switch on, the safe mode is already selected rather than every in-scope build changing at once. Enforcing is then a second, deliberate click |
| *Diagnostics* | ticked | Turn off once the rollout is settled |

### 3. Rollout order

Install with the switch **off** → restart (the one restart) → switch **on** with
observe-only → read a day of console evidence → untick observe-only.

### 4. Accept these three known limits before starting

- **3 of 802 jobs** (`cln-app-terraform-pipeline`, `ck-analytics-app-services-terraform`,
  `ck-ecs-terraform`) have provider-level `assume_role`; their post-hop calls carry
  `aws-go-sdk-<nanotime>` and are traceable only transitively today. **A fix is proven** — a
  Terraform `*_override.tf` written at build time, no repo change; see MEMORY.md addendum 6.
- A node whose role AWS will not let self-assume stays **unattributed but working**.
- **Two job types were never run under 2.2**: a Freestyle job that really calls AWS, and
  an inline Pipeline that really calls AWS. Both code paths are test-locked and the
  Freestyle path is additive (it cannot shadow), but neither has live evidence.

### 5. Set up gap detection after the install

Log recorder on `io.github.rads4.ckaws` at WARNING, plus the CloudTrail session-name
buckets. See *Detecting unaudited calls automatically* below. Deferred by decision, but
without it nothing reports centrally.

---

# v2.0 (2026-08-07)

## M12i is closed: the plugin was not the cause

Three independent grounds, from reading the builds directly:

1. **The failing request cannot involve AWS credentials.** It targets an S3
   *static website* endpoint over plain HTTP, in a third-party account. Website
   endpoints serve anonymously and reject SigV4, and Gradle applies AWS
   authentication only to `s3://` URLs. No `s3://` repository exists in the build.
2. **The same 403s appear in builds that succeeded**, before and after, on the
   same agent.
3. **A later build failed identically with the plugin not involved at all** — no
   `[ck-aws]` lines in its log — needing a *different* artifact version.

The "flag OFF → succeeds" observation was **confounded**: the succeeding build
resolved from the Gradle cache and never exercised the failing path.

**The method lesson, which is why the fixtures below exist.** The earlier
investigation reproduced against an agent shape that does not occur in the fleet
— no `[default]`, two profiles — and closed the leading hypothesis on that basis.
The measured shape was `[default]` plus six profiles, one assuming no role. *A
negative result against the wrong shape is not a negative result.*
`ProductionShapeFixturesTest` pins both real shapes so this cannot recur.

## Attributing the unprofiled path

Calls naming no profile fall through to the agent's base identity, whose session
name the platform assigns and no caller can influence. Per the proof above, only
an AssumeRole can name a session — so attribution requires assuming a role.

**The decisive measurement: the instance role can already assume itself.** Its
trust policy delegates to the account root and an identity policy permits it, so
the generated `[default]` becomes an ordinary assume-role profile with **no IAM
change at all**:

```ini
[default]
role_arn          = <the agent's own instance role>
credential_source = Ec2InstanceMetadata
role_session_name = jk-<job>-<build>
```

**Self-assume, not a new role — and this is the load-bearing decision.**
Resource-based policies grant access *by principal ARN*. Sampling three buckets
found two granting to the instance role by name, one of them cross-account. A
dedicated audit role with identical *identity* policies would still be denied by
every such policy, and fixing that means discovering and amending resource
policies across every account — unbounded, and never provably complete.
Self-assume keeps the ARN, so every existing grant keeps working.

The one-hour role-chaining cap needs no special machinery: the SDK re-assumes on
expiry, as it already does for every cross-account profile, evidenced by a
74-minute production build running on a chained profile.

## Rejected, so they are not re-proposed

**A dedicated audit role.** Principal-ARN mismatch, above.

**`sts:SourceIdentity`.** It propagates through role chaining and is immutable,
which is exactly what would make a bypass traceable — but **every downstream role
in the chain must allow `sts:SetSourceIdentity` or the assume fails**. Several
jobs already call `aws sts assume-role` directly from the unprofiled identity, so
enabling it would break precisely those jobs. Doing it properly means enumerating
every assumable role and amending each trust policy first: its own project.

**`credential_process` for the unprofiled path, and Layer C.** Both were proposed
to defeat the one-hour cap and to set source identity. Source identity is
deferred and the cap is handled natively, so neither is needed — and both add
machinery to a component whose value depends on being simple enough to debug.

**Job-name → profile mapping for `[default]` (the original Layer B).** Rejected as
*dangerous*, not merely unnecessary. It would map e.g. `uat/*` to a non-prod
profile, but unprofiled calls in those jobs legitimately reach the ops account —
ECR lives there — so remapping would break image pushes. The correct rule is
**identity-preserving and universal**: one rule, every job, no matching. That also
delivers the actual requirement, since a pipeline created tomorrow is attributed
on its first build with nothing to register.

## Verifying the output before it is used

Fail-open catches *exceptions*. It cannot catch the failure that matters most:
producing a file successfully that is subtly wrong. Exporting such a file would
fail every AWS call in the build, with nothing thrown and the guard never firing.

`AwsConfigOverlay.validate` therefore checks the transform's own contract —
**additions only**: every original line still present and in order, every section
surviving. A violation means contributing nothing, the same outcome as any other
failure. This is the one genuine safety gap v2.0 closes.

## Controls that exist so this is the last upgrade

A plugin install needs a restart; configuration does not. Anything that will ever
need to change must therefore be configuration:

| Was | Now | Why it mattered |
|---|---|---|
| `-Dio.github.rads4.ckaws.diagnostics` | checkbox | A system property needs a restart, so the one switch needed during an incident was the one that could not be thrown — and the workaround was shipping a diagnostic build, which happened |
| include pattern only | include **and exclude** | Removing one job from scope otherwise means a negative-lookahead over every job |
| — | node-label scoping | One agent carries a different configuration; another makes no AWS calls at all |

**Include and exclude fail in opposite directions, deliberately.** An unparseable
include matches nothing (never widens); an unparseable exclude excludes nothing
(never silently disables attribution). Both narrow the blast radius of a typo,
in the direction appropriate to each.

Session names now truncate the job name's **tail**, not its head: in a folder
hierarchy the leading segments are shared and the trailing segment is the job
itself, so truncating from the front would merge two jobs onto one session name.

## Freestyle builds

{@code ManagedAwsContext} hooks Pipeline's step machinery, and nothing else. A
Freestyle build never passes through it, so it made AWS calls that no session
name identified. On this controller that is not a rounding error: of 775
buildable jobs, **35 are Freestyle**, and they include production S3 uploads and
Route 53 backup and restore.

**`EnvironmentContributor`, not `RunListener#setUpEnvironment`.** The listener
hook is the one that looks right, and it is wrong: it runs *before* the workspace
lease is acquired, so `build.getWorkspace()` is still null and there is nowhere to
write the generated file. Measured, not reasoned — the first implementation used
it, contributed nothing, and did so **silently**, because fail-open means a build
that gets nothing still succeeds. Only an end-to-end test with a real shell step
caught it. An environment contributor runs when a build computes its environment,
by which time the workspace exists.

Two consequences shaped the implementation:

- **It fires for every build type, including Pipeline**, so it handles
  `AbstractBuild` alone and leaves Pipeline to `ManagedAwsContext`. Both
  contributing would prepare the same build twice.
- **It is called repeatedly** — every time a build computes its environment — so
  preparation is memoized by run and workspace in
  `ManagedAwsContext#prepareOnce`. Without that the file would be rewritten on
  every query.

Both paths share `prepareOnce`: the decoration, the safety check, the file, the
memo and the cleanup record are one implementation. Only the wrapper differs —
`EnvironmentExpander` for Pipeline, `EnvVars` for Freestyle. Two implementations
of one behaviour would drift, and the one that drifted would be the one nobody
tested.

`AbstractBuild` also covers Matrix and Maven jobs, so the two extension points
together reach every standard Jenkins build type.

## Coverage, measured on the real controller

| Root element | Count | Reached by |
|---|---|---|
| `flow-definition` (Pipeline, inline or SCM) | 740 | `ManagedAwsContext` |
| `project` (Freestyle) | 35 | `ManagedAwsFreestyleEnvironment` |
| `Folder` | 27 | n/a — folders do not build |

Where a Pipeline's Groovy comes from is irrelevant: inline, `Jenkinsfile` from
SCM, multibranch and shared-library steps all take the same path. So is the node
— the plugin reads whichever agent the build lands on and decorates that agent's
own configuration.

What still is not reached: a build that calls `aws sts assume-role` itself, a
client pinning a credential provider in code, `docker run` with nothing mounted,
and controller-JVM calls made by other plugins.

---

# M12 — Universal Attribution (decided 2026-08-06)

**Implemented 2026-08-06. This supersedes M11's *content* while keeping its
*mechanism*.**

**The rule reversal is now decided:** the plugin reads the executing node's own
AWS configuration. It does not decide identity — it decorates the decision the
node already made. The old rule ("never read `~/.aws/config`") existed to keep the
identity decision Jenkins-owned; the requirement now is the opposite, and the
plugin is explicitly a decorator, not a provider.

## The requirement

Every AWS API call originating from Jenkins must become attributable in CloudTrail
to the build — AWS CLI, boto3, Terraform, Docker ECR login, Java/Go/Python SDKs,
shell scripts, shared libraries, existing repositories and repositories that do
not exist yet. **The only intended behavioural difference is the session name.**

Explicitly out of scope as a solution: enumerating or configuring the
authentication path of each repository. Some pipelines use profiles, some use
IMDS, some use Terraform, some use raw SDKs. Whatever a pipeline does today must
keep working, untouched, and simply become attributed.

## Is this achievable with a Jenkins plugin alone? No — and precisely why

Three facts, verified against the STS service model and the botocore provider
chain, and they chain:

1. **Only three AWS operations let a caller name a session:** `AssumeRole` and
   `AssumeRoleWithWebIdentity` (`RoleSessionName`), and `GetFederationToken`
   (`Name`, which requires IAM *user* credentials and so is unavailable to an
   instance role). `GetSessionToken` has no name parameter. **Attribution is only
   ever a by-product of assuming a role.**
2. **An instance-role session's name is assigned by EC2 and is immutable** —
   `assumed-role/<role>/i-<instance-id>`. There is no request in which a caller
   could influence it, because IMDS simply serves the credentials.
3. **Therefore a build authenticating via IMDS cannot be attributed unless
   something assumes a role on its behalf, which requires a trust policy that
   permits it.** Trust policies live in IAM. A plugin cannot create one.

The limitation is **AWS (STS + EC2)**. It is not Jenkins, not the SDKs, not
Terraform, not Docker.

| Authentication style today | Attributable, zero repo change? | Cost |
|---|---|---|
| `--profile X` that assumes a role | **Yes** | nothing |
| boto3 `profile_name=X` | **Yes** | nothing |
| Terraform with a profile or `AWS_PROFILE` | **Yes** | nothing |
| Java / Go / .NET / Ruby SDK via shared config | **Yes** | nothing |
| `aws ecr get-login-password \| docker login` | **Yes** | nothing |
| **Raw IMDS — no profile, no assume** | **Yes, but** | **one IAM trust-policy edit per account** |
| Client pinning a credential provider in code | **No** | unreachable |
| Process in a container with nothing mounted | **No** | unreachable |
| Controller-side AWS calls by other plugins | **No** | outside the build |

## Why M11 as implemented does not satisfy this

- **It requires enumeration.** It overrides `AWS_CONFIG_FILE` with a file built
  *only* from the Jenkins mapping, so any profile name not configured in Jenkins
  fails the build with `The config profile (X) could not be found`. Disqualifying.
- **It leaves IMDS pipelines unattributed.** They fall through to the instance
  role exactly as today. Disqualifying against the requirement above.

Not wasted: the injection point, the `<workspace>@tmp/ck-aws/` lifecycle, cleanup,
the feature flag and the off-is-invisible property all carry over unchanged. What
changes is **what gets written into the file**.

## The decision: overlay, do not replace

```
Layer A   ATTRIBUTE WHAT ALREADY EXISTS  (no enumeration)
          Copy the agent's own AWS configuration into the build's private
          temp dir and inject ONE line into every profile that assumes a role:
              role_session_name = jk-<job>-<build>
          role_arn, source_profile, credential_source, region, MFA settings:
          all copied verbatim. The mechanism is preserved; only the label is
          added. A profile added to an agent tomorrow is attributed on its
          next build, with no Jenkins configuration at all.

Layer B   ATTRIBUTE THE UNPROFILED PATH, SELECTED BY JOB NAME
          Job-name rules (first match wins) choose which configured profile
          becomes the [default] in the generated config - i.e. the identity
          for every AWS call that does NOT name a profile:
              prod/.*  -> prod        uat/.*  -> non_prod
              dev.*    -> non_prod    ops/.*  -> ops
          No match: the plugin does nothing at all for that job.
          THIS is the layer that may need one IAM trust-policy edit; which
          depends on the mode chosen, below.

Layer C   OPTIONAL, LATER
          AWS_CONTAINER_CREDENTIALS_FULL_URI. Verified from the botocore
          chain (post_profile = [..., container_provider,
          instance_metadata_provider]): it outranks IMDS but loses to an
          explicit profile. Catches clients that ignore shared config, at the
          cost of a local credential endpoint. Not needed for v1.
```

The Jenkins `profile → roleArn` mapping does not disappear. It **demotes from
source of truth to optional override**, for profiles an administrator wants
Jenkins to own or to add.

## Fail-open is the outermost layer, not an inner one

The plugin's contribution is optional by definition, so **nothing it does may fail a
build**. The entire contribution path — the configuration lookup, every
`DelegatedContext.get` (each declared to throw `IOException`), and the decoration
itself — runs inside one guard that catches **`Throwable`** and re-throws only
`InterruptedException`, so an aborted build stays aborted.

`Throwable`, not `Exception`, deliberately: a `LinkageError` or
`NoClassDefFoundError` after an upgrade would otherwise take out every step on the
controller rather than one build. Catching `Error` is normally poor practice; here
the alternative is failing deployments to protect a JVM that is already unwell.

The outermost handler performs **no console I/O** — it is reached in states where
attempting it could itself throw. Expected, diagnosable failures are reported to
the build log one level in.

*An earlier version guarded only the decoration. That left four statements and the
whole of `Error` outside the net, any of which would have propagated into the step
and failed the build. Found by review before release, not by an outage.*

## What M12 actually does

1. Discover the executing node's AWS configuration — `AWS_CONFIG_FILE` from the
   node's own environment if set, otherwise `$HOME/.aws/config`, read from
   `Computer#getEnvironment()`. No hardcoded user, path or cloud.
2. Copy it, adding `role_session_name = jk-<job>-<build>` to profiles that assume
   a role and do not already pin one. **Additions only.**
3. Append Jenkins-configured profiles the node does not define. The node always
   wins.
4. Point `AWS_CONFIG_FILE` at the copy. Export `CK_AWS_SESSION_NAME`. **Export no
   credentials, and never touch `AWS_SHARED_CREDENTIALS_FILE`.**
5. Delete the copy when the build finishes.

Anything that goes wrong at any step contributes nothing and the build proceeds
exactly as today.

## Two defects found by pre-implementation review (2026-08-06)

Both were in M11 as built, both would have broken existing builds, and both were
found by testing rather than reasoning. Recorded so the M12 implementation cannot
reintroduce them.

**1. `AWS_SHARED_CREDENTIALS_FILE` must never be touched.** M11 points it at an
empty file so generated profiles cannot be shadowed by the node's own credentials.
Measured: with it blanked, a profile chaining through `source_profile` to a
credentials-file profile fails with exit 253, *"The source_profile "base"
referenced in the profile "chained" does not exist."* With it left alone the chain
resolves normally. Under an overlay there is nothing to shadow, so the reason for
blanking it no longer exists — and leaving it alone also avoids copying static
secret keys into the workspace.

**2. The overlay must be a line-based textual transform, never parse-and-
regenerate.** Round-tripping an INI drops comments, reorders keys and mangles
sections the parser does not model (`[sso-session]`, `[services]`). A prototype
line-based transform over a realistic node config produced a diff containing
**additions only** — two `role_session_name` lines — while leaving comments,
section order, `[sso-session]`, profiles without a `role_arn`, and a profile whose
`role_session_name` an administrator had already pinned exactly as they were.
**A pinned session name is a deliberate decision and must never be overridden.**

## The two authentication modes (decided 2026-08-06)

Every managed build gets an STS session named `jk-<job>-<build>`. There is no
other way to obtain one: verified from the STS service model, `AssumeRole`
requires **both** `RoleArn` and `RoleSessionName`; `GetSessionToken` accepts no
name at all; and `GetFederationToken` — the only other operation with a
caller-chosen name — is documented as requiring *"the long-term security
credentials of an IAM user"*, so an instance role cannot call it. **Attribution
always costs an AssumeRole against a named target role.**

The two modes therefore differ in exactly one thing: **where the target role ARN
comes from.** Base credentials are IMDS either way, because that is how the agent
authenticates.

| Mode | Base credentials | Target role | Permissions vs today | IAM change |
|---|---|---|---|---|
| `AssumeRole` | IMDS | the configured ARN | those of the target role | none, if the trust already exists |
| `InstanceProfile` | IMDS | **the agent's own role** (self-assumption) | **identical** | **one trust-policy edit** |

**The exact limitation, and it is IAM, not Jenkins:** a role does not trust itself
by default. An EC2 instance role's trust policy names `ec2.amazonaws.com` only, so
self-assumption is denied until an administrator adds the role's own ARN as a
principal. *(Whether a same-account self-assumption also needs `sts:AssumeRole` in
the role's identity policy must be confirmed in a sandbox before rollout — the
trust policy is certainly required; the second grant may be.)*

**This redefines `InstanceProfile` mode.** As implemented at M11 it means "render a
profile with no credential keys, fall through to the agent's identity, stay
unattributed". From M12 it means "self-assume the agent's own role for
attribution" — turning the mode from a documented gap into a solution.

### Which mode preserves behaviour

| Job shape | Attributed | Behaviour preserved |
|---|---|---|
| Matched, `--profile X` where X assumes a role | yes | yes — identical role and permissions |
| Matched, no profile, rule → `InstanceProfile` | yes | **yes — identical permissions** |
| Matched, no profile, rule → `AssumeRole` | yes | **no — permissions become the target role's** |
| Unmatched by any rule | no, unchanged | yes — untouched |

The third row is the one to think about. Mapping a folder that today runs on the
bare instance role to `AssumeRole → non_prod` attributes it immediately and needs
no IAM change, but its effective permissions change; if the instance role holds
anything the target role does not, that build breaks. **`InstanceProfile` mode is
the one that satisfies "exactly as they do today", and it costs the trust-policy
edit.** The two goals are not in conflict — that edit is simply the irreducible
price of attributing an IMDS identity.

## The founding rule this reverses

Since M6 this document has said: **"The plugin must never read
`~/.aws/config`."** The reasoning was that the identity *decision* must be
Jenkins-owned rather than agent-owned.

The M12 requirement inverts that premise. It asks for the agent's existing
decision to be **preserved**, with Jenkins contributing only attribution — and
that cannot be done without reading the file. **The rule is therefore reversed for
the overlay path, deliberately and with the trade-off stated: Jenkins stops being
the authority on which role a build assumes, and becomes the authority on how that
assumption is labelled.** The old rule still governs the override path, where
Jenkins does decide.

## What remains unreachable, whatever is built

1. Clients that pin a credential provider in code (e.g. an explicit
   `InstanceProfileCredentialsProvider`).
2. AWS calls inside containers with nothing mounted.
3. Controller-side AWS calls by other Jenkins plugins.
4. Deliberate bypass by someone with `sh` on an agent.

Truly universal attribution needs per-build federated identity — OIDC /
`AssumeRoleWithWebIdentity` — plus removing the instance-profile principal from
those trust policies. That is an infrastructure programme, not a plugin, and it
remains the long-term destination.

## Open items before M12 implementation

- **Explicit agreement to reverse the no-read rule.** Not to be assumed.
- The IAM trust-policy edit enabling self-assumption, per account, for
  `InstanceProfile` mode — and confirmation of whether an identity-policy grant is
  needed alongside it.
- Role **path** is not recoverable from an assumed-role ARN
  (`assumed-role/<name>/<session>` omits it), so runtime discovery of the agent's
  own role ARN needs `iam:GetRole`, or an administrator override, where roles use
  paths.
- Which folders map to which profile, and in which mode — the decision that
  determines whether a formerly-IMDS job keeps its permissions.
- **`InstanceProfile` mode cannot be validated locally.** There is no IMDS on a
  development machine, and the only role available in the Ops account
  (`ck-jenkins-plugin-validation-role`) trusts `CKPrism-AdministratorAccess`
  only — read-only `iam:GetRole`, 2026-08-06 — so it cannot assume itself. Making
  it self-assumable is an IAM modification that this project will not make. The
  mode can be designed and unit-tested; its live behaviour stays **unproven**
  until a sandbox role with self-trust exists. Any local "IMDS validation" would
  exercise the plugin's own code, not AWS, and must not be presented as more.
- Whether AWS SDK for Java **v1** honours `role_session_name` from shared config.

---

# M11 — Managed Authentication (decided 2026-08-06, finalized after review)

> **Superseded as the rollout content by M12 above**, and insufficient for the
> universal-attribution requirement. The mechanism described here — injection
> point, file location, lifecycle, cleanup, feature flag — is implemented,
> validated and carried forward unchanged. Only the *content* of the generated
> file changes.

**This section records a change of rollout mechanism, decided after the wrapper
was fully validated. Nothing below has been deleted: the wrapper architecture in
the rest of this document is implemented, validated end to end against real AWS,
and remains correct. What changed is the requirement, and therefore which
mechanism carries the rollout.**

> **Naming.** This was called *"ambient authentication"* in the first draft. It is
> now **Managed Authentication** — the name describes the behaviour rather than
> the mechanism: the plugin *manages* AWS authentication for Jenkins builds.
> Earlier documents and commit messages use the old term; they refer to the same
> design.

Full engineering detail — lifecycles, failure modes, class design, performance
and scalability analysis, proofs, and the list of claims still unverified — is in
[docs/MANAGED_AUTHENTICATION_DESIGN.md](docs/MANAGED_AUTHENTICATION_DESIGN.md).
This section records the decision and the reasoning.

## What changed

The requirement is now: **deployment repositories, Jenkinsfiles and future
repositories must remain completely unaware of the plugin.** No wrappers, nothing
for developers to remember, configuration performed once by a Jenkins
administrator inside Jenkins itself.

`ckAwsWithProfile` cannot satisfy that. It is opt-in by construction, so it can be
forgotten — which is the definition of accidental bypass. **The wrapper is
therefore no longer the rollout mechanism.** It is retained, undeprecated, as the
explicit override for builds that need a second account or an ad-hoc ARN.

"Non-bypassable" in this requirement means **no accidental bypass** — nothing a
developer can forget. Deliberate bypass by someone with `sh` on an agent is
explicitly out of scope.

## The decision

**Managed Authentication: Jenkins generates a per-build AWS config file and
injects it into every Pipeline step.**

```
Layer 0   CONFIGURATION — unchanged
          profile -> roleArn (+ region), Manage Jenkins / JCasC
          + enable flag, job-name pattern, credential_source
              |
Layer 1A  MANAGED INJECTION  (NEW — the default path)
          DynamicContext.Typed<EnvironmentExpander>, consulted by
          ContextVariableSet for every step.
          Writes <workspace>@tmp/ck-aws/config containing, per profile:
              role_arn, credential_source, role_session_name = jk-<job>-<build>
          Exports AWS_CONFIG_FILE / AWS_SHARED_CREDENTIALS_FILE /
          CK_AWS_SESSION_NAME. No credentials anywhere.
              |
Layer 1B  EXPLICIT OVERRIDE — ckAwsWithProfile, unchanged
```

The generated file is the file the agent already has, plus **one line**:
`role_session_name = jk-<job>-<build>`. Everything else — the AssumeRole, the
caching, the refresh — is done natively by whatever AWS tool the build runs.

## Why a generated config file is forced, not chosen

Two measured facts remove every alternative:

1. **An explicitly passed profile deletes the environment provider from the
   credential chain.** `botocore/credentials.py:95` sets
   `disable_env_vars = session.instance_variables().get('profile') is not None`
   and then `providers.remove(env_provider)`. Measured against
   `aws-cli 2.35.1` / `botocore 1.42.65`: with `--profile X` (or
   `boto3.Session(profile_name=…)`) the config file wins and exported
   credentials are ignored; without a profile, the environment wins;
   `AWS_PROFILE` does **not** trigger the removal. 12 of the 13 AWS invocations
   in the deployment library pass `--profile`, which is exactly why the wrapper
   needed M7's `profileGuard` — i.e. a repository change.
2. **`AWS_ROLE_SESSION_NAME` cannot substitute for the file.** It is read only by
   `AssumeRoleWithWebIdentityProvider._CONFIG_TO_ENV_VAR`
   (`credentials.py:1879-1886`) — the OIDC path. The provider the agents use
   (`role_arn` + `credential_source`) reads `role_session_name` from the config
   file only (`:1643`, `:1693`, `:1956`).

Therefore the only channel that reaches a `--profile` call site *and* can carry a
per-build session name is a config file Jenkins writes per build. Under a future
OIDC design this layer disappears, because `AWS_ROLE_SESSION_NAME` is honoured
there.

## Directions considered and rejected

- **`credential_process` helper on the agent.** Zero repo changes and native
  refresh, but it moves the control plane onto the agent filesystem and the role
  mapping out of Jenkins; its session name would have to come from `$JOB_NAME` in
  the build environment, which is forgeable and wrong for concurrent builds on one
  agent. Also measured at **one helper invocation per `aws` process** versus one
  AssumeRole per build for the native `role_arn` form.
- **Static credentials in an ephemeral credentials file.** Forces the plugin to
  eagerly assume every configured role, writes real credential material to disk,
  and reintroduces the 1-hour chained-session cap with no refresh.
- **Folder-scoped configuration (`AbstractFolderProperty`).** Considered because a
  Pipeline's own Jenkinsfile rewrites its job property list — all 12 CloudKeeper
  entry points declare `options { buildDiscarder(...) }`, so a UI-set *job*
  property can be silently deleted on the first build. A folder property is
  immune. **Dropped anyway**: the global mapping is sufficient, and a second
  configuration location is complexity the requirement does not ask for.
- **Custom credential type in the job's Credentials dropdown.** Infeasible, proven
  from bytecode: every public method on `CredentialsProvider` is a *query*. There
  is no push/bind/inject API; the only two routes from a credential into a build
  (`BindingStep`, `SecretBuildWrapper`) are wrappers. That dropdown is also the
  *SCM checkout* credential and is never exported to the build.

## Jenkins internals established during the review

Recorded so they are not re-litigated. All verified from bytecode at the pinned
versions.

- **`DynamicContext` is consulted per step, and block scope wins.**
  `ContextVariableSet.get` walks its own block-scoped `values` list first, then
  `ExtensionList.lookup(DynamicContext.class)`. `DelegatedContext` can fetch
  `Run`. So an explicit `ckAwsWithProfile` still wins and no double AssumeRole
  occurs. Core also holds a `ThreadLocal` re-entrancy guard.
  `DynamicContext.Typed` lives in `workflow-step-api:700.v6e45cb_a_5a_a_21`,
  already a dependency — **no new dependency and no 2.479.3 baseline problem.**
- **`DefaultStepContext` can derive `Run`, `Job`, `Node`, `Computer`, `Launcher`,
  `FilePath`, `EnvVars`, `TaskListener` and `EnvironmentExpander`**, and computes
  step environment via
  `EnvironmentExpander.getEffectiveEnvironment(run.getEnvironment(listener), …)`.
- **`BuildWrapper`/`SimpleBuildWrapper` cannot attach to a Pipeline job.**
  `WorkflowJob extends hudson.model.Job`, not `AbstractProject`, and does not
  implement `BuildableItemWithBuildWrappers`. There is no "Build Environment"
  section on a Pipeline job. Only `wrap([$class: …])` from a Jenkinsfile — a
  wrapper. It *does* work for freestyle jobs, which is the fallback if any exist.
- **`LauncherDecorator` cannot carry build attribution.**
  `decorate(Launcher, Node)` has no `Run` parameter, so it cannot derive
  `jk-<job>-<build>`. And environment injection alone loses to `--profile`
  regardless.
- **`EnvironmentContributor` reaches every step but has no `FilePath`**, so it
  cannot write the file; it also fires when rendering build pages.
- **`StepListener.notifyOfNewStep(Step, StepContext)` returns `void`** — it can
  detect and abort, never inject. Useful only as an audit/enforcement detector.
- **`ContextVariableSet` never caches `DynamicContext` results.** `values` is
  `final` and `get()` never writes to it; the only static state is a `ThreadLocal`
  re-entrancy guard. Results are recomputed on every query. That is what makes
  node changes, `parallel` branches and multi-agent builds resolve correctly — and
  why the plugin must memoise for itself, and must evict that memo at
  `onFinalized` or leak one entry per node block forever.
- **Plugin upgrades always require a Jenkins restart.**
  `PluginManager.dynamicLoad` throws `RestartRequiredException` on the
  "plugin is already installed" branch (Jenkins 2.479.2 bytecode). A *new* plugin
  id can be dynamically loaded once, on first install only. This is why the
  feature ships inside `ck-aws` behind a flag rather than as a second plugin: the
  flag makes enable/disable a **restart-free configuration change**, which no
  amount of plugin separation can match.

## File location — decision reversed during the final review

The first draft put the generated file at `<agent root>/ck-aws/<run>/`, chosen to
be unreachable by anything the build does. The final review reversed it to
**`<workspace>@tmp/ck-aws/`** on three counts:

1. **It eliminates the container limitation.** `docker.image().inside { }`,
   Declarative `agent { docker }` and Kubernetes agents make the workspace and its
   `@tmp` sibling visible inside the container; the agent root is not visible.
2. **It bounds storage without relying on cleanup.** The path is stable per
   workspace and overwritten every build, so the footprint is
   `workspaces × ~1 KB`, not `builds × 1 KB`. Cleanup becomes hygiene rather than
   a correctness requirement, and the orphan sweeper proposed in the first draft
   is deleted from the design.
3. **Losing the file fails loudly.** Measured: `AWS_CONFIG_FILE` pointing at a
   missing path plus `--profile X` exits **253** with
   `The config profile (X) could not be found`. There is no silent fallback to an
   unattributed identity, which is what makes the residual `cleanWs()` exposure
   tolerable.

`@tmp` is a *sibling* of the workspace, so `deleteDir()`, `git clean -fdx` and
`stash` (which is workspace-rooted) cannot reach it. `cleanWs()` from the
ws-cleanup plugin can, and is handled by verifying existence **once per `node`
block** — keyed on the enclosing `ExecutorStep` FlowNode id — never per step.

## Disabled must be indistinguishable from not installed

This is the property that makes upgrading Infrastructure Jenkins safe, and it is
non-negotiable. With the master switch off, the injection point returns `null` on
its **first** check — before profiles, before the `Run`, before a session name,
before any filesystem or remoting call. No variable is exported, no file written,
no AWS call made.

Three independent conditions produce that same do-nothing outcome, so a
misconfiguration cannot accidentally activate the feature: the switch is off; the
switch is on but no profiles are configured (injecting an empty config would break
every `--profile` command, so this fails **open** to the status quo); or the job
does not match the rollout pattern, where an unparseable pattern matches nothing
rather than everything.

Switched on, the plugin's job is to **attribute** AWS calls, not to gate them. It
adds a session name to authentication that was already happening. A build that
cannot prepare its configuration logs a warning and proceeds unauthenticated
rather than failing — a feature that applies to every job at once must never turn
a local problem into a fleet outage.

## What this buys, beyond zero repository changes

- **The 1-hour chained-session cap stops being a problem** — the highest open risk
  since M6. Credential refresh is native to every AWS SDK; the plugin does not
  implement it.
- **No credential material anywhere.** Nothing in the build environment, nothing
  in CPS program state, nothing to mask in the console. This retires — rather than
  patches — the inaccurate claim that block-scoped credentials never enter CPS
  program state as plaintext; `hudson.util.Secret` declares no `writeObject` or
  `writeReplace` and is therefore *not* encrypted under plain Java serialization.
- **Fewer STS calls**, not more: one `AssumeRole` per (build, profile), cached by
  the AWS CLI in `~/.aws/cli/cache` keyed by role ARN and session name.
- **Rollback is a checkbox**, not a deployment or a commit.

## Known limitations, accepted before implementation

1. **A profile name used by a repository but absent from the Jenkins mapping fails
   the build** — exit 253, `The config profile (X) could not be found`. Loud, never
   silently wrong, but a failure. Mitigation is to inventory every `--profile`
   string before enabling. Largest operational risk in the design.
2. **AWS calls inside a hand-rolled `docker run` are not covered** — nothing is
   mounted unless the pipeline mounts it. **No regression**: a container has no
   `~/.aws/config` today either, so no existing pipeline can be using `--profile`
   inside one, and an unprofiled call still falls through to IMDS exactly as it
   does now. `docker.image().inside { }` and Kubernetes agents *are* covered.
3. **Freestyle jobs are not covered** — `DynamicContext` is Pipeline-only.
   `SimpleBuildWrapper` is the available second path if any exist.
4. **Calls that name no profile are unattributed unless a default is configured** —
   `Utilities.dockerLoginEcr` and all of `cln-infra-terraform`. Measured: with no
   `[default]`, resolution falls through to IMDS and behaves exactly as today, so
   starting without one is provably a no-op. Attributing the Terraform repository
   without repository changes *requires* a `[default]`, which simultaneously
   changes `dockerLoginEcr`'s identity — a genuine trade-off, deferred to its own
   rollout stage.
5. **Controller-side AWS calls by other Jenkins plugins remain unattributed.**
   Unchanged from today.
6. **Profile names containing whitespace cannot work** — botocore's config parser
   rejects `[profile with space]`. Measured; form validation must reject them.
   Dashes, underscores, dots, colons, slashes and mixed case all work, so
   arbitrary organisation-chosen names remain supported.
7. **Session-name truncation can collide in deep multibranch hierarchies.**
   `SessionName` truncates the middle segment to stay within STS's 64 characters,
   so two branches sharing a long prefix can produce the same
   `jk-<truncated>-<build>`. Pre-existing since M1, but Managed Authentication
   makes it fleet-wide. Fixing it means appending a short deterministic hash on
   truncation — which changes a load-bearing convention and is therefore **a
   separate decision, not folded into M11**.

## Non-bypassability, restated

- **Accidental bypass — eliminated.** Nothing to remember; new repositories are
  covered from their first build.
- **Deliberate bypass — unchanged and still possible, and out of scope.**
  `terraform-assume-role` trusts the agent's EC2 instance role
  (`credential_source = Ec2InstanceMetadata`), so any process on the agent can
  assume it directly via IMDS. Only federated per-build identity
  (OIDC / `AssumeRoleWithWebIdentity`) plus removing the instance-profile
  principal from those trust policies closes it. That remains the long-term
  destination and supersedes the Layer 3 `sts:RoleSessionName StringLike "jk-*"`
  condition, which any caller can imitate.

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

> **Superseded as the rollout mechanism (2026-08-06).** Everything in this layered
> model is implemented and validated end to end, and Layer 0 is unchanged and still
> required. But Layer 1 is opt-in, so it can be forgotten — and the requirement is
> now that repositories stay unaware of the plugin entirely. `ckAwsWithProfile` is
> retained as **Layer 1B, the explicit override**, not as the way pipelines are
> expected to authenticate; **Layer 1A (managed injection) becomes the default
> path.** See "M11 — Managed Authentication" above. The reasoning recorded in this
> section remains accurate and is kept deliberately; only the conclusion about
> *which mechanism carries the rollout* has changed.

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

> **Resolved by M11 for the managed path.** When the AWS tool performs its own
> AssumeRole from a generated config profile, refresh is native: botocore returns
> `RefreshableCredentials` and re-assumes on expiry under the same session name.
> The plugin does not have to implement refresh at all. The constraint above still
> applies inside an explicit `ckAwsWithProfile` block (Layer 1B), which holds a
> fixed set of credentials for the life of the block.

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

M11 gives each profile an explicit **authentication mode**, so the same-account
case is a documented configuration rather than an inference from a blank field:

```yaml
unclassified:
  ckAws:
    managedAuthentication: true
    profiles:
      - name: "non_prod"
        mode: "AssumeRole"          # cross-account; carries jk-<job>-<build>
        roleArn: "arn:aws:iam::123456789012:role/non_prod"
        region: "us-east-1"
      - name: "ops"
        mode: "InstanceProfile"     # same-account; the agent's own identity
```

| Mode | Role ARN | Rendered as | CloudTrail |
|---|---|---|---|
| `AssumeRole` | required | a profile resolving through the plugin's helper | `jk-<job>-<build>` |
| `InstanceProfile` | not used | a profile section with no credential keys | the agent's instance-role session, as today |

A profile section carrying no credential keys makes the AWS SDKs fall *through* to
the agent's identity; an **unknown** profile is a hard error. That asymmetry —
verified against botocore 1.42.65 — is what lets a same-account profile keep
working without the plugin pretending to authenticate it.

M11 also adds three global settings alongside the mapping, all optional except the
first, and all on the same screen:

| Setting | Default | Purpose |
|---|---|---|
| `managedAuthentication` | `false` | Master switch. Ships off, so the upgrade is inert. Toggling it is a restart-free rollback |
| `jobRules` (M12) | empty | Ordered `pattern -> profile` rules, matched against a job's full name; first match wins. Selects the `[default]` identity for calls that name no profile. **No match means the plugin does nothing for that job** |
| `jobNamePattern` (M11) | empty (= all jobs) | Staged rollout by job full name. Superseded by `jobRules`, which both gates and selects |
| `credentialSource` | `Ec2InstanceMetadata` | Base identity of the agent. Accepts the three values botocore validates (`Ec2InstanceMetadata`, `EcsContainer`, `Environment`), so the plugin is not tied to EC2 agents |

A `defaultProfile` setting is deliberately **not** included initially — see M11's
limitation 4.

**Adding a profile is a row in System configuration. It must never require a
plugin release.** Nothing in the source tree may enumerate, default to, or branch
on a profile name.

---

# Migration strategy

Migration is **staged, per-consumer, and reversible at every step**. No stage
requires the next one.

> **Stages 1–5 below describe the wrapper rollout (M6/M7). They were executed and
> validated, and are retained as the historical record.** The M11 decision replaces
> them going forward with the Managed Authentication rollout in
> [docs/MANAGED_AUTHENTICATION_DESIGN.md](docs/MANAGED_AUTHENTICATION_DESIGN.md)
> §14, whose Stage 3 *reverts* the M7 library change. Stage 7 (Layer 3) is
> unchanged and still last.

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

### Stage 6 — ~~RunListener default injection~~ → Managed Authentication (M11)

Superseded. The zero-repository-change requirement moved this from "an
optimisation once the explicit path is proven" to *the* rollout mechanism, and
`RunListener` is not the extension point that can carry it (it cannot inject into
a Pipeline step's environment). See M11 above.

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
- Credentials never appear in a step return value.
  *(Correction, 2026-08-06: the original wording said "or in CPS program state as
  plaintext". That is not accurate — `hudson.util.Secret` declares no
  `writeObject`/`writeReplace` and is therefore not encrypted under plain Java
  serialization, so the wrapper's `EnvironmentExpander` does hold credential
  material in CPS program state. M11's managed path puts no credentials in the
  environment at all, which removes the exposure rather than restating the
  claim.)*
- `AuthCore`, `CliStsAssumeRole`, and `SessionName` are unchanged.
- All pre-existing tests still pass.
- No AWS-service branching and no CloudKeeper- or deployment-specific logic
  anywhere in the plugin.

---

# Definition of Done — M11

- Managed Authentication is off by default; enabling it is a restart-free configuration
  change, and so is disabling it.
- With it enabled, an unmodified pipeline containing only
  `node { sh 'aws sts get-caller-identity --profile <name>' }` produces an ARN
  ending in `assumed-role/<role>/jk-<job>-<build>`, with **zero** references to
  the plugin anywhere in the Jenkinsfile.
- CloudTrail shows `AssumeRole` with
  `requestParameters.roleSessionName = jk-<job>-<build>`, and the downstream
  call's `sessionContext.sessionIssuer.arn` is the target role.
- With managed authentication enabled but no profiles configured, builds behave exactly as they
  do today (fail open to the agent's own configuration).
- The generated file contains no credential material, and is deleted at
  `onFinalized` for SUCCESS, FAILURE and ABORTED builds.
- `ckAwsWithProfile` still works, still wins inside its own block, and does not
  cause a second AssumeRole.
- `AuthCore`, `CliStsAssumeRole`, `SessionName`, `AwsProfile` and all existing
  tests are unchanged.
- The file-format renderer is unit-tested without `JenkinsRule`.
- Plugin id and artifact identity remain `ck-aws`.
- No CloudKeeper-specific value anywhere — including `credential_source`, which is
  configurable.

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
- **Do not write credential material into the generated AWS config file.** Its
  security properties — best-effort cleanup, no masking, relaxed permissions
  tolerance — all depend on it containing nothing but a role ARN, a session name,
  a region and a `credential_source`.
- **Do not make Managed Authentication default to on.** The master switch ships off so
  that a plugin upgrade is behaviourally inert.
- **Do not put the generated file in the workspace, and never trust that a file in
  `@tmp` is still there.** `deleteDir()`, `git clean -fdx` and `cleanWs()` all
  reach those. This rule predates the implementation and the implementation
  drifted from it: 2.1 and every unreleased build before the fix wrote to
  `<workspace>@tmp` and memoized the
  path for the whole build, so a mid-build clean left every later step exporting
  `AWS_CONFIG_FILE=<deleted file>`. An AWS SDK reads a missing config file as an
  *empty* one, so `--profile x` then fails with "The config profile could not be
  found" — nothing thrown, nothing logged. Reproduced in
  `ProductionFailureModesTest`. 2.2 keeps the `@tmp` sibling (the only location a
  container mount is guaranteed to see) and **re-checks existence before reusing
  the memo**, regenerating when it is gone. Either invariant is acceptable;
  silently handing out a stale path is not.
- **Do not anchor the generated file to the current working directory.** A
  `DynamicContext` is handed the *current* `FilePath`, so inside
  `dir('application/service')` that is a directory in the middle of a checked-out
  source tree. 2.2 wrote `ck-aws/` there — once per `dir` block — and for a `dir`
  outside the workspace would leave a directory no cleanup reclaims. Anchor to
  `node.getWorkspaceFor(job)` when the current path is genuinely inside it, and
  fall back otherwise so `ws()` and concurrent `…@2` workspaces stay correct.
- **Do not return a bare value from a `DynamicContext` whose type another
  contributor also supplies.** `ContextVariableSet.get` scans the current level,
  consults every `DynamicContext`, and only *then* recurses to the parent. An
  unconditional non-null answer therefore **shadows the enclosing level**. For
  `EnvironmentExpander` that means every binding published by `withCredentials`,
  `withEnv`, `withAWS`, `withSonarQubeEnv` or `configFileProvider` vanishes the
  moment any inner block (`dir`, `ws`, `container`) adds a context level of its
  own. Always `merge(ours, context.get(EnvironmentExpander.class))` — and note
  `merge()` null-checks only its *first* argument, so a null second argument NPEs
  on expand. Ours must expand **first**, so a value the job set deliberately
  wins. Cost of getting this wrong, measured: `dev2/rivon` #942 and #944 lost
  their Nexus credentials and failed; #943, with the job out of scope, passed.
- **Do not memoize without a lock.** Parallel branches share a workspace and will
  both miss the memo and write the same file at once; a third branch can then
  read a half-written config. `putIfAbsent` does not prevent this — it
  deduplicates the entry after both writes have already happened.
- **Do not perform an STS call from a `DynamicContext`.** It is consulted for
  every step; the managed path must stay I/O-free after the first write. The
  AssumeRole belongs to the AWS tool, not to the plugin.
- Do not add a second configuration location (job property, folder property) —
  the global mapping is the source of truth.

---

# What 2.2 exports, and why each one

| variable | purpose | covers |
|---|---|---|
| `AWS_CONFIG_FILE` | points every AWS tool at the decorated copy of the node's own config | AWS CLI, boto3, Terraform's default credential resolution — **every** AWS call that goes through a shell |
| `CK_AWS_SESSION_NAME` | the build's session name, for a script that wants to log or tag with it | informational |
| `AWS_ROLE_SESSION_NAME` | names a session when a **tool assumes a role itself** | the Terraform "second hop" — see below |

## The second-hop problem

The generated config names the session for every role the *shared config* assumes. It
cannot name a hop the tool performs on its own: a Terraform provider carrying its own
`assume_role` block assumes a further role from the already-assumed session, and with no
`session_name` in that block the provider picks the name. The calls that actually change
infrastructure then carry a generated name, and CloudTrail ties them back only through
the AssumeRole event.

Measured: **3 of 21 Terraform jobs** have a provider-level `assume_role` and none sets
`session_name` — `cln-infra-terraform-pipelines/cln-app-terraform-pipeline`,
`ck-analytics-app-services-terraform`, `ck-ecs-terraform`. The other 18 use the profile
from `AWS_CONFIG_FILE` and are fully attributed already.

**The fix must be plugin-side, not repo-side.** The premise of this plugin is attribution
with no change to any Jenkinsfile, `.tf` file or shared library — a fix that requires
editing repositories is not a fix. Every AWS SDK reads `AWS_ROLE_SESSION_NAME`, so
exporting it names the second hop without touching anything.

**Status: MEASURED, and it does NOT work.** `AWS_ROLE_SESSION_NAME` was exported into a
build that ran `terraform plan` against a provider with its own `assume_role` block. The
provider ignored it:

```
CALLER:    .../ck-ops-jenkins-master-instance-iam-role/jk-poc-canary-terraform-secondhop-2
requested: roleSessionName = aws-go-sdk-1786899555220461151
RESULT:    .../terraform-assume-role/aws-go-sdk-1786899555220461151
```

The Terraform AWS provider builds the second AssumeRole from the `assume_role` block
alone and generates `aws-go-sdk-<nanotime>` when `session_name` is absent. No environment
variable reaches it.

**Superseded 2026-08-17.** A Terraform `*_override.tf` created in the working directory at build
time sets `session_name` on the provider's own `assume_role`, proven against the real cross-account
role with the real repo shape (MEMORY.md addendum 6). The override MUST copy `role_arn` verbatim —
omitting it silently drops the assume and Terraform runs as the raw instance role. The text below
records the original measurement, which remains accurate about environment variables. The only direct
fix is `session_name` in the provider block — a repository change, which the project's
premise excludes.

**What is true instead: attribution is transitive, and complete.** CloudTrail records the
*caller* of that AssumeRole as `jk-<job>-<build>`. So every Terraform API call can be
tied back with one join: `aws-go-sdk-<n>` → the AssumeRole event that created it →
`jk-<job>-<build>`. Direct labelling is missing for 3 of 802 jobs; traceability is not.

The export is retained because it is additive and free, and it may name a second hop for
any tool that *does* read it — but it must not be described as covering Terraform.

---

# Residual risk register (as of 2.2)

Everything below was established by reproduction or by census, not by argument.
Re-verify before widening scope.

## The safety net is not the master switch

The master switch is what you reach for *after* something breaks, and it needs a
human watching a queue. The real net has three layers:

1. **Structural.** The plugin can now only *add*, and **both** surfaces it touches
   are checked at runtime, not argued from construction:
   - config file — `AwsConfigOverlay.validate()`
   - environment — `ManagedAwsContext.wouldRemoveSomething()`, which expands the
     enclosing environment and the proposed merged one and compares them. If any
     variable the enclosing block set would be dropped or altered, the plugin
     contributes nothing and the build keeps its own environment.

   This closes the defect class that broke `dev2/rivon`: a contribution that
   succeeded and still took something away, which the exception guard could never
   have caught because nothing threw.

   The environment check earlier relied on merge ordering being correct by
   construction. That was replaced because "correct by construction" is exactly the
   claim that failed for rivon, and because the ordering argument depends on
   `DelegatedContext.get` returning the enclosing expander faithfully. Should it
   ever return null, a partial view, or a different level — in a nesting shape
   nobody has written yet — the merge is built from the wrong base and the argument
   silently stops holding. Comparing actual expansions needs no such assumption, so
   **an unimagined shape fails safe instead of failing silently.** That is what
   makes future jobs safe without enumerating them.
2. **Per-job, instant.** *Except jobs matching* is a kill switch for one job that
   needs no restart and no global toggle. Reach for it before the master switch.
3. **Observe only.** Prepare everything, export nothing. Widen the scope to every
   job, let real traffic run for a day, read the evidence, then enforce. This is
   how you survey 740 jobs — including the 637 whose Jenkinsfiles live in SCM —
   without any possibility of affecting one of them, and without watching a queue.

   **Precisely what it does:** the whole path runs — the node's config is read,
   decorated, validated, and the file *is* **written** to `<workspace>@tmp/ck-aws/
   config`. Only the export is withheld, so `AWS_CONFIG_FILE` and
   `CK_AWS_SESSION_NAME` stay unset, nothing reads the file, and behaviour is
   unchanged. Verified both halves on a canary: file present, variables unset.

   **The record lives only in each build's console log** — there is no aggregation
   and no central report. Observe-only answers "what would this build do", one
   build at a time. "Is anything slipping through" is a CloudTrail question.

Reach for them in that order. The master switch is the fourth thing, not the
first.

## Cannot fail — proven and test-locked

Context shadowing, source-tree pollution, stale memo after a mid-build clean, the
parallel write race, and a job's own `AWS_CONFIG_FILE` being overwritten. All in
`ProductionFailureModesTest`. The shadowing fix is at the context-resolution
layer, so it holds for **every** step — including ones nobody has written yet.

## Coverage is structurally complete

806 job configs: 740 Pipeline, 39 Freestyle, 27 folders. **No matrix, maven,
multibranch or external jobs.** Every buildable job is either a `WorkflowRun`
(hooked by `ManagedAwsContext`) or an `AbstractBuild` descendant (hooked by
`ManagedAwsFreestyleEnvironment`, which is additive and cannot shadow). Matrix and
Maven builds, should any appear, are `AbstractBuild` descendants and are covered
by the Freestyle path for free.

## Will not be audited today — by design, not by defect

- **Calls naming no profile on a node that cannot self-assume.** Bare `aws`
  commands resolve straight to IMDS, whose session name EC2 fixes to the instance
  ID. *Attribute unprofiled calls as the node's own instance role* closes this for
  every node whose role can assume itself — proven on all 7 slave types and the
  controller. A node where the probe fails is left exactly as it was and logs a
  warning; it stays unattributed rather than breaking.
- **Terraform's own `assume_role` block** (55 of 432 workspaces). `session_name`
  appears in no `.tf` file, so the second hop gets an SDK-generated name. Still
  traceable transitively — CloudTrail records the `jk-*` session that called
  `AssumeRole`. Closing it is a Terraform-repo change: set `session_name` from the
  `CK_AWS_SESSION_NAME` this plugin already exports.

## Configuration surface — what is load-bearing and what is not

**2.3 removed two form entries.** *Attribute unprofiled calls as* (the static ARN) and *Apply on
nodes labelled* are gone from the UI; both properties are retained `@Deprecated` so existing XML
still loads and the tests keep a settable ARN and a way to pin to one node. The form is now **eight
fields**. What follows describes the full property set, including the two that are no longer typeable.

Two fields reviewed as "dead weight" were kept after tracing their callers, and the reasoning is
worth not repeating: **`profiles` is the configuration source for `CkAwsWithProfileStep`** — removing
it deletes a shipped feature, not clutter — and **`credentialSource` is written into every generated
`[default]`** (`credential_source = Ec2InstanceMetadata`), so it is functional output even though its
value has never varied.

**Load-bearing — do not remove:**

| Field | Why |
|---|---|
| *Managed authentication* | Master switch, and the restart-free rollback |
| *Except jobs matching* | The incident switch: one job excluded without a global toggle |
| *Attribute unprofiled calls as the node's own instance role* | Audits ~98% of calls. Without it coverage is ~2% |
| *Observe only* | The rollout mechanism; full scope at zero risk |
| *Diagnostics* | Every piece of POC evidence came from it |

**Removed from the form in 2.3:**

- ***Attribute unprofiled calls as* (the static role ARN) was a footgun.** It was
  used as a deliberate poison pill during testing — pointing it at a nonexistent
  ARN breaks every bare `aws` call in every build, which is exactly what a typo
  would do. Fully superseded by the per-node checkbox, which resolves each node's
  real role and *verifies the assume succeeds* before using it.
- ***Apply on nodes labelled*** — no demonstrated use case. Its original
  justification (agents that differ, or make no AWS calls) is handled
  structurally: a node with no config gets nothing contributed, and a node that
  cannot self-assume is detected and skipped. Never used in any test or run.

**Kept after tracing callers — these are NOT dead:**

- ***AWS profiles*** (the repeatable list) — every diagnostic block in the POC
  printed `sections appended: []`, so it has never appended a profile. But it is
  also the configuration source for `CkAwsWithProfileStep`, the M11 override layer.
  Removing it deletes a shipped feature.
- ***Agent base identity*** — only affects profiles the plugin writes, which in
  practice is just `[default]` — but it *is* written there, on every build. On EC2
  it is always `Ec2InstanceMetadata`; it would matter if agents moved to ECS or EKS.

*Apply to jobs matching* sits in between: largely redundant now that observe-only
surveys everything at zero risk, but it costs nothing and was used constantly.

## Detecting unaudited calls automatically

Neither the plugin nor observe-only reports centrally. Two mechanisms cover it,
and they catch different things — use both.

1. **Jenkins side — a log recorder.** No code, no build impact. *Manage Jenkins →
   System Log → Add recorder* on `io.github.rads4.ckaws` at WARNING collects every
   decline in one place: nodes with no resolvable role, contributions refused by
   the additions-only invariant, and anything the guard swallowed. **It only sees
   what the plugin knows** — it will never show the Terraform second hop, because
   there the plugin succeeded.
2. **AWS side — CloudTrail, and this is the real detector.** It is outcome-based:
   it measures what actually reached AWS. Bucket calls made by the Jenkins instance
   role by session-name shape — `jk-<job>-<build>` is audited; `i-0…` is an
   uncovered unprofiled call; `aws-go-sdk-…` / `botocore-session-…` / 32-hex is a
   tool that assumed a role itself. Anything not `jk-` is a gap, automatically and
   permanently, **including in jobs nobody has written yet.** Implement as a Logs
   Insights query, a metric filter with an alarm, or an EventBridge rule — all
   AWS-side only, structurally incapable of affecting a build.

Known blind spot in both: a job authenticating some entirely other way, e.g. static
keys in a Jenkins credential. The census found none, but neither mechanism would
see one appear.

## Session names: settled, do not re-litigate

All **27** role ARNs in the controller's configuration were probed with
`sts assume-role --role-session-name jk-probe-secops-1`. **26 succeeded.** The one
denial, `275595855473/SecOpsAdminRole`, was re-probed with an SDK-style name and a
neutral name and denied identically — so it is a pre-existing permission gap, not
a session-name restriction. **No trust policy anywhere constrains
`sts:RoleSessionName`.** This risk is closed; re-probe only if trust policies
change.

## No production job has ever been damaged

Scanning build history for AWS auth-failure signatures: `Unable to parse config
file` appears in **7 builds, all of them canaries** (`ckaws-canary*` #3-#5) — the
duplicate-key defect from the unprofiled-ARN experiment, contained entirely to the
canary set. Zero production jobs. `config profile could not be found`: zero.
`The source_profile`: zero. Two `ExpiredToken` and one `sts:AssumeRole` denial
exist in `prod/marketplace`, `qa1/marketplace` and `slack-messages-monitoring` —
all out of scope, all pre-existing, none plugin-related.

## May fail — ranked, with the trigger
2. **Malformed node configuration.** `ops_rds` on the controller carries a
   `role_arn` with no `credential_source`. The plugin adds a session name and
   leaves it no worse, but it was already broken.
3. **Windows agents.** `chmod` would throw, the guard would fail open, and the
   build would run unaudited rather than break. No Windows agents exist today.
4. **Container agents.** `container()`, `docker.inside` and `withKubeConfig` have
   **zero** executed history, so `@tmp` visibility inside a container mount is
   untested. Verify before adopting Kubernetes agents.
5. **Per-step cost.** Re-checking the file's existence is one remote stat per
   step (~1-2 ms). At the observed ~64 steps per build this is noise; a build with
   thousands of steps would notice. Throttle only with evidence.

## How this was measured, so it can be repeated

Build logs record every executed step, which is the only way to see inside the
637 jobs whose Jenkinsfiles live in SCM. Jenkins prefixes each console line with
a binary console note, so `grep '^\[Pipeline\]'` matches nothing — drop the
anchor. Last 3 builds of every job is 690 logs and ~230 MB; run it `nice -n19
ionice -c3` and off-hours.

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
