# Infra Jenkins outage, 2026-08-18 17:45–18:15 UTC — analysis

**Question asked:** could ck-aws 2.2.0 have caused it?
**Answer: no, and this is established from evidence rather than argued from design.**
The mechanism that *did* produce the outage is identified below, and it is a latent defect in the
systemd unit that was going to fire on some restart regardless of which plugins were installed.

All findings below are read-only observations. No change was made to any infra host.

---

## 1. What happened, from three independent sources

**ALB target group `jenkins-graviton`** (times IST; UTC = −5:30):

```
23:10   healthy=1   requests=301               normal
23:15   healthy=0   conn-errors=55   5xx=22    restart; target drops out
23:20   healthy=0   conn-errors=35
23:25   healthy=0   conn-errors=59
23:30   healthy=0   conn-errors=40
23:35   healthy=0   conn-errors=6
23:45   healthy=1   requests=208               replacement instance serving
```

`TargetConnectionErrorCount` totalled ~200 while `HTTPCode_Target_5XX_Count` was 22, all in the
first bucket. **The ALB could not open TCP to port 8080.** That is a process that is not running —
not an application returning errors — and it is what surfaces to users as 502/504.

**EC2 CPU on `i-0924a915a1c76f33e`:** 55–68% peak at 23:15–23:20 (JVM starting), then **flat 1.4%
for 25 minutes**. Nothing was executing.

**Console output:** kernel uptime 12,126,789 s ≈ 140 days at power-off, then a clean graceful
shutdown. No OOM, no panic, no JVM crash. **The host never failed; the Jenkins service did.**

**Recovery:** `jenkins-new` (`i-0007d48a74436e085`) launched 18:10:48 UTC from an older AMI;
`jenkins-17` stopped 18:18:15 UTC by prerana@cloudkeeper.com.

---

## 2. The mechanism — a 90-second hard kill with an 11-second margin

The `jenkins.service` unit, read off the POC clone (built from the infra AMI, so this *is* infra's
unit):

```
Type=notify
NotifyAccess=main
TimeoutStartUSec=1min 30s     <- 90 s to signal readiness
Restart=on-failure            <- a start timeout counts as failure
RestartUSec=100ms             <- retries almost immediately
StartLimitBurst=5             <- five attempts...
StartLimitIntervalSec=5m      <- ...in five minutes, then systemd STOPS TRYING
```

`Type=notify` means the 90-second clock runs until Jenkins itself signals "fully up and running".
On a controller with 808 jobs, 206 plugins and years of build history, that is not a generous
budget — and the margin is now measured, not guessed.

**From `jenkins-new`'s own journal, the night of the incident:**

```
18:12:09  starting, PID 667
18:12:31  "Started initialization"
18:14:35  systemd: jenkins.service: Failed with result 'timeout'
18:14:35  systemd: Failed to start jenkins.service
18:14:37  retry, PID 3089
18:15:56  "Jenkins is fully up and running"        <- 79 seconds
```

**Infra Jenkins boots in ~79 seconds against a 90-second hard kill. Eleven seconds of headroom,
about 12%.** And `jenkins-17` carried more build history than the restored AMI, so its boot was
slower still.

Once a boot exceeds 90 s the outcome is not a slow start, it is a **permanent stop**: killed,
retried five times in five minutes, then abandoned by systemd. Port 8080 never opens again. That
is precisely the observed signature — connection errors rather than 5xx, CPU flat because no JVM
is running, and an outage that ends only when a human intervenes.

---

## 3. Why ck-aws 2.2.0 is not the cause

Each of these is a checked fact, not a design argument.

| # | Check | Result |
|---|---|---|
| 1 | Sezpoz `@Extension` index (the list Jenkins instantiates at boot), 2.1 vs 2.2.0 | **byte-identical** — same 7 extensions |
| 2 | New top-level classes in 2.2.0 | **none** |
| 3 | Bundled third-party jars | **zero, in both** — the `.hpi` contains only `ck-aws.jar`. No classpath surface, so no version conflict is possible |
| 4 | `@Initializer` / `PeriodicWork` / `ItemListener` / `@PostConstruct` | **none** — the plugin contributes nothing to Jenkins' init reactor |
| 5 | Static initializer blocks, threads, timers at class-load | **none** |
| 6 | Plugin dependencies | `workflow-step-api:700…`, **same as 2.1**; infra had 710 |
| 7 | Jar size | 87,784 → 101,152 bytes (+13 KB of bytecode) |

