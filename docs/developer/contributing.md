# Building & contributing

> How to build, run, and contribute to OmniAuth.

## Prerequisites

- **JDK 21** (Amazon Corretto 21 recommended).
- **Maven 3.9.x**.
- No separate Jenkins download is needed — the plugin runs via `mvn hpi:run` (see below).

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
mvn package -DskipTests   # → target/omniauth-governance.hpi
```

Run the tests with a normal `mvn test` / `mvn verify`.

### Build configuration notes

The `pom.xml` intentionally disables two checks that are incompatible with the current
Maven/JDK toolchain:

- `license-maven-plugin` is bound to `phase: none`, and `license.skip=true` — its Groovy/ASM
  stack cannot parse some newer class files on Maven 3.9.13's classpath.
- `spotless.check.skip=true`.

`access-modifier-checker` is set to `failOnError=false` to allow extending
`@Restricted` matrix-auth converter classes.

## Run locally

`mvn hpi:run -Dport=9090` builds the plugin and launches Jenkins with it loaded, resolving
the full dependency-plugin closure automatically. It serves at a `/jenkins` context path with
`JENKINS_HOME` defaulting to `./work`:

```bash
mvn hpi:run -Dport=9090
# → http://localhost:9090/jenkins/
```

To pick up source changes, stop and restart `hpi:run` — it recompiles and repackages on launch.

> Stop `hpi:run` before running `mvn test`: the test harness forks its own Jenkins and will
> conflict with a running instance on the same port.

## Project layout

```
src/main/java/io/jenkins/plugins/omniauth/   # plugin classes (+ util/)
src/main/resources/io/jenkins/plugins/omniauth/
    OmniAuthSecurityRealm/     # login.jelly, config.jelly, …
    OmniAuthManagementLink/    # console views
    EntraOAuthConfig/          # config.jelly
    OmniAuthAuthorizationStrategy/
    OmniAuthPageDecorator/, OmniAuthRootAction/
src/test/…                     # JenkinsRule integration tests
docs/                          # this documentation
```

See [Architecture](architecture.md) for the class-level map.

## Coding conventions

- Match the surrounding code's style, naming, and comment density.
- Keep SSO/provider logic behind `TokenHelper` so it stays testable (see
  [Extension points](extension-points.md)).
- Add or update integration tests (`JenkinsRule`) for behavior changes.
- Update the relevant docs page and the [Changelog](../CHANGELOG.md) with any user-facing
  change.

## Commit & PR conventions

- Write clear, human-style commit messages describing the change and its rationale.
- Reference the issue where applicable.
- Follow the Jenkins
  [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md).

## Reporting issues

File issues in the [Jenkins issue tracker](https://issues.jenkins.io/) with Jenkins version,
plugin version, and relevant controller-log excerpts.

---
_Related: [Architecture](architecture.md) · [Extension points](extension-points.md)_
