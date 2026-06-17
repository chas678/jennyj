# Packaging exploration — making jenny a download-and-run CLI

Status: **exploration / decision plan** (no build changes made). Goal: distribute
jenny as a simple "download and execute" CLI instead of a `java -jar` invocation that
requires a pre-installed JRE.

Distribution targets assumed: **macOS arm64** and **Linux x64**. _[ASSUMPTION — confirm;
add macOS x64 / Windows x64 if needed. This materially changes the CI matrix for every
non-jar option, since none of them cross-compile.]_

---

## 1. Current state (verified)

| Fact | Value | Source |
|------|-------|--------|
| Artifact | shaded uber-jar `target/jenny.jar` | `pom.xml` maven-shade-plugin, `finalName=jenny` |
| Size | **12 MB** (12,154,027 bytes) | `du`/`ls` on `target/jenny.jar` |
| Run command | `java -jar target/jenny.jar …` | README |
| Requires | a separately-installed **Java 26** JRE | not bundled — this is why it is not "download-and-run" today |
| Main class | `com.burtleburtle.jenny.cli.JennyCli` (picocli) | shade `ManifestResourceTransformer` |
| Cold start (`-h`) | **~0.08–0.10 s** (3 runs: 0.10 / 0.08 / 0.08) | `time java -jar … -h` |
| JDK modules needed | `java.base, java.compiler, java.desktop, java.naming, java.sql, jdk.management, jdk.unsupported` | `jdeps --print-module-deps --ignore-missing-deps target/jenny.jar` |
| Key deps | Timefold 2.1.0, picocli 4.7.7, Guava 33.6.0-jre, slf4j 2.0.18, logback 1.5.34 | `pom.xml` |

**Decisive observation:** a jenny run is dominated by the solver (default ~60–110 s of
search). Process **startup time is therefore irrelevant** for this tool — the headline
advantage of GraalVM native-image (millisecond startup) buys nothing here. The only
distribution problems worth solving are **"no Java install required"** and **"single file
to download."**

### The module set is inflated by unused transitive baggage (verified)

`java.sql` and `java.desktop` are **not** required by jenny's code, logback, or Guava —
they are dragged in by transitive libraries that the shade plugin bundles but the CLI never
exercises. Traced with `jdeps -v`:

- **`java.sql`** ← `io.micrometer.core.instrument.binder.db.*` (**Micrometer**, Timefold's
  optional monitoring dependency).
- **`java.desktop`** ← `io.micrometer.core.instrument.binder.logging.Log4j2Metrics` **and**
  `org.glassfish.jaxb.runtime.*` (**JAXB runtime**; its built-in leaf-type handling
  statically references `java.awt`/`java.beans` for Image/Color datatypes).

- **JAXB** is never on jenny's runtime path: the CLI builds its `SolverConfig`
  **programmatically** — `JennyCli.java:205-210` (`JennySolverFactory.createConfig()` →
  `SolverFactory.create(config)`); it never calls `createFromXmlResource`, so the JAXB
  runtime is never invoked (`solverConfig.xml` is reference/docs only). Fully removable.
- **Micrometer** is more subtle: jenny enables no monitoring, but Timefold's default solve
  path statically loads `io.micrometer.core.instrument.MeterRegistry`, so Micrometer
  **cannot be removed wholesale** (verified — doing so throws `ClassNotFoundException:
  MeterRegistry` at solve time). Only the **unused optional binders** are removable:
  `binder/db` (→ `java.sql`) and `binder/logging/Log4j2Metrics` (→ `java.desktop`).

`jdeps` reports any *statically referenced* module even on never-executed paths, which is
why `java.sql`/`java.desktop` appear. Pruning JAXB + those two binders removes both modules
and slims the jar — see the S1 spike for the executed result.

### Timefold native-image reality (verified by inspecting `timefold-solver-core-2.1.0.jar`)

