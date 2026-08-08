# v2.0.0 — agreed scope and decision record

Outcome of the `jenkins-node-inspect` session (2026-08-07), in which the production Jenkins
estate — seven agents and the controller — was inspected read-only over SSM and the M12i
incident was resolved.

**This document is sanitized for a public repository.** Real account IDs, role ARNs, node
inventories and raw evidence are in `.session-archive/` (gitignored). Placeholders used here:
`<ops-account>`, `<nonprod-account>`, `<prod-account>`, `<instance-role>`.

---

## 1. M12i resolved — the plugin was not the cause

A Gradle build failed with 403 while Managed Authentication was on, and the plugin was
suspected. It is exonerated, on three independent grounds:

1. **The failing request cannot involve AWS credentials.** It targets an **S3 static website
   endpoint over plain HTTP**, in a third-party account. Website endpoints serve anonymously
   and reject SigV4, and Gradle applies AWS authentication only to `s3://` URLs. There are no
   `s3://` repositories anywhere in the build.
2. **The same 403s appear in builds that succeeded**, before and after the failure, on the same
   agent.
3. **A later build failed identically with the plugin not involved at all** — no `[ck-aws]`
   lines in its log — and needed a *different* artifact version, showing the whole path on that
   bucket was returning 403.

The "flag OFF → succeeds" observation was **confounded**: the succeeding build resolved
everything from the Gradle cache and never exercised the failing path.

**Method note worth keeping:** the earlier investigation reproduced against an agent
configuration shape that does not exist in production (no `[default]`, two profiles; production
has `[default]` plus six). Its negative result was therefore not transferable. Real fixtures are
in scope for v2.0.0 precisely to prevent a repeat.

## 2. Field validation — the design works in production

Two unmodified production pipelines ran with Managed Authentication on and were fully
attributed in CloudTrail: a prod ECS deployment (25 events) and a non-prod one (15+ events),
each under its own `jk-<job>-<build>` session, via the agent's own profile definition.

**Toggling the flag off mid-build is safe.** Both builds completed with attribution intact after
the switch was turned off while they were running. This is structural: `AWS_CONFIG_FILE` is
exported into the build environment at decoration time and the flag is not consulted again for
that build. Running builds finish under their existing session; only new builds see the change.
**This makes the switch a genuinely safe incident control, and should be documented as a
guarantee.**

## 3. Authentication in the estate — measured

All agents and the controller share one base identity, delivered by IMDS. There is **no second
mechanism**: across every job, `withAWS` = 0 and `amazonWebServicesCredentials` = 0. Every
`withCredentials` in the estate is a *database* credential. `role_session_name` appears nowhere.

Two paths:

| Path | Shape | Attributable |
|---|---|---|
| **A** — named profile that assumes a role | `role_arn` + `credential_source = Ec2InstanceMetadata` | **Yes** — decorate with `role_session_name` |
| **B** — unprofiled | no `role_arn` (`ops`, `[default]`), or no profile named | **Yes, via self-assume** — see §4 |

Path B is not marginal: it covers same-account traffic — ECR, S3, SSM, Secrets Manager.

The **controller runs builds too** (4 executors, and jobs pinned to `master`), and carries a
superset of the agents' profiles. Built-in-node coverage must be verified or that surface stays
unattributed.

## 4. The decisive finding — self-assume needs no IAM change

Tested directly on a production host:

```
before: assumed-role/<instance-role>/i-xxxxxxxx      ← IMDS, unattributable
after:  assumed-role/<instance-role>/jk-selftest-1   ← named session
```

The instance role can already assume **itself**, because the trust policy delegates to the
account root and an identity policy permits it. So Path B is closed with **zero IAM changes**,
and `[default]` takes the *same config shape* as every Path A profile:

```ini
[default]
role_arn          = arn:aws:iam::<ops-account>:role/<instance-role>
credential_source = Ec2InstanceMetadata
role_session_name = jk-<job>-<build>
```

