# Releasing & versioning

> How the OmniAuth version is determined, surfaced, and how releases are cut.

## Where the version comes from

The plugin version is **derived from the build** — never hardcoded in source or docs.

- At runtime it is read from the plugin wrapper (`PluginWrapper.getVersion()`), surfaced on the
  **About / Info** page (**Manage Jenkins → OmniAuth → Info**).
- In a development build it reports **`1.0.0-SNAPSHOT`**.
- The released version is the Maven artifact version baked into the `.hpi` at package time.

Because it is derived, there is nothing to keep in sync by hand: bump the Maven version and the
About page, manifest, and update-center metadata all follow.

## Versioning scheme

The project follows [Semantic Versioning](https://semver.org/):

- **MAJOR** — incompatible changes to configuration, stored data shape, or the permission model.
- **MINOR** — backward-compatible features.
- **PATCH** — backward-compatible fixes.

Pre-release builds use a suffix, e.g. `1.0.0-beta.1`.

## Cutting a release

There is **no Jenkins CD pipeline** for this plugin; releases are cut manually.

1. Ensure `main` is green (`mvn test`) and the [CHANGELOG](../CHANGELOG.md) reflects what's shipping.
2. Set the release version in `pom.xml` (drop `-SNAPSHOT`, or use a `-beta.N` suffix).
3. Build the artifact: `mvn package -DskipTests` → `target/omni-auth.hpi`.
4. Tag the commit (e.g. `git tag v1.0.0-beta.1`) and push the tag.
5. Attach the `.hpi` to the release.
6. Bump `pom.xml` back to the next `-SNAPSHOT` for ongoing development.

## First public release

The first public release is tagged **`v1.0.0-beta.1`**. It marks the feature-complete baseline
documented in the [CHANGELOG](../CHANGELOG.md) — authentication (native + Entra SSO),
authorization and access governance, JIT, provisioning portal, audit + retention, dashboard,
backup/restore, and notifications.

---
_Related: [Contributing](contributing.md) · [Changelog](../CHANGELOG.md)_
