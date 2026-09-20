# Research capability and evidence matrix

This matrix describes the current implementation boundary. Historical
research results and benchmark records have been removed; a local test or
diagnostic computation does not become confirmation or an observed fill.

| Capability | Implementation | Verification | Current boundary |
|---|---|---|---|
| Premise-first family creation | `strategy-research` skill and `RESEARCH-PROTOCOL.md` | Protocol and contract tests | A frozen premise is required before search; mechanism changes require a new premise |
| PIT data and source custody | Source registry, v5 data contracts, feature/label separation | Hash, availability, and negative-path tests | Unknown or incomplete PIT inputs fail closed |
| Candidate generation and accounting | Maintained Java v1-v5 research engines | Legacy and v5 candidate, exposure, and budget tests | Every evaluated behavior counts; generated records use a fresh caller root |
| Fixed baseline diagnostics | `StrategyFixedBaselineV5` and `fixed-baseline` façade | Baseline, control, physical-input, and contract tests | Diagnostic output cannot promote a family |
| Frozen refinement | `fixed-baseline-refinement` façade | Refinement inventory and custody tests | Members remain subject to the same evidence gates |
| Operating-characteristics diagnostics | `StrategyOperatingCharacteristicsV4` and parallel evaluators | Geometry, Wilson, resource, dependency, and equivalence tests | Synthetic results are conditional diagnostics and may remain blocked |
| Adaptive WFO | Existing Java research-run implementation | WFO, purge/embargo, stress, and portfolio tests | Requires complete PIT data, robustness, and portfolio evidence |
| Prospective operation | Reservation, signal, and outcome reconciliation contracts | Append-only ledger and maturity/reconciliation tests | Future-only evidence; no active forward cycle is claimed |
| Activation | External trust root and signed lease verification | Trust and authorization negative tests | Research emits no `ACTIVE` decision |
| Production reporting | `bin/analytics` fetch, position, compute, lint, render, export | Report contract and signal-feed tests | Reports and ledger exports are retained and remain the publication boundary |

The five runtime JSON policies in `strategy-research/config/` are the source
registry, research universe, attestation key registry, fixed-baseline portfolio
policy, and fixed-baseline lifecycle timing. Current runtime and CI paths
consume them.

Tests use bounded input vectors under
`analytics-research/src/test/resources/fixtures/`. They preserve meaningful
hash and schema behavior without retaining historical result registries.