**And the runtime gate.** Both entry points test the master switch as their *first statement*:

```java
CkAwsGlobalConfiguration configuration = CkAwsGlobalConfiguration.get();
if (configuration == null || !configuration.isManagedAuthentication()) {
    return null;
}
```

`ManagedAwsContext.contribute():252` and `ManagedAwsFreestyleEnvironment.contribute():80`. Two
in-memory reads — a registry lookup and a volatile boolean — then return. No I/O, no context
lookup, no remote call.

Infra's config XML, read before the upload, is `<managedAuthentication>false</managedAuthentication>`.
So the whole chain is dead: `contribute()` returns at line 254 → `prepareOnce` never runs →
`ManagedAwsRecord.record` (`ManagedAwsContext:601`, the only place a `ManagedAwsAction` is attached)
never runs → `ManagedCleanupListener.onFinalized` exits at its null check, so it never makes the
remote `FilePath.deleteRecursive()` call that would be the one blocking operation in the plugin.
IMDS resolution is a `MasterToSlaveFileCallable` on the prepare path, behind the same gate.

**Empirical confirmation.** POC clone (same AMI lineage, 834 jobs, 205 plugins), ck-aws **2.2.0**
installed, `managedAuthentication=false` — exactly infra's configuration — restarted 2026-08-19
05:51:42 UTC:

```
05:51:46  Started initialization
05:51:59  Started all plugins
05:52:19  Completed initialization
05:52:20  Jenkins is fully up and running          <- 38 seconds
plugin load failures: 0        systemd start timeouts: 0
```

---

## 4. What is NOT established

**Which change pushed infra past 90 seconds is unknown**, and cannot be determined without
`journalctl -u jenkins` from `i-0924a915a1c76f33e` for 17:45–18:15 UTC. That instance is stopped;
its disk is intact and a post-failure AWSBackup snapshot exists (`snap-0cf4943a553381575`), but
reading either requires attaching a volume, which was declined.

The restart carried **three** plugin changes, and the rollback reverted all three, so the
successful boot isolates nothing:

| | jenkins-17 (failed) | jenkins-new (healthy) |
|---|---|---|
| ck-aws | 2.2.0 | 2.1 |
| role-strategy | 170,605 B (17 Aug, by randeep.arora@) | 164,023 B (Nov 2025) |
| commons-lang3-api | 648,617 B (17 Aug, dependency) | 613,078 B (Sep 2024) |

`role-strategy` is the live authorization strategy and is constructed while `config.xml` is
unmarshalled, early in startup, against 459 user records — so a major-version upgrade there is a
more plausible source of extra seconds than a plugin that adds no extensions and does nothing.
**That is a hypothesis and is labelled as one.** It is not a finding.

---

## 5. The conclusion that matters more than blame

**Infra Jenkins runs an 11-second margin against a hard 90-second kill, with `Restart=on-failure`
and `StartLimitBurst=5`.** Every restart is a coin flip; when it loses, systemd gives up
permanently and the outage lasts until someone notices. Boot time only grows — more jobs, more
history, more plugins — so this was going to fire on some restart regardless of ck-aws.

**Remediation is one drop-in file** (an infra change, for whoever owns that controller — not done
here):

```ini
# /etc/systemd/system/jenkins.service.d/override.conf
[Service]
TimeoutStartSec=600
```

`TimeoutStartSec=0` disables the limit entirely, which is what the Jenkins project's own packaging
moved to for exactly this reason. Either way, verify with `systemd-analyze` after the next restart
and record the actual boot duration so the margin is known rather than discovered.

**Do not restart infra Jenkins again — with or without ck-aws — until this is fixed.** The next
restart has the same ~12% margin that this one did.
