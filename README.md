# ck-aws

A Jenkins plugin that centralizes **AWS identity** for builds. It assumes an AWS
role using a deterministic, build-attributable STS session name
(`jk-<job>-<build>`) and publishes the resulting temporary credentials into a
scoped region of a pipeline as standard AWS environment variables.

It does not run AWS commands, and it contains no organization-specific,
service-specific, or deployment-specific logic. Anything that consumes AWS
credentials — the AWS CLI, boto3, Terraform, Docker — consumes them the way it
always does.

**Status: M6 — layered architecture.** The block-scoped `ckAwsWithProfile` step,
JCasC-backed profile→role mapping, and agent-side execution are implemented. See
[CLAUDE.md](CLAUDE.md) for the architecture, [MEMORY.md](MEMORY.md) for
session-by-session history, and [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md)
for a codebase walkthrough.

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
    profiles:
      - name: "non_prod"
        roleArn: "arn:aws:iam::123456789012:role/non_prod"
        region: "us-east-1"
      - name: "prod"
        roleArn: "arn:aws:iam::210987654321:role/prod"
```

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

- **No credential refresh.** EC2 instance role → target role is role chaining,
  which caps the session at **1 hour** regardless of the role's configured
  maximum. A block that runs longer than an hour will fail on credential expiry.
- **No retry or timeout** around the AssumeRole subprocess.
- **No generic AWS CLI executor** (`ckAws.run([...])`). Deliberately optional and
  not yet implemented — see CLAUDE.md, Layer 2.
- **No RunListener / automatic profile injection.** Explicit block only.
- **No IAM trust-policy enforcement.** That is an AWS-side change, and it must
  come after every consumer has migrated.

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
docs/DEPLOYMENT_LIBRARY_INTEGRATION_PLAN.md      M7 proposal (not yet approved; nothing modified)
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
