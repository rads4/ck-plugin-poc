# ck-aws

A Jenkins plugin (proof of concept) that centralizes AWS authentication and
generic AWS CLI execution for deployment pipelines, so deployment Groovy no
longer performs STS calls directly.

**Status: M5 (production packaging) — complete. The `ckAwsAssumeRole` pipeline
step performs STS AssumeRole with the `jk-<job>-<build>` session-name
convention, via a generic process executor, and has been validated against real
AWS. The temporary M4 validation surface has been removed; `target/ck-aws.hpi`
is the installable artifact. Generic AWS CLI execution (`ckAws.run([...])`),
retry/timeout, and the RunListener/JCasC paths are not implemented.**

See [CLAUDE.md](CLAUDE.md) for the architecture and milestone plan,
[MEMORY.md](MEMORY.md) for session-by-session progress, and
[docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md) for a full walkthrough of the
codebase.

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

## Usage

The plugin contributes one Pipeline step. It returns the generated session name
and nothing else — no credential material reaches the Pipeline DSL.

```groovy
def session = ckAwsAssumeRole(roleArn: 'arn:aws:iam::123456789012:role/non_prod')
// session == 'jk-<job>-<build>'
```

The build log shows:

```
[ck-aws] Assuming role arn:aws:iam::123456789012:role/non_prod as session jk-myjob-123
[ck-aws] Assumed role  arn:aws:iam::123456789012:role/non_prod as session jk-myjob-123
```

CloudTrail in the target account records that same `jk-<job>-<build>` session
name on the `AssumeRole` event — the point of the whole plugin.

The step is available in the Snippet Generator as **"Assume an AWS role for this
build"**.

### Requirements at run time

- The `aws` CLI must be on the controller's `PATH`.
- A base identity the controller can use to call `sts:AssumeRole` (instance
  role, `AWS_PROFILE`, etc.) must be resolvable from the controller's ambient
  environment, and a region must resolve. The plugin does **not** read
  `~/.aws/config`; the AWS CLI resolves both itself.
- The target role's trust policy must permit that base identity.

### Known limitations (POC)

- The AssumeRole subprocess runs on the **Jenkins controller JVM**, not on the
  agent selected by an enclosing `node` block.
- Credentials are not exported to subsequent steps — that is a future
  block-scoped `withProfile { }` step.
- No retry, no timeout, no credential caching/refresh. Chained sessions are
  capped at 1 hour.
- Profile→role ARN mapping is not implemented; the ARN is passed explicitly.

### System properties

| Property | Effect |
|---|---|
| `io.github.rads4.ckaws.awsExecutable` | Overrides the `aws` executable used for the AssumeRole call. A test hook (the child inherits the JVM environment, so tests cannot prepend to `PATH` in-process). Defaults to `aws`. |

## Installing the built plugin

```bash
mvn clean verify        # produces target/ck-aws.hpi
```

Install `target/ck-aws.hpi` via **Manage Jenkins → Plugins → Advanced settings →
Deploy Plugin**, then restart Jenkins. The target Jenkins must be 2.479.2 or
newer.

## Live AWS validation

The full stack was validated against real AWS from a local `hpi:run` Jenkins:
AssumeRole succeeded and `sts get-caller-identity`, called with the issued
temporary credentials, returned an ARN ending in
`assumed-role/<role>/jk-<job>-<build>` — confirming the session-name convention
survives real STS and is build-scoped. Exactly two read-only AWS APIs were
exercised: `sts:AssumeRole` and `sts:GetCallerIdentity`. See
[MEMORY.md](MEMORY.md) (Session 5) for the evidence.

The temporary system properties used to drive that validation
(`io.github.rads4.ckaws.awsProfile`, `io.github.rads4.ckaws.validateIdentity`)
and their supporting code were **removed in M5** and no longer exist.

## Project layout

```
pom.xml                                          plugin POM (hpi packaging)
src/main/java/io/github/rads4/ckaws/auth/        auth core (Jenkins- and CLI-agnostic)
src/main/java/io/github/rads4/ckaws/auth/cli/    the only class that knows `sts assume-role`
src/main/java/io/github/rads4/ckaws/exec/        generic process executor (no AWS awareness)
src/main/java/io/github/rads4/ckaws/steps/       the ckAwsAssumeRole pipeline step
src/main/resources/index.jelly                   description shown in Manage Plugins
src/test/java/io/github/rads4/ckaws/             tests
docs/DEVELOPER_GUIDE.md                          codebase walkthrough for new maintainers
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
