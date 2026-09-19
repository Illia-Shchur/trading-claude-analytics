# Strategy research v5

The v5 implementation is additive to the existing schema set and uses the
deterministic Java façade. Generated evidence, receipts, raw data, and
historical indexes are disposable run outputs and are created only in a fresh
caller supplied root.

Run commands from the repository root so the launcher binds build identity and
resource paths to the checkout being reviewed:

```sh
./bin/analytics build-identity
./bin/analytics strategy-research-v5 validate \
  --input .research-run/record.json \
  --record-root .research-run/records
./bin/analytics strategy-research-v5 index \
  --root .research-run/curated-records \
  --out .research-run/index.json \
  --record-root .research-run/records
./bin/analytics strategy-research-v5 presentation-export \
  --root .research-run/curated-records \
  --output .research-run/research-ui.json
```

The fixed diagnostic commands share the production evaluator and retain typed
source, dependency, executor, and content-hash checks:

```sh
./bin/analytics strategy-research-v5 fixed-baseline \
  --baseline .research-run/baseline.json \
  --controls .research-run/controls.json \
  --experiment .research-run/experiment.json \
  --physical-input .research-run/physical-input.json \
  --exposure-head .research-run/exposure-head.json \
  --out .research-run/fixed-baseline-result.json
./bin/analytics strategy-research-v5 fixed-baseline-refinement \
  --refinement .research-run/refinement-plan.json \
  --baseline .research-run/baseline.json \
  --controls .research-run/controls.json \
  --experiment .research-run/experiment.json \
  --physical-input .research-run/physical-input.json \
  --exposure-head .research-run/exposure-head.json \
  --predecessor .research-run/fixed-baseline-result.json \
  --starting-exposure-head .research-run/starting-exposure-head.json \
  --out-dir .research-run/refinement-results
./bin/analytics strategy-research-v5 operating-characteristics-preflight \
  --plan .research-run/operating-characteristics-plan.json
./bin/analytics strategy-research-v5 operating-characteristics-run \
  --plan .research-run/operating-characteristics-plan.json \
  --baseline .research-run/baseline.json \
  --controls .research-run/controls.json \
  --experiment .research-run/experiment.json \
  --source-freeze .research-run/source-freeze.json \
  --out .research-run/operating-characteristics-result.json
```

Fixed and synthetic paths are diagnostics. They do not authorize orders,
promotion, or `ACTIVE`; incomplete physical inputs remain `BLOCKED` or
`INSUFFICIENT_EVIDENCE`. Adaptive research still requires the premise-first
protocol, PIT data contracts, chronological walk-forward evaluation, robust
statistics, stress and portfolio gates, and the separate custody boundary.

Prospective operation is future only. A reservation freezes the candidate,
source, evaluator, and build identities before the first signal. Each matured
outcome reopens its typed source and verifies the append-only ledger head:

```sh
./bin/analytics strategy-research-v5 prospective-runner \
  --ledger .research-run/shadow-ledger \
  --record-root .research-run/records \
  --expected-head-sha256 <head-sha256> \
  --reservation .research-run/reservation.json \
  --source-receipt .research-run/source-receipt.json \
  --bar .research-run/completed-bar.json \
  --feature-input .research-run/feature.json \
  --candidate-set .research-run/candidates.json \
  --evaluator-code .research-run/evaluator-code.json \
  --signal-decision .research-run/signal.json
```

The current fixed policy dependencies live in `strategy-research/config/`:

```text
fixed-baseline-portfolio-policy-v001.json
fixed-baseline-lifecycle-timing-v001.json
```

Tests use small, bounded input vectors in
`analytics-research/src/test/resources/fixtures/`. They contain no run
results or receipts, and embedded historical path fields are identity metadata
only. A future run supplies its own inputs and receipts.

For contract details, read [RESEARCH-PROTOCOL.md](RESEARCH-PROTOCOL.md) and
the [strategy-research skill](../.agents/skills/strategy-research/SKILL.md).
