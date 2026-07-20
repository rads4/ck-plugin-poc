# ck-aws

A Jenkins plugin (proof of concept) that centralizes AWS authentication and
generic AWS CLI execution for deployment pipelines, so deployment Groovy no
longer performs STS calls directly.

**Status: M0 — scaffold only. No authentication, executor, or pipeline steps
are implemented yet.**

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

## Project layout

```
pom.xml                                          plugin POM (hpi packaging)
src/main/java/io/github/rads4/ckaws/             plugin source (empty at M0)
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
