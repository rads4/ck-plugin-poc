# M7 — Deployment library integration plan

**Status: proposal. Nothing in `cln-deployment-scripts` has been modified.**
This document exists to be reviewed and approved before any change is made to
that repository.

Target: migrate `cln-deployment-scripts` from AWS CLI profile resolution
(`aws ... --profile ${prof}` → agent `~/.aws/config`) to the plugin's Layer 1
contract (`ckAwsWithProfile`), so that every AWS call it makes carries a
`jk-<job>-<build>` session name in CloudTrail.

---

## 1. Measured current state

Counted, not estimated, on 2026-08-04.

**13 AWS CLI invocations across 4 files.** 12 carry `--profile`; 1 does not.

| File | Line | Call | `--profile`? |
|---|---|---|---|
| `src/com/packages/Deploy.groovy` | 23 | `aws ecs describe-task-definition` | yes |
| `src/com/packages/Deploy.groovy` | 27 | `aws ecs register-task-definition` | yes |
| `src/com/packages/Deploy.groovy` | 28 | `aws ecs update-service` | yes |
| `src/com/packages/Deploy.groovy` | 40 | `aws ecs wait services-stable` | yes (quoted) |
| `src/com/packages/Deploy.groovy` | 66 | `aws ecs register-task-definition` (rollback) | yes |
| `src/com/packages/Deploy.groovy` | 67 | `aws ecs update-service` (rollback) | yes |
| `src/com/packages/Utilities.groovy` | 25 | `aws ssm get-parameter` (ARN) | yes |
| `src/com/packages/Utilities.groovy` | 43 | `aws ssm get-parameter` (value) | yes |
| `src/com/packages/Utilities.groovy` | 114 | `aws ecr get-login-password \| docker login` | **no** |
| `vars/frontEndDeployment.groovy` | 153 | `aws ssm get-parameter` | yes |
| `vars/frontEndDeployment.groovy` | 294 | `aws ssm put-parameter` | yes |
| `vars/extensionDeploymentslack.groovy` | 78 | `aws ssm get-parameter` | yes |
| `vars/extensionDeploymentslack.groovy` | 85 | `aws ssm get-parameter` | yes |

**`prof` is threaded through 9 function signatures:**

| File | Line | Signature |
|---|---|---|
| `Utilities.groovy` | 24 | `fetchArnFromParameterStore(secretName, prof)` |
| `Utilities.groovy` | 33 | `replaceSecrets(taskDefTemplateFile, tdEnvVars, envName, appName, prof)` |
| `Utilities.groovy` | 42 | `fetchValueFromParameterStore(secretName, prof)` |
| `Utilities.groovy` | 47 | `replaceSecretsInDockerFile(dockerFileName, tdEnvVars, envName, appName, prof)` |
| `Utilities.groovy` | 72 | `initTaskDefinition(..., appName, prof)` |
| `Utilities.groovy` | 108 | `dockerLoginEcr(ecrUrl, regionName, prof)` — **accepts `prof` and never uses it** |
| `Utilities.groovy` | 205 | `updateInTaskDefinitionParameterValue(tdParameter, taskDefTemplateFile, appName, prof)` |
| `Deploy.groovy` | 16 | `ecsDeploy(taskDefination, taskDefTemplateFile, clusterName, serviceName, prof)` |
| `Deploy.groovy` | 37 | `rollbackDeploy(taskDefination, clusterName, serviceName, prof, regionName)` |

**`prof` is set in 12 `vars/*.groovy` entry points**, always as
`(envName == 'prod') ? 'prod' : 'non_prod'` (two DR files test `envName == 'dr'`):
`kongDeployment:23`, `drstormusDeployment:43`, `nodeDeployment:15`,
`frontEndFargatedeployment:15`, `extensionDeploymentslack:9`, `fluentDeployment:14`,
`drfeDeployment:15`, `stormusDeployment:57`, `kongaDeployment:21`,
`frontEndDeployment:30`, `pythonAppDeployment:42`, `drstrapiDeployment:15`.

**Structure:** all entry points are *declarative* pipelines
(`pipeline { agent { } stages { stage { steps { script { } } } } }`). AWS calls
are spread across several stages, so a single wrapper around the whole pipeline
is not expressible — the wrapper goes inside each AWS-touching `script { }`.

