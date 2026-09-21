# Hourly exploratory replay — v003

This run investigates the frozen liquidation premise with public historical inputs. A sample below 30 market-wide shock groups does not block computation. It remains DEVELOPMENT evidence, with no promotion or claim of demonstrated independence. The 1-, 3- and 7-day price responses are diagnostics; position lifecycles still have a 60-day ceiling.

## Inputs and executable

1. Restore the retained source prerequisites described in `DATA-AVAILABILITY.md`, then run `python3 tools/acquire_liquidation_exploratory_v003.py`. An existing data freeze is verified rather than overwritten.
2. Build the CLI and copy its executable JAR into a new immutable filename under `.research-run/liquidation-exploratory-v003/executor/`. Record its SHA-256 and source-file hashes. Never overwrite the JAR used by an earlier attempt.
3. Optionally run `strategy-research-v5 liquidation-hourly-preflight --input .research-run/liquidation-exploratory-v003/input-manifest.json --out <new-preflight-path>` through that JAR. Redirect stdout to a file because detailed coverage is large. Preflight reopens source data without computing strategy returns.
4. Run `strategy-research-v5 liquidation-hourly-run` with `--input`, `--policy`, `--policy-freeze`, `--data-freeze`, all four `--expected-*-byte-sha256` digests (policy, input, data-freeze, executor), and `--run-root`. The runner verifies the retained raw and normalized bytes, reserves a unique attempt directory and executes the three fixed variants plus their doubled-fee/slippage sensitivities. Redirect stdout and stderr to distinct retained files.
5. Preserve every attempt. Supply `--rerun-reason` for a subsequent attempt and record any operational launch failure before attempt creation. A correctness repair requires a new executable digest; it does not authorize changing the strategy after seeing outcomes.
6. Inspect the completion receipt, result byte hash, all six account reconciliations, trade/stage counts, holding durations, cluster counts and missing-response exclusions. Publish a compact results artifact and readable interpretation; retain source archives, trade records and event-stream digests.

The parent process enforces the owner's 24-hour work budget. The executable does not impose an internal deadline.

## Interpretation boundaries

- Decisions use the frozen November 11, 2022 through July 14, 2026 window. Source prices extend through September 19, 2026 for diagnostics and lifecycle completion.
- Market-wide shock groups depend on source-event geometry before returns. Actual overlapping holdings and lagged portfolio-return dependence are measured separately. The 67-day purge and 7-day embargo remain leakage rules; this run does not claim walk-forward validation.
- Unlevered response means first average observations within a shock group, then weight groups equally. Missing price paths are excluded and counted. The bootstrap uses a shared group universe; its intervals are descriptive.
- Funding is excluded, not treated as observed zero. Execution uses hourly trade OHLC as a mark-price proxy, assumed fees/slippage, approximate contract rules and prior-hour volume capacity. Simulated drawdown follows a conservative event path rather than an observed synchronized portfolio mark.
- The complete sample is exposed DEVELOPMENT data after this run. Comparing the three variants does not establish superiority over the deferred unconditional-direction and price/OI controls.

## Required verification

Run the affected acquisition and Java fixtures, then `./mvnw --batch-mode --no-transfer-progress clean install`. Run `python3 tools/check_new_code_coverage.py --base 0882d8af8a2faedb52d6e993f7fbff2014485833 --report analytics-coverage/target/site/jacoco-aggregate/jacoco.xml --minimum 80 --branch-minimum 80`; new executable Java lines and branches must each meet 80%. Include new production files in the Git diff before checking. The frozen replay JAR can run while the separate build verifies the final source tree.

## Exact retained result invocation

From the repository root, the final completed run used:

```sh
java -Xmx4g -jar .research-run/liquidation-exploratory-v003/executor/liquidation-hourly-exploratory-v003-repack2.jar \
  strategy-research-v5 liquidation-hourly-run \
  --input .research-run/liquidation-exploratory-v003/input-manifest.json \
  --policy docs/research/liquidation-exploratory-v003/exploratory-policy.json \
  --policy-freeze docs/research/liquidation-exploratory-v003/FREEZE-MANIFEST.json \
  --data-freeze .research-run/liquidation-exploratory-v003/data-freeze.json \
  --expected-policy-byte-sha256 c96f0350a0e4d483bec0b4b3af568063768c42cb3a46e8db761520a4aad565d5 \
  --expected-input-byte-sha256 33940db2c17b140976911c8c637383b4918b693f2b9d1d7fe835e3404ad7b655 \
  --expected-data-freeze-byte-sha256 e7854346247910b3c6ed63273f87bee470b1ecbc8f61e6adb75e84af0b6316db \
  --expected-executor-byte-sha256 6221f8fd368aac9085ca99ea8a261bb224718f65ea23288e14c1dff76443e49f \
  --run-root .research-run/liquidation-exploratory-v003/runs \
  --rerun-reason 'Correct reporting only: closed-position unrealized PnL and event-versus-intent diagnostic metadata; unchanged strategy, data, fills and response calculations'
```

For another invocation, redirect output to new files and provide its actual reason; do not reuse the historical correction reason. A newly built JAR may have a different digest even with identical source. Retain its source receipt and use its actual independently recorded digest, not the example value above.
