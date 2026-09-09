#!/usr/bin/env sh
set -eu

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <parquet-manifest.json> <parquet-root> <signal-bars.json>" >&2
  exit 2
fi

manifest=$1
root=$2
output=$3
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

# The Java producer reopens every manifest partition, checks its byte/row
# commitment, and derives the setup role from completed 4h bars.  The output
# is an immutable physical role; labels and 1m execution bars remain separate
# inputs to the fixed evaluator.
exec "$repo_root/bin/analytics" strategy-research-v5 fixed-baseline-produce-signal-bars \
  --manifest "$manifest" --root "$root" --out "$output"