---

## 2. The blocking mechanic

Three separate reasons a naive "just wrap it" migration silently does nothing.

**2.1 `--profile` beats environment credentials.** AWS CLI resolution order is
command-line flags → environment variables → config file. Inside a
`ckAwsWithProfile` block, an `aws ... --profile prod` call still resolves through
the agent's `~/.aws/config` and still produces a `botocore-session-<epoch>`
CloudTrail entry. **Wrapping without removing the flag is a no-op that looks like
success.** This is why §3 Phase A exists.

**2.2 boto3 `profile_name` beats environment credentials too.**
`code/dr_sync.py:70,73` calls `boto3.Session(profile_name=os.environ["SOURCE_PROFILE"])`.
Same failure mode. Handled in Phase C.

**2.3 `dockerLoginEcr` changes identity.** `Utilities.groovy:114` runs
`aws ecr get-login-password` with **no** `--profile`, so today it uses the agent's
ambient instance role. Inside a block it will use the assumed role instead. This
is the only call in the library whose effective identity *changes direction*
under migration, and it will fail unless the target role holds
`ecr:GetAuthorizationToken`. Verified in §5 before Phase B, not discovered in
Phase B.

---

## 3. Change set

### Phase A — shared library, backward compatible, zero behaviour change

Goal: make the `--profile` flag suppressible without changing what any current
caller does. Merging Phase A alone must produce byte-identical AWS commands.

**A1. `src/com/packages/Deploy.groovy`** — add a private helper:

```groovy
// Emits the --profile flag only when a profile name was supplied. An empty
// profile means "credentials come from the environment" (ck-aws plugin).
def profileFlag(prof) {
    return (prof?.toString()?.trim()) ? "--profile ${prof}" : ""
}
```

Then replace the flag at 6 sites:

| Line | From | To |
|---|---|---|
| 23 | `--task-definition '${taskDefination}' --profile ${prof}` | `--task-definition '${taskDefination}' ${profileFlag(prof)}` |
| 27 | `--family '${taskDefination}' --profile ${prof} --cli-input-json` | `--family '${taskDefination}' ${profileFlag(prof)} --cli-input-json` |
| 28 | `--service '${serviceName}' --profile ${prof} --task-definition` | `--service '${serviceName}' ${profileFlag(prof)} --task-definition` |
| 40 | `--services '${serviceName}' --profile '${prof}'` | `--services '${serviceName}' ${profileFlag(prof)}` |
| 66 | `--family '${taskDefination}' --profile ${prof} --cli-input-json` | `--family '${taskDefination}' ${profileFlag(prof)} --cli-input-json` |
| 67 | `--service '${serviceName}' --profile ${prof} --task-definition` | `--service '${serviceName}' ${profileFlag(prof)} --task-definition` |

Note line 40 currently quotes the value (`--profile '${prof}'`); the helper emits
it unquoted, consistent with the other five. Profile names contain no spaces, so
this is safe — but it is a real (if inert) difference and is called out here
rather than slipped in.

**A2. `src/com/packages/Utilities.groovy`** — add the same helper (deliberately
duplicated rather than shared, to avoid introducing a cross-class dependency
between two `return this` script classes), then replace at 2 sites:

| Line | From | To |
|---|---|---|
| 25 | `--with-decryption --profile ${prof} --query Parameter.ARN` | `--with-decryption ${profileFlag(prof)} --query Parameter.ARN` |
| 43 | `--with-decryption --profile ${prof} --query Parameter.Value` | `--with-decryption ${profileFlag(prof)} --query Parameter.Value` |

`Utilities.groovy:114` (`dockerLoginEcr`) is **not** changed in Phase A — it has
no flag to suppress.

**Phase A is independently mergeable and independently revertable.** With every
current caller passing `'prod'` or `'non_prod'`, the rendered command strings are
identical to today's.

### Phase B — pilot entry point

**Recommended pilot: `vars/nodeDeployment.groovy`** (200 lines, 3 AWS-touching
stages, no inline `aws` strings of its own — every AWS call goes through the
library functions already covered by Phase A).

