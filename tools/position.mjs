// ============================================================================
// tools/position.mjs — reads position-snapshot/1, exported from the
// personal-accounting ledger and derived from actual Binance fills. This is the
// Hard Rule 8 entry point: a FRESH snapshot is the POSITION OF RECORD and
// supersedes any figure narrated forward from a prior report.
//
//   node tools/position.mjs <asset|all> [--file <path>] [--max-age-min N]
//                           [--fills N] [--json]
//
// Exit codes are the contract, not decoration:
//   0  FRESH or STALE — a position claim may be made (STALE with a banner)
//   1  EXPIRED, missing, unparseable, or wrong schema — cold start per Rule 4
//   2  NOT_COVERED — the asset has no ledger counterpart (gold). NEVER qty 0.
//
// Deliberately NOT a fetch.mjs subcommand. fetch.mjs is the LIVE numeric
// backbone: network-only, never touches the filesystem, every block carrying a
// public source + timestamp. That property is what makes Hard Rule 1 auditable —
// a reader can tell at a glance whether a figure is externally verifiable market
// data or private account state. Folding a private ledger into it destroys the
// distinction, and the two have opposite failure semantics besides (Promise.all
// over HTTP vs a synchronous read with a non-zero exit path).
// ============================================================================
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { positionFreshness, positionForAsset, positionSnapshotCheck,
  POSITION_SNAPSHOT_SCHEMA } from './lib.mjs'

const DEFAULT_PATH = process.env.TRADING_EXCHANGE_DIR
  ? join(process.env.TRADING_EXCHANGE_DIR, 'position-snapshot.json')
  : join(homedir(), '.trading-claude', 'exchange', 'position-snapshot.json')

// ── argv ────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2)
const flag = (name, fallback = null) => {
  const i = argv.indexOf(name)
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback
}
const target = (argv[0] || '').toLowerCase()
const file = flag('--file', DEFAULT_PATH)
const maxAge = flag('--max-age-min', null)
const fillLimit = Number(flag('--fills', 10))

if (!target || target.startsWith('--')) {
  console.error('usage: node tools/position.mjs <asset|all> [--file <path>] [--max-age-min N] [--fills N] [--json]')
  process.exit(1)
}

// ── read ────────────────────────────────────────────────────────────────────
let snap
try {
  snap = JSON.parse(readFileSync(file, 'utf8'))
} catch (e) {
  // A missing or corrupt file is not an error to work around — it is the
  // documented EXPIRED path, and Hard Rule 4's dry-powder default already
  // handles it correctly. Refuse the POSITION CLAIM, never the report.
  emit({
    ok: false, band: 'EXPIRED', file, schema_expected: POSITION_SNAPSHOT_SCHEMA,
    error: e.code === 'ENOENT' ? 'snapshot file not found' : `unreadable: ${e.message}`,
    instruction: 'Proceed as a COLD START per Hard Rule 4 (all dry powder, no assumed deployment) and say so explicitly in the report. Regenerate with: mvn spring-boot:run -Pskip-frontend -Dspring-boot.run.profiles=local,export',
  })
  process.exit(1)
}

const structure = positionSnapshotCheck(snap)
if (!structure.ok) {
  emit({ ok: false, band: 'EXPIRED', file, schema_expected: POSITION_SNAPSHOT_SCHEMA,
    schema_found: snap?.schema ?? null, errors: structure.errors,
    instruction: 'Schema mismatch — do NOT read figures out of an unrecognised file. Cold start per Hard Rule 4.' })
  process.exit(1)
}

// ── freshness ───────────────────────────────────────────────────────────────
const opts = maxAge === null ? {} : { stale: Number(maxAge) }
const fresh = positionFreshness(snap.generated_at, snap.source?.holdings_as_of, Date.now(), opts)

if (fresh.band === 'EXPIRED') {
  emit({ ok: false, band: 'EXPIRED', file, freshness: fresh,
    instruction: 'Cold start per Hard Rule 4, stated explicitly. The ledger is too old to be the position of record.' })
  process.exit(1)
}

