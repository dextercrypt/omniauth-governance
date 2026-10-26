# Platform support & upgrade policy

> Which Jenkins and Java versions OmniAuth supports, why the floor is where it is, and the
> playbook for moving the floor forward when a Java or Jenkins major version is forced on us.

The guiding rule: **we support our declared baseline and every version newer than it.** The
baseline is the *oldest* environment we promise to run on — never a ceiling. Raising the
baseline is a deliberate, documented decision, not something that happens by accident.

---

## Current support matrix

As of the current baseline:

| Axis | Supported | Where it is set |
|---|---|---|
| **Jenkins (minimum)** | **2.541.2** | `pom.xml` → `<jenkins.version>` |
| **Jenkins (newer)** | any later weekly or LTS | runs forward by policy |
| **Java** | **17 or 21** | derived from `jenkins.version` by the parent POM |
| **Built & verified on** | **Java 21** (Amazon Corretto 21) | CI / local build |
| **Parent plugin POM** | `4.80` | `pom.xml` `<parent>` |

> The README advertises **"Jenkins 2.541.2+ · Java 21"**. That is the *tested* claim and is
> intentionally narrower than the technical floor below. Only widen it to "Java 17 or 21" once
> a Java 17 build/test run has actually been done (see [Pre-release verification](#pre-release-verification)).

---

## What actually forces the floor

The baseline is not an arbitrary number. Two hard constraints set the *real* technical floor,
and one convention sets the *declared* one.

### 1. Jakarta servlet migration — hard floor ≈ Jenkins 2.479.1 LTS

OmniAuth implements servlet-layer components directly against the **`jakarta.servlet.*`**
namespace — `Filter`s (`LoginContextFilter`, `OmniAuthPageDecorator`) and `CrumbExclusion`s
(`SosCrumbExclusion`, `NativeLoginCrumbExclusion`), plus `jakarta.servlet.http.*` across the
request-handling code.

Jenkins core switched `javax.servlet` → `jakarta.servlet` at **weekly 2.475 / LTS 2.479.1**
(late 2024). On anything older, those classes do not exist with the right signatures and the
plugin **will not load at all**. This cannot be toggled off — it is a compile-time binding.
Running below the jakarta cutover is impossible without rewriting every filter back to `javax`.

> A few call sites still use the pre-jakarta **Stapler** API (`StaplerRequest`/`StaplerResponse`,
> not `StaplerRequest2`), which survives via a compatibility bridge. That is why one
> `javax.servlet.http.HttpSession` reference remains. It does **not** lower the floor — the
> jakarta filters already set it.

### 2. matrix-auth dependency — reinforces the jakarta-era floor

The entire authorization engine is built on the **Matrix Authorization Strategy** plugin:
`OmniAuthAuthorizationStrategy extends ProjectMatrixAuthorizationStrategy`, and we use its
`PermissionEntry` / `AuthorizationType` / `AuthorizationMatrixProperty` types and XML
converters. The pinned version (`matrix-auth` `3.2.9`) requires a jakarta-era Jenkins, so it
aligns with — and does not raise — the floor set by constraint 1.

matrix-auth is **not bundled in Jenkins core**, but it is a **suggested plugin** in the setup
wizard and is installed on the vast majority of controllers. We depend on it deliberately;
removing it would mean reimplementing a mature, security-audited authorization engine from
scratch. It is listed under Requirements in the README and must stay declared as a dependency.

### 3. Declared baseline — a convention, not a hard limit

The hard floor is ~2.479.1, but we **declare** 2.541.2 because that is the LTS line we actively
develop and test against. Keeping a recent-but-not-bleeding-edge baseline is the standard
Jenkins practice (roughly the last ~12 months of LTS). Lowering the declaration toward the true
floor is possible but not worth the extra test burden for a shrinking set of old installs.

---

## How the floor drives everything else

Changing the single line `<jenkins.version>` cascades:

- The **parent plugin POM derives the Java compile level** from it. We never set `java.level`
  or `maven.compiler.release` ourselves. Bumping Jenkins is how the Java target moves.
- It sets the **oldest API** we may call. Using an API introduced in a newer core raises the
  floor whether we intend it or not — so new API use is a baseline decision.
- Plugin dependency versions (matrix-auth, cloudbees-folder, Jakarta Mail…) must stay
  compatible with the chosen line. Today they are pinned by hand; a baseline bump is the moment
  to consider importing the **Jenkins plugin BOM** for that line so they move as a tested set.

**"Support Java 25" and "raise the Jenkins baseline" are the same action.** We do not select a
Java version directly — we select a Jenkins LTS that supports it, and Java follows.

---

## The forward playbook

Two clocks force the baseline forward. Watch both; act before, not after, the deadline.

### Clock A — Java major end-of-life

The running JVM warns when its Java line nears end of support in Jenkins.

| Java | Role today | End-of-support signal |
|---|---|---|
| Java 17 | lower bound of current line | dropped from LTS at 2.555.1 (Apr 2026) |
| Java 21 | our tested JVM | Jenkins EOL on/after **2027-09-30** |
| Java 25 | next target | Jenkins EOL warnings begin **Mar 2029** |

When our tested Java line approaches EOL, the move is: pick the current LTS that supports the
**next** Java LTS (e.g. a 2.555.1+ line for Java 25), bump the baseline, and re-verify. Java 25
is the long-runway choice once we move.

### Clock B — Jenkins LTS aging out

Independently of Java, let the declared baseline drift too far behind the current LTS and we
lose test coverage and start depending on behaviour users have moved past. Revisit the baseline
at least **once a year**, and whenever a dependency (matrix-auth, folders) ships a release that
needs a newer core.

### Upgrade procedure (raising the baseline)

1. **Branch.** Never change the baseline on `main` directly.
2. **Bump** `<jenkins.version>` in `pom.xml` to the target LTS.
3. **Align dependencies** — bump matrix-auth / cloudbees-folder / etc. to versions compatible
   with that line; consider importing the matching plugin **BOM** instead of hand-pinning.
4. **Build on the target Java** — `export JAVA_HOME=<target JDK>` then `mvn -o clean verify`.
   Fix any compile errors (removed APIs) and failing tests.
5. **Manual smoke test** on the target Jenkins + Java (see below) — automated tests under-cover
   the servlet/filter/UI paths that version changes break.
6. **Classify the change.** Dropping a Java version or an old Jenkins line is a **breaking
   requirement change** → it ships in a MINOR/MAJOR release with a clear CHANGELOG entry, never
   a patch. See [Releasing & versioning](releasing.md).
7. **Update docs** — this matrix, the README badges/Requirements, and
   [getting-started/requirements.md](../getting-started/requirements.md).

---

## Pre-release verification

Before any release — and especially before claiming "runs on the latest" — verify **two
independent axes**. The first is cheap; the second is the one that actually gives confidence.

### Axis A — test suite on the newest Java (cheap, run first)

Proves our bytecode and dependencies are happy on a new JVM. Runs against the current
`jenkins.version` core, just on a newer JDK:

```bash
export JAVA_HOME=/path/to/jdk-25     # e.g. Corretto 25 or Temurin 25
mvn clean verify
```

### Axis B — plugin inside the latest Jenkins (the real test)

Proves the servlet filters, CrumbExclusions, login flows and UI work on the newest core. For a
security plugin this manual pass is **non-negotiable** — unit tests do not cover it.

```bash
# build the hpi (on the new JDK, to flush out compile issues)
export JAVA_HOME=/path/to/jdk-25
mvn -o clean package -DskipTests

# deploy into a CLEAN work dir (stale .hpi shadows the .jpi)
cp target/omniauth-governance.hpi work/plugins/omniauth-governance.jpi
rm -f work/plugins/omniauth-governance.hpi && rm -rf work/plugins/omniauth-governance

# run the LATEST LTS war on the new JDK
JENKINS_HOME=$PWD/work java -jar jenkins-war-<latest-LTS>.war --httpPort=9090 --prefix=/jenkins
```

Then walk the critical paths by hand:

- [ ] Native login **and** SSO login
- [ ] Authorization matrix + scoped / time-limited grants
- [ ] JIT request → approve → auto-expire
- [ ] **SOS** `/sos/recover` (arm, recover with a code, deactivate)
- [ ] Break Glass TOTP step-up
- [ ] Audit log records and filters correctly
- [ ] Login-page banners / branding filters render

Passing both axes on the latest LTS + newest Java lets us honestly state "verified on Jenkins
\<latest\> + Java \<N\>" **without** having to raise the declared baseline.

---

## Decision log

- **Baseline held at Jenkins 2.541.2 / Java 21** — current. Tested on Corretto 21. Java 21 is
  fully supported on this LTS line; no benefit to bumping until a forced move. Forward
  compatibility is handled by the verification axes above, not by raising the floor.
- **2026-10-26 — Forward-compatibility verified on Jenkins 2.580.1 (latest LTS) + Corretto 25**
  (no baseline change). Axis A: `mvn clean verify` on JDK 25 → **553 tests, 0 failures**, hpi
  built; enforcer `RequireJavaVersion` / `EnforceBytecodeVersion` and `access-modifier-checker`
  all passed. Only **SpotBugs 4.8.3.1** could not read Java 25 bytecode ("class file major
  version 69") → skipped; it is build-time tooling and does not affect runtime. Axis B: the hpi
  deployed into Jenkins 2.580.1 running on Corretto 25 — plugin loaded cleanly, OmniAuth realm
  active, native login + CSRF exclusion worked, and the Overview / Access Management / Audit Log
  / Emergency Access / Settings pages all rendered HTTP 200 with no stack traces. The SOS live
  cycle passed end-to-end (dark → armed `/sos/recover` form → deactivated → dark). **Conclusion:
  OmniAuth runs on the latest Jenkins LTS and Java 25 today; the baseline stays at 2.541.2 /
  Java 21 by choice.** Known follow-up for an eventual bump: raise the SpotBugs version so static
  analysis parses Java 25.

Record each future baseline change here with the date, the trigger (Clock A or B), the old →
new versions, and the release it shipped in.