**Why this matters beyond convenience:** the principal ARN is unchanged, so every
resource-based policy that grants access by naming the instance role keeps working. Two such
bucket policies were found by sampling three buckets — one of them cross-account — which is
what killed the alternative design.

## 5. Rejected alternatives, and why

Recorded so they are not re-proposed.

**A dedicated audit role (`ck-jenkins-audit-*`).** Rejected. Resource-based policies grant
access by **principal ARN**, so a new role with identical *identity* policies would still be
denied by every bucket, key or repository policy naming the instance role. Sampling found such
policies immediately, including cross-account. Making it work would require discovering and
amending resource policies across ~17 accounts — unbounded, and never provably complete.

**`sts:SourceIdentity`.** Rejected for now. Source identity propagates through role chaining and
is immutable, which is exactly what would make bypass traceable — but **every downstream role
assumed in the chain must allow `sts:SetSourceIdentity` in its trust policy, or the assume
fails**. Several existing jobs call `aws sts assume-role` directly from the unprofiled identity;
enabling source identity would break precisely those jobs. If wanted later: enumerate every
assumable target role, add the permission to each trust policy, *then* enable. That is its own
project.

**`credential_process` for the unprofiled path.** Not needed. It was proposed to control the
AssumeRole call (for source identity) and to defeat the one-hour role-chaining cap. Since source
identity is deferred and the SDK re-assumes automatically on expiry — evidenced by a
74-minute production build already running on a chained profile — plain config is sufficient and
far simpler.

**Layer C (`AWS_CONTAINER_CREDENTIALS_FULL_URI`).** Not needed, for the same reason. No local
credential endpoint, no new attack surface.

**Job-name → profile mapping for `[default]` (the original "Layer B").** Rejected as
**dangerous**. It would map, say, `uat/*` to a non-prod profile — but unprofiled calls in those
jobs legitimately reach the *ops* account (ECR lives there), so remapping would break image
pushes. The correct rule is **identity-preserving and universal**: one rule, every job, no
pattern matching. This also gives the desired property that any new pipeline is attributed
automatically, with no registration.

## 6. v2.0.0 scope — **implemented**

All nine items are built and covered, plus three defects found by installing and
running the canaries. `ck-aws 2.0`, **199 tests** green (was 144). Build a release
with `mvn -Dchangelist= clean verify`.

### Found after the first install, and fixed

1. **A configuration ending in a blank line suppressed decoration entirely.**
   Joining lines with `"\n"` collapses a final empty element, so the safety check
   correctly saw a line lost — for a difference that carries no meaning. It
   blocked the controller and nothing else, because only the controller's file
   ends that way. *The safety check working as designed, on its first real run.*
2. **A named profile with no `role_arn` was not attributed** — the same
   unattributable path as `[default]`, reached by name rather than omission.
3. **Freestyle builds were not covered at all** — 35 of 775 buildable jobs,
   including production S3 and Route 53 work. The obvious hook
   (`RunListener#setUpEnvironment`) runs before the workspace exists and fails
   *silently*; `EnvironmentContributor` is the correct seam.

### Coverage, measured

| Root element | Count | Reached |
|---|---|---|
| Pipeline (inline, SCM, multibranch) | 740 | yes |
| Freestyle | 35 | yes |
| Folders | 27 | n/a |

Verified by running the overlay against all eight real fleet configurations, and
by tests that use a **real agent** rather than the built-in node.


Two bugs were found in the implementation itself and are worth recording, because
both were caught by writing the test rather than by reading the code:

1. Refactoring `appliesTo` to add the exclude pattern briefly made an **invalid
   include pattern return `true`** — widening scope to every job on a typo. Fixed
   by treating "pattern absent" and "pattern unparseable" as distinct fallbacks.
2. A local variable named `jenkins` shadowed the `jenkins.model` package.