- Timefold uses **Gizmo** (Quarkus's bytecode generator) by default for member accessors
  and solution cloning: packages `…/accessor/gizmo/` and `…/solution/cloner/gizmo/` are
  present. **Runtime bytecode generation is forbidden under GraalVM native-image's
  closed-world model** — this is the central native blocker.
- The core jar ships **no `META-INF/native-image` reachability metadata** (grep returned
  nothing). So plain `solver-core` is not turnkey for native-image; metadata must be
  supplied by us.
- A **reflection fallback exists**: `DomainAccessType` enum, `ReflectionFieldMemberAccessor`,
  `ReflectionMethodMemberAccessor`, `ReflectionBeanPropertyMemberAccessor`, and
  `FieldAccessingSolutionCloner` are all in the jar. Setting `domainAccessType = REFLECTION`
  avoids runtime codegen and is the only viable native path.
- jenny does **not** currently set `domainAccessType` (grep of `src/main/java` found none),
  so it runs on the default **GIZMO** path. _Native-image would require switching this in
  `JennySolverFactory.createConfig()` / `JennyCli`._
- The native-image **tracing agent cannot capture Gizmo-generated classes** (they are
  synthesised at runtime, not loaded as resources) — so the REFLECTION switch is
  **mandatory**, not a convenience.
- picocli 4.7.7 core is on the classpath; its GraalVM reflection config comes from the
  separate **`picocli-codegen`** annotation processor, which is **not currently a
  dependency**.
- Guava and logback do not bundle in-jar native-image metadata; they rely on the GraalVM
  **reachability-metadata repository** (auto-consumed by the native build plugin). Coverage
  for these exact versions is _[unverified]_ — validate in the spike.

---

## 2. Comparison matrix

Strategies:
- **S1** — Optimized shaded JAR (today), optionally wrapped with `jpackage`.
- **S2** — `jlink`-trimmed custom runtime image, optionally wrapped with `jpackage`.
- **S3** — GraalVM `native-image` single binary.

| Dimension | S1 shaded JAR | S2 jlink (+jpackage) | S3 GraalVM native |
|-----------|---------------|----------------------|-------------------|
| **"No Java needed"** | ❌ needs JRE (✅ only if jpackage bundles a runtime → becomes S2) | ✅ bundled runtime | ✅ self-contained |
| **Single file** | ✅ one jar (but +JRE) | ⚠️ app-image dir / installer | ✅ one binary |
| **Download size** | 12 MB jar (+ user's JRE) | ~**50–70 MB**/platform (trimmed runtime ~40–55 MB¹ + 12 MB app) | ~**30–80 MB**/platform _[unverified — spike]_ |
| **Cold startup** | ~0.1 s (irrelevant here) | ~0.1 s (JVM; irrelevant here) | ~ms (irrelevant here) |
| **Build/CI complexity** | **LOW** (already builds) | **MEDIUM** (jdeps→jlink→jpackage, per-OS) | **HIGH** (GraalVM toolchain, metadata harness, per-OS native builds, multi-minute builds) |
| **Cross-platform** | ✅ one jar runs anywhere w/ JRE | ❌ per-platform; **no cross-compile** → CI matrix | ❌ per-platform; **no cross-compile** → CI matrix |
| **Correctness / runtime risk** | **LOW** (current tested artifact) | **LOW** (same bytecode on a JVM; Gizmo works) | **HIGH** (Timefold Gizmo→reflection switch + metadata; an untraced solve path can fail at an end-user's runtime) |
| **Maintenance** | **LOW** | **MEDIUM** (revalidate module set on dep changes) | **HIGH** (re-trace metadata + native test suite on every Timefold/dep bump) |

¹ `java.desktop` and `java.sql` are **not genuinely required** — they come from shaded-but-
unused Micrometer + JAXB (see §1). Excluding those deps should drop the module set to ~5 and
remove `java.desktop`; `--strip-debug --compress=zip-9 --no-man-pages --no-header-files`
trims the rest.

---

## 3. Per-strategy spike plans (future tasks — not executed here)

### S1 — Optimized shaded JAR (+ optional jpackage)
1. **Prune unused transitive baggage — ✅ DONE** (commit on branch `packaging/slim-shaded-jar`).
   Implemented as **shade `<filters>`** on the uber-jar (not dependency `<exclusions>`), so
   the libs stay on the test/compile classpath while only the shipped jar is slimmed.
   Excluded: `org/glassfish/jaxb/**`, `com/sun/xml/**`, `com/sun/istack/**`,
   `jakarta/xml/bind/**`, `jakarta/activation/**`, `org/eclipse/angus/**` (all JAXB), plus
   `io/micrometer/core/instrument/binder/db/**` and `…/binder/logging/**` (the two unused
   binders — NOT all of Micrometer; see §1). **Measured result:**
   - Jar: **11.5 MB → 10.2 MB** (−1.39 MB, ~11.4%, ~1,600 classes removed).
   - Module set: `java.sql`, `java.desktop`, **and** `java.compiler` dropped →
     `java.base, java.logging, java.naming, java.xml, jdk.management, jdk.unsupported`.
   - 56 unit tests green; real solves (basic + withouts + `-h`) run clean off the slimmed
     jar with no `ClassNotFoundException`. _(A first attempt excluding `io/micrometer/**`
     wholesale threw `ClassNotFoundException: MeterRegistry` at solve time — Timefold loads
     it on the default path. Narrowed to the two binders.)_
2. Optional further shrink: audit remaining shaded contents
   (`unzip -l target/jenny.jar | sort -k1`); Guava is the next-largest — consider whether
   the full `-jre` artifact is needed.
2. To make it "download-and-run" you must bundle a runtime → that is exactly S2's
   `jpackage` step. **S1-without-a-runtime cannot meet the goal** unless we accept
   "user must have Java 26."
3. **Risk:** none beyond today. **De-risk:** n/a.

### S2 — jlink runtime + jpackage  *(recommended primary — see §4)*
0. **Do the S1 prune first** — it removes `java.sql` + `java.desktop` from the module set
   below, materially shrinking the runtime image.
1. Compute modules **without** `--ignore-missing-deps` and reconcile any additions:
   `jdeps --multi-release 26 --print-module-deps target/jenny.jar`. Verified post-prune set
   (6 modules): `java.base, java.logging, java.naming, java.xml, jdk.management,
   jdk.unsupported` — `java.desktop`/`java.sql`/`java.compiler` are gone.
2. Build a trimmed runtime:
   `jlink --add-modules <module-set> --strip-debug --compress=zip-9 --no-man-pages
   --no-header-files --output build/jre` and record its size.
3. Smoke-test the app on the trimmed runtime: `build/jre/bin/java -jar target/jenny.jar
   -n3 -s0 … -w…` (use the README self-test) and confirm a **solver run** works — not just
   `-h` — because Gizmo/reflection paths only exercise during solving.
4. Wrap per-platform with `jpackage --type app-image` (and optionally `dmg`/`deb`):
   `jpackage --type app-image --name jenny --input target --main-jar jenny.jar
   --runtime-image build/jre`.
5. **Risks:** (a) `jdeps` misses a reflectively/dynamically referenced module → caught by
   the step-3 solver smoke test; (b) `java.desktop` bloat → test removing it. **De-risk:**
   run the existing IT inputs against the jlink runtime in CI before publishing.

### S3 — GraalVM native-image  *(optional stretch — gated on feasibility, see §4)*
1. **Feasibility gate first.** In a throwaway branch, set `domainAccessType =
   DomainAccessType.REFLECTION` on the `SolverConfig` (in `JennySolverFactory`) and confirm
   the JVM build still passes the full suite — this proves the no-codegen path is behaviour-
   equivalent before any native work.
2. Add `picocli-codegen` as an `annotationProcessor` (generates picocli reflect-config).
3. Generate Timefold/Guava/logback metadata by running the **tracing agent** over a
   *representative input matrix* (cover `-n2` and `-n3`, multiple `-w` withouts, `-o`
   seed-file ingestion, `--bench`, and an uncoverable-tuple case):
   `java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image -jar target/jenny.jar …`
4. Build with the GraalVM `native-maven-plugin` (`mvn -Pnative package`), enabling the
   reachability-metadata repository for Guava/logback.
5. **Native test suite:** re-run the JVM correctness oracles (ConstraintProviderTest /
   CliRegression / a benchmark self-test) **against the native binary** — JVM-green does not
   imply native-green.
6. Per-platform builds in CI: separate macOS-arm64 and linux-x64 runners (no cross-compile).
7. **Risks:** (a) a solve path not exercised during tracing → `ClassNotFoundException` /
   reflection failure at an end user's runtime; (b) constraint-stream (Bavet) internals may
   need extra metadata beyond the agent's capture; (c) Timefold `solver-core` native support
   is community-effort (official native is via the Quarkus extension) _[unverified for 2.1.0
   standalone]_. **De-risk:** treat step 1 + step 5 as hard gates; abandon S3 if either
   fails rather than shipping a fragile binary.

---

## 4. Recommendation

**Adopt S2 (jlink-trimmed runtime, packaged with `jpackage`) as the primary
download-and-run distribution; keep the shaded JAR (S1) as the cross-platform / "I already
have Java" fallback. Treat S3 (GraalVM native) as an optional stretch experiment, pursued
only if a single tiny binary becomes a hard requirement and the §3-S3 feasibility gate
passes.**

Reasoning tied to the matrix:
- The goal is "no Java install + easy to run," **not** speed. Startup time — GraalVM's one
  real advantage — is irrelevant because the solver dominates wall time by ~1000×.
- S2 delivers the actual goal (bundled runtime, no JRE needed) at **LOW correctness risk**:
  it is the same bytecode on a real JVM, so Timefold's default Gizmo path keeps working
  untouched. Cost is a per-platform CI matrix and ~50–70 MB downloads — acceptable.
- S3 offers a smaller single binary but at **HIGH risk and HIGH maintenance**: it forces a
  Timefold reflection-mode switch plus a metadata harness, and a missed code path fails at
  the *user's* runtime, not in our build. For a tool whose only S3 win (startup) doesn't
  matter, that risk is not justified as the primary path.
- S1 alone cannot meet the goal (still needs a JRE); it remains valuable as the universal
  fallback and the source artifact S2 wraps.

### Open questions / assumptions to confirm
- [ ] **Platform targets** — macOS arm64 + Linux x64 only? Add macOS x64 / Windows? Drives
      the CI matrix size for S2 and S3.
- [ ] Acceptable download size ceiling? (S2 ~50–70 MB vs S3 ~30–80 MB.)
- [ ] Distribution channel — GitHub Releases asset, Homebrew/apt, or a curl-installer? This
      affects whether `jpackage` installers (`.dmg`/`.deb`) or a plain tarball app-image is
      preferable.
- [x] **Is `java.desktop` (and `java.sql`) genuinely required? RESOLVED — no.** They come
      from shaded-but-unused Micrometer + JAXB (CLI uses programmatic config, no monitoring);
      prunable via the S1 step. Confirm the post-prune module set with a real-solve smoke
      test before relying on it.
- [ ] GraalVM reachability-metadata coverage for Guava 33.6.0-jre and logback 1.5.34
      _[unverified]_ — only relevant if S3 is pursued.
- [ ] Does Timefold 2.1.0 `solver-core` (non-Quarkus) build cleanly under native-image with
      `DomainAccessType.REFLECTION`? _[unverified — this is the S3 go/no-go gate]_.