**B1. Gate, near line 15** (next to `this.prof = ...`):

```groovy
// Per-BUILD opt-in, not per-file: nodeDeployment serves many apps and
// environments, and the pilot must not migrate all of them at once.
this.useCkAwsPlugin = (env.CK_AWS_PLUGIN ?: 'false') == 'true'
// What the library should put on the command line. Empty when the plugin is
// supplying credentials through the environment.
this.cliProf = useCkAwsPlugin ? '' : prof
```

**B2. Conditional wrapper helper**, same file:

```groovy
def withAwsSession(Closure body) {
    if (useCkAwsPlugin) {
        ckAwsWithProfile(prof) { body() }
    } else {
        body()
    }
}
```

**B3. Wrap the three AWS-touching stages and pass `cliProf`:**

| Stage | Line | Change |
|---|---|---|
| Building and Pushing Docker Image | 117 | wrap in `withAwsSession { }`; `dockerLoginEcr(ecrUrl, regionName, cliProf)` |
| Deploy App | 133, 135, 138 | wrap in `withAwsSession { }`; pass `cliProf` to `updateInTaskDefinitionParameterValue`, `initTaskDefinition`, `ecsDeploy` |
| Verifying Deployment | 151 | wrap in `withAwsSession { }`; `rollbackDeploy(..., cliProf, regionName)` |

Three separate blocks means three AssumeRole calls per build. That is deliberate:
it keeps each block short relative to the 1-hour chained-session cap, and each
stage's CloudTrail events remain attributable to the same `jk-<job>-<build>`
session name regardless.

**No other file changes in Phase B.**

### Phase C — remaining consumers

**C1. The other 11 `vars/*.groovy`** — same three edits as B1–B3, one file per
change, each independently revertable. Two files (`vars/frontEndDeployment.groovy`
at 153 and 294, `vars/extensionDeploymentslack.groovy` at 78 and 85) additionally
have their own inline `aws` strings, which need the Phase A `profileFlag`
treatment locally.

**C2. `code/dr_sync.py`** (lines 69–73):

```python
# from
source_session = boto3.Session(profile_name=os.environ.get("SOURCE_PROFILE"))
# to
source_session = boto3.Session(profile_name=os.environ.get("SOURCE_PROFILE") or None)
```

`profile_name=None` makes boto3 fall through to the standard environment
credential chain. With `SOURCE_PROFILE` / `DEST_PROFILE` left set, behaviour is
unchanged — so this edit is also backward compatible on its own. Its caller
`code/jenkinsFile` then sets them to `""` and wraps the `sh 'python3
code/dr_sync.py'` step. Note this script uses two profiles (source and
destination); under one block both become the same assumed identity, which
matches the current configuration where both are `prod`.

**C3. `cln-infra-terraform/jenkins/*.groovy`** — out of scope for M7, and
simpler: those pipelines already consume credentials purely from the environment
(ambient instance role plus `AWS_REGION`), so migration is wrapping only, with no
in-block edits. Separate change, separate approval.

---

## 4. What is deliberately NOT changed

- The 9 function signatures keep their `prof` parameter. Removing it is a wide,
  mechanical, all-at-once change with no migration value — the parameter becomes
  inert once callers pass `''`, and can be deleted later as cleanup.
- `dockerLoginEcr`'s unused `prof` parameter (`Utilities.groovy:108`) stays.
- No refactor of the deployment logic, stage structure, or error handling.
- No adoption of `ckAws.run([...])` — it does not exist, and the ECR pipeline at
  `Utilities.groovy:114` could not use it anyway.
- The agents' `~/.aws/config` stays in place throughout, as the fallback path.

---

## 5. Prerequisites — verify before Phase B

| # | Check | Why it blocks |
|---|---|---|
| 1 | Plugin ≥ M6 installed on Infra Jenkins and `ckAwsWithProfile` visible in the Snippet Generator | The step does not exist in the currently installed build |
| 2 | JCasC entries for `prod` and `non_prod` pointing at the ARNs the agents' `~/.aws/config` resolves to today | Read that file once, manually, to learn the mapping. The plugin never reads it at run time |
| 3 | Whether that config uses `credential_source = Ec2InstanceMetadata` or a `source_profile` chain | Determines what base identity the plugin reproduces, and whether chaining behaves the same |
| 4 | Target roles hold `ecr:GetAuthorizationToken` | `dockerLoginEcr` changes identity under migration (§2.3) |
| 5 | Typical wall-clock duration of a `nodeDeployment` build | Chained sessions cap at 1 hour and the plugin has no refresh yet |
| 6 | Agent has `aws` on `PATH` for the Jenkins user | Already true today, but the plugin now invokes it directly |

