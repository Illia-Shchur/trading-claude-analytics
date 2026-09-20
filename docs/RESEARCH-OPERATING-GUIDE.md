# Governed strategy research operating guide

This guide describes the current strategy-research boundary. It does not
authorize orders, activation, or promotion. Historical generated registries,
benchmark packages, and result archives are removed; new outputs use a fresh
ignored root such as `.research-run/records/`.

## Before a run

Read the [strategy-research skill](../.agents/skills/strategy-research/SKILL.md)
and [RESEARCH-PROTOCOL.md](../strategy-research/RESEARCH-PROTOCOL.md). Create
and review an immutable premise before any grid or outcome inspection. The
premise names the mechanism, actors, direction, horizon, expected ranges,
failure regimes, invalidation test, inputs, availability timestamps, and
falsifying null.

Verify the repository and toolchain before producing evidence:

```sh
git fetch origin
git status --short
./mvnw --batch-mode --no-transfer-progress clean install
./bin/analytics build-identity
./bin/analytics strategy-research
./bin/analytics strategy-research-next
./bin/analytics strategy-research-v5 --help
```

Use the pinned Java 21 toolchain documented in `docs/JAVA-MIGRATION.md`.
Inputs must be completed-bar and point-in-time safe. Separate feature data
from future labels, and retain the source, executor, dependency, and content
hashes needed to reproduce the run.

## Maintained Java command surfaces

The old Node wrappers and historical registries were removed. The Java
adapters for strategy-research/1-3 and /4 remain available as
`./bin/analytics strategy-research` and `./bin/analytics
strategy-research-next`; v5 is additive at `./bin/analytics
strategy-research-v5`. Use explicit caller roots for generated records and
receipts. For example, v5 validation and indexing are:

```sh
./bin/analytics strategy-research-v5 validate \
  --input .research-run/record.json \
  --record-root .research-run/records
./bin/analytics strategy-research-v5 index \
  --root .research-run/records \
  --out .research-run/index.json \
  --record-root .research-run/records
```

`fixed-baseline` requires `--baseline`, `--controls`, `--experiment`,
`--physical-input` (or its `--input` alias), and `--exposure-head`.
`fixed-baseline-refinement` also requires a frozen `--refinement` inventory,
`--predecessor`, and `--starting-exposure-head`. The synthetic
`operating-characteristics-preflight` command requires a frozen `--plan`; its
run command additionally requires `--baseline`, `--controls`, `--experiment`,
and `--source-freeze`. Use only frozen, hash-bound inputs from the research
owner. Outputs remain diagnostic and cannot authorize promotion or `ACTIVE`.

The fixed policy dependencies are runtime inputs in `strategy-research/config/`:

```text
fixed-baseline-portfolio-policy-v001.json
fixed-baseline-lifecycle-timing-v001.json
```

Prospective and adaptive research remain subject to the premise-first policy,
PIT data contracts, chronological walk-forward evaluation, robust statistics,
stress and portfolio gates, and separate custody requirements. An input or
capability gap remains `BLOCKED`, `DEVELOPMENT`, or
`INSUFFICIENT_EVIDENCE`; never convert it to confirmation.

## Test fixtures and publication

Meaningful regression tests use bounded JSON input vectors in
`analytics-research/src/test/resources/fixtures/`. They preserve hash and
schema behavior without retaining the historical result registry. Future runs
supply their own inputs and receipts.

Run affected tests after each contract change, then the clean reactor. For
production Java changes, run the new-code coverage checker against the
aggregate JaCoCo XML with the reviewed base. Market reports and exports remain
under their existing protected paths; validate reports with
`./bin/analytics lint-report` and use
`./bin/analytics export-signals --dry-run` when checking publication wiring.
