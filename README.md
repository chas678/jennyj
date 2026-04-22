# jenny-timefold

A Timefold Solver 2.0 re-implementation of Bob Jenkins' [jenny][jenny], a
combinatorial test-case generator that covers every allowed *n*-tuple of
features across *m* feature dimensions.

Same CLI surface as jenny. The aim is better solution quality (fewer test
cases) at a tolerable runtime cost, while staying byte-compatible on output.

Design-of-record: `docs/DESIGN.md`. Resumable work plan: `TASKS.md`.

## Build

Requires JDK 25 and Maven (or mvnd). On macOS:

```
cc -O2 -o ~/src/jenny/jenny ~/src/jenny/jenny.c   # optional, for --bench
mvn package                                       # or: mvnd package
```

Produces `target/jenny.jar` (shaded, executable).

## Run

```
java -jar target/jenny.jar -n3 2 3 8 3 2 2 5 3 2 2 -w1a2bc3b -w1b3a -s3
```

Flags mirror jenny's:

| flag            | meaning                                                   |
| --------------- | --------------------------------------------------------- |
| `-n<k>`         | tuple size (default 2, max 32)                            |
| `-s<seed>`      | random seed                                               |
| `-w<spec>`      | forbidden combination, e.g. `-w1a2cd4ac` (repeatable)     |
| `-o<file>`      | read existing tests (not yet wired — see `TASKS.md` T18)  |
| `-h`            | help                                                      |
| *positional*    | feature counts per dimension, in order (2..52 each)       |
| `--time-limit-seconds` | solver wall-clock budget (default 5)               |

Positional dimension sizes can appear anywhere on the command line, as in
jenny.

## Head-to-head bench

```
java -jar target/jenny.jar --bench -n2 2 3 8 3 2 2 5 3 2 2 --time-limit-seconds 10
```

Prints a two-row comparison of test count and wall time against the C
jenny binary. The C binary is discovered via `--jenny-path`, then
`$JENNY_BIN`, then `~/src/jenny/jenny`.

## Tests

```
mvn test      # 20 tests across 5 classes
```

[jenny]: https://burtleburtle.net/bob/math/jenny.html