---

## 6. Migration order

1. Merge **Phase A**. Deploy. Run one normal (unmigrated) build of any app.
   *Expected: no observable difference anywhere.*
2. Add JCasC profile entries. Run one throwaway pipeline using
   `ckAwsWithProfile('non_prod') { sh 'aws sts get-caller-identity' }`.
   *Expected: ARN ends `assumed-role/<role>/jk-<job>-<build>`.*
3. Merge **Phase B** with `CK_AWS_PLUGIN` unset. Run a normal build.
   *Expected: no observable difference — the gate is off.*
4. Run one **non-production** `nodeDeployment` build with `CK_AWS_PLUGIN=true`.
   This is the real pilot.
5. Verify §7. Leave the pilot job opted in for a week of normal use.
6. Opt in remaining non-production jobs, then production, one at a time.
7. **Phase C**, one file per change.
8. Only once every consumer of a target role authenticates through Layer 1:
   apply the IAM trust-policy condition (a separate decision, separate approval,
   and irreversible in the sense that it breaks anything unmigrated).

---

## 7. Acceptance criteria for the pilot

- Build completes successfully end to end.
- CloudTrail in the target account shows `jk-<job>-<build>` as the session name on
  the `AssumeRole` event **and** on the `ecs:*`, `ssm:*` and `ecr:*` events made
  inside the blocks — not just on AssumeRole.
- No `botocore-session-*` sessions from that build.
- No credential material anywhere in the console log.
- ECR login succeeds (the §2.3 identity change).
- Deploy and rollback paths both exercised, or rollback exercised separately.
- Setting `CK_AWS_PLUGIN=false` restores the previous behaviour with no code
  change.

---

## 8. Rollback strategy

| Level | How | Speed |
|---|---|---|
| One build | Re-run with `CK_AWS_PLUGIN=false` | Immediate, no merge |
| One entry point | Revert that file's Phase B/C commit | One commit |
| All entry points | Revert Phase C then Phase B commits | Independent commits |
| Shared library | Revert Phase A | Byte-identical to today |
| Plugin itself | Reinstall previous `.hpi` | Unmigrated jobs unaffected |
| JCasC mapping | Revert the config commit | Unmigrated jobs unaffected |

The rollback path stays open because the agent-side `~/.aws/config` is never
removed. Deleting it is a separate decision, taken only after nothing depends on
it — and it is what makes the IAM trust policy in step 8 the point of no return,
not any of the changes above.

---

## 9. Risks and open questions

1. **1-hour chained-session cap, no refresh.** The highest risk. A block that
   runs longer than an hour fails on credential expiry. Mitigated in this plan by
   wrapping per stage rather than per pipeline, but unmeasured — prerequisite #5.
2. **`ecr:GetAuthorizationToken` on the target roles** — prerequisite #4. This is
   the most likely cause of a pilot failure.
3. **Region.** The library hardcodes `us-east-1`; DR targets `us-east-2`;
   Terraform reads `AWS_REGION`. Existing calls that pass `--region` explicitly
   keep winning over the block's `AWS_REGION`, which is correct and harmless — but
   it means the JCasC `region` field must not be assumed to control anything in
   this library.
4. **Declarative pipeline structure** forces per-stage wrapping rather than one
   wrapper per build; more AssumeRole calls, more CloudTrail volume.
5. **`params` vs `env` for the gate.** The plan reads `env.CK_AWS_PLUGIN`, which
   works for both a job parameter and an injected environment variable. If any
   entry point already defines a conflicting variable of that name, rename.
6. **Unverified beyond `nodeDeployment.groovy`.** The other 11 entry points were
   surveyed (line numbers in §1 are real) but their stage structure has not been
   read line by line. Phase C should re-verify each file before editing it.
