# Releasing

`jenny` follows [Semantic Versioning](https://semver.org/). The pom `<version>`
is the single source of truth; the git tag `vX.Y.Z` must equal it, and the
release asset is the shaded `jenny.jar`. The public API for SemVer is the CLI
surface (flags + output format), not solver internals.

## Cut a release

1. **Changelog** — move the `## [Unreleased]` entries into a new
   `## [X.Y.Z] - YYYY-MM-DD` section in `CHANGELOG.md`; add the compare/tag links
   at the bottom.
2. **Version** — set `<version>X.Y.Z</version>` in `pom.xml` (no `-SNAPSHOT`).
3. **Verify locally** — `mvn -DskipITs package` and
   `java -jar target/jenny.jar --version` prints `jenny X.Y.Z`. For a real
   release also run the quality oracle: `mvn verify -Dit.test=JennyBeatsBenchmarkIT`.
4. **Commit & tag**
   ```bash
   git commit -am "Release vX.Y.Z"
   git tag vX.Y.Z
   git push && git push --tags
   ```
5. **Automation** — pushing the tag triggers `.github/workflows/release.yml`, which
   builds the jar, creates the GitHub release with `jenny.jar` attached, and opens
   a PR (or commits) to the `chas678/homebrew-jennyj` tap updating the formula's
   `url` + `sha256`.
6. **Verify the install** — after the tap formula lands:
   ```bash
   brew update && brew upgrade chas678/jennyj/jenny   # or `brew install` first time
   jenny --version
   brew test chas678/jennyj/jenny
   ```

## Versioning guidance

- **MAJOR** — a breaking change to CLI flags or output format.
- **MINOR** — new flags/features that stay backward-compatible.
- **PATCH** — bug fixes, solver tuning, dependency bumps (e.g. Timefold) that
  don't change the CLI contract.
