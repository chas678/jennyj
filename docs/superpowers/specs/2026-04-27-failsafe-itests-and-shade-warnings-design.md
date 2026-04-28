# Failsafe ITs + shade-plugin warning cleanup — design

**Date:** 2026-04-27
**Status:** approved, ready for plan

## Problem

`mvn package` is slow because the test phase runs three solver-bound tests
that together take ~113 seconds:

| Test | Duration | Nature |
|------|----------|--------|
| `JennyBeatsBenchmarkTest` | 91.4 s | Solver run to convergence vs jenny.c |
| `SolverProfilingTest` | 20.8 s | Solver profiling, prints stats |
| `GreedyInitializerProfilingTest` | 1.8 s | Initializer profiling, prints stats |

The first is already gated behind `@Tag("benchmark")` and surefire's
`<excludedGroups>`, but the gate is fragile (it's been disabled in recent
runs). The other two have no gate at all.

`mvn package` also emits a wall of `[WARNING]` lines from
`maven-shade-plugin`. None are compilation warnings — all come from the
shade step. Three categories:

1. 15× *Discovered module-info.class. Shading will break its strong
   encapsulation.* — JPMS module descriptors from dependencies being
   inlined into the flat-classpath uber jar.
2. 5× *N JARs define overlapping resources* — `META-INF/LICENSE`,
   `META-INF/NOTICE`, `META-INF/LICENSE.md`, `META-INF/NOTICE.md`,
   `META-INF/LICENSE.txt`.
3. 1× *N JARs define overlapping classes:
   `META-INF.versions.9.module-info`* — multi-release JAR module
   descriptors.
4. 1× *N JARs define overlapping resources: `META-INF/MANIFEST.MF`* —
   informational; the existing `ManifestResourceTransformer` already
   resolves it correctly, but the warning still fires.

## Goals

- `mvn test` and `mvn package` finish in seconds, not minutes.
- `mvn verify` runs the full suite including the long-running tests.
- `mvn package` emits zero `[WARNING]` lines from `maven-shade-plugin`.
- Keep license attributions from dependencies in the uber jar.

## Non-goals

- Changing what the long-running tests assert. Their bodies are unchanged.
- Adding Maven profiles. The standard `test` vs `verify` distinction is
  enough.
- Adding new test categories beyond unit / integration.
- Touching tests that already run quickly (`BenchRunnerTest` at 0.4 s,
  `CliRegressionTest` which runs picocli in-process).

## Design

### Test layout

The three solver-bound tests are renamed to follow Failsafe's default
naming convention (`*IT.java`):

| Old class | New class |
|-----------|-----------|
| `com.burtleburtle.jenny.solver.JennyBeatsBenchmarkTest` | `com.burtleburtle.jenny.solver.JennyBeatsBenchmarkIT` |
| `com.burtleburtle.jenny.solver.SolverProfilingTest` | `com.burtleburtle.jenny.solver.SolverProfilingIT` |
| `com.burtleburtle.jenny.bootstrap.GreedyInitializerProfilingTest` | `com.burtleburtle.jenny.bootstrap.GreedyInitializerProfilingIT` |

The `@Tag("benchmark")` annotation is removed from
`JennyBeatsBenchmarkIT` — the filename convention now does the gating.

The test bodies are otherwise unchanged.

### POM changes — surefire / failsafe

Remove the existing tag-based exclusion machinery:

- Delete the `<surefire.excludedGroups>benchmark</surefire.excludedGroups>`
  property.
- Delete the `<excludedGroups>${surefire.excludedGroups}</excludedGroups>`
  element from the surefire plugin config.
- Delete the surrounding comment that documents the property.

Add `maven-failsafe-plugin` 3.5.2 (matching surefire) with:

- Same `argLine` as surefire (`-Dnet.bytebuddy.experimental=true`).
- Default executions bound to `integration-test` and `verify` goals.
- No `<includes>` override — failsafe's defaults (`*IT.java`,
  `IT*.java`, `*ITCase.java`) cover the renamed files.

Resulting build behavior:

| Command | What runs | Approx duration |
|---------|-----------|-----------------|
| `mvn test` | surefire only | seconds |
| `mvn package` | surefire + jar + shade | seconds |
| `mvn verify` | surefire + jar + shade + failsafe | ~2 minutes |
| `mvn verify -DskipITs` | as `mvn package` | seconds |

### POM changes — shade plugin warnings

The shade plugin block gains two additions inside its `<configuration>`:

**1. `<filters>` to drop module descriptors from dependencies.**

```xml
<filters>
    <filter>
        <artifact>*:*</artifact>
        <excludes>
            <exclude>module-info.class</exclude>
            <exclude>META-INF/versions/*/module-info.class</exclude>
            <exclude>META-INF/MANIFEST.MF</exclude>
        </excludes>
    </filter>
</filters>
```

The uber jar is a flat-classpath jar, not a JPMS module, so dependency
module descriptors are dead weight. Excluding `META-INF/MANIFEST.MF` is
safe because `ManifestResourceTransformer` synthesises a fresh manifest
from project metadata.

**2. License/notice transformers to merge attribution files.**

Two new transformers are added alongside the existing
`ManifestResourceTransformer` and `ServicesResourceTransformer`:

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ApacheLicenseResourceTransformer"/>
<transformer implementation="org.apache.maven.plugins.shade.resource.ApacheNoticeResourceTransformer"/>
```

These are bundled with `maven-shade-plugin` (no extra dependency
needed). They concatenate `META-INF/LICENSE*` and `META-INF/NOTICE*` from
all source jars into a single file each in the uber jar, preserving
attribution.

### Verification

A clean `mvn package` after these changes must:

- Build green.
- Print zero `[WARNING]` lines from shade-plugin.
- Skip all three renamed integration tests in the surefire phase.

A `mvn verify` must:

- Run all surefire tests.
- Run all three failsafe ITs.
- Build green.

The shaded uber jar must:

- Boot the CLI as today (`java -jar target/jenny.jar -n2 3 3 3` produces
  the same output).
- Contain merged `META-INF/LICENSE` and `META-INF/NOTICE` files.

## Risks

- **Shade filter scope.** The `*:*` artifact selector applies the
  module-info exclusion to every dependency. Some libraries embed code in
  `META-INF/versions/9/` that is not a module descriptor; the filter only
  excludes `module-info.class` paths, so those classes survive.
- **Manifest filter.** Excluding dependency `META-INF/MANIFEST.MF` files
  is correct only because `ManifestResourceTransformer` rebuilds the
  manifest. If anyone later removes that transformer, the uber jar would
  ship without a manifest. Mitigated by leaving a one-line comment in
  the POM near the filter explaining the dependency.
- **Failsafe doesn't fail the build by default until `verify`.** The
  `mvn integration-test` goal alone reports failures but does not
  exit non-zero — that's `verify`'s job. Documented in Maven; mentioned
  here so future contributors don't get surprised.

## Out of scope

- `BenchRunnerTest` (0.4 s) — stays in surefire.
- `CliRegressionTest` — stays in surefire (picocli runs in-process; fast).
- The `@Tag` JUnit annotation infrastructure stays available; the
  project may want finer-grained tagging within failsafe later (e.g.
  `@Tag("nightly")` for tests that should run on a schedule).
- No CI configuration changes. The project has no CI yet.
