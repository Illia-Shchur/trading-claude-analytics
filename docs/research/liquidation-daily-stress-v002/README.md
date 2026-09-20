# Liquidation daily stress v002

This is the owner-approved shorter-history successor to [v001](../liquidation-structure-v001/SPECIFICATION.md), in the same `liquidation-structure` family. Its first implementation acquires daily liquidation observations and counts a predeclared stress gate. It does not generate trades or test returns.

- [Implementation and backtest plan](IMPLEMENTATION-AND-BACKTEST-PLAN.md)
- [Actual-data diagnostic results](RESULTS.md)
- [Verification](VERIFICATION.md)
- [Frozen specification](SPECIFICATION.md)
- [Frozen precommit](frozen-precommit.json), with [readable rendering](frozen-precommit.md)
- [Original freeze manifest](FREEZE-MANIFEST.json)
- [Implementation clarifications](CLARIFICATIONS.md)
- [Remaining research gates](NEXT-STEPS.md)

The durable frozen-precommit copies are byte-identical to the original registry files named in the freeze manifest. The original manifest is preserved. Raw provider responses, acquisition manifests and diagnostic outputs remain in gitignored `.research-run/` storage.

## Reproduce acquisition

Supply `COINALYZE_API_KEY` through the process environment. Do not put it in a source file, URL, committed configuration or command argument. Acquisition reads it only for the API request header.

```sh
./bin/analytics public-data-adapters coinalyze-daily \
  --from 2022-08-11 --to 2026-09-20 \
  --as-of 2026-09-20T00:00:00Z \
  --root .research-run/liquidation-daily-stress-v002/acquisition
```

Use a new root for each acquisition; dates are UTC and `--to` is exclusive. The importer resolves the four Binance USDT perpetual contracts from provider catalogues, retains raw bytes and hashes, and reports missing calendar days. It never fills missing observations with zeros.

## Reproduce the daily diagnostic

Create an input JSON with schema `daily-stress-preflight-input/1` and two references: `precommit_ref` and `acquisition_manifest_ref`. Each reference contains exactly `path` and `byte_sha256`. Bind the frozen precommit above and the acquired `coinalyze-daily-acquisition.json`; hash the actual file bytes with SHA-256. Relative reference paths resolve against the input file directory.

From the repository root, this creates the bindings from the actual files:

```sh
python3 - <<'PY'
from pathlib import Path
import hashlib, json
run = Path('.research-run/liquidation-daily-stress-v002')
def ref(path):
    path = path.resolve()
    return {'path': str(path), 'byte_sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
value = {
    'schema': 'daily-stress-preflight-input/1',
    'precommit_ref': ref(Path('docs/research/liquidation-daily-stress-v002/frozen-precommit.json')),
    'acquisition_manifest_ref': ref(run / 'acquisition/coinalyze-daily-acquisition.json'),
}
with (run / 'preflight-input.json').open('x') as output:
    json.dump(value, output, indent=2)
    output.write('\n')
PY
```

```sh
./bin/analytics strategy-research-v5 daily-stress-preflight \
  --input .research-run/liquidation-daily-stress-v002/preflight-input.json \
  --out .research-run/liquidation-daily-stress-v002/preflight-result.json
```

The diagnostic reopens raw files, verifies hashes and rederives normalized rows. It uses only the preceding 90 contiguous daily observations for each side's nearest-rank 95th percentile. A current positive observation must strictly exceed that threshold. The assumed availability is daily bucket start +48 hours. Eligible decisions begin November 11, 2022 and stop before July 15, 2026.

## Meaning of the result

Stress flags are not entries, independent episodes or evidence of profitability. These are Binance-specific observed liquidations, not market-wide totals; the provider warns that Binance publishes limited liquidation snapshots, so the figures are not a complete count of all forced liquidations. Historical publication/revision provenance remains unknown, so this is a disclosed retrospective proxy. The result stays `BLOCKED` / `DEVELOPMENT`; it cannot authorize SHADOW or live use.

The remaining executable strategy requires qualified price/OI inputs, derivatives execution metadata, a 60-day lifecycle with shared isolated-position accounting across stages, and matching walk-forward controls. Existing production guards remain in force. Follow the staged sequence in the specification before adding macro or a composite score.