// ── project ─────────────────────────────────────────────────────────────────
const base = {
  ok: true,
  file,
  schema: snap.schema,
  generated_at: snap.generated_at,
  holdings_as_of: snap.source?.holdings_as_of ?? null,
  freshness: fresh,
  // The two carve-outs that survive even at FRESH. They are not caveats on trust
  // in the ledger; they are properties the ledger structurally cannot supply.
  carve_outs: {
    prices: 'Snapshot marks are INFORMATIONAL ONLY and never become the report\'s canonical spot. Hard Rule 1 wants ≥3 independent synchronized venue quotes — sourcing spot from your own database defeats the cross-check.',
    phase_attribution: 'The ledger knows what is held, not which tranche authorized it. Attribution comes from deal tags only; an untagged holding is reported as real-but-UNTAGGED, never inferred from quantity or timing.',
  },
  dry_powder: snap.dry_powder,
  portfolio: snap.portfolio,
  // The realized carry cost of a borrow, plus the borrows still open. Two uses: Hard Rule 6's Carry
  // Cost Ledger finally has a measured number behind it, and an open borrow (or a past accrual) is the
  // evidence that a negative replayed quantity is a genuine short rather than a gap in ingestion.
  // Absent on a snapshot written before 2026-07-30 — which is NOT the same as a zero carry.
  carry: snap.carry ?? null,
  carry_note: snap.carry
    ? 'Spot/margin financing only, cross margin only. NOT futures funding (that is futures.funding_total_usd) — do not sum the two without saying which is which. An empty open_borrows means nothing was borrowed at the last link, not that nothing was ever borrowed; the history is interest_by_asset.'
    : 'NOT PRESENT in this snapshot — the producer predates the carry ledger. Report carry cost as UNKNOWN, never as zero.',
  // A position's SIDE, which its quantity does not carry: trade_derived_qty is a net across wallets, so a
  // spot long can offset a margin short down to nearly nothing. Read short_qty per position, not the sign.
  short_leg_note: (snap.positions || []).some(p => p.short_qty !== undefined)
    ? 'Each position carries short_qty / short_avg_price_usd when it is net short — borrow-corroborated, not inferred from a sale. A null or absent short_qty on a position means no short. total_cost_usd is NEGATIVE on a short: money received, not spent.'
    : 'NOT PRESENT in this snapshot — the producer predates the signed cost-basis model. Report short exposure as UNKNOWN, never as zero; on that producer a short surfaced as basis_reliable:false instead.',
  performance_overall: snap.performance?.overall ?? null,
  performance_by_tag_prefix: snap.performance?.by_tag_prefix ?? [],
  coverage: snap.coverage,
}

if (target === 'all') {
  emit({ ...base, positions: snap.positions, futures: snap.futures,
    deals: { open_count: snap.deals?.open_count, closed_count: snap.deals?.closed_count,
      open: snap.deals?.open ?? [] } })
  process.exit(0)
}

const projected = positionForAsset(snap, target)
if (!projected.covered) {
  // Spread the FULL projection, not a hand-picked subset — a not-covered
  // asset with an alias (e.g. gold → PAXG) must still surface
  // requested_asset/ledger_asset/alias_note per Hard Rule 8; cherry-picking
  // fields here previously dropped them silently.
  emit({ ...base, ...projected, ok: false, covered: false })
  // Exit 2 is its own code precisely so "not covered" can never be confused with
  // "expired" upstream — they route to different report language.
  process.exit(2)
}

if (projected.fills && Number.isFinite(fillLimit)) {
  projected.fills = { ...projected.fills, fills: projected.fills.fills.slice(0, Math.max(0, fillLimit)) }
}
emit({ ...base, ...projected })
process.exit(0)

// ── output ──────────────────────────────────────────────────────────────────
// Output is always JSON — every figure here is destined for a report's audit
// trail, and a second human-prose formatter would be a second place for a number
// to drift from the file it came from. `--json` is accepted and is a no-op, so
// the flag reads the same as on the other tools.
function emit(obj) {
  console.log(JSON.stringify(obj, null, 2))
}
