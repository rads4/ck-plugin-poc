# ck-aws

A Jenkins plugin (proof of concept) that centralizes AWS authentication and
generic AWS CLI execution for deployment pipelines, so deployment Groovy no
longer performs STS calls directly.

**Status: M4 — the `ckAwsAssumeRole` pipeline step performs STS AssumeRole with
the `jk-<job>-<build>` session-name convention, via a generic process executor.
Generic AWS CLI execution (`ckAws.run([...])`), retry/timeout, and the
RunListener/JCasC paths are not implemented.**

See [CLAUDE.md](CLAUDE.md) for the architecture and milestone plan, and
[MEMORY.md](MEMORY.md) for session-by-session progress.

## Requirements

| | Version | Why |
|---|---|---|
| JDK | 17+ (21 recommended) | Parent POM enforces `[17,)`; warns unless LTS 17/21/25 |
| Maven | 3.9.6+ | Enforced by the plugin parent POM |
| Jenkins core | 2.479.2 | Matches CloudKeeper's production Jenkins version |

## Building

```bash
mvn verify          # compile, run tests, package target/ck-aws.hpi
```

## Running locally

The plugin is developed against a local Jenkins started by Maven:

```bash
mvn hpi:run -Dport=8081
```

Then browse to <http://localhost:8081/jenkins/>.

> **Note on the port:** `hpi:run` defaults to 8080. If a Jenkins service is
> already running on that port, startup fails with
> `java.net.BindException: Address already in use`. Pass `-Dport=8081` (or any
> free port) rather than stopping the other instance.

## Verifying the plugin loaded

Either check **Manage Jenkins → Plugins → Installed** for `CK AWS Plugin`, or
query the API:

```bash
curl -s "http://localhost:8081/jenkins/pluginManager/api/json?depth=1" \
  | jq '.plugins[] | select(.shortName=="ck-aws")'
```

Expect `"active": true` and `"requiredCoreVersion": "2.479.2"`.

The same assertion runs headlessly as part of `mvn verify` — see
`PluginLoadsTest`.

## Live AWS validation (M4) — temporary, remove in M5

Two system properties exist **only** to validate the plugin against a real AWS
account from a local `hpi:run` Jenkins. They are invisible to the Pipeline DSL —
the step's API is unchanged — and both are meant to be deleted in M5, together
with the `verifyIdentity` / `temporaryProfileEnvironment` methods in
`CkAwsAssumeRoleStep`.

| Property | Effect |
|---|---|
| `io.github.rads4.ckaws.awsProfile` | Sets `AWS_PROFILE` on the AssumeRole child process, selecting the base identity. Unset ⇒ plain environment inheritance, as before. |
| `io.github.rads4.ckaws.validateIdentity` | When `true`, verifies the issued credentials with one `sts get-caller-identity` call. Off by default. |

Neither property reads `~/.aws/config` — the plugin only names a profile and
the AWS CLI resolves it.

```bash
# the base profile's SAML session must be live
aws sts get-caller-identity --profile ops-admin

AWS_DEFAULT_REGION=us-east-1 mvn hpi:run -Dport=8081 \
  -Dio.github.rads4.ckaws.awsProfile=ops-admin \
  -Dio.github.rads4.ckaws.validateIdentity=true
```

Then run a pipeline job containing only:

```groovy
ckAwsAssumeRole(roleArn: 'arn:aws:iam::685502069032:role/ck-jenkins-plugin-validation-role')
```

A validation run makes **exactly two** AWS calls, both read-only:
`sts:AssumeRole` and `sts:GetCallerIdentity`.

> **Why `AWS_DEFAULT_REGION` is set explicitly.** The identity check runs with
> `AWS_PROFILE` removed — otherwise the CLI could answer from the base profile
> and the check would prove nothing — which also drops that profile's region.
> The CLI then falls back to the `default` profile's region, so without an
> explicit `AWS_DEFAULT_REGION` the two calls can land in *different* regions,
> and CloudTrail Event History is per-region. No region is defaulted in code: if
> none can be resolved, the CLI's own error is surfaced and the build fails.

## Project layout

```
pom.xml                                          plugin POM (hpi packaging)
src/main/java/io/github/rads4/ckaws/auth/        auth core (Jenkins- and CLI-agnostic)
src/main/java/io/github/rads4/ckaws/auth/cli/    the only class that knows `sts assume-role`
src/main/java/io/github/rads4/ckaws/exec/        generic process executor (no AWS awareness)
src/main/java/io/github/rads4/ckaws/steps/       the ckAwsAssumeRole pipeline step
src/main/resources/index.jelly                   description shown in Manage Plugins
src/test/java/io/github/rads4/ckaws/             tests
```

## Notes on deviations from the archetype

Generated from `io.jenkins.archetypes:empty-plugin:1.37`, with three
deliberate changes:

1. **Jenkins baseline lowered to 2.479.2** (archetype default: 2.528.3) to
   match CloudKeeper's actual Jenkins.
2. **Parent POM raised to `6.2211.v27f680c93c53`** (archetype pin:
   `6.2138.v03274d462c13`) — the current release.
3. **`.mvn/extensions.xml` and `.mvn/maven.config` removed.** These configure
   `git-changelist-maven-extension` and the incrementals profiles, used for
   publishing to the Jenkins update center from `jenkinsci`-hosted repos.
   Release/CI is out of scope for this POC, and the extension derives
   `${changelist}` from git history this repo does not yet have.
