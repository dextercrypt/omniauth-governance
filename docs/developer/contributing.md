# Building & contributing

> How to build, run, and contribute to Omni Auth.

## Prerequisites

- **JDK 21** (Amazon Corretto 21 recommended).
- **Maven 3.9.x**.
- A local `jenkins-war-2.541.2.war` for running (see below).

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
mvn package -DskipTests   # → target/omni-auth.hpi
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

`mvn hpi:run` is **not** used (its embedded Jetty does not register the Jenkins
web-fragment, so Stapler/`WebAppMain` never initialize). Instead, run the standalone WAR
against a local home:

```bash
JENKINS_HOME=./work \
  java -jar jenkins-war-2.541.2.war --httpPort=9090 --prefix=/jenkins
# → http://localhost:9090/jenkins/
```

### Deploying a fresh build into the running home

```bash
cp target/omni-auth.hpi "$JENKINS_HOME/plugins/omni-auth.jpi"
rm -rf "$JENKINS_HOME/plugins/omni-auth" "$JENKINS_HOME/plugins/omni-auth.hpi"
# then restart Jenkins
```

> A leftover `.hpi` next to the `.jpi`, or a stale exploded directory, will shadow your new
> build. Remove both and restart.

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

## Capturing screenshots for docs

Pages marked _🖼 screenshot_ need images captured against a live instance:

1. Run locally (above) and configure the feature.
2. Capture the referenced screen at a consistent width.
3. Save under `docs/images/<area>/<name>.png` and link it from the page.

## Reporting issues

File issues in the [Jenkins issue tracker](https://issues.jenkins.io/) with Jenkins version,
plugin version, and relevant controller-log excerpts.

---
_Related: [Architecture](architecture.md) · [Extension points](extension-points.md)_
