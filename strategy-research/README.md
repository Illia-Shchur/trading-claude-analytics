# Strategy research

This directory holds the durable policy and runtime inputs for new crypto
strategy research. Historical experiment records and generated registries have
been removed from the checkout. Future records, receipts, and raw data belong
under a fresh ignored run root such as `.research-run/records/`.

The authoritative research rules are in the
[strategy-research skill](../.agents/skills/strategy-research/SKILL.md) and
[RESEARCH-PROTOCOL.md](RESEARCH-PROTOCOL.md). Research can conclude
`REJECTED`, `SHADOW`, or `CANDIDATE_REVIEW`; it cannot authorize `ACTIVE`.
Fixed and synthetic diagnostics are evidence-limited and cannot promote a
family.

## Retained inputs

- `config/` contains the source registry, research universe, attestation key
  registry, and fixed-baseline portfolio and lifecycle policies.
- `templates/` contains the premise-first precommit template.
- `confirmations/` documents the confirmation boundary and external custody
  requirements.
- `RESEARCH-PROTOCOL.md` records the premise, point-in-time data, search,
  robustness, portfolio, stress, and prospective evidence contracts.

The engine and meaningful regression tests remain. Small JSON input vectors
live under `analytics-research/src/test/resources/fixtures/`; they exercise
schemas, hashes, provenance, and fail-closed behavior. They contain no prior
run results or receipts.

## Maintained Java command surfaces

The legacy strategy-research/1-3 and /4 capabilities remain available through
the Java command adapters, alongside the additive v5 façade. Inspect each
adapter's command list from the repository root:

```sh
./bin/analytics build-identity
./bin/analytics strategy-research
./bin/analytics strategy-research-next
./bin/analytics strategy-research-v5 --help
```

The Java commands replace the removed Node wrappers. Legacy commands that
write files must receive an explicit `--root` or `--out` under the caller's
fresh run directory. The v5 validation and index commands also accept explicit
record roots:

```sh
./bin/analytics strategy-research-v5 validate \
  --input .research-run/record.json \
  --record-root .research-run/records
./bin/analytics strategy-research-v5 index \
  --root .research-run/records \
  --out .research-run/index.json \
  --record-root .research-run/records
```

The fixed diagnostic commands require frozen, hash-bound input contracts and
fail closed when an input is missing or mismatched. Their complete argument
sets are listed in `V5-README.md`; diagnostic results cannot replace the
premise-first, point-in-time, walk-forward, robustness, stress, portfolio, or
custody requirements for a strategy decision. The historical campaign
outputs and benchmark evidence were removed; the research engines and
maintained Java command surfaces remain available for new work.

## Verification

Use the pinned Java 21 reactor and affected research tests before recording a
new result. Keep generated data outside Git unless a current contract
explicitly requires a small, reviewed configuration or schema. The production
engine accepts fresh caller-supplied roots; no historical registry is needed
to create new inputs or evaluate a supported command.
