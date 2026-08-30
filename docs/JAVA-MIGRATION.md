# Java/Spring migration

This repository has been migrated from ESM Node.js to a Java 21, Maven, Spring
Boot command-line application. The Java entry point is:

```sh
./bin/analytics --help
```

The cutover was proved with frozen differential fixtures captured from the
retired implementation. Java remains authoritative only while deterministic
JSON, hashes, Markdown, files, standard streams, and exit codes continue to
match those known-answer vectors.

## Source inventory

The synchronized migration baseline contains 124 tracked JavaScript files and
47,597 lines:

- 68 production tools under `tools/`;
- 54 test programs under `test/`;
- two identical calibration Workflow templates under `.agents/` and `.claude/`.

The inventory also included 14 ignored historical run scripts under
`.report-run/`. All 14 are classified as absorbed: the two historical report
draft builders map to the deterministic reporting pipeline, while the 12
one-shot search/diagnostic grids map to the differentially tested SwingEngine
primitives they invoked. The two byte-identical calibration Workflow templates
also map to `CalibrationRunCommand`; the templates themselves declare
`calib-run` to be their canonical execution path. These scripts and templates
are retired; the ledger test requires their historical rows to retain live Java
owner and test-owner files and fails if JavaScript reappears.

The machine-checked migration ledger is
[`docs/java-source-map.json`](java-source-map.json). Its compatibility test
fails whenever a ledger row marked `PORTED` or `ABSORBED` no longer points to
both an implementation and test file, or a retired JavaScript source reappears.

## Module boundaries

| Module | Responsibility |
| --- | --- |
| `analytics-contracts` | strict JSON, RFC 8785/JCS, hashes, schema registry |
| `analytics-core` | indicators, framework rules, positions, scoring, calendars |
| `analytics-infrastructure` | safe files, immutable stores, Git, crypto, DuckDB, workers |
| `analytics-market-data` | public HTTP, CSV/ZIP, venue and macro adapters |
| `analytics-reporting` | finalize, render, lint, export, snapshot, tripwire |
| `analytics-research` | calibration, swing engine, research v1 through v5 |
| `analytics-cli` | Spring Boot/Picocli command adapters only |
| `analytics-compatibility-tests` | Node-versus-Java differential tests |

Domain code stays independent of Spring. Spring owns application assembly and
command discovery, not scoring or contract behavior.

## Verification gates

JavaScript was retired after all of the following cutover gates were satisfied:

1. Every tracked and relevant ignored JavaScript behavior has a Java owner and
   a test mapping.
2. Commands have success and fail-closed unit, integration, security, property,
   and frozen differential fixtures covering their applicable process and file contracts.
3. All 127 JSON schemas compile and their observed AJV behavior is preserved.
4. Canonical bytes, SHA-256 values, signatures, time zones, and numeric edge
   behavior match frozen known-answer vectors.
5. Unit, property, integration, security, concurrency, and transaction tests
   pass, including required DuckDB/Parquet integration in CI.
6. GitHub workflows and operational documentation run only the Java commands.
7. A repository search finds no remaining supported JavaScript runtime path,
   `package.json`/Node dependencies are gone, and the complete Maven build is
   green from a clean checkout.

JaCoCo and PIT measurements below are continuing hardening signals, not hidden
cutover gates. They identify weakly exercised branches and mutation survivors
without invalidating behavior proved by the mapped compatibility, property,
integration, security, concurrency, and transaction suites. Maven `verify`
does not currently impose a repository-wide percentage threshold.

## Local checks

```sh
./mvnw test
./mvnw verify
```

Critical mutation targets use the `mutation` profile. Keep the target class and
its focused test explicit so a passing run cannot silently analyze a different
package:

```sh
./mvnw -pl analytics-market-data -Pmutation \
  -DtargetClasses=com.tradinganalytics.marketdata.PublicDataSmokeService \
  -DtargetTests=com.tradinganalytics.marketdata.PublicDataSmokeServiceTest \
  org.pitest:pitest-maven:mutationCoverage

./mvnw -pl analytics-cli -Pmutation \
  -DtargetClasses=com.tradinganalytics.cli.WriterInstallationCliCommand \
  -DtargetTests=com.tradinganalytics.cli.WriterInstallationCliCommandTest \
  org.pitest:pitest-maven:mutationCoverage

./mvnw -pl analytics-infrastructure -Pmutation \
  -DtargetClasses=com.tradinganalytics.infrastructure.github.GitHubAttestationSignerV5 \
  -DtargetTests=com.tradinganalytics.infrastructure.github.GitHubAttestationSignerV5Test \
  -DmutationThreshold=80 -DtestStrengthThreshold=90 -DcoverageThreshold=90 \
  org.pitest:pitest-maven:mutationCoverage

./mvnw -pl analytics-infrastructure -Pmutation \
  -DtargetClasses='com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5*' \
  -DtargetTests=com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5Test \
  -DmutationThreshold=70 -DtestStrengthThreshold=80 -DcoverageThreshold=90 \
  org.pitest:pitest-maven:mutationCoverage
```