| # | Change | Rationale |
|---|---|---|
| 1 | **Validate the generated config before exporting `AWS_CONFIG_FILE`** | The one real safety gap: fail-open catches exceptions, not "wrote a malformed file successfully". On failure, contribute nothing |
| 2 | **`[default]` self-assume with `role_session_name`** | Closes Path B; no IAM change; same config shape as Path A |
| 3 | **Diagnostics as a configuration checkbox** | Today a system property, so debugging needs a controller restart — the reason a diagnostic build had to be shipped once already |
| 4 | **Exclude pattern** alongside the include pattern | Excluding one job otherwise needs a negative-lookahead over every job |
| 5 | **Node-label scoping** | One agent has a divergent config; another makes no AWS calls at all |
| 6 | **Real production configs as test fixtures** (both shapes, with placeholder account IDs) | Prevents another invalid reproduction |
| 7 | **Verify built-in-node coverage** | The controller runs builds and holds the largest profile surface |
| 8 | **Session-name truncation keeps the job name's tail** | The build number is already preserved; the *tail* is the distinguishing part, and one real job name exceeds the available length |
| 9 | **Test asserting Managed Authentication is OFF by default** | Prevents regression of the safety default |

After this, every axis that varies is a configuration field — **no further upgrade should be
required**.

## 7. Pattern semantics

`Pattern.compile(p).matcher(job.getFullName()).matches()` — note `matches()`, not `find()`.

| Rule | Detail |
|---|---|
| Full-string match | `authbridge` does **not** match `folder/authbridge` |
| Matched against the job's **full name** | Folder jobs are `folder/job` |
| `/` is literal | No escaping needed |
| `.` is a wildcard | Write `\.` for a literal dot |
| Blank include = **all jobs** | The one dangerous default |
| Invalid regex = **matches nothing** | Fails closed; a typo disables, never enables |

Exclude uses identical semantics, evaluated **after** include; blank excludes nothing.

## 8. Fail-safety — four independent layers

1. **Master switch, default OFF.** Off means nothing exported, written or called.
2. **Scope gate.** Non-matching jobs receive nothing; an invalid pattern matches nothing.
3. **Validate-before-export** (new in v2.0.0). A bad file is never handed to a build.
4. **Fail-open outermost.** Catches `Throwable`, re-throws only `InterruptedException`.

**Rollback never requires a restart** — untick the box, or add an exclude. This is what makes a
single restart sufficient even if a defect is found.

## 9. Known limits — state them, do not overstate the plugin

- A build that calls `aws sts assume-role` itself bypasses the overlay. The shared instance role
  grants broad `sts` permissions, so this is possible by construction.
- Clients pinning a credential provider in code, and `docker run` with nothing mounted, are
  unreachable.
- Controller-JVM calls made by other plugins are outside any build environment.

**The plugin is an observability layer over conventional usage, not a control.** Enforcement
belongs in IAM (a trust-policy condition on `sts:RoleSessionName`) and must follow migration,
not precede it.

## 10. Rollout

| Step | Action | Restart |
|---|---|---|
| 1 | Local testing against real-shape fixtures | — |
| 2 | Install v2.0.0, flag **OFF** | **Yes — the only one** |
| 3 | Include pattern = the canary job only; verify both paths | No |
| 4 | Widen by folder | No |
| 5 | Long-build test (>1 h, unprofiled) before production folders | No |
| 6 | Production folders last | No |

A canary job exists for step 3: a Pipeline job with an inline script that prints
`CK_AWS_SESSION_NAME`, `AWS_CONFIG_FILE`, the generated config, and runs `sts
get-caller-identity` twice — once unprofiled, once with a named profile. Read-only, no side
effects. Its pre-upgrade baseline is recorded in `.session-archive/`.

**Scheduling note:** do not roll out during the GitLab→Bitbucket migration. Concurrent SCM
breakage would confound attribution of any failure — the same confounding that made the M12i
incident hard to diagnose.
