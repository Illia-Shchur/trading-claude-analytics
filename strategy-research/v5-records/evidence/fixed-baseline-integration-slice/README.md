# Retained fixed-baseline integration slice

This directory retains the first physically executed fixed-baseline integration slice. The setup role came from the verified 4h Parquet producer; the child role contains 14,400 contiguous BTCUSDT 1m rows fetched in 15 HTTP 200 Binance public REST requests. The durable input bundle is `physical-input-durable.json` and the replay result is `fixed-baseline-result.json`.

The run is deliberately scoped to one BTC event. It reports `evaluation_scope=INTEGRATION_SLICE`, `full_experiment_complete=false`, no matched control, one production `TradeLifecycleV5` trade, one merged lifecycle episode, and `INSUFFICIENT_EVIDENCE`. It does not satisfy the frozen eight-asset experiment. The public hydration receipt remains separately content-addressed under `strategy-research/v5-records/receipts/` and retains its `UNVERIFIED_DEVELOPMENT_ONLY` and `USER_BOUND_RETROSPECTIVE` limitations.

The older `retained-result-before-durable-relocation.json` and `physical-input-original.json` preserve the original first-run bytes. The durable replay has a different physical-input identity because its root is repository-relative; its economic result is otherwise the same slice and has its own semantic/custody hashes.