The 2026-08-28 signer checkpoint kills 251 of 274 generated mutants (92%),
with 95% test strength and 355/379 mutated lines covered (94%). Its focused
suite covers both PAT and GitHub App auditor custody, exact Node acceptance,
Ed25519 registry binding, immutable 0600 output, and fail-closed policy drift.

The 2026-08-28 physical settings-capture checkpoint kills 724 of 981 generated
mutants (74%), with 80% test strength and 1,473/1,551 mutated lines covered
(95%). It completes without timeouts, memory errors, or run errors. Its focused
suite covers exact Node assembly bytes, PAT and pinned App authentication,
OIDC/JWKS verification, ruleset and environment custody, malformed/non-success
HTTP evidence, credential confinement, artifact writes, and fail-closed drift.

The 2026-08-28 readiness-v5 checkpoint ports all 10 exports from
`strategy-readiness-v5.mjs` into the research orchestration owner, while generic
Actions-attestation verification remains in infrastructure. Its focused
Node-oracle suite is 10/10 green and preserves 17 differential, physical,
tamper, Ed25519, activation-success, and fail-closed gates; the full research
reactor is 702/702 green and `JavaSourceMapTest` is 1/1 green. Focused JaCoCo for
`StrategyReadinessV5` is 63.47% lines, 27.48% branches, 67.33% methods, and
54.69% instructions, so readiness still has a hardening gap. The
bounded PIT attempt produced no report: baseline discovery loaded a stale
installed, package-private `ActionsAttestationVerifierV5` and aborted with an
`IllegalAccessError` when readiness accessed that class and its nested
`Request`. Mutation strength is therefore unmeasured and must not be inferred
from JaCoCo. The original GitHub-capture program is mapped to its closest
single ledger owner, `GitHubSettingsCaptureV5Test`; its assertions are covered
collectively by that physical-capture suite, `GitHubAttestationSignerV5Test`,
and `StrategyReadinessV5NodeOracleTest`, including capture/API custody,
credential confinement, signing, hash binding, activation, and tamper rejection.

The 2026-08-28 statistical-v5 checkpoint maps all 67 Node exports. Its focused
suite has 27 Node differentials and five jqwik properties (311 generated
checks), including genetic checkpoint/resume, the complete eight-quarter nested
WFO route, and physical-evaluator trust spoofing. Outer-class JaCoCo is 77.03%
lines and 51.40% branches. A correctly scoped single-thread PIT attempt did not
produce a report: after two timed-out mutants, a later minion remained CPU-bound
for more than five minutes and the run was stopped at 10m45s. Mutation strength
therefore remains unmeasured and must not be inferred from the coverage result.
The three original Node statistical, corrections, and publication-transaction
programs also complete successfully against the frozen oracle source.

The 2026-08-28 evaluator-v5 checkpoint ports all nine ESM exports from
`strategy-evaluator-v5.mjs` (seven functions and two code-hash constants); its
worker is a message protocol with zero ESM exports. The focused evaluator suite
has 18 tests, including two jqwik properties (220 generated checks), and the
combined evaluator/statistical `verify` is 50/50 green. A genuine Node-to-Java
physical-null differential builds the original eight-quarter physical Parquet
fixture and exercises all four null methods through transformed role custody,
nested GA/WFO selection, production `runNullControlsV5`, content-addressed
iteration CAS/resume, and tamper rejection. The verified capability flag is
installed only after the authoritative loader reopens and verifies the physical
artifacts. The full `analytics-research` suite is 250/250 green. JaCoCo for the
evaluator family (outer plus nested classes, excluding the worker) is 77.49%
lines / 52.75% branches / 87.74% methods; the worker family is 90.24% / 75.00%
/ 100%. A single-thread evaluator-only PIT attempt did not complete: it created
24 prescan units, calculated coverage in 47 seconds, scheduled 16 mutation
units, emitted one `Minion exited abnormally due to TIMED_OUT` warning, and
required explicit termination after the external wall guard failed to reap its
descendant minions. It produced no final report, so mutation strength remains
unmeasured and must not be inferred from JaCoCo.

The 2026-08-28 prospective-v5 checkpoint ports all 21 function exports plus the
public `MAX_PROSPECTIVE_LEASE_MS` constant (22/22 surface). Its focused suite
has 11 core tests and two runner tests; the full `analytics-research` suite is
225 green and the focused `analytics-cli` suite is 11 green. Focused JaCoCo is
87.33% lines / 52.31% branches for `StrategyProspectiveV5` and 87.65% lines /
60.94% branches for `StrategyProspectiveRunnerCommandAdapter`. The two original
prospective Node suites also complete successfully against the frozen oracle
source. Targeted PIT generated 995 mutants and killed 676 (68% raw), with 80%
test strength and 395/454 mutated lines covered (87%). One increment mutant
timed out; there were no run or memory errors.

The 2026-08-28 performance-v5 checkpoint ports all 10 exports from
`strategy-research-v5-performance.mjs`, the executable benchmark command, and
the worker message/evaluation protocol. The focused suite has eight Node-oracle
tests, three trust/security tests, and three jqwik properties (98 generated
checks): 14/14 tests pass. The full `analytics-research` suite is 225/225 green,
and the Spring/Picocli `PortedToolCliCommandsTest` reactor run is 11/11 green.
Focused outer-class JaCoCo is 81.22% lines / 44.21% branches / 80.43% methods
for `StrategyPerformanceV5`, 71.85% / 39.80% / 87.74% for
`StrategyPerformanceV5Benchmark`, and 87.35% / 53.58% / 92.45% for
`StrategyPerformanceV5Worker`. The differentials cover deterministic cache,
workload, complexity, funding, hashing, lazy materialization, worker success
and rejection paths, the synthetic benchmark, and sampled/full physical
JSONL-plus-Parquet data-plane runs including tamper, symlink, and hard-link
rejection. A single-thread PIT run explicitly targeting the three performance
classes and their three focused test classes was stopped at its 10-minute cap
without producing a report; it emitted five timed-out-minion warnings and one
memory-error warning. Mutation strength is therefore unmeasured and must not be
inferred from JaCoCo. Remaining coverage gaps are concentrated in deep
canonical eight-asset validation, separated-artifact manifest branches, and
pre-entry/derivative-mark lazy hydration variants.

The 2026-08-30 `lib.mjs` / `selftest.mjs` checkpoint closes the aggregate
deterministic-helper facade without collapsing its domain boundaries. A live
dynamic import inventories exactly 140 exports (101 functions and 39 values),
and `ToolchainSelftestContract` freezes that complete name set, the ten real
repository facade owners, and the intentional Java spellings for the twelve
non-exact aliases: `buildReportPhaseRegistry`, `marketFlowBlock`, `pctChange`,
`reportPhaseRegistryIssues`, `rollingBouncePct`, `rollingDrawdownFromATH`,
`rollingSMADistance`, `rollingWilderRSI`, `shortEV`, `stdev`, `weightedEV`, and
`wilderRSI`. The aggregate contract contains no success stub or copied result;
`ToolchainSupportParityTest` executes the Java owner against frozen known-answer
fixtures and validates the retired selftest contract inventory. The observed inventory is exactly 910
calls (543 `eq`, 367 `ok`, 908 unique names); the only duplicate labels are
`FR-B has no Phase 3` and `...claiming nothing about where the coins are`, each
executed twice. Its five tests are green and include direct aggregate
constant/helper differentials, a 241-row score/stop SHA-256 grid, mechanical
gate anti-spoof checks, the exact export inventory, and the exact executed-call
inventory.

A clean, one-session JaCoCo capture of those five cross-module parity tests
covers the outer `ToolchainSupport` at 433/438 lines (98.86%), 223/286 branches
(77.97%), 49/49 methods (100%), and 2,658/2,764 instructions (96.16%). Including
its nested band facades and `LegSpec`, the family reaches 441/462 lines (95.45%),
223/286 branches (77.97%), 57/73 methods (78.08%), and 2,699/2,859 instructions
(94.40%). `ToolchainSelftestContract` reaches 19/19 lines, 1/1 methods, and
103/103 instructions; it has no branches. The bounded PIT run deliberately had
a narrower scope: it mutated `ToolchainSupport*` inside `analytics-core` using
only `ToolchainSupportTest`, not the cross-module parity suite. It generated 305
mutants, killed 96, left 27 survivors and 182 without coverage: 31% raw mutation
score, 78% test strength for covered mutants, and 263/467 mutated lines covered
(56%), with no timeouts, memory errors, or run errors. These mutation numbers
are recorded as the focused unit-test baseline and are not evidence that the
cross-module parity suite has the same mutation strength.

The compatibility module intentionally requires Node until the final cutover.
It is an oracle dependency, not the destination architecture.
