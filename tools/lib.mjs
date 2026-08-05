// ============================================================================
// tools/lib.mjs — pure computation library for the Fallen Knives / Flying
// Rocket / framework-calibration toolchain. NO network, NO filesystem.
// Every function implements the corresponding SKILL.md rule LETTER-FOR-LETTER;
// if a SKILL band changes, this file must change in the same commit
// (see "Deterministic Toolchain" sections in the SKILLs).
// ============================================================================

// ── generic math ────────────────────────────────────────────────────────────

/** Wilder RSI. closes = chronological (oldest → newest). */
export function wilderRSI(closes, period = 14) {
  if (!Array.isArray(closes) || closes.length < period + 1) {
    return { rsi: null, closes_used: closes ? closes.length : 0, confidence: 'insufficient',
      note: `need ≥${period + 1} closes for a seed, ≥15 for a low-confidence read, ≥30 for unflagged (FK momentum input rule)` }
  }
  let gain = 0, loss = 0
  for (let i = 1; i <= period; i++) {
    const d = closes[i] - closes[i - 1]
    if (d >= 0) gain += d; else loss -= d
  }
  let avgGain = gain / period, avgLoss = loss / period
  for (let i = period + 1; i < closes.length; i++) {
    const d = closes[i] - closes[i - 1]
    avgGain = (avgGain * (period - 1) + Math.max(d, 0)) / period
    avgLoss = (avgLoss * (period - 1) + Math.max(-d, 0)) / period
  }
  const rsi = avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss)
  const n = closes.length
  // FK momentum input rule: 15–29 closes = low-confidence; ≥30 unflagged
  const confidence = n >= 30 ? 'ok' : 'low'
  return { rsi: round2(rsi), closes_used: n, period, confidence }
}

export function sma(values, n) {
  if (!Array.isArray(values) || values.length < n) return null
  const tail = values.slice(-n)
  return tail.reduce((a, b) => a + b, 0) / n
}

export function drawdownPct(spot, ath) { return round2((1 - spot / ath) * 100) }

function round2(x) { return x == null ? null : Math.round(x * 100) / 100 }

/** Median of a numeric array. null on empty input. Even-length averages the two middle values. */
export function median(values) {
  if (!Array.isArray(values) || values.length === 0) return null
  const sorted = values.slice().sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

function round3(x) { return x == null ? null : Math.round(x * 1000) / 1000 }

/**
 * Canonical spot = median of the primary source + ≥2 others (FK SKILL:166).
 * `quotes`: [{source, symbol, value, ts, ts_kind}], ts_kind ∈
 * 'venue' | 'receipt' | 'bar_close'.
 *
 * Encoded adjudications, each pinned by a selftest vector:
 * - A `bar_close` quote (a Yahoo daily candle's last close) is ALWAYS frozen
 *   and never enters the median — it is not a live venue print.
 * - A stale (outside `windowMin`) quote within `spreadFlagPct` of the live
 *   cluster's median is excluded from the median but NOT flagged (SKILL:
 *   "need not be flagged"). A stale AND divergent quote is still shown, with
 *   its age and an explicit EXCLUDED reason — never silently dropped.
 * - Spread is computed over the INCLUDED set only.
 * - Exactly `spreadFlagPct` (0.5%) is NOT `> 0.5%` — the strict SKILL letter.
 * - Zero usable quotes → `canonical: null`, never a throw.
 */
export function spotPanel(quotes, { nowMs = Date.now(), windowMin = 120, spreadFlagPct = 0.5 } = {}) {
  if (!Array.isArray(quotes) || quotes.length === 0) {
    return { canonical: null, method: 'median', median_kind: null, n_sources: 0, n_synchronized: 0,
      spread_pct: null, spread_gt_0_5pct: null, low_confidence: true, low_confidence_reason: 'no quotes supplied',
      synchronized_window_min: windowMin, sources: [], excluded: [], priority_first: null, priority_first_delta_pct: null,
      warning: 'no usable spot quotes' }
  }
  const windowMs = windowMin * 60000
  const venueQuotes = quotes.filter(q => q.ts_kind !== 'bar_close')
  const barCloseQuotes = quotes.filter(q => q.ts_kind === 'bar_close')
  const freshVenue = venueQuotes.filter(q => q.ts == null || (nowMs - q.ts) <= windowMs)
  const staleVenue = venueQuotes.filter(q => q.ts != null && (nowMs - q.ts) > windowMs)

  const provisionalMedian = median(freshVenue.map(q => q.value))
  const included = freshVenue.slice()
  const excluded = []
  for (const q of staleVenue) {
    const ageMin = Math.round((nowMs - q.ts) / 60000)
    const deltaPct = provisionalMedian ? Math.abs(q.value / provisionalMedian - 1) * 100 : null
    if (deltaPct != null && deltaPct <= spreadFlagPct) {
      excluded.push({ ...q, age_min: ageMin, reason: null,
        note: 'stale but within tolerance of the live cluster — excluded from the median, not flagged' })
    } else {
      excluded.push({ ...q, age_min: ageMin, reason: `EXCLUDED — outside ${windowMin}min window, divergent` })
    }
  }
  for (const q of barCloseQuotes) excluded.push({ ...q, age_min: null, reason: 'frozen bar close — never enters the median' })

  const values = included.map(q => q.value)
  const canonical = values.length ? median(values) : null
  const spreadPct = values.length >= 2 ? round3((Math.max(...values) - Math.min(...values)) / Math.min(...values) * 100)
    : (values.length === 1 ? 0 : null)
  const spreadGt = spreadPct != null ? spreadPct > spreadFlagPct : null

  const priorityFirst = quotes.length ? quotes[0].value : null
  const priorityFirstDeltaPct = (canonical != null && priorityFirst != null) ? round3((priorityFirst / canonical - 1) * 100) : null

  const lowConfidence = values.length < 2
  const lowConfidenceReason = lowConfidence
    ? (values.length === 0 ? 'no synchronized quotes available' : 'only one synchronized quote — no independent cross-check')
    : null

  return {
    canonical, method: 'median', median_kind: `${values.length}-source`,
    n_sources: quotes.length, n_synchronized: values.length,
    spread_pct: spreadPct, spread_gt_0_5pct: spreadGt,
    low_confidence: lowConfidence, low_confidence_reason: lowConfidenceReason,
    synchronized_window_min: windowMin,
    sources: included, excluded,
    priority_first: priorityFirst, priority_first_delta_pct: priorityFirstDeltaPct,
    warning: spreadGt ? `inter-source spread ${spreadPct}% > ${spreadFlagPct}% — reconcile before scoring` : null,
  }
}

/** Sample standard deviation (n-1 denominator). null on n<2. */
export function stdev(values) {
  if (!Array.isArray(values) || values.length < 2) return null
  const n = values.length
  const mean = values.reduce((a, b) => a + b, 0) / n
  const variance = values.reduce((a, v) => a + (v - mean) ** 2, 0) / (n - 1)
  return Math.sqrt(variance)
}

/**
 * Percentile rank of `x` within `values` — where a reading sits in its OWN
 * recent distribution, 0-100 (market-data-extension plan, Tier 0/B1). This
 * is DISCLOSED CONTEXT ONLY: it re-expresses an existing scored input
 * (RSI, drawdown, funding, F&G) against its own 2y history, it never
 * becomes a new leg or gate itself — turning it into one is a
 * framework-calibration job, not a toolchain one.
 *
 * Midrank on ties: a value equal to k other observations counts as HALF a
 * rank for each tie, matching the standard percentile-rank convention (a
 * value tied with everything reads as the 50th percentile, not the 0th or
 * 100th, and does not silently favor one direction). Nulls in `values` are
 * dropped, never coerced to 0 — a missing history point must not manufacture
 * a fake percentile.
 */
export function percentileRank(values, x) {
  const clean = (values || []).filter(v => typeof v === 'number' && Number.isFinite(v))
  if (clean.length === 0 || typeof x !== 'number' || !Number.isFinite(x)) return null
  let below = 0, equal = 0
  for (const v of clean) { if (v < x) below++; else if (v === x) equal++ }
  return Math.round(((below + equal / 2) / clean.length) * 10000) / 100
}

/**
 * Summary stats over a numeric series for disclosure panels
 * (market-data-extension plan, Tier 0/B1). Pure, null-safe, no percentile —
 * pair with percentileRank() for "where does the CURRENT reading sit."
 * Nulls are dropped, never coerced to 0.
 */
export function distributionStats(values) {
  const clean = (values || []).filter(v => typeof v === 'number' && Number.isFinite(v))
  if (clean.length === 0) return { n: 0, min: null, max: null, median: null, mean: null, stdev: null }
  const mean = clean.reduce((a, b) => a + b, 0) / clean.length
  return {
    n: clean.length,
    min: Math.min(...clean),
    max: Math.max(...clean),
    median: median(clean),
    mean: Math.round(mean * 10000) / 10000,
    stdev: stdev(clean),
  }
}

/**
 * Pearson correlation coefficient. THROWS on length mismatch (a caller bug,
 * not a data condition). Returns null — never NaN — on zero variance in
 * either series: a NaN would make `corr > 0.7` evaluate false, silently
 * reading as "not risk-on" (fail-OPEN on a surcharge suppressor). null routes
 * to the documented "not computed → surcharge OFF, disclosed" path instead.
 */
export function pearson(xs, ys) {
  if (xs.length !== ys.length) throw new Error(`pearson: length mismatch (${xs.length} vs ${ys.length})`)
  const n = xs.length
  if (n < 2) return null
  const mx = xs.reduce((a, b) => a + b, 0) / n
  const my = ys.reduce((a, b) => a + b, 0) / n
  let sxy = 0, sxx = 0, syy = 0
  for (let i = 0; i < n; i++) {
    const dx = xs[i] - mx, dy = ys[i] - my
    sxy += dx * dy; sxx += dx * dx; syy += dy * dy
  }
  if (sxx === 0 || syy === 0) return null
  return sxy / Math.sqrt(sxx * syy)
}

/** % change between values[values.length-1-n] and the last value. null if out of range. */
export function pctChange(values, n) {
  if (!Array.isArray(values) || values.length <= n || n < 1) return null
  const from = values[values.length - 1 - n]
  const to = values[values.length - 1]
  if (from === 0) return null
  return round2((to / from - 1) * 100)
}

/**
 * Length of the trailing (or leading) run of consecutive elements satisfying
 * `predicate`. `from: 'end'` (default) scans backward from the last element;
 * `from: 'start'` scans forward from the first.
 */
export function consecutiveRun(values, predicate, { from = 'end' } = {}) {
  if (!Array.isArray(values) || values.length === 0) return 0
  let count = 0
  if (from === 'end') {
    for (let i = values.length - 1; i >= 0; i--) {
      if (!predicate(values[i])) break
      count++
    }
  } else {
    for (let i = 0; i < values.length; i++) {
      if (!predicate(values[i])) break
      count++
    }
  }
  return count
}

/**
 * % slope of the n-period SMA over the trailing `lookback` periods:
 * (sma(values, n) / sma(values.slice(0, -lookback), n) - 1) × 100.
 * null if there is insufficient history for either SMA.
 */
export function smaSlope(values, { n = 200, lookback = 20 } = {}) {
  if (!Array.isArray(values) || values.length < n + lookback) return null
  const smaNow = sma(values, n)
  const smaPast = sma(values.slice(0, values.length - lookback), n)
  if (smaNow == null || smaPast == null || smaPast === 0) return null
  return round2((smaNow / smaPast - 1) * 100)
}

/**
 * Realized volatility over the trailing `window` sessions of a chronological
 * closes series — annualized stdev of log returns, in percent
 * (market-data-extension plan, Tier 0/B2). DISCLOSED CONTEXT ONLY: not a
 * scored leg or gate.
 *
 * `annualize` is an asset-class convention, same split as isTradingDay()'s
 * assetClass — crypto trades 365 days/year, equities/gold ~252 — and must be
 * supplied explicitly by the caller rather than defaulted per-asset here
 * (lib.mjs has no concept of "which asset," fetch.mjs does).
 * `null` (not a crash) when there is insufficient history for the window.
 */
export function realizedVol(closes, { window = 30, annualize = 365 } = {}) {
  if (!Array.isArray(closes) || closes.length <= window) return null
  const rets = logReturns(closes.slice(-(window + 1)))
  if (rets.length < 2) return null
  const sd = stdev(rets)
  if (sd == null) return null
  return Math.round(sd * Math.sqrt(annualize) * 10000) / 100
}

/**
 * Realized-vol block: rv10/rv30/rv90 off the LATEST point in `closes`, all
 * under the same `annualize` convention (market-data-extension plan, B2).
 * Any window without enough history is `null`, never a value silently
 * computed off a shorter, undisclosed window.
 */
export function realizedVolBlock(closes, { annualize = 365 } = {}) {
  return {
    rv10: realizedVol(closes, { window: 10, annualize }),
    rv30: realizedVol(closes, { window: 30, annualize }),
    rv90: realizedVol(closes, { window: 90, annualize }),
    annualize_convention: annualize,
  }
}

/**
 * Non-crypto sentiment PROXY panel — DISCLOSED REGIME CONTEXT ONLY, never a
 * scored leg input. This exists because gold has no Alternative.me F&G, and
 * every gold report to date has scored the sentiment leg at the NOT-FOUND
 * fallback of 2 while asserting that no free daily gold fear instrument
 * exists. Two candidates were tested against 10y of daily data on
 * 2026-08-05, and BOTH FAILED as scored inputs — that evidence is why this
 * block is context and not a leg:
 *
 *   1. GVZ (CBOE gold-ETF volatility index). No contrarian gradient at all.
 *      Forward 20d gold returns by GVZ percentile bucket ran 1.10 / 1.39 /
 *      1.20 / 0.54 / 1.20 / 1.48 % — flat noise. A volatility index is
 *      direction-blind: gold IV spikes on crisis-bid melt-UPS as readily as
 *      on flushes, so a high print cannot be read as fear. The prior
 *      reports' refusal to score it is CONFIRMED, now on evidence.
 *
 *   2. PHYS closed-end premium/discount (Sprott Physical Gold Trust market
 *      price vs its own metal value). Structurally the right shape — gold's
 *      own price cancels in the ratio, so it isolates what investors will
 *      pay OVER metal, and it is neither positioning (not COT, so no
 *      double-key with capitulation-(b)/gate 1) nor a transform of the
 *      closes the momentum/valuation legs already read. It showed a clean
 *      full-sample gradient (60d fwd 5.40 -> 2.61 % from discount to
 *      premium, 75% -> 61% win) and an arb-pinned GLD control confirmed the
 *      construction does not manufacture it. It STILL FAILS: split-half, the
 *      2018-21 era INVERTS (deep-discount 60d fwd -1.48%, 36% win) while
 *      2022-26 gives +7.71%, 82%. The deep-discount tail returns 3.67% vs a
 *      3.59% UNCONDITIONAL baseline — no edge — and its 140 qualifying days
 *      are only 16 distinct episodes, so effective N is ~16. That is the
 *      framework's own "overfit to one regime" rejection category.
 *
 * So: surfaced every report as disclosed context, explicitly unscored. The
 * sentiment leg keeps its fallback of 2. Promoting either of these into the
 * rubric is a framework-calibration job with adversarial refutation, not a
 * toolchain one — and on today's evidence it would be rejected.
 *
 * `baselineWindow` detrends the CEF ratio against its own trailing mean
 * (Sprott's ~0.4%/yr fee drift would otherwise contaminate a raw level);
 * both the baseline and the percentile window are strictly TRAILING, so
 * there is no lookahead.
 */
export function sentimentProxyBlock({ volCloses = null, cefCloses = null, refCloses = null,
  baselineWindow = 250, percentileWindow = 504 } = {}) {
  const out = { scored: false,
    note: 'DISCLOSED REGIME CONTEXT ONLY — not a scored leg input. Both proxies were tested over 10y on 2026-08-05 and failed as scored inputs (GVZ: no gradient; PHYS premium: era-dependent, effective N~16). The sentiment leg keeps its NOT-FOUND fallback of 2.' }

  if (volCloses && volCloses.length) {
    const last = volCloses[volCloses.length - 1]
    const hist = volCloses.slice(-percentileWindow - 1, -1)
    out.vol_index = { last: round2(last),
      percentile_vs_2y: hist.length ? percentileRank(hist, last) : null,
      history_days: volCloses.length,
      interpretation: 'direction-blind — a high print means turbulence, NOT fear; never read as a fear signal' }
  }

  // CEF premium: the trust's market price relative to the metal it holds.
  // Detrended against its own trailing baseline, so the reading is "rich or
  // cheap vs its own recent norm", not an absolute NAV premium (which would
  // need Sprott's published NAV — no stable free daily endpoint exists).
  if (cefCloses && refCloses && cefCloses.length === refCloses.length && cefCloses.length > baselineWindow) {
    const ratio = cefCloses.map((v, i) => (refCloses[i] ? v / refCloses[i] : null))
    const prem = ratio.map((v, i) => {
      if (v == null || i < baselineWindow - 1) return null
      let s = 0, n = 0
      for (let k = i - baselineWindow + 1; k <= i; k++) { if (ratio[k] != null) { s += ratio[k]; n++ } }
      return n === baselineWindow ? round2((v / (s / n) - 1) * 100) : null
    })
    const last = prem[prem.length - 1]
    if (last != null) {
      const hist = prem.slice(-percentileWindow - 1, -1).filter(v => v != null)
      out.cef_premium = { premium_pct: last,
        percentile_vs_2y: hist.length ? percentileRank(hist, last) : null,
        baseline_window_days: baselineWindow, history_days: prem.filter(v => v != null).length,
        sign: last < 0 ? 'DISCOUNT' : 'PREMIUM',
        interpretation: 'discount = investors paying below metal value (fear-shaped); UNSCORED — the signal is era-dependent and did not survive split-half validation' }
    }
  }
  return out
}

/**
 * Rolling realized-vol series (market-data-extension plan, B2) — one
 * `realizedVol` reading per trailing point in `closes`, so a LATEST reading
 * can be percentile-ranked (percentileRank()) against its own recent
 * history rather than judged against an absolute cut-point. Pure function;
 * fetch.mjs (Tier 0 wiring, B3) supplies the closes array and does the
 * percentileRank() call itself — this only produces the series.
 */
export function rollingRealizedVol(closes, { window = 30, annualize = 365 } = {}) {
  const out = []
  for (let i = window + 1; i <= closes.length; i++) {
    const v = realizedVol(closes.slice(0, i), { window, annualize })
    if (v != null) out.push(v)
  }
  return out
}

/**
 * Rolling Wilder RSI series (market-data-extension plan, B3) — one
 * wilderRSI().rsi reading per trailing point in `closes`, so the LATEST
 * weekly/daily RSI-14 can be percentile-ranked against its own history
 * instead of judged only against the fixed FK/FR bands. Pure; drops points
 * where wilderRSI() itself reports insufficient history (never fabricates
 * an early reading).
 */
export function rollingWilderRSI(closes, period = 14) {
  const out = []
  for (let i = period + 1; i <= closes.length; i++) {
    const r = wilderRSI(closes.slice(0, i), period)
    if (r.rsi != null) out.push(r.rsi)
  }
  return out
}

/**
 * Rolling drawdown-from-running-ATH series (market-data-extension plan,
 * B3) — at each point, % below the highest close SEEN SO FAR (not the
 * eventual full-series high, which would be look-ahead bias). Feeds the
 * "drawdown-from-ATH percentile" context field. Pure.
 */
export function rollingDrawdownFromATH(closes) {
  const out = []
  let runningHigh = -Infinity
  for (const c of closes) {
    if (typeof c !== 'number') continue
    runningHigh = Math.max(runningHigh, c)
    out.push(drawdownPct(c, runningHigh))
  }
  return out
}

/**
 * Rolling % distance from a trailing SMA(n) (market-data-extension plan,
 * B3) — e.g. distance-to-200dma at every historical point, for percentile
 * ranking the CURRENT distance. Pure; points before `n` closes exist are
 * skipped, not fabricated.
 */
export function rollingSMADistance(closes, n) {
  const out = []
  for (let i = n; i <= closes.length; i++) {
    const window = closes.slice(0, i)
    const s = sma(window, n)
    if (s != null && s !== 0) out.push(round2((window[window.length - 1] / s - 1) * 100))
  }
  return out
}

/**
 * Rolling bounce-% series (FR-parity plan, FR4) — at each point, % the
 * current close sits above the LOW of the trailing `lowN` closes (a
 * closes-only proxy for dailyTrend()'s `trend.bounce_pct`, which uses
 * session lows; this is what a 2y CLOSE-only series can support). Feeds the
 * "is this bounce big FOR THIS ASSET" percentile — §4B's rally leg has the
 * largest max (5) and its band edges are fixed absolute percentages. FIXED
 * `lowN`-session TRAILING window (a rolling low, not a running-since-start
 * one) — matches rollingSMADistance()'s windowed style, unlike
 * rollingDrawdownFromATH()'s running-high style. Pure.
 */
export function rollingBouncePct(closes, lowN = 40) {
  const out = []
  for (let i = lowN; i <= closes.length; i++) {
    const window = closes.slice(i - lowN, i)
    const low = Math.min(...window)
    if (low > 0) out.push(round2((closes[i - 1] / low - 1) * 100))
  }
  return out
}

/**
 * Rolling %-below-trailing-high series (FR-parity plan, FR4) — at each
 * point, % below the HIGH of the trailing `windowN` closes (a FIXED
 * trailing window, unlike rollingDrawdownFromATH()'s running-since-start
 * high). Feeds a percentile context for `high_1y.pct_below` — the FR
 * phase-of-cycle cap's own input, today judged only against the fixed
 * 10%/20% cap tiers. This is a CLOSES-only proxy over the fetched daily
 * window, not the weekly-high computation `outp.high_1y` itself uses — the
 * two are related, not identical, and the emitted note says so explicitly
 * rather than implying equivalence. Pure.
 */
export function rollingTrailingHighDistance(closes, windowN = 365) {
  const out = []
  for (let i = windowN; i <= closes.length; i++) {
    const window = closes.slice(i - windowN, i)
    const high = Math.max(...window)
    if (high > 0) out.push(drawdownPct(closes[i - 1], high))
  }
  return out
}

/**
 * Parse a Deribit option instrument name like "BTC-28AUG26-110000-P" into
 * {currency, expiry (ms epoch, UTC), strike, type: 'C'|'P'}. `null` on
 * anything that doesn't match Deribit's own naming convention — a single
 * malformed/unexpected instrument must never crash the whole book parse.
 */
function parseDeribitInstrument(name) {
  const m = /^([A-Z]+)-(\d{1,2})([A-Z]{3})(\d{2})-(\d+)-([CP])$/.exec(String(name || ''))
  if (!m) return null
  const months = { JAN: 0, FEB: 1, MAR: 2, APR: 3, MAY: 4, JUN: 5, JUL: 6, AUG: 7, SEP: 8, OCT: 9, NOV: 10, DEC: 11 }
  const mon = months[m[3]]
  if (mon == null) return null
  // Deribit options expire 08:00 UTC.
  const expiry = Date.UTC(2000 + Number(m[4]), mon, Number(m[2]), 8, 0, 0)
  return { currency: m[1], expiry, strike: Number(m[5]), type: m[6] }
}

/**
 * BTC/ETH options vol surface from a Deribit book-summary snapshot
 * (market-data-extension plan, C1). DISCLOSED CONTEXT ONLY — not a scored
 * leg or gate; promoting any of this into the rubric is a
 * framework-calibration job.
 *
 * `bookRows` = raw `get_book_summary_by_currency?kind=option` result array.
 * `dvolCandles` = raw `get_volatility_index_data` candle tuples
 * (`[ts, open, high, low, close]`, Deribit's own shape) — the LAST candle's
 * close is the current DVOL reading. `rv30` (percent, from
 * realizedVolBlock()) is optional; supplying it also derives the variance
 * risk premium (ATM IV − realized vol), the part that actually distinguishes
 * priced fear from panicked fear.
 *
 * An EMPTY `bookRows`/`dvolCandles` is Deribit's own documented response for
 * an unsupported or illiquid currency — verified live 2026-08-03: SOL is a
 * LISTED Deribit currency (it appears in get_currencies) but its options
 * book and DVOL both return empty arrays, not an error. A currency-list
 * membership check is therefore NOT sufficient; this function tests the
 * actual payload and reports `available:false` rather than fabricating a
 * zero IV — the same "absent, not zero" discipline as ASSETS.gold's missing
 * `funding` block.
 *
 * The skew is explicitly MONEYNESS-based (`|strike/spot − 1|` buckets, ~10%
 * OTM put vs ~10% OTM call), NOT a true 25-delta risk reversal — the book
 * summary carries no per-instrument delta, and fetching Greeks per
 * instrument would be hundreds of calls. Naming it `rr25` would overclaim
 * precision this data cannot support.
 */
export function deribitVolBlock({ dvolCandles = [], bookRows = [], rv30 = null, nowMs = Date.now(), minDaysOut = 7, maxDaysOut = 45 } = {}) {
  const dvol = Array.isArray(dvolCandles) && dvolCandles.length ? dvolCandles[dvolCandles.length - 1][4] : null
  const note = 'DISCLOSED CONTEXT ONLY — not a scored leg or gate. Skew is MONEYNESS-based (|strike/spot-1| buckets), NOT a true 25-delta risk reversal (book summary carries no per-instrument delta).'

  const parsed = (bookRows || []).map(r => {
    const p = parseDeribitInstrument(r && r.instrument_name)
    return p && r.mark_iv != null && r.underlying_price != null ? { ...p, mark_iv: r.mark_iv, underlying_price: r.underlying_price } : null
  }).filter(Boolean)

  if (!parsed.length) return { available: false, reason: 'no usable option quotes — empty or illiquid book', dvol, rv30, note }

  const withDays = parsed.map(p => ({ ...p, days_out: (p.expiry - nowMs) / 86400000 }))
  const inWindow = withDays.filter(p => p.days_out >= minDaysOut && p.days_out <= maxDaysOut)
  if (!inWindow.length) return { available: false, reason: `no expiry ${minDaysOut}-${maxDaysOut} days out`, dvol, rv30, note }

  // nearest-to-midpoint expiry within the window (e.g. ~26 days for the 7-45 default)
  const targetDays = (minDaysOut + maxDaysOut) / 2
  const nearest = inWindow.reduce((best, p) => (Math.abs(p.days_out - targetDays) < Math.abs(best.days_out - targetDays) ? p : best))
  const chain = inWindow.filter(p => p.expiry === nearest.expiry)
  const spot = median(chain.map(p => p.underlying_price))

  const closestByStrike = (target, type) => {
    const pool = chain.filter(p => p.type === type)
    return pool.length ? pool.reduce((best, p) => (Math.abs(p.strike - target) < Math.abs(best.strike - target) ? p : best)) : null
  }
  const atmCall = closestByStrike(spot, 'C'), atmPut = closestByStrike(spot, 'P')
  const atmIvs = [atmCall, atmPut].filter(Boolean).map(p => p.mark_iv)
  const atmIv = atmIvs.length ? round2(atmIvs.reduce((a, b) => a + b, 0) / atmIvs.length) : null

  const putLeg = closestByStrike(spot * 0.9, 'P'), callLeg = closestByStrike(spot * 1.1, 'C')
  const skew = (putLeg && callLeg) ? round2(putLeg.mark_iv - callLeg.mark_iv) : null
  const vrp = (atmIv != null && rv30 != null) ? round2(atmIv - rv30) : null

  return {
    available: true, dvol,
    expiry_used: new Date(nearest.expiry).toISOString().slice(0, 10),
    days_out: round2(nearest.days_out),
    spot_used: round2(spot),
    atm_iv_pct: atmIv,
    skew_90_110_moneyness_pct: skew,
    skew_sign_convention: 'POSITIVE = the ~10% OTM put is richer (higher IV) than the ~10% OTM call = a downside hedging bid. A distribution blow-off shows this skew COMPRESSING toward zero or INVERTING (calls bid), not rising — same discipline as fundingBlock.sign_convention and basisBlock.sign_convention: stated in the output because this repo has already been bitten twice by an inverted sign (the Jul-2026 funding correction; the ETH backtest\'s "funding cuts against shorts" error).',
    skew_legs: { put_strike: putLeg ? putLeg.strike : null, call_strike: callLeg ? callLeg.strike : null },
    rv30_pct: rv30,
    vrp_pct: vrp,
    note,
  }
}

/**
 * Perp basis + carry context (market-data-extension plan, C2). DISCLOSED
 * CONTEXT ONLY — not a scored leg or gate. `mark`/`index` come from
 * Binance's premiumIndex (mark price vs index price). `fundingAnnualizedPct`
 * is fr.annualizedFunding()'s OWN OUTPUT for the SAME asset — this function
 * does not recompute it, just re-expresses it against a risk-free
 * benchmark, keeping the sign convention verbatim: POSITIVE funding = longs
 * pay shorts = carry INCOME to a short (the exact convention already pinned
 * in compute.mjs fr-funding — restating it differently here would be a bug
 * factory, not a feature). `riskFreePct` (e.g. fetchMacro()'s
 * dry_powder_benchmark.annualized_pct) is optional; supplying it derives
 * `vs_risk_free_pp`. The `label` is DESCRIPTIVE ONLY — same discipline as
 * correlationRegime()'s label ladder — it carries no scoring consequence.
 */
export function basisBlock({ mark = null, index = null, fundingAnnualizedPct = null, riskFreePct = null } = {}) {
  if (typeof mark !== 'number' || typeof index !== 'number' || index === 0) {
    return { available: false, reason: 'mark/index price unavailable', note: 'DISCLOSED CONTEXT ONLY — not a scored leg or gate' }
  }
  const perpBasisPct = round2((mark / index - 1) * 100)
  const carryPct = typeof fundingAnnualizedPct === 'number' ? fundingAnnualizedPct : null
  const vsRiskFreePp = (carryPct != null && typeof riskFreePct === 'number') ? round2(carryPct - riskFreePct) : null
  const label = carryPct == null ? 'not computed' : carryPct > 0 ? 'positive (longs pay shorts)' : carryPct < 0 ? 'negative (shorts pay longs)' : 'flat'
  return {
    available: true,
    perp_basis_pct: perpBasisPct,
    annualized_carry_pct: carryPct,
    vs_risk_free_pp: vsRiskFreePp,
    label,
    sign_convention: 'POSITIVE funding = longs pay shorts = carry INCOME to a short (FR SKILL, Jul 2026) — identical convention to fr.annualizedFunding()',
    note: 'DISCLOSED CONTEXT ONLY — not a scored leg or gate; label is descriptive, consequence-free (mirrors the correlation label-ladder discipline)',
  }
}

/**
 * Binance derivatives positioning context (market-data-extension plan, C3).
 * DISCLOSED CONTEXT ONLY — not a scored leg or gate. Combines three raw
 * Binance fapi series, each already chronological (oldest → newest), each
 * passed as-is from the endpoint:
 *  - `longShortRows`: futures/data/globalLongShortAccountRatio ({longShortRatio, ...})
 *  - `takerRows`: futures/data/takerlongshortRatio ({buySellRatio, ...})
 *  - `oiRows`: futures/data/openInterestHist ({sumOpenInterest, ...})
 *
 * Two honesty constraints stated IN THE OUTPUT, not only a comment — this
 * mirrors fundingBlock()'s existing oi_90d_high_available discipline:
 *  - Binance's own endpoints cap this history at ~30 days — `history_days`
 *    reports the ACTUAL count obtained, never a 90-day claim.
 *  - these are Binance-ACCOUNT-weighted, SINGLE-VENUE series — not
 *    market-wide open interest and not a cross-exchange volume measure.
 * `oi_90d_high_available`/`oi_within_5pct_of_90d_high` are carried through
 * UNCHANGED from fundingBlock()'s existing null/false discipline — this
 * function does not attempt to satisfy that 90d requirement from 30d data.
 */
export function positioningBlock({ longShortRows = [], takerRows = [], oiRows = [] } = {}) {
  const nums = (rows, field) => (rows || []).map(r => Number(r && r[field])).filter(v => Number.isFinite(v))
  const latest = arr => (arr.length ? arr[arr.length - 1] : null)
  const direction = (hist, current) => {
    if (current == null || hist.length < 2) return null
    const prior = hist[hist.length - 2]
    return current > prior ? 'rising' : current < prior ? 'falling' : 'flat'
  }

  const lsHist = nums(longShortRows, 'longShortRatio')
  const takerHist = nums(takerRows, 'buySellRatio')
  const oiHist = nums(oiRows, 'sumOpenInterest')

  const lsNow = latest(lsHist), takerNow = latest(takerHist), oiNow = latest(oiHist)

  return {
    long_short_account_ratio: lsHist.length ? {
      latest: lsNow, percentile_vs_history: percentileRank(lsHist.slice(0, -1), lsNow), direction: direction(lsHist, lsNow),
    } : null,
    taker_buy_sell_ratio: takerHist.length ? {
      latest: takerNow, percentile_vs_history: percentileRank(takerHist.slice(0, -1), takerNow), direction: direction(takerHist, takerNow),
    } : null,
    open_interest: oiHist.length ? {
      latest: oiNow, percentile_vs_history: percentileRank(oiHist.slice(0, -1), oiNow), direction: direction(oiHist, oiNow),
      oi_90d_high_available: false, oi_within_5pct_of_90d_high: null,
    } : null,
    history_days: Math.max(lsHist.length, takerHist.length, oiHist.length),
    scope_note: 'Binance-ACCOUNT-weighted, SINGLE-VENUE series (not market-wide OI, not a cross-exchange measure); history capped at ~30 days by the endpoint itself, never treated as 90d',
    note: 'DISCLOSED CONTEXT ONLY — not a scored leg or gate',
  }
}

/**
 * Fed net liquidity proxy = WALCL - RRP - TGA (market-data-extension plan,
 * C4). DISCLOSED CONTEXT ONLY — not a scored leg or gate.
 *
 * UNIT TRAP, stated first — same class as the Binance fundingRate
 * fraction-vs-percent trap fundingBlock() already guards against: FRED
 * reports WALCL and WTREGEN in $ MILLIONS but RRPONTSYD in $ BILLIONS.
 * Mixing them unconverted is a 1000x error. The conversion happens INSIDE
 * this function (rrpontsydBillions * 1000) — a caller can never pass all
 * three in "FRED's native units" and silently get a wrong answer; the
 * parameter name itself states the expected unit.
 */
export function netLiquidity({ walclMillions = null, rrpontsydBillions = null, wtregenMillions = null } = {}) {
  if (typeof walclMillions !== 'number' || typeof rrpontsydBillions !== 'number' || typeof wtregenMillions !== 'number') {
    return { available: false, reason: 'WALCL/RRPONTSYD/WTREGEN unavailable', note: 'DISCLOSED CONTEXT ONLY — not a scored leg or gate' }
  }
  const rrpMillions = rrpontsydBillions * 1000
  const netMillions = round2(walclMillions - rrpMillions - wtregenMillions)
  return {
    available: true,
    net_liquidity_usd_millions: netMillions,
    net_liquidity_usd_trillions: round2(netMillions / 1e6),
    components: { walcl_usd_millions: walclMillions, rrpontsyd_usd_billions: rrpontsydBillions, wtregen_usd_millions: wtregenMillions },
    cadence_note: 'WALCL/WTREGEN publish WEEKLY (Thursdays) — this is a weekly figure, not daily; a stale mid-week read must not be mistaken for fresh',
    note: 'DISCLOSED CONTEXT ONLY — not a scored leg or gate',
  }
}

/**
 * Aggregate stablecoin supply context (market-data-extension plan, C5).
 * DISCLOSED CONTEXT ONLY — a capital-flow tell, not a settled figure.
 *
 * `rows` = raw DefiLlama `stablecoincharts/all` result array, each row
 * `{date (unix seconds, string), totalCirculatingUSD: {peggedUSD, ...}}`
 * (verified live 2026-08-03), chronological oldest → newest. Emits the
 * latest total + 30d/90d net change + percentile vs the full supplied
 * history — labeled explicitly as THIRD-PARTY, cross-chain aggregation
 * subject to back-revision, never presented as a settled point figure.
 */
/**
 * Spot-borrow context from a Bitfinex margin-funding ticker (FR-parity plan,
 * FR5). DISCLOSED CONTEXT ONLY — not a scored leg or gate; FR SKILL §2's
 * Carry row, §2.5's smaller-alt row, and the Carry Cost Ledger all mandate a
 * spot-borrow cell with no source ever pinned for it.
 *
 * `ticker` is Bitfinex's own raw `GET /v2/ticker/f<CCY>` array shape:
 * [FRR, BID, BID_PERIOD, BID_SIZE, ASK, ASK_PERIOD, ASK_SIZE, ...]. FRR is a
 * DAILY rate FRACTION (e.g. 3.56e-8 for BTC) — annualizing it is ×100 for
 * percent then ×365 for the year, the SAME class of unit trap the Binance
 * fundingRate fraction-vs-percent trap already guards against, so both
 * conversions happen INSIDE this function rather than left to a caller. The
 * output keeps 8 decimal places (not the usual round2) because these rates
 * are genuinely this small (BTC ~0.0013%/yr) — round2 would floor every one
 * of them to 0.00 and silently discard the reading, and even 6dp double-
 * rounds a chained daily->annualized computation into a double-digit
 * relative error. `annualized_pct` is computed directly from `frr`, never
 * chained off the already-rounded `daily_rate_pct`.
 *
 * Three caveats stated IN THE OUTPUT, not only a comment — mirrors
 * positioningBlock()'s scope_note discipline:
 *  1. SINGLE VENUE.
 *  2. a LENDING book, not necessarily the venue a short is actually borrowed
 *     on — this is a market-rate proxy, not the analyst's real borrow cost.
 *  3. frequently THIN (bid_size/ask_size are emitted so a sub-1-unit quote
 *     is visible as the noise it is, not read as a real market rate).
 */
export function borrowBlock(ticker) {
  const note = 'DISCLOSED CONTEXT ONLY — not a scored leg or gate. Bitfinex margin-funding book: a SINGLE VENUE, and a LENDING book — not necessarily the venue a short is actually borrowed on. Frequently THIN (see bid_size/ask_size) — a single large quote can move the headline rate; check the size before reading the rate as a real market.'
  if (!Array.isArray(ticker) || ticker.length < 7 || typeof ticker[0] !== 'number') {
    return { available: false, reason: 'malformed or missing Bitfinex funding ticker', note }
  }
  const [frr, bid, bidPeriodDays, bidSize, ask, askPeriodDays, askSize] = ticker
  // 8 decimal places, not the usual round2/6 — these rates are genuinely
  // this small (BTC ~1e-6 %/day) and rounding EACH stage independently
  // (rather than chaining annualized off an already-rounded daily figure)
  // avoids compounding a coarse rounding into a large relative error.
  const r8 = v => (typeof v === 'number' ? Math.round(v * 1e8) / 1e8 : null)
  const dailyRatePct = r8(frr * 100)
  const annualizedPct = r8(frr * 100 * 365)
  return {
    available: true,
    daily_rate_pct: dailyRatePct,
    annualized_pct: annualizedPct,
    bid_pct: r8(typeof bid === 'number' ? bid * 100 : null),
    bid_period_days: typeof bidPeriodDays === 'number' ? bidPeriodDays : null,
    bid_size: typeof bidSize === 'number' ? round2(bidSize) : null,
    ask_pct: r8(typeof ask === 'number' ? ask * 100 : null),
    ask_period_days: typeof askPeriodDays === 'number' ? askPeriodDays : null,
    ask_size: typeof askSize === 'number' ? round2(askSize) : null,
    scope_note: 'single-venue Bitfinex margin-FUNDING (lending) book, not necessarily the short\'s actual borrow venue; ~daily FRR annualized ×365 (simple, matching the fr-funding annualization convention)',
    note,
  }
}

export function stablecoinBlock(rows) {
  const clean = (rows || [])
    .map(r => ({ date: r && r.date, value: r && r.totalCirculatingUSD ? Number(r.totalCirculatingUSD.peggedUSD) : NaN }))
    .filter(r => Number.isFinite(r.value))
  if (!clean.length) return { available: false, reason: 'no usable rows', note: 'DISCLOSED CONTEXT ONLY — third-party aggregation, subject to back-revision' }

  const values = clean.map(r => r.value)
  const latest = values[values.length - 1]
  const nDaysAgo = n => (values.length > n ? values[values.length - 1 - n] : null)
  const netChangePct = (n) => { const past = nDaysAgo(n); return past != null && past !== 0 ? round2((latest / past - 1) * 100) : null }

  return {
    available: true,
    total_circulating_usd: Math.round(latest),
    net_change_30d_pct: netChangePct(30),
    net_change_90d_pct: netChangePct(90),
    percentile_vs_history: percentileRank(values.slice(0, -1), latest),
    n_days_history: values.length,
    as_of: clean[clean.length - 1].date,
    note: 'DISCLOSED CONTEXT ONLY — third-party cross-chain aggregation (DefiLlama), subject to back-revision; a capital-flow tell, not a settled figure',
  }
}

/**
 * Short EV with the carry zero-floor and the FR SKILL's two carry-side
 * vetoes (FR-parity plan, FR6). Mirrors §5/§6 LETTER-FOR-LETTER — this is
 * NOT disclosed context, it computes two of the framework's actual gate
 * checks (the +3% minimum-edge filter and the 40%-of-target carry veto),
 * today done as hand arithmetic across three separately-documented sign
 * conventions.
 *
 * `directionalEV` is supplied by the caller (e.g. weightedEV()'s output on
 * the report's own scenario grid) — this function does not derive it.
 * `fundingAnnualizedPct` is the SAME market-rate convention as
 * fr.annualizedFunding()/basisBlock() (POSITIVE = longs pay shorts = carry
 * INCOME to a short). Carry EV (%) = fundingAnnualizedPct × (holdDays/365),
 * per §5's own formula.
 *
 * Zero-floor on carry INCOME (§5, Jul 2026 correction): for the two GATE
 * checks only, carry income is floored at ZERO — `carry_ev_pct_floored =
 * min(carry_ev_pct_true, 0)`, so a real cost (negative) still counts in
 * full but an income (positive) can never help a short clear the filter or
 * shrink the veto. The headline `*_true` figures are the UNFLOORED signed
 * values, always printed alongside for transparency — the floor exists at
 * the two decision points, not in the reported EV itself.
 *
 * `passes_min_edge_filter` is `total_short_ev_for_gates > 3` — STRICT `>`;
 * the SKILL says the filter must be "exceeded", so exactly +3% does NOT
 * clear it. `carry_veto` is `carry_pct_of_target > 40` — also STRICT; the
 * SKILL says "if carry > 40% of target gain", so exactly 40% does NOT fire.
 * Neither edge is re-adjudicated here differently from the SKILL letter.
 *
 * `ledger_note` names the THIRD documented sign trap in this repo (after
 * the Jul-2026 funding correction and the FR2 skew-sign pin): the position
 * snapshot's `funding_usd` is ACCOUNT CASHFLOW (negative = paid out of the
 * account, whichever side it's on) and inverts against this MARKET-RATE
 * convention. It fills the realized Carry-Cost-Ledger column (§6) and
 * NOTHING computed here.
 */
export function shortEV({ directionalEV = null, fundingAnnualizedPct = null, holdDays = null, targetGainPct = null } = {}) {
  if (typeof directionalEV !== 'number' || typeof fundingAnnualizedPct !== 'number' || typeof holdDays !== 'number') {
    return { available: false, reason: 'directionalEV/fundingAnnualizedPct/holdDays required',
      sign_convention: 'POSITIVE funding = longs pay shorts = carry INCOME to a short (FR SKILL, Jul 2026)' }
  }
  const carryEvPctTrue = round2(fundingAnnualizedPct * (holdDays / 365))
  const carryEvPctFloored = Math.min(carryEvPctTrue, 0)
  const totalShortEvTrue = round2(directionalEV + carryEvPctTrue)
  const totalShortEvForGates = round2(directionalEV + carryEvPctFloored)
  const passesMinEdgeFilter = totalShortEvForGates > 3

  const hasTarget = typeof targetGainPct === 'number' && targetGainPct > 0
  const carryPctOfTarget = hasTarget ? round2(Math.abs(carryEvPctFloored) / targetGainPct * 100) : null
  const carryVeto = carryPctOfTarget != null ? carryPctOfTarget > 40 : null

  return {
    available: true,
    directional_ev_pct: round2(directionalEV),
    carry_ev_pct_true: carryEvPctTrue,
    carry_ev_pct_floored: carryEvPctFloored,
    carry_floor_applied: carryEvPctTrue !== carryEvPctFloored,
    total_short_ev_true: totalShortEvTrue,
    total_short_ev_for_gates: totalShortEvForGates,
    passes_min_edge_filter: passesMinEdgeFilter,
    carry_pct_of_target: carryPctOfTarget,
    carry_veto: carryVeto,
    sign_convention: 'POSITIVE funding = longs pay shorts = carry INCOME to a short (FR SKILL, Jul 2026) — identical convention to fr.annualizedFunding()/basisBlock()',
    ledger_note: 'the position snapshot\'s funding_usd is ACCOUNT CASHFLOW (negative = paid OUT of the account, whichever side the position is on) and INVERTS against this MARKET-RATE convention — use the ledger figure ONLY to fill the realized Carry-Cost-Ledger column (SKILL §6), never here',
  }
}

/**
 * Snapshot-to-snapshot boundary-crossing diff (market-data-extension plan,
 * D2). Pure — `tools/tripwire.mjs` does the file I/O, this does the
 * comparison. Reports ONLY scoring-relevant crossings, each via the
 * EXISTING classifier it would use in a report (fk.*, fr.*, frChannel) —
 * never a reimplementation of a band or gate.
 *
 * `prevSnapshot`/`nextSnapshot` are `tools/snapshot.mjs` records' own
 * `.snapshot` field: `{ btc: <fetchAsset() output>, eth: ..., macro: ... }`.
 * `checkpoints` (optional) = `{ btc: { line }, ... }` — a report-authored
 * stop/checkpoint price, which nothing in a bare fetch snapshot knows;
 * supplying it also checks whole-ADR-unit crossings in distance-to-line.
 *
 * Every crossing types are DISCLOSURE — "a boundary moved," not a
 * recommendation. This does not compute or move any score itself.
 *
 * FR-parity plan (FR3, 2026-08-05) added the Channel B / FR-only crossings
 * that were entirely missing: `fr_euphoria_band`, `fr_momentum_band`,
 * `frb_rally_band`, `frb_momentum_band`, `frb_weekly_rsi50_qualifier` (the
 * §4B hard qualifier zeroing the momentum leg — a distinct event from a
 * band-to-band delta, given its own type), `frb_maturity_penalty`, and
 * `fr_gate8_sustained_negative` (FR1's `funding.sustained3_below_minus5` —
 * the actual scoring-relevant funding boundary; the pre-existing
 * `funding_sign` crossing is informational only, read by FK capitulation,
 * and was never a gate). Every new check requires the field on BOTH
 * snapshots — a prev snapshot predating FR1/FR4 (or missing the block for
 * any reason) yields NO crossing, never one from an assumed default.
 */
export function tripwireDiff(prevSnapshot, nextSnapshot, { checkpoints = {} } = {}) {
  const crossings = []
  const assets = Object.keys(nextSnapshot || {}).filter(k => k !== 'macro' && prevSnapshot && prevSnapshot[k])

  for (const a of assets) {
    const p = prevSnapshot[a], n = nextSnapshot[a]
    const AS = a.toUpperCase()

    if (p.sentiment && n.sentiment && p.sentiment.avg_3d != null && n.sentiment.avg_3d != null) {
      const from = fk.sentimentBand(p.sentiment.avg_3d), to = fk.sentimentBand(n.sentiment.avg_3d)
      if (from !== to) crossings.push({ asset: AS, type: 'fk_sentiment_band', from, to, prev_value: p.sentiment.avg_3d, next_value: n.sentiment.avg_3d })

      const pStreak = p.sentiment.streaks_daily_prints, nStreak = n.sentiment.streaks_daily_prints
      if (pStreak && nStreak) {
        const pOn = pStreak.le15 >= 7, nOn = nStreak.le15 >= 7
        if (pOn !== nOn) crossings.push({ asset: AS, type: 'fk_gate1_streak_le15_ge7', from: pOn, to: nOn, prev_value: pStreak.le15, next_value: nStreak.le15 })
      }
    }

    if (p.weekly && n.weekly && p.weekly.rsi14 && n.weekly.rsi14 && p.weekly.rsi14.rsi != null && n.weekly.rsi14.rsi != null) {
      const from = fk.momentumBand(p.weekly.rsi14.rsi).band, to = fk.momentumBand(n.weekly.rsi14.rsi).band
      if (from !== to) crossings.push({ asset: AS, type: 'fk_momentum_band', from, to, prev_value: p.weekly.rsi14.rsi, next_value: n.weekly.rsi14.rsi })
    }

    if (p.weekly && n.weekly && p.weekly.sma_200w && n.weekly.sma_200w &&
        typeof p.weekly.sma_200w.within_8pct === 'boolean' && typeof n.weekly.sma_200w.within_8pct === 'boolean' &&
        p.weekly.sma_200w.within_8pct !== n.weekly.sma_200w.within_8pct) {
      crossings.push({ asset: AS, type: 'gate6_within_8pct', from: p.weekly.sma_200w.within_8pct, to: n.weekly.sma_200w.within_8pct })
    }

    if (p.trend && n.trend && !p.trend.insufficient && !n.trend.insufficient && p.high_1y && n.high_1y &&
        p.high_1y.pct_below != null && n.high_1y.pct_below != null) {
      const from = frChannel({ pctBelow1yATH: p.high_1y.pct_below, ma200Falling: p.trend.ma200_falling, priceBelowMA200: p.trend.price_below_ma200 })
      const to = frChannel({ pctBelow1yATH: n.high_1y.pct_below, ma200Falling: n.trend.ma200_falling, priceBelowMA200: n.trend.price_below_ma200 })
      if (from !== to) crossings.push({ asset: AS, type: 'fr_channel_routing', from, to })

      const capFrom = fr.phaseCycleCap(p.high_1y.pct_below), capTo = fr.phaseCycleCap(n.high_1y.pct_below)
      if (capFrom !== capTo) crossings.push({ asset: AS, type: 'fr_phase_cycle_cap', from: capFrom, to: capTo, prev_value: p.high_1y.pct_below, next_value: n.high_1y.pct_below })
    }

    if (p.funding && n.funding && p.funding.mean_annualized_pct != null && n.funding.mean_annualized_pct != null) {
      const from = Math.sign(p.funding.mean_annualized_pct), to = Math.sign(n.funding.mean_annualized_pct)
      if (from !== to) crossings.push({ asset: AS, type: 'funding_sign', from, to, prev_value: p.funding.mean_annualized_pct, next_value: n.funding.mean_annualized_pct })
    }

    // ── FR-parity plan, FR3: Channel B / FR-only crossings ─────────────────
    // funding_sign above is informational only (FK capitulation reads it) —
    // it was never the scoring-relevant funding boundary. This block adds the
    // one that is: FR1's sustained3_below_minus5, plus every §4A/§4B band edge
    // the tripwire had no coverage for at all. Every check requires BOTH
    // snapshots to carry the field — a prev snapshot predating FR1/FR4 (or any
    // transient fetch gap) yields NO crossing, never a crossing from an
    // assumed default (fail-closed, Hard Rule 6).

    if (p.sentiment && n.sentiment && p.sentiment.avg_3d != null && n.sentiment.avg_3d != null) {
      const from = fr.euphoriaBand(p.sentiment.avg_3d), to = fr.euphoriaBand(n.sentiment.avg_3d)
      if (from !== to) crossings.push({ asset: AS, type: 'fr_euphoria_band', from, to, prev_value: p.sentiment.avg_3d, next_value: n.sentiment.avg_3d })
    }

    if (p.weekly && n.weekly && p.weekly.rsi14 && n.weekly.rsi14 && p.weekly.rsi14.rsi != null && n.weekly.rsi14.rsi != null) {
      const from = fr.momentumBand(p.weekly.rsi14.rsi), to = fr.momentumBand(n.weekly.rsi14.rsi)
      if (from !== to) crossings.push({ asset: AS, type: 'fr_momentum_band', from, to, prev_value: p.weekly.rsi14.rsi, next_value: n.weekly.rsi14.rsi })

      // The weekly-RSI>=50 hard qualifier (SKILL §4B) zeroes frB.momentumBand
      // outright — a genuinely different event from a band-to-band delta, so
      // it gets its own crossing type rather than being folded into
      // frb_momentum_band below.
      const qFrom = p.weekly.rsi14.rsi >= 50, qTo = n.weekly.rsi14.rsi >= 50
      if (qFrom !== qTo) crossings.push({ asset: AS, type: 'frb_weekly_rsi50_qualifier', from: qFrom, to: qTo, prev_value: p.weekly.rsi14.rsi, next_value: n.weekly.rsi14.rsi })
    }

    if (p.trend && n.trend && !p.trend.insufficient && !n.trend.insufficient) {
      if (p.trend.bounce_pct != null && n.trend.bounce_pct != null) {
        const from = frB.rallyBand(p.trend.bounce_pct), to = frB.rallyBand(n.trend.bounce_pct)
        if (from !== to) crossings.push({ asset: AS, type: 'frb_rally_band', from, to, prev_value: p.trend.bounce_pct, next_value: n.trend.bounce_pct })
      }

      if (p.trend.rsi14 != null && n.trend.rsi14 != null && p.weekly && n.weekly && p.weekly.rsi14 && n.weekly.rsi14
          && p.weekly.rsi14.rsi != null && n.weekly.rsi14.rsi != null) {
        const from = frB.momentumBand(p.trend.rsi14, p.weekly.rsi14.rsi), to = frB.momentumBand(n.trend.rsi14, n.weekly.rsi14.rsi)
        if (from !== to) crossings.push({ asset: AS, type: 'frb_momentum_band', from, to, prev_value: p.trend.rsi14, next_value: n.trend.rsi14 })
      }

      if (p.trend.bounce_age_sessions != null && n.trend.bounce_age_sessions != null) {
        const from = frB.maturityPenalty(p.trend.bounce_age_sessions), to = frB.maturityPenalty(n.trend.bounce_age_sessions)
        if (from !== to) crossings.push({ asset: AS, type: 'frb_maturity_penalty', from, to, prev_value: p.trend.bounce_age_sessions, next_value: n.trend.bounce_age_sessions })
      }
    }

    if (p.funding && n.funding && typeof p.funding.sustained3_below_minus5 === 'boolean' && typeof n.funding.sustained3_below_minus5 === 'boolean'
        && p.funding.sustained3_below_minus5 !== n.funding.sustained3_below_minus5) {
      // The SCORING-relevant funding boundary (Channel B gate 8 — the only
      // gate in either framework that voids an unlock on its own), as
      // distinct from funding_sign above.
      crossings.push({ asset: AS, type: 'fr_gate8_sustained_negative', from: p.funding.sustained3_below_minus5, to: n.funding.sustained3_below_minus5 })
    }

    const cp = checkpoints[a]
    if (cp && cp.line != null && p.spot && n.spot && p.spot.canonical != null && n.spot.canonical != null &&
        p.daily && n.daily && p.daily.adr5 && n.daily.adr5 && p.daily.adr5.adr && n.daily.adr5.adr) {
      const distFrom = Math.abs(p.spot.canonical - cp.line) / p.daily.adr5.adr
      const distTo = Math.abs(n.spot.canonical - cp.line) / n.daily.adr5.adr
      if (Math.floor(distFrom) !== Math.floor(distTo)) {
        crossings.push({ asset: AS, type: 'checkpoint_adr_distance', from: round2(distFrom), to: round2(distTo), line: cp.line })
      }
    }
  }

  return { crossings, n_crossings: crossings.length }
}

/**
 * Log returns of a chronological closes series. Null-skips any pair spanning
 * a non-positive close (log undefined) rather than throwing — a single bad
 * print should not void an entire correlation window.
 */
export function logReturns(closes) {
  const out = []
  for (let i = 1; i < closes.length; i++) {
    const a = closes[i - 1], b = closes[i]
    if (typeof a !== 'number' || typeof b !== 'number' || a <= 0 || b <= 0) continue
    out.push(Math.log(b / a))
  }
  return out
}

/**
 * Inner-join two {date, value} series on shared dates (dates are ISO strings,
 * compared as-is). This is the correlation join: crypto trades 7 days/week,
 * equities 5 — correlating unaligned series element-wise yields a plausible,
 * meaningless number. Returns aligned {dates, xs, ys} plus counts of rows
 * dropped from each side for being unmatched.
 */
export function alignSeries(a, b) {
  const bByDate = new Map(b.map(row => [row.date, row.value]))
  const dates = [], xs = [], ys = []
  let droppedA = 0
  for (const row of a) {
    if (bByDate.has(row.date)) {
      dates.push(row.date); xs.push(row.value); ys.push(bByDate.get(row.date))
    } else {
      droppedA++
    }
  }
  const aDates = new Set(a.map(row => row.date))
  const droppedB = b.filter(row => !aDates.has(row.date)).length
  return { dates, xs, ys, dropped: { a: droppedA, b: droppedB } }
}

/** corr > 0.7 is the ONLY value this function's boolean output depends on. */
export function corrSurcharge(corr) { return corr != null && corr > 0.7 }

/**
 * FR/FK correlation-regime label + the two thresholds that actually carry
 * consequence (FK SKILL:164 + :200). The label ladder itself — inverse /
 * decoupled / mild / risk-on — is DESCRIPTIVE ONLY; only `> 0.7` (the
 * cross-asset surcharge) and `< 0.8` (the Phase 2 condition) gate anything.
 * Do not treat a label edge (0, 0.2) as a threshold — it isn't one.
 *
 * `corr: null` (not computed — e.g. pearson() returned null on zero
 * variance) routes to the SKILL's documented default: surcharge OFF,
 * Phase 2 condition satisfied, disclosed as not computed rather than guessed.
 */
export function correlationRegime(corr) {
  if (corr == null) {
    return { corr: null, label: 'not computed', surcharge_applied: false, phase2_corr_condition: true,
      note: 'correlation not computed (insufficient data or zero variance) — surcharge OFF, Phase 2 condition satisfied, by SKILL default' }
  }
  const label = corr < 0 ? 'inverse' : corr < 0.2 ? 'decoupled' : corr <= 0.7 ? 'mild' : 'risk-on'
  return { corr, label, surcharge_applied: corrSurcharge(corr), phase2_corr_condition: corr < 0.8, note: null }
}

/**
 * End-to-end correlation from two chronological {date, close} series: joins
 * on shared dates FIRST (alignSeries — the crypto-7d/equity-5d weekend-drop
 * join), THEN takes log returns of each aligned close series, THEN pearson()
 * on the two return series (2026-08 fix — every machine block already
 * claimed "Pearson on daily log returns"; the code computed raw price-level
 * Pearson instead, which is spurious between two trending series — two
 * independently-trending series can show a high level correlation with zero
 * genuine return co-movement). Order matters: aligning AFTER differencing
 * would difference across a dropped weekend and manufacture a fake 2-day
 * return sitting next to 1-day returns — so alignment happens on levels,
 * and returns are taken from the (already date-matched) aligned arrays.
 * `window` (trading days of OVERLAP, not raw input length) trims to the
 * trailing N aligned CLOSES before differencing, so it still means
 * "N sessions of overlap," not "N return observations" (which is N-1).
 */
export function correlationFromCloses(seriesA, seriesB, { window = null } = {}) {
  const aligned = alignSeries(
    seriesA.map(r => ({ date: r.date, value: r.close })),
    seriesB.map(r => ({ date: r.date, value: r.close })),
  )
  const trimmed = window != null && aligned.dates.length > window
    ? { dates: aligned.dates.slice(-window), xs: aligned.xs.slice(-window), ys: aligned.ys.slice(-window) }
    : aligned
  const retX = logReturns(trimmed.xs), retY = logReturns(trimmed.ys)
  const corr = retX.length >= 2 && retY.length >= 2 ? pearson(retX, retY) : null
  return { ...correlationRegime(corr), method: 'pearson_daily_log_returns',
    n_aligned_sessions: trimmed.dates.length, n_return_observations: retX.length, dropped: aligned.dropped,
    window_start: trimmed.dates[0] || null, window_end: trimmed.dates[trimmed.dates.length - 1] || null }
}

// ── Fallen Knives: score arithmetic ─────────────────────────────────────────

/** Per-asset .5 rounding conventions (FK SKILL §4, codified 2026-07-10). */
export const ROUNDING = {
  btc: 'half-up', gold: 'half-up', eth: 'half-down',
  // Equity indices pinned 2026-08-05 (FR non-crypto calibration). half-down
  // resolves a .5 AWAY from an unlock on FR and away from a deploy on FK — the
  // conservative direction on both sides of the book. Pinned because the two
  // SPX reports of 2026-08-04 declared DIFFERENT conventions (half-down at
  // 16:42, half-up at 22:34) on the same asset, same day, same data.
  spx: 'half-down', sp500: 'half-down', ndx: 'half-down', nasdaq: 'half-down',
}

/** Round a raw composite per convention: 'half-up' | 'half-down'. */
export function roundScore(raw, convention) {
  if (convention === 'half-up') return Math.floor(raw + 0.5)
  if (convention === 'half-down') return Math.ceil(raw - 0.5)
  throw new Error(`unknown rounding convention "${convention}" — declare half-up or half-down (FK SKILL §4)`)
}

/**
 * FK phase gate thresholds = ceil(fraction × active_denominator).
 * Fractions: 1A ⅓ · 1B 5/9 · 2 ⅔ · 3 7/9. [V] floors fixed: 2/3/3/4.
 * Regression anchor: ceil(7/9 × 8) = 7, NOT 6 (the ETH Jun-2026 misprint).
 */
export function ceilThresholds(active) {
  if (!Number.isInteger(active) || active < 1 || active > 9) throw new Error('active denominator must be an integer 1–9')
  return {
    active,
    p1a: Math.ceil(active / 3),
    p1b: Math.ceil((5 * active) / 9),
    p2: Math.ceil((2 * active) / 3),
    p3: Math.ceil((7 * active) / 9),
    v_floor: { p1a: 2, p1b: 3, p2: 3, p3: 4 },
  }
}

/** The six [V] gates on the FK board. */
export const FK_V_GATES = [1, 2, 3, 4, 7, 8]

/**
 * FR phase gate thresholds = ceil(FR_GATE_FLOORS/9 × active_denominator).
 * DISTINCT FROM ceilThresholds() ABOVE, which is the FALLEN KNIVES converter:
 * FK's legacy p3 floor is 7/9, FR's is 8/9, so the FK tool understates FR's
 * deepest floor by one at active 8 and 9. The FR linter has always used
 * FR_GATE_FLOORS directly and is unaffected; this exists so the REPORT-FACING
 * `compute.mjs thresholds --fr` prints the floors an FR report is required to
 * publish (§4). Found 2026-08-05: sp500_flying_rocket_20260804_2234 caught the
 * discrepancy by hand and said so; the next report might not have.
 */
export function frThresholds(active) {
  if (!Number.isInteger(active) || active < 1 || active > 9) throw new Error('active denominator must be an integer 1–9')
  return {
    active,
    p1a: Math.ceil(FR_GATE_FLOORS.p1a / 9 * active),
    p1b: Math.ceil(FR_GATE_FLOORS.p1b / 9 * active),
    p2: Math.ceil(FR_GATE_FLOORS.p2 / 9 * active),
    p3: Math.ceil(FR_GATE_FLOORS.p3 / 9 * active),
  }
}

/**
 * Non-crypto asset classes covered by the FR SKILL's "Adapted Non-Crypto
 * Reads" annex (2026-08-05). These assets are OUT OF DECLARED SCOPE — the map
 * exists to make the annex's restrictions enforceable, not to grant coverage.
 */
export const FR_NONCRYPTO_CLASS = {
  spx: 'equity_index', sp500: 'equity_index', ndx: 'equity_index', nasdaq: 'equity_index',
  gold: 'metals', xau: 'metals', paxg: 'metals', silver: 'metals', xag: 'metals',
}

/**
 * The FROZEN N/A gate set per non-crypto class, and the active denominator it
 * implies. Gates 4 (perp funding) and 6 (Coinbase Premium) have no instrument
 * in ANY market state; gate 9 (BTC-dominance / altseason rotation) is an
 * intra-crypto rotation construct whose only non-crypto substitute — sector or
 * safe-haven leadership — restates gate 8's breadth logic rather than adding
 * independent evidence, so it is N/A rather than a free extra gate.
 *
 * Pinned because the four non-crypto reports produced FOUR denominators (gold
 * 7, gold "~6", SPX 7, SPX 6) — a per-report knob on a protective floor.
 */
export const FR_NONCRYPTO_NA = { equity_index: [4, 6, 9], metals: [4, 6, 9] }

/** Resolve an asset string to its non-crypto class, or null if crypto/unknown. */
export function frNonCryptoClass(asset) {
  return FR_NONCRYPTO_CLASS[String(asset || '').toLowerCase()] || null
}

/**
 * FK phase SCORE unlock lines (SKILL §6). Cut 2026-07-27 under owner agility
 * mandate #2: 1A 10→8, 1B 13→11; the Phase 2/3 lines are unchanged.
 *
 * These lines are read against the ADJUSTED score (legs + D1 term) for phases
 * 1A/1B/2 — all three are reachable by the analyst channels. Phase 3 alone is
 * read against the MECHANICAL score: no analyst channel reaches it (D2 is
 * barred there and D1 may never be its sole enabler), though the mechanical
 * Deep-Value-Override branch still can.
 */
export const FK_SCORE_UNLOCK = { p1a: 8, p1b: 11, p2: 15, p3: 17 }

// ── Fallen Knives: Analyst Discretion Layer (SKILL D1–D6, 2026-07-27) ───────

/** Max absolute value and step of the D1 discretionary score term. */
export const FK_DISCRETION = { max: 2, step: 0.5 }

/**
 * Validate a D1 discretionary term: a number in [−2, +2] on a 0.5 step.
 * `null`/`undefined` is INVALID by design — the SKILL requires the field to be
 * printed every report, writing 0 when no adjustment was taken, so that a
 * silent omission can never pass as a deliberate zero.
 */
export function discretionValid(v) {
  if (typeof v !== 'number' || !Number.isFinite(v)) return { ok: false, reason: 'missing or non-numeric (write 0 when no adjustment was taken)' }
  if (Math.abs(v) > FK_DISCRETION.max) return { ok: false, reason: `|${v}| exceeds the ±${FK_DISCRETION.max} bound (D1)` }
  if (Math.abs(v / FK_DISCRETION.step - Math.round(v / FK_DISCRETION.step)) > 1e-9)
    return { ok: false, reason: `${v} is not on the ${FK_DISCRETION.step} step (D1)` }
  return { ok: true, reason: null }
}

/**
 * Which FK phases a given score unlocks on the score axis alone. Gate count /
 * [V] floor / conviction path are checked separately.
 *
 * Pass `mechanical` to evaluate Phase 3 against the leg sum (SKILL §6, pinned
 * 2026-07-27); omit it and every phase is read against `adjusted`.
 */
export function fkPhasesUnlockedByScore(adjusted, mechanical = adjusted) {
  return Object.entries(FK_SCORE_UNLOCK)
    .filter(([p, line]) => (p === 'p3' ? mechanical : adjusted) >= line)
    .map(([p]) => p)
}

/** The mechanical score: the leg sum with no D1 term, rounded per convention. */
export function mechanicalScore(legSum, convention) { return roundScore(legSum, convention) }

/**
 * D5 discretion tax: a discretionary tranche's hard price-only stop may sit no
 * more than 15% below its fill. Returns the deepest permissible line and the
 * boolean. Gate-earned tranches are not subject to this (they carry the
 * compound stop) — callers must only apply it to discretionary fills.
 */
export const FK_D5_MAX_STOP_DISTANCE_PCT = 15

export function d5StopCheck(fill, stop) {
  if (typeof fill !== 'number' || typeof stop !== 'number') return { pass: false, reason: 'fill and stop must both be numbers' }
  const floor = round2(fill * (1 - FK_D5_MAX_STOP_DISTANCE_PCT / 100))
  const distancePct = round2((1 - stop / fill) * 100)
  if (stop >= fill) return { pass: false, floor, distance_pct: distancePct, reason: 'stop is at or above the fill' }
  return { pass: stop >= floor, floor, distance_pct: distancePct, reason: stop >= floor ? null : `stop sits ${distancePct}% below fill — deeper than the ${FK_D5_MAX_STOP_DISTANCE_PCT}% D5 limit` }
}

/**
 * D6 ratchet: a stop parameter may only move TOWARD price. For a long book
 * every tier (catastrophic floor, compound price line, compound score line,
 * D5 line) is monotonically non-decreasing; checkpoint dates only move earlier.
 * The single permitted downward move is a catastrophic re-anchor onto a zone
 * NAMED in a prior report — pass `{ priorNamedZone: true }` to allow it.
 */
export function ratchetCheck(oldValue, newValue, { priorNamedZone = false, tier = 'stop' } = {}) {
  if (typeof oldValue !== 'number' || typeof newValue !== 'number') return { pass: false, reason: 'both values must be numbers' }
  if (newValue >= oldValue) return { pass: true, direction: newValue === oldValue ? 'unchanged' : 'toward price', reason: null }
  if (priorNamedZone && tier === 'catastrophic')
    return { pass: true, direction: 'away from price (permitted exception)', reason: 'catastrophic re-anchor onto a prior-report-named deeper zone — must be atomic and cited' }
  return { pass: false, direction: 'away from price', reason: `D6 ratchet: ${tier} ${oldValue} → ${newValue} widens the stop — prohibited, not merely disclosable` }
}

// ── Fallen Knives: rubric band classifiers ──────────────────────────────────
// Convention (FK SKILL §4, adjudicated 2026-07-10): chained ≤/≥, first match
// wins; an EXACT EDGE belongs to the band whose inequality includes it
// (higher-score band). Deviation permitted only toward the LOWER score, flagged.

export const fk = {
  /** 3-day avg F&G: ≤10→5 · ≤15→4 · ≤25→3 · ≤35→2 · ≤50→1 · >50→0 */
  sentimentBand(v) { return v <= 10 ? 5 : v <= 15 ? 4 : v <= 25 ? 3 : v <= 35 ? 2 : v <= 50 ? 1 : 0 },

  /**
   * Weekly RSI: <30→4 · ≤35→3 · ≤40→2 · ≤45→1 · >45→0.
   * lowConfidence (15–29 closes): a value within 2 RSI points of a band edge
   * takes the LOWER-score band (FK momentum input rule).
   */
  momentumBand(rsi, { lowConfidence = false } = {}) {
    const band = r => (r < 30 ? 4 : r <= 35 ? 3 : r <= 40 ? 2 : r <= 45 ? 1 : 0)
    let result = band(rsi), edgeApplied = false
    if (lowConfidence) {
      for (const edge of [30, 35, 40, 45]) {
        if (Math.abs(rsi - edge) <= 2) {
          const lower = band(edge + 1e-9)
          if (lower < result) { result = lower; edgeApplied = true }
        }
      }
    }
    return { band: result, low_confidence_edge_rule_applied: edgeApplied }
  },

  /** MVRV-Z: <0.1→5 · ≤0.5→4 · ≤2→3 · ≤3→2 · ≤5→0 · >5→−2 (trim signal) */
  mvrvZBand(z) { return z < 0.1 ? 5 : z <= 0.5 ? 4 : z <= 2 ? 3 : z <= 3 ? 2 : z <= 5 ? 0 : -2 },

  /** Alt fallback, drawdown-from-ATH % (positive number): ≥70→5 · ≥60→4 · ≥50→3 · ≥40→2 · ≥30→1 · <30→0 */
  drawdownBand(dd) { return dd >= 70 ? 5 : dd >= 60 ? 4 : dd >= 50 ? 3 : dd >= 40 ? 2 : dd >= 30 ? 1 : 0 },

  /**
   * Gold/low-vol adaptation band-set (FK SKILL §4): ≥45→3 gated behind a
   * CONFIRMED COT flush (absent a flush, cap at 2) · ≥36→2 · ≥28→2 · ≥20→2 · ≥12→1 · <12→0.
   * Eligibility preconditions (documented vol ratio ≤½ BTC) are NOT checked here.
   */
  goldLowVolBand(dd, { cotFlushConfirmed = false } = {}) {
    if (dd >= 45) return cotFlushConfirmed ? 3 : 2
    return dd >= 36 ? 2 : dd >= 28 ? 2 : dd >= 20 ? 2 : dd >= 12 ? 1 : 0
  },
}

// ── Flying Rocket: rubric band classifiers ──────────────────────────────────
// Convention (Hard Rule 6 — asymmetry tax): where the FR SKILL's dash-range
// bands leave an exact edge ambiguous, code resolves it to the LOWER-score
// band (the harder-to-short reading). This is a tightening-or-neutral mirror
// of the FK edge convention, never a loosening.

export const fr = {
  /** 3-day avg F&G greed side: ≥90→5 · 80–89→4 · 70–79→3 · 60–69→2 · 50–59→1 · <50→0 */
  euphoriaBand(v) { return v >= 90 ? 5 : v >= 80 ? 4 : v >= 70 ? 3 : v >= 60 ? 2 : v >= 50 ? 1 : 0 },

  /** Weekly RSI: >75→4 · 70–75→3 · 65–70→2 · 60–65→1 · <60→0. Exact 75→3, 70→2, 65→1, 60→0 (conservative). */
  momentumBand(rsi) { return rsi > 75 ? 4 : rsi > 70 ? 3 : rsi > 65 ? 2 : rsi > 60 ? 1 : 0 },

  /** MVRV-Z: >5→5 · 3–5→4 · 2–3→3 · 1–2→1 · <1→0. Exact 5→4, 3→3, 2→1, 1→0 (conservative). */
  mvrvZBand(z) { return z > 5 ? 5 : z > 3 ? 4 : z > 2 ? 3 : z > 1 ? 1 : 0 },

  /** Alt fallback, % below ATH: <5→5 · 5–15→3 · 15–30→1 · >30→0. Exact 5→3, 15→1, 30→0 (conservative). */
  athDistanceBand(pctBelow) { return pctBelow < 5 ? 5 : pctBelow < 15 ? 3 : pctBelow < 30 ? 1 : 0 },

  /**
   * §4A distribution leg, count of 3 conditions (a/b/c) → same number, clamped
   * 0–3. Numerically identical to frB.structureBand today but kept as a
   * SEPARATE function, not an alias: this mirrors a different SKILL section
   * (§4A distribution vs §4B bear-structure), and an alias would let a future
   * calibration move one band and silently move the other.
   */
  distributionBand(n) { return Math.max(0, Math.min(3, n | 0)) },

  /**
   * §4A vulnerability leg, count of 3 conditions (b/c + MVRV-Z) → same number,
   * clamped 0–3. Same non-aliasing rationale as distributionBand above.
   */
  vulnerabilityBand(n) { return Math.max(0, Math.min(3, n | 0)) },

  /**
   * Phase-of-cycle hard cap from % below 1-year ATH: >20%→cap 8 · 10–20%→cap 14 ·
   * <10%→no cap. Exact 20→14 (SKILL letter); exact 10→14 (ambiguous → conservative
   * = cap applies: a cap lowers the score, the harder-to-short direction).
   */
  phaseCycleCap(pctBelow1yATH) { return pctBelow1yATH > 20 ? 8 : pctBelow1yATH >= 10 ? 14 : null },

  /** Perp funding: per-8h % → annualized % (×3×365). POSITIVE = carry INCOME to a short (Jul 2026 sign convention). */
  annualizedFunding(per8hPct) { return round2(per8hPct * 3 * 365) },

  /** Squeeze-trap penalty tier from annualized funding % and OI proximity (FR SKILL §4). */
  squeezeTrapPenalty({ fundingAnnualizedPct, sustained3Intervals = false, oiWithin5PctOf90dHigh = false, singleIntervalBelowMinus7 = false }) {
    const base = fundingAnnualizedPct < -5 && sustained3Intervals
    const escalatedImmediate = singleIntervalBelowMinus7 && oiWithin5PctOf90dHigh
    if ((base && oiWithin5PctOf90dHigh) || escalatedImmediate) return { raw_penalty: -2, gate_surcharge: 2, tier: 'escalated' }
    if (base) return { raw_penalty: -2, gate_surcharge: 1, tier: 'base' }
    return { raw_penalty: 0, gate_surcharge: 0, tier: 'none' }
  },
}

// ── Flying Rocket: two-channel architecture (SKILL §2.5/§4B, 2026-07-27) ─────

/**
 * Channel router. Replaces the old ">20% off ATH ⇒ dead" reading of the
 * phase-of-cycle cap: that regime is now Channel B (bear continuation) when the
 * downtrend is confirmed, and stand-down when it is not.
 *
 *   A    — within 20% of the 1-year ATH; score §4A, cap tiers as before
 *   B    — >20% off AND below a falling 200dma; score §4B
 *   none — >20% off with a flat/rising 200dma, or price above it: no channel
 *
 * `ma200Falling` and `priceBelowMA200` must BOTH be true for B. Missing/non-
 * boolean values resolve to `none` (fail closed — the harder-to-short reading,
 * Hard Rule 6).
 */
export function frChannel({ pctBelow1yATH, ma200Falling, priceBelowMA200 } = {}) {
  if (typeof pctBelow1yATH !== 'number' || !Number.isFinite(pctBelow1yATH)) return 'none'
  if (pctBelow1yATH <= 20) return 'A'
  return (ma200Falling === true && priceBelowMA200 === true) ? 'B' : 'none'
}

/**
 * Channel B rubric bands (SKILL §4B). Same edge convention as every other FR
 * band: an exact edge resolves to the LOWER-score band via `>` chains.
 */
export const frB = {
  /** Rally off the trailing 40-session low, %: >35→5 · >25→4 · >18→3 · >12→2 · >8→1 · else 0 */
  rallyBand(pct) { return pct > 35 ? 5 : pct > 25 ? 4 : pct > 18 ? 3 : pct > 12 ? 2 : pct > 8 ? 1 : 0 },

  /**
   * Daily Wilder RSI-14: >65→4 · >58→3 · >52→2 · >45→1 · else 0.
   * Hard qualifier: weekly RSI ≥50 forces this leg to 0 — a bounce that has
   * repaired the higher timeframe is not an exhaustion (SKILL §4B).
   */
  momentumBand(dailyRSI, weeklyRSI) {
    if (typeof weeklyRSI === 'number' && weeklyRSI >= 50) return 0
    return dailyRSI > 65 ? 4 : dailyRSI > 58 ? 3 : dailyRSI > 52 ? 2 : dailyRSI > 45 ? 1 : 0
  },

  /** Resistance confluence, count of 4: 4→5 · 3→4 · 2→3 · 1→1 · 0→0 */
  resistanceBand(n) { return n >= 4 ? 5 : n === 3 ? 4 : n === 2 ? 3 : n === 1 ? 1 : 0 },

  /** Bear-structure integrity, count of 3 → same number. 0 VOIDS the channel. */
  structureBand(n) { return Math.max(0, Math.min(3, n | 0)) },

  /** Relative sentiment / positioning, count of 3 → same number. */
  sentimentBand(n) { return Math.max(0, Math.min(3, n | 0)) },

  /** Bounce-maturity floor: a rally younger than 8 sessions costs 2 raw points. */
  maturityPenalty(bounceSessions) { return bounceSessions < 8 ? -2 : 0 },
}

// ── Flying Rocket: daily trend derivation (feeds frChannel / frB inputs) ─────
// `sessions`: chronological (oldest→newest) [{date, high, low, close}]. This is
// the block that used to be hand-computed off-tool for every FK/FR report —
// commit 2 of the 2026-08 toolchain-extension plan.

/**
 * Derives every daily-timeframe input frChannel() and frB.* need from a raw
 * OHLC session array: RSI-14, 50/200dma, 200dma slope, the trailing 40-session
 * low, bounce %, and bounce age. Three definitional calls are ENCODED here,
 * not left to the caller, because each one moves a live score or gate:
 *
 * 1. `ma200_falling` is STRICT `< 0`. A flat slope is NOT falling — it routes
 *    frChannel() to 'none' (fail-closed, Hard Rule 6). This is what produced
 *    gold's 'none' at a +0.52% slope on 2026-08-01.
 * 2. The 50/200 "gap narrowed" comparison uses `|gap|`. With a negative gap
 *    (50 below 200, the normal bearish-cross state) a naive `<` on the signed
 *    gap inverts the meaning. `ma50_below_ma200` and `gap_narrowed_20` are
 *    reported SEPARATELY; `structure_b` is derived from both, never assumed.
 * 3. `bounce_age_sessions` counts sessions SINCE the 40-session low (to now),
 *    NOT low-to-high. Pinned by the ETH 2026-08-01 report: low on Jun-26,
 *    age 37. This is the highest-risk call in the block — it drives
 *    frB.maturityPenalty, a −2 SCORE term — so `sessions_low_to_high` (the
 *    alternative reading) is emitted alongside rather than discarded.
 */
export function dailyTrend(sessions, { spot = null, fast = 50, slow = 200, slopeN = 20, lowN = 40 } = {}) {
  const need = slow + slopeN
  if (!Array.isArray(sessions) || sessions.length < need) {
    return { insufficient: `need ≥${need} daily sessions for a ${slow}dma + ${slopeN}-session slope, got ${sessions ? sessions.length : 0}` }
  }
  const closes = sessions.map(s => s.close)
  const price = spot != null ? spot : closes[closes.length - 1]

  const rsi14 = wilderRSI(closes, 14)
  const ma50 = sma(closes, fast)
  const ma200 = sma(closes, slow)
  const ma200SlopePct = smaSlope(closes, { n: slow, lookback: slopeN })
  const ma200Falling = ma200SlopePct == null ? null : ma200SlopePct < 0
  const priceBelowMa200 = ma200 == null ? null : price < ma200
  const ma50BelowMa200 = (ma50 == null || ma200 == null) ? null : ma50 < ma200

  const closesPast = closes.slice(0, closes.length - slopeN)
  const ma50Past = sma(closesPast, fast)
  const ma200Past = sma(closesPast, slow)
  const gapNowPct = (ma50 == null || ma200 == null || ma200 === 0) ? null : Math.abs(ma50 - ma200) / ma200 * 100
  const gapPastPct = (ma50Past == null || ma200Past == null || ma200Past === 0) ? null : Math.abs(ma50Past - ma200Past) / ma200Past * 100
  const gapNarrowed20 = (gapNowPct == null || gapPastPct == null) ? null : gapNowPct < gapPastPct
  const structureB = ma50BelowMa200 === true && gapNarrowed20 === true

  const withinPct = (a, b, pct) => (a == null || b == null || b === 0) ? null : Math.abs(a / b - 1) * 100 <= pct
  const within3pctOfMa200 = withinPct(price, ma200, 3)
  const within3pctOfMa50FromBelow = (ma50 == null) ? null : (price <= ma50 && withinPct(price, ma50, 3))

  const lowWindow = sessions.slice(-lowN)
  const lows = lowWindow.map(s => s.low)
  const low40 = Math.min(...lows)
  const lowIdx = lows.indexOf(low40) // first occurrence — earliest session that set the low
  const bouncePct = low40 === 0 ? null : round2((price / low40 - 1) * 100)
  const bounceAgeSessions = (lows.length - 1) - lowIdx
  const highsAfterLow = lowWindow.slice(lowIdx).map(s => s.high)
  const highAfterLow = Math.max(...highsAfterLow)
  const sessionsLowToHigh = highsAfterLow.indexOf(highAfterLow)

  return {
    insufficient: null,
    rsi14: rsi14.rsi, rsi14_confidence: rsi14.confidence,
    ma50: round2(ma50), ma200: round2(ma200),
    ma200_slope20_pct: ma200SlopePct, ma200_falling: ma200Falling,
    price_below_ma200: priceBelowMa200, ma50_below_ma200: ma50BelowMa200,
    gap_now_pct: gapNowPct == null ? null : round2(gapNowPct), gap_narrowed_20: gapNarrowed20,
    structure_b: structureB,
    within_3pct_of_ma200: within3pctOfMa200, within_3pct_of_ma50_from_below: within3pctOfMa50FromBelow,
    low_40s: round2(low40), bounce_pct: bouncePct,
    bounce_age_sessions: bounceAgeSessions, sessions_low_to_high: sessionsLowToHigh,
  }
}

/**
 * FR §4B single-session stall confirmation: the bounce fails to extend its
 * high AND the session closes at or below the prior close. A session that
 * prints even a marginal new high does NOT confirm — the bounce is still
 * live, so `confirmed` stays false rather than being inferred from the close
 * alone. null (not false) on missing inputs — an unconfirmed stall must not
 * be reported as an explicitly-checked "no".
 */
export function frStallConfirmation({ close, priorClose, high, bounceHigh } = {}) {
  if ([close, priorClose, high, bounceHigh].some(v => typeof v !== 'number')) return null
  const failedNewHigh = high < bounceHigh
  const closedDown = close <= priorClose
  return { confirmed: failedNewHigh && closedDown, failed_new_high: failedNewHigh, closed_down: closedDown }
}

// ── Flying Rocket: score lines, gates, discretion layer (SKILL S1–S7) ────────

/**
 * Phase unlock lines on the score axis (SKILL §6, cut 2026-07-27 from
 * 13/15/17/19). Phase 3 is Channel-A-only, mechanical-only, and unchanged.
 */
export const FR_SCORE_UNLOCK = { p1a: 11, p1b: 13, p2: 15, p3: 19 }

/**
 * Channel B ladder (added 2026-07-27 after the risk audit's ladder-calibration
 * finding). §4B's rubric scores 2–4 points HIGHER than §4A on the same quality
 * of setup, so reusing Channel A's ladder put Phase 2 — Channel B's maximum —
 * at the MODAL Channel B signal, while in Channel A it is the rare one. The B
 * ladder is shifted +2 to restore the intended rarity. Phase 3 is absent by
 * construction: Channel B cannot reach it at any score.
 */
export const FR_SCORE_UNLOCK_B = { p1a: 13, p1b: 15, p2: 17 }

/** The ladder for a channel. Channel B has no p3 entry — the phase is unreachable. */
export function frUnlockLadder(channel) { return channel === 'B' ? FR_SCORE_UNLOCK_B : FR_SCORE_UNLOCK }

/**
 * Score composite arithmetic — extracted verbatim from lint-report.mjs's
 * inline score check (commit 5 swaps the linter to call this instead of
 * reimplementing the math). Shared by FK and FR: FK calls with `penalty:0`,
 * which collapses every FR-only term to a no-op.
 *
 * `mechanical` excludes `discretionary` by construction — it is what every
 * protective rule reads (D1/S1: "discretion buys entries, never exits").
 * `raw`/`adjusted` include it. BOTH are clamped 0–20 BEFORE any cap is
 * applied — a cap lowers an already-valid score, it does not participate in
 * producing one. `cap.applied` (not merely `cap` being present) gates the cap,
 * matching the linter's `S.cap && S.cap.applied` check exactly.
 */
export function frComposite({ legs, penalty = 0, discretionary = 0, rounding, channel = 'A', cap = null } = {}) {
  const legSum = Object.values(legs || {}).reduce((a, v) => a + (v || 0), 0)
  const raw = round2(legSum + penalty + discretionary)
  const mechanicalUnrounded = roundScore(legSum + penalty, rounding)
  const adjustedUnrounded = roundScore(raw, rounding)
  const mechanicalClamped = Math.max(0, Math.min(20, mechanicalUnrounded))
  const adjustedClamped = Math.max(0, Math.min(20, adjustedUnrounded))
  const capApplied = !!(cap && cap.applied)
  const capValue = cap ? cap.value : null
  const mechanical = capApplied ? Math.min(mechanicalClamped, capValue) : mechanicalClamped
  const adjusted = capApplied ? Math.min(adjustedClamped, capValue) : adjustedClamped
  const clamped = mechanicalClamped !== mechanicalUnrounded || adjustedClamped !== adjustedUnrounded
  return { leg_sum: legSum, penalty, mechanical, raw, adjusted, cap_applied: capApplied, cap_value: capValue, clamped, channel }
}

// Which `counts` key feeds which leg, per channel — the sub-conditions with
// no classifier (on-chain / options / flows / swing-point judgment).
const FR_COUNT_TO_LEG = {
  A: { mvrv_z: 'valuation', distribution_count: 'distribution', vulnerability_count: 'vulnerability' },
  B: { resistance_count: 'valuation', structure_count: 'distribution', sentiment_count: 'vulnerability' },
}
// The INPUT value (not the output score) that saturates each count's band —
// mvrvZBand/resistanceBand have wider input domains than their 0-3 "count"
// siblings, so "ceiling" must feed the band fn a saturating input, not its
// own max score (mvrvZBand(5) is 4, not 5 — the band is strict `>5`).
const FR_COUNT_CEILING_INPUT = {
  mvrv_z: 6, distribution_count: 3, vulnerability_count: 3,
  resistance_count: 4, structure_count: 3, sentiment_count: 3,
}

/**
 * End-to-end FR companion score from one fetch run's market data plus the
 * analyst `counts` that have no classifier and none is possible: §4A
 * distribution (a)(b)(c), §4A vulnerability (b)(c), §4B resistance (c)(d),
 * §4B structure (a), §4B sentiment (c), and MVRV-Z. This is the mandatory
 * companion (Hard Rule 5) that, before this function, had to be eyeballed —
 * nothing in the repo produced `ma200Falling`/`priceBelowMA200` for
 * frChannel() to route on.
 *
 * Routes via frChannel(). Channel B scores the §4B leg formulas; Channel A
 * AND stand-down ('none') both use §4A — 'none' still needs a headline
 * number because Hard Rule 5's cross-validation and its own pause-condition
 * both read it, even though nothing unlocks off a stand-down score.
 *
 * A missing count scores 0 (conservative, Hard Rule 6) but is recorded in
 * `inputs_missing`; `score_floor`/`score_ceiling` bound what the score could
 * be if every missing count took its most conservative vs its maximum value.
 * If that range straddles 9 or 12, `hard_rule_5_dischargeable` is false — the
 * FK SKILL's "companion cannot be computed → pause net-new long deployment
 * only" branch applies.
 *
 * `oi_within_5pct_of_90d_high` unknown is reported as `null` in the output —
 * never `false` — but INTERNALLY the squeeze-trap penalty treats an unknown
 * as if it WERE within 5%: the alternative (treating unknown as false)
 * suppresses the escalation, which is fail-OPEN on a protective penalty
 * (Hard Rule 6 wants the harder-to-short reading under uncertainty).
 */
export function frCompanion({ market = {}, counts = {}, rounding = 'half-up' } = {}) {
  const channel = frChannel({
    pctBelow1yATH: market.pct_below_1y_ath,
    ma200Falling: market.ma200_falling,
    priceBelowMA200: market.price_below_ma200,
  })
  const useB = channel === 'B'
  const countMap = FR_COUNT_TO_LEG[useB ? 'B' : 'A']
  const missing = Object.keys(countMap).filter(k => counts[k] == null)

  function buildLegs(atCeiling) {
    const at = k => (counts[k] != null ? counts[k] : (atCeiling && missing.includes(k) ? FR_COUNT_CEILING_INPUT[k] : 0))
    if (useB) {
      return {
        euphoria: frB.rallyBand(market.bounce_pct != null ? market.bounce_pct : 0),
        momentum: frB.momentumBand(market.daily_rsi != null ? market.daily_rsi : 0, market.weekly_rsi),
        valuation: frB.resistanceBand(at('resistance_count')),
        distribution: frB.structureBand(at('structure_count')),
        vulnerability: frB.sentimentBand(at('sentiment_count')),
      }
    }
    return {
      euphoria: fr.euphoriaBand(market.fng_avg_3d != null ? market.fng_avg_3d : 0),
      momentum: fr.momentumBand(market.weekly_rsi != null ? market.weekly_rsi : 0),
      valuation: fr.mvrvZBand(at('mvrv_z')),
      distribution: fr.distributionBand(at('distribution_count')),
      vulnerability: fr.vulnerabilityBand(at('vulnerability_count')),
    }
  }

  const oiUnknown = market.oi_within_5pct_of_90d_high == null
  const squeeze = fr.squeezeTrapPenalty({
    fundingAnnualizedPct: market.funding_annualized_pct != null ? market.funding_annualized_pct : 0,
    sustained3Intervals: !!market.sustained_3_intervals,
    oiWithin5PctOf90dHigh: oiUnknown ? true : market.oi_within_5pct_of_90d_high === true,
    singleIntervalBelowMinus7: !!market.single_interval_below_minus_7,
  })
  const maturity = useB ? frB.maturityPenalty(market.bounce_age_sessions != null ? market.bounce_age_sessions : 999) : 0
  const penalty = Math.max(-4, squeeze.raw_penalty + maturity)

  const capValue = market.pct_below_1y_ath != null ? fr.phaseCycleCap(market.pct_below_1y_ath) : null
  const cap = { applied: channel === 'A' && capValue != null, value: capValue }

  const legs = buildLegs(false)
  const composite = frComposite({ legs, penalty, discretionary: 0, rounding, channel, cap })

  let scoreFloor = null, scoreCeiling = null, dischargeable = true
  if (missing.length) {
    scoreFloor = frComposite({ legs, penalty, discretionary: 0, rounding, channel, cap }).adjusted
    scoreCeiling = frComposite({ legs: buildLegs(true), penalty, discretionary: 0, rounding, channel, cap }).adjusted
    const straddles = n => scoreFloor < n && scoreCeiling >= n
    dischargeable = !(straddles(9) || straddles(12))
  }

  return {
    channel,
    score: { legs, penalty, mechanical: composite.mechanical, adjusted: composite.adjusted, rounding },
    cap,
    squeeze: { tier: squeeze.tier, gate_surcharge: squeeze.gate_surcharge },
    inputs_missing: missing,
    confidence: missing.length ? 'partial' : 'full',
    score_floor: scoreFloor, score_ceiling: scoreCeiling,
    hard_rule_5_dischargeable: dischargeable,
    oi_within_5pct_of_90d_high: oiUnknown ? null : market.oi_within_5pct_of_90d_high,
    standalone_report_owed: composite.adjusted >= 9,
  }
}

/** Legacy /9 gate floors; 1A moved 4 → 3 on 2026-07-27. Convert with ceilThresholds(). */
export const FR_GATE_FLOORS = { p1a: 3, p1b: 5, p2: 6, p3: 8 }

/**
 * Mechanical stop bounds per phase, as % ABOVE the fill (SKILL §6).
 * Channel B is tighter everywhere and has no Phase 3.
 */
export const FR_MECH_STOP_PCT = {
  A: { '1a': 8, '1b': 10, '2': 12, '3': 15 },
  B: { '1a': 6, '1b': 6, '2': 8 },
}

/**
 * Minimum stop distance (added 2026-07-27 after the risk audit).
 * A stop parked at the bounce high +1% typically lands 2.5–4% above the fill;
 * against ETH's ~2–3% daily sigma that is a ~80% touch probability from noise
 * alone, before any edge. A stop must therefore sit at least 1.5 × ADR(5)
 * above the fill. If 1.5 × ADR(5) exceeds the phase ceiling, the tape is too
 * volatile for the phase and there is NO TRADE — the rule tightens entry
 * rather than widening risk.
 */
export const FR_MIN_STOP_ADR_MULT = 1.5

export function frStopBand(fill, { adr5 = null, channel = 'A', phase = '1a' } = {}) {
  const ceilPct = (FR_MECH_STOP_PCT[channel] || FR_MECH_STOP_PCT.A)[phase]
  if (ceilPct == null) return { ok: false, reason: `phase ${phase} is unreachable in Channel ${channel}` }
  const ceiling = round2(fill * (1 + ceilPct / 100))
  if (adr5 == null) return { ok: true, ceiling, ceiling_pct: ceilPct, floor: null, floor_pct: null, reason: 'ADR(5) not supplied — minimum-distance rule not checkable' }
  const floorPct = round2((FR_MIN_STOP_ADR_MULT * adr5 / fill) * 100)
  if (floorPct > ceilPct) return { ok: false, ceiling, ceiling_pct: ceilPct, floor_pct: floorPct, reason: `1.5×ADR(5) = ${floorPct}% exceeds the ${ceilPct}% phase ceiling — tape too volatile for this phase, no trade` }
  return { ok: true, ceiling, ceiling_pct: ceilPct, floor: round2(fill * (1 + floorPct / 100)), floor_pct: floorPct, reason: null }
}

/** Per-asset concentration cap across BOTH channels, % of the dedicated short book. */
export const FR_MAX_PER_ASSET_PCT = 30

/** Max absolute value and step of the S1 discretionary term (mirrors FK D1). */
export const FR_DISCRETION = { max: 2, step: 0.5 }

/**
 * Which FR phases a score unlocks on the score axis alone. Phase 3 reads the
 * MECHANICAL score (S1: "S1 buys entries, never exits"); every other phase
 * reads the adjusted one. Gate count, channel, and the §7 preflight are
 * checked separately.
 */
export function frPhasesUnlockedByScore(adjusted, mechanical = adjusted) {
  return Object.entries(FR_SCORE_UNLOCK)
    .filter(([p, line]) => (p === 'p3' ? mechanical : adjusted) >= line)
    .map(([p]) => p)
}

/** S5 discretion tax and the Channel B structural limits. */
export const FR_S5 = { maxStopPct: 6, maxTimeStopDays: 14, maxBookPct: 20 }
export const FR_CHANNEL_B = { maxBookPct: 30, maxTimeStopDays: { p1a: 21, p1b: 21, p2: 28 } }

/**
 * S5 stop tax: an analyst-channel (S1/S2) tranche's hard stop may sit no more
 * than 6% ABOVE its fill. Note the direction — a short's stop is above entry,
 * the mirror of FK's d5StopCheck.
 */
export function s5StopCheck(fill, stop) {
  if (typeof fill !== 'number' || typeof stop !== 'number') return { pass: false, reason: 'fill and stop must both be numbers' }
  const ceiling = round2(fill * (1 + FR_S5.maxStopPct / 100))
  const distancePct = round2((stop / fill - 1) * 100)
  if (stop <= fill) return { pass: false, ceiling, distance_pct: distancePct, reason: 'stop is at or below the fill — a short stop sits ABOVE entry' }
  return { pass: stop <= ceiling, ceiling, distance_pct: distancePct, reason: stop <= ceiling ? null : `stop sits ${distancePct}% above fill — wider than the ${FR_S5.maxStopPct}% S5 limit` }
}

/**
 * S6 ratchet, short side: a stop moves TOWARD price only — for a short that is
 * DOWN, never up. Time stops ratchet identically (days may shrink, never grow).
 *
 * Unlike FK's D6 there is NO exception: the only case S6 exempts is the first
 * stop set from a genuinely flat book, where no prior value exists and this
 * check is simply not called. Trimming a position does not make the book flat.
 */
export function frRatchetCheck(oldValue, newValue, { tier = 'stop' } = {}) {
  if (typeof oldValue !== 'number' || typeof newValue !== 'number') return { pass: false, reason: 'both values must be numbers' }
  if (newValue <= oldValue) return { pass: true, direction: newValue === oldValue ? 'unchanged' : 'toward price', reason: null }
  return { pass: false, direction: 'away from price', reason: `S6 ratchet: ${tier} ${oldValue} → ${newValue} widens the stop — prohibited, not merely disclosable` }
}

// ── Flying Rocket: funding block ─────────────────────────────────────────────

/**
 * Derives the funding inputs FR §4B and fr.squeezeTrapPenalty() need from raw
 * Binance-shaped funding intervals: `[{fundingRate, fundingTime}]`,
 * chronological (oldest → newest). `n` caps how many trailing intervals to use
 * (Binance funds every 8h — 45 intervals ≈ 15 days).
 *
 * Two unit traps, both encoded here rather than left to a caller:
 * 1. Binance's `fundingRate` is a FRACTION ("0.0001" = 0.01%), but
 *    fr.annualizedFunding() takes a per-8h PERCENT — a caller passing the raw
 *    fraction through would be 100× off. `per8hPct = Number(fundingRate)*100`.
 * 2. FK's capitulation-(b) counts funding INTERVALS (8h prints); FR-B's
 *    relative-sentiment-(b) counts SESSIONS (calendar days, ≤3 intervals
 *    each). Conflating them is a ≤3× scoring error, so both are emitted
 *    under unit-explicit keys — never a single ambiguous "streak".
 *
 * `oi_90d_high_available` is always `false` here: Binance's OI history
 * endpoint serves ~30 days, but fr.squeezeTrapPenalty() wants a 90-day high.
 * `oi_within_5pct_of_90d_high` stays `null` — a 30-day number must never
 * pass itself off as a 90-day one.
 *
 * THE THIRD UNIT TRAP (added 2026-08-05, FR-parity plan): the SKILL's
 * squeeze-trap penalty fires on funding annualized **< −5%** sustained ≥3
 * consecutive intervals, and fr.squeezeTrapPenalty() takes that as a
 * caller-supplied `sustained3Intervals` boolean. Until now the only run this
 * block emitted was `longest_negative_run_intervals`, which counts MERELY
 * NEGATIVE prints — and −5% annualized is −0.004566% per 8h, so that field is
 * a ~1000× looser bar than the one the penalty needs. Reading it as the
 * penalty's input produces a false −2 and, in Channel B, a false gate-8 veto
 * (the only gate in either framework that voids an unlock on its own). The
 * correctly-thresholded runs are now emitted alongside it.
 *
 * `longest_negative_run_intervals` is KEPT and is not wrong — it answers FK's
 * question (capitulation-(b) genuinely counts merely-negative prints). The two
 * are distinguished by name and by `threshold_note`, not by replacement:
 * renaming would break every stored snapshot and report for no gain.
 *
 * The `< -5` and `< -7` comparisons are STRICT, matching fr.squeezeTrapPenalty()
 * and the SKILL letter exactly. This function does not re-adjudicate the edge:
 * a mismatch between the field and the function it feeds would be its own bug.
 */
export function fundingBlock(intervals, { n = 45 } = {}) {
  if (!Array.isArray(intervals) || intervals.length === 0) {
    return { insufficient: 'no funding intervals supplied', n_intervals: 0 }
  }
  const used = intervals.slice(-n)
  const per8hPct = used.map(iv => Number(iv.fundingRate) * 100)
  const meanPer8hPct = per8hPct.reduce((a, b) => a + b, 0) / per8hPct.length

  const days = []
  for (let i = 0; i < used.length; i++) {
    const key = new Date(used[i].fundingTime).toISOString().slice(0, 10)
    const day = days.find(d => d.key === key)
    if (day) day.values.push(per8hPct[i]); else days.push({ key, values: [per8hPct[i]] })
  }
  const dailyAvgPct = days.map(d => d.values.reduce((a, b) => a + b, 0) / d.values.length)

  // Per-INTERVAL annualized rate — the unit the squeeze-trap penalty's -5%/-7%
  // thresholds are written in. Annualizing each interval separately (rather
  // than thresholding the mean) is what "sustained >=3 consecutive intervals"
  // actually asks for.
  const annualizedPer8h = per8hPct.map(v => fr.annualizedFunding(v))
  const runBelowMinus5 = consecutiveRun(annualizedPer8h, v => v < -5, { from: 'end' })
  const idxBelowMinus7 = annualizedPer8h.map((v, i) => (v < -7 ? i : -1)).filter(i => i >= 0)
  const lastBelowMinus7 = idxBelowMinus7.length ? idxBelowMinus7[idxBelowMinus7.length - 1] : null

  return {
    n_intervals: used.length,
    n_sessions: days.length,
    mean_per_8h_pct: round2(meanPer8hPct),
    mean_annualized_pct: fr.annualizedFunding(meanPer8hPct),
    longest_negative_run_intervals: consecutiveRun(per8hPct, v => v < 0, { from: 'end' }),
    longest_negative_run_sessions: consecutiveRun(dailyAvgPct, v => v < 0, { from: 'end' }),
    // ── squeeze-trap / gate-8 inputs (FR SKILL §4 penalty) ──
    longest_run_below_minus5_annualized_intervals: runBelowMinus5,
    sustained3_below_minus5: runBelowMinus5 >= 3,
    min_interval_annualized_pct: annualizedPer8h.length ? Math.min(...annualizedPer8h) : null,
    single_interval_below_minus7: lastBelowMinus7 !== null,
    most_recent_below_minus7_intervals_ago: lastBelowMinus7 === null ? null : (annualizedPer8h.length - 1) - lastBelowMinus7,
    oi_90d_high_available: false,
    oi_within_5pct_of_90d_high: null,
    sign_convention: 'POSITIVE funding = longs pay shorts = carry INCOME to a short (FR SKILL, Jul 2026)',
    threshold_note: 'sustained3_below_minus5 is the boolean fr.squeezeTrapPenalty({sustained3Intervals}) wants: >=3 consecutive intervals each ANNUALIZED below -5% (= -0.004566% per 8h). longest_negative_run_intervals counts MERELY NEGATIVE prints (FK capitulation-(b)) and is a ~1000x looser bar — it must never be read as the squeeze-trap input. single_interval_below_minus7 scans the whole used window; most_recent_below_minus7_intervals_ago exposes its recency so a stale print is not read as "prints".',
  }
}

// ── EV / probability matrix ─────────────────────────────────────────────────

/**
 * scenarios: [{name, p, mid} | {name, p, low, high}], p in percentage points.
 * Returns weighted EV, probability sum, per-component contributions.
 */
export function weightedEV(scenarios) {
  const components = scenarios.map(s => {
    const mid = s.mid != null ? s.mid : (s.low + s.high) / 2
    return { name: s.name, p: s.p, mid: round2(mid), contribution: round2((s.p / 100) * mid) }
  })
  const ev = round2(components.reduce((a, c) => a + c.contribution, 0))
  const prob_sum = round2(scenarios.reduce((a, s) => a + s.p, 0))
  return { ev, prob_sum, prob_sum_ok: Math.abs(prob_sum - 100) <= 0.5, components }
}

/**
 * FK §5 mandatory sum-check: stated EV must match the recomputation within
 * 0.5% RELATIVE TO THE RECOMPUTED EV. Also flags prob-sum ≠ 100 and any
 * Rally cell > 50% (the post-adjustment cap).
 */
export function evCheck(statedEV, scenarios, { spot = null, tolPct = 0.5 } = {}) {
  const w = weightedEV(scenarios)
  const relDiffPct = w.ev === 0 ? null : round2(Math.abs(statedEV - w.ev) / Math.abs(w.ev) * 100)
  const rally = scenarios.find(s => /rally/i.test(s.name))
  return {
    recomputed_ev: w.ev, stated_ev: statedEV, rel_diff_pct: relDiffPct,
    within_tolerance: relDiffPct != null && relDiffPct <= tolPct,
    prob_sum: w.prob_sum, prob_sum_ok: w.prob_sum_ok,
    rally_cap_ok: !rally || rally.p <= 50,
    vs_spot_pct: spot ? round2((w.ev / spot - 1) * 100) : null,
    components: w.components,
  }
}

// ── stops / deployment coherence ────────────────────────────────────────────

/** FK coherence boolean: CATASTROPHIC stop strictly below the deepest active buy-zone floor. */
export function stopCoherence(catastrophic, deepestZoneFloor) {
  return { pass: catastrophic < deepestZoneFloor, catastrophic, deepest_zone_floor: deepestZoneFloor,
    rule: 'catastrophic stop must sit STRICTLY below the deepest active buy-zone floor (the compound line may sit inside a band by design)' }
}

// ── ADR ─────────────────────────────────────────────────────────────────────

/**
 * 5-day ADR over FULL sessions only (FK stop rules, hardened 2026-07-10).
 * sessions: chronological [{date:'YYYY-MM-DD', high, low}]. exclude: dates of
 * holiday-abbreviated sessions (must be excluded AND disclosed).
 */
export function adr(sessions, { n = 5, exclude = [] } = {}) {
  const ex = new Set(exclude)
  const usable = sessions.filter(s => !ex.has(s.date) && s.high != null && s.low != null)
  const used = usable.slice(-n)
  if (used.length < n) return { adr: null, note: `only ${used.length} usable sessions (need ${n})`, used, excluded: exclude }
  const value = round2(used.reduce((a, s) => a + (s.high - s.low), 0) / n)
  return { adr: value, used: used.map(s => ({ date: s.date, range: round2(s.high - s.low) })), excluded: exclude }
}

// ── sentiment streaks ───────────────────────────────────────────────────────

/**
 * Gate-1 streak: consecutive DAILY prints ≤ threshold, counted from the most
 * recent print backward. values: newest-first [{value:number, date?:string}].
 */
export function fngStreak(values, threshold) {
  let streak = 0
  for (const v of values) { if (v.value <= threshold) streak++; else break }
  return streak
}

// ── position snapshot (Hard Rule 8) ─────────────────────────────────────────
// Pure projection + freshness banding over the position-snapshot/1 file exported
// from the personal-accounting ledger. No filesystem here — tools/position.mjs
// owns the read; everything rule-shaped lives in this file so selftest covers it.

export const POSITION_SNAPSHOT_SCHEMA = 'position-snapshot/1'

/** Freshness bounds in MINUTES: ≤12h FRESH, ≤72h STALE, beyond that EXPIRED. */
export const POSITION_FRESHNESS = { stale: 720, expired: 4320 }

/**
 * Band a snapshot's age. Takes BOTH timestamps and uses the OLDER of the two:
 * `crypto_holding` refreshes only on `POST /link`, so a file written a minute ago
 * can be valuing week-old balances. Banding on `generated_at` alone would report
 * FRESH for a position nobody has re-read in a week — the failure mode this
 * function exists to prevent, not an edge case.
 *
 * All arguments are epoch-ms numbers or ISO strings; a missing/unparseable
 * `generatedAt` is EXPIRED, never a pass. A missing `holdingsAsOf` is treated as
 * unknown-and-therefore-old for the same fail-closed reason.
 */
export function positionFreshness(generatedAt, holdingsAsOf, now = Date.now(), opts = {}) {
  const { stale = POSITION_FRESHNESS.stale, expired = POSITION_FRESHNESS.expired } = opts
  const nowMs = toMs(now)
  const genMs = toMs(generatedAt)
  const holdMs = toMs(holdingsAsOf)

  if (genMs === null) {
    return { band: 'EXPIRED', age_min: null, driver: 'generated_at', generated_age_min: null,
      holdings_age_min: null, stale_after_min: stale, expired_after_min: expired,
      note: 'generated_at is missing or unparseable — treat as no snapshot at all (cold start, Hard Rule 4)' }
  }
  const genAge = Math.round((nowMs - genMs) / 60000)
  // Unknown holdings_as_of fails closed: unknown age is old age, never fresh age.
  const holdAge = holdMs === null ? Infinity : Math.round((nowMs - holdMs) / 60000)
  const age = Math.max(genAge, holdAge)
  const driver = holdAge > genAge ? 'holdings_as_of' : 'generated_at'

  const band = age <= stale ? 'FRESH' : (age <= expired ? 'STALE' : 'EXPIRED')
  const note = band === 'FRESH'
    ? 'position of record — supersedes any figure carried forward from a prior report'
    : band === 'STALE'
      ? 'descriptive use only, with an age banner. May NOT satisfy a phase-dependent unlock precondition or fill a realized ledger column.'
      : 'cold start per Hard Rule 4 — state explicitly that no fresh ledger was available'
  return { band, age_min: age === Infinity ? null : age, driver,
    generated_age_min: genAge, holdings_age_min: holdAge === Infinity ? null : holdAge,
    stale_after_min: stale, expired_after_min: expired, note }
}

function toMs(v) {
  if (v === null || v === undefined) return null
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  const t = Date.parse(v)
  return Number.isNaN(t) ? null : t
}

/**
 * Structural check before a single figure is read. Deliberately shallow — it
 * verifies the schema tag and that every block a caller dereferences exists, so a
 * v2 file or a truncated write is rejected loudly instead of yielding undefined
 * that formats as a plausible blank.
 */
export function positionSnapshotCheck(snap) {
  const errors = []
  if (!snap || typeof snap !== 'object') return { ok: false, errors: ['not a JSON object'] }
  if (snap.schema !== POSITION_SNAPSHOT_SCHEMA) {
    errors.push(`schema is ${JSON.stringify(snap.schema)}, expected "${POSITION_SNAPSHOT_SCHEMA}"`)
  }
  for (const key of ['generated_at', 'source', 'portfolio', 'dry_powder', 'positions',
    'futures', 'trades', 'deals', 'performance', 'coverage']) {
    if (snap[key] === undefined || snap[key] === null) errors.push(`missing top-level "${key}"`)
  }
  if (snap.positions !== undefined && !Array.isArray(snap.positions)) errors.push('"positions" is not an array')
  return { ok: errors.length === 0, errors }
}

/**
 * Project one asset out of a snapshot.
 *
 * `covered:false` is the whole point of this function. An asset the ledger does
 * not track (gold) must never come back as `qty: 0` — a zero position and an
 * unknown position lead to opposite decisions, and the framework has no other
 * signal to tell them apart.
 *
 * Attribution comes ONLY from deal tags. The ledger knows what is held and what
 * it cost; it does not know which tranche authorized it, and `crypto_trade` has
 * no tranche dimension to infer one from. An open deal with no tag is reported
 * UNTAGGED — real position, honest attribution — never guessed from size or date.
 */
export const LEDGER_ASSET_ALIASES = {
  // The framework analyses SPOT GOLD; the ledger can only hold a token. PAXG is
  // fully-backed tokenized gold redeemable for LBMA bars and tracks spot ~1:1,
  // so reading it as the gold position is far closer to the truth than a cold
  // start that pretends the position does not exist.
  //
  // It is a PROXY, not the same instrument, and the difference is not cosmetic:
  // PAXG carries issuer/custody counterparty risk that spot gold does not, and
  // it can trade at a premium or discount to XAU. So the alias is disclosed on
  // every response rather than silently resolved — a report states that its
  // gold position is held as PAXG.
  GOLD: {
    ledger: 'PAXG',
    note: 'Position read from PAXG, tokenized gold. PAXG is a PROXY for spot gold — fully backed and tracking XAU ~1:1, but carrying issuer/custody counterparty risk that spot gold does not, and able to trade at a premium or discount. Quantity and cost basis are real; treat the instrument as PAXG, not bullion. Canonical gold SPOT still comes from Hard Rule 1 sources, never from this mark.',
  },
}

// ── custody status (position-snapshot/1 qty_reconciliation_status) ──────────
// The ledger sees Binance. It does not see a hardware wallet, and a withdrawal
// is not a trade — so a coin moved to cold storage leaves the live balance
// while its cost basis stays on the books from the fill that bought it.
//
// Reporting that as an unknown was the first fix and it was not enough: a
// position of record that says "unknown" on 0.5 BTC is read downstream as FLAT,
// and flat is the one answer that is definitely wrong. The exporter now nets
// recorded withdrawals against the gap, and this lifts the verdict out of the
// position object so a report cannot skim past it.
//
// OFF_VENUE is a belief, never a fact. Confirm custody before sizing anything
// against it — and never let it satisfy a phase-dependent unlock precondition,
// which needs a tagged deal on a quantity the ledger can actually see.
export function custodyForPosition(position, asset) {
  // An absent row is not a clean row. Until 2026-07-30 this function opened with
  // `if (!position || ...)` and answered RECONCILED / on_venue — synthesising an
  // affirmative all-clear out of nothing at all. That mattered because the
  // exporter drove its position loop off LIVE holdings only, so an asset sold
  // down to exactly zero emitted no row: SOL replayed to -1.15 with an
  // underivable basis and reached a report as reconciled and reliable. The
  // exporter now emits a row for every replayed asset, and this refuses to
  // invent one when it does not.
  if (!position) {
    return { status: 'NO_POSITION_ROW', on_venue: null, off_venue_qty: null,
      note: `The snapshot carries no position row for ${asset}, so custody is UNKNOWN — not reconciled. An absent row is the absence of an answer, never an all-clear. Do NOT report this asset as on-venue, flat, or exited on the strength of this response; a snapshot written before 2026-07-30 omitted any asset whose live balance was exactly zero, including assets the replay still held a position in.` }
  }
  const status = position.qty_reconciliation_status || null
  if (status === null || status === 'RECONCILED') {
    return { status: status || 'RECONCILED', on_venue: true, off_venue_qty: null,
      note: 'Live balance agrees with the fill replay; the position is where the ledger can see it.' }
  }
  if (status === 'EXPLAINED_BY_EXTERNAL_TRANSFER') {
    return {
      status,
      on_venue: false,
      off_venue_qty: position.off_venue_qty ?? null,
      custody_adjusted_unrealized_pnl_usd: position.custody_adjusted_unrealized_pnl_usd ?? null,
      note: `${position.off_venue_qty} ${asset} left the exchange as a withdrawal, not a sale, and is presumed held in external custody. REPORT THIS AS A HELD POSITION — do NOT read the near-zero live balance as flat or as an exit. The mark is custody-adjusted and therefore a belief the ledger cannot verify: it cannot tell cold storage from a sale on another venue. Confirm custody before sizing against it, and do not let it satisfy a phase-dependent unlock precondition.`,
    }
  }
  // A migration seed is the one divergence where the LIVE side is trustworthy on its own. The gap is a
  // synthetic OPENING_BALANCE fill from the 2026-07-08 floor migration, sized from a pre-floor history that
  // was then deleted — so the replay is inflated by coins that may never have existed, while the live
  // balance is still a direct observation of the exchange. Report the live quantity; distrust the basis.
  if (status === 'EXPLAINED_BY_SYNTHETIC_OPENING_BALANCE') {
    return {
      status,
      on_venue: true,
      off_venue_qty: null,
      cost_basis_contaminated: true,
      note: `The replay exceeds the live balance by a synthetic OPENING_BALANCE seed carried at the ledger's data floor — an accounting artefact, not coins, and one that can never be reconciled because the pre-floor fills it was computed from were deleted. REPORT THE LIVE QUANTITY AS THE POSITION (this asset may genuinely be flat); do NOT report trade_derived_qty as ${asset} held, and do NOT treat it as off-venue custody — unlike a withdrawal there is no evidence these coins exist. Cost basis, unrealized PnL and realized PnL for ${asset} are contaminated by the seed's price: quote them as unreliable or not at all, and do not let them satisfy a phase-dependent unlock precondition.`,
    }
  }
  return {
    status: 'UNEXPLAINED',
    on_venue: null,
    off_venue_qty: null,
    note: 'The live balance and the fill replay disagree, and neither recorded withdrawals nor a migration seed accounts for the gap. This is a data defect — an unread wallet, an uncovered venue, or an incomplete backfill — not a position. Do NOT report a figure for this asset in either direction; fix the ledger first.',
  }
}

// ── cost-basis reliability (position-snapshot/1 basis_reliable) ────────────
// Orthogonal to custody, and the distinction matters: custody asks "are the
// coins where the ledger can see them", this asks "does the ledger know what
// they cost". An asset can be perfectly RECONCILED on quantity and still have
// no derivable basis.
//
// It goes UNRELIABLE when the ledger's replay disposed of more than it ever saw
// acquired. Until 2026-07-30 that meant one of two things and the ledger could
// not say which: a margin short, or a sale of coins whose acquisition was never
// ingested. The engine now models shorts, so on a snapshot produced from that
// date the flag means only the second — a genuine ingestion gap — and a short is
// a position with a real basis, reported through `short_qty`. Older files still
// carry the older, vaguer meaning, which is why the PRODUCER'S OWN note is
// preferred over anything reconstructed here: it was written by the code that
// set the flag and cannot drift from it.
//
// Why it exists: the engine used to treat "sold more than held" as if it were
// "sold down to dust" and snap the position to zero, so a short's quantity was
// erased and the buy-back that closed it re-accumulated from zero. Every short
// round trip added its full size to the position. On real history that turned a
// true 1.98 SOL opening balance into 833.5, and booked short-sale proceeds as
// pure profit because the basis of the sold quantity was zero.
export function basisForPosition(position, asset) {
  // Same trap as custodyForPosition, and the more dangerous of the two: a
  // missing row used to return `reliable: true`, inverting an oversold warning
  // into a clean bill of health. Reliability is a claim about a row; with no row
  // there is nothing to make the claim about.
  if (!position) {
    return { reliable: null, oversold_qty: null,
      note: `NO POSITION ROW for ${asset} — cost-basis reliability is UNKNOWN, not confirmed. Do not read this as a reliable basis, and do not quote average cost, cost basis, unrealized PnL or ROI on the strength of it.` }
  }
  if (position.basis_reliable !== false) {
    // A clean flag is not the same claim as a clean replay. From 2026-07-31 the
    // producer forgives an unbacked slice worth under $1 rather than flagging it
    // — before that date the band was a 1e-8 QUANTITY, which meant nothing across
    // assets and flagged 90 of 98 positions on gaps like 5e-8 ADA. `dust_unbacked_qty`
    // is what the threshold waived, published precisely so this branch can say so.
    // Absent (older file, or nothing waived) ⇒ stay silent; it is not a defect and
    // must not read as one.
    const dust = position.dust_unbacked_qty ?? null
    return {
      reliable: true,
      oversold_qty: null,
      dust_unbacked_qty: dust,
      note: dust
        ? `Basis reliable for ${asset}, with a disclosure: ${dust} was disposed unbacked but waived as sub-dollar dust rather than counted against the flag. Quote the cost figures normally — this is the ordinary state of a long-tail book, not a defect — but do not describe the replay as having had nothing missing.`
        : null,
    }
  }
  return {
    reliable: false,
    oversold_qty: position.oversold_qty ?? null,
    // The producer ships its own wording with the flag. Prefer it: a snapshot from
    // the signed engine says "unbacked disposal, NOT a short", an older one says
    // "short or ingestion gap, cannot tell", and rewriting either from here would
    // put this file's assumptions in front of the file's own evidence.
    note: position.basis_unreliable_note
      || `COST BASIS NOT DERIVABLE for ${asset}: the ledger's replay disposed of more than it ever saw acquired (${position.oversold_qty ?? 'an unrecorded quantity'} beyond the position), because the asset was sold short on margin or because an acquisition was never ingested — the ledger cannot tell which. Do NOT quote average cost, cost basis, unrealized PnL or ROI for ${asset}; state that the basis is unknown. The QUANTITY is still sound and is the position of record. Realized PnL is an UPPER BOUND, not a result: a short leg was realized against a zero basis, so it overstates the gain. This may not satisfy a phase-dependent unlock precondition that reads cost basis, and nothing is sized against a cost basis that does not exist.`,
  }
}

// ── the short leg (position-snapshot/1 short_qty) ─────────────────────────
// A THIRD orthogonal question, and the one a short-side framework actually asks:
// custody asks where the coins are, basis asks what they cost, this asks which
// WAY the position points. It is not answerable from the quantity, because the
// quantity is a NET across wallets: hold 10 spot and short 4 on margin and the
// net reads +6, with the borrow — the thing that must be covered, that pays
// carry, and that a squeeze runs against — nowhere in the number.
//
// Absent on a snapshot written before 2026-07-30, when the engine could not
// represent a short at all. Absent is UNKNOWN, never "no short": that producer
// would have reported a short as an underivable basis instead, so read
// `basis.reliable === false` on an old file as possibly-a-short.
export function shortForPosition(position, asset) {
  if (!position) {
    return { short: null, short_qty: null, avg_entry_usd: null,
      note: `NO POSITION ROW for ${asset} — whether a short is open is UNKNOWN, not answered.` }
  }
  if (position.short_qty === undefined) {
    return { short: null, short_qty: null, avg_entry_usd: null,
      note: `NOT PRESENT in this snapshot — the producer predates the signed cost-basis model and could not represent a short. Report short exposure in ${asset} as UNKNOWN, never as zero; on this producer a short surfaced as basis_reliable:false instead.` }
  }
  const qty = position.short_qty === null ? 0 : Number(position.short_qty)
  if (!(qty > 0)) {
    return { short: false, short_qty: 0, avg_entry_usd: null, note: null }
  }
  return {
    short: true,
    short_qty: position.short_qty,
    avg_entry_usd: position.short_avg_price_usd ?? null,
    note: position.short_note
      || `NET SHORT ${position.short_qty} ${asset} at an average entry of ${position.short_avg_price_usd ?? 'an unstated price'}. trade_derived_qty is a NET and may read flat or even long against an offsetting spot position; this leg still has to be covered and still pays carry. total_cost_usd is NEGATIVE for a short — money received, not spent.`,
  }
}

export function positionForAsset(snap, assetRaw) {
  const requested = String(assetRaw || '').toUpperCase()
  const alias = LEDGER_ASSET_ALIASES[requested] || null
  const asset = alias ? alias.ledger : requested
  const aliasFields = alias
    ? { requested_asset: requested, ledger_asset: alias.ledger, alias_note: alias.note }
    : {}
  const notTracked = (snap.coverage?.assets_not_tracked || [])
    .map(a => String(a).toUpperCase())
    // An alias resolves the coverage gap it was written for: the ledger lists GOLD
    // as untracked because it holds no bullion, which stops being the relevant fact
    // once the request has been routed to the token that stands in for it.
    .filter(a => !(alias && a === requested))
  if (notTracked.includes(asset)) {
    return { asset, ...aliasFields, covered: false, reason: 'not_tracked',
      note: 'This asset has no counterpart in the ledger and never will. Carry position state forward from the prior report; do NOT read a zero position from this response.' }
  }

  const position = (snap.positions || []).find(p => String(p.asset).toUpperCase() === asset) || null
  const openDeals = (snap.deals?.open || []).filter(d => String(d.asset).toUpperCase() === asset)
  const closedDeals = (snap.deals?.closed || []).filter(d => String(d.asset).toUpperCase() === asset)
  const fills = (snap.trades?.by_asset || []).find(t => String(t.asset).toUpperCase() === asset) || null
  const futures = (snap.futures?.open_positions || []).filter(p => String(p.base_asset).toUpperCase() === asset)
  const funding = (snap.futures?.funding_by_asset || []).find(f => String(f.asset).toUpperCase() === asset) || null

  if (!position && openDeals.length === 0 && closedDeals.length === 0 && futures.length === 0) {
    // "Genuine flat" is a real claim and it rests on one property of the producer: the exporter emits a
    // position row for every asset the REPLAY holds, not only for the ones the exchange still holds. Before
    // 2026-07-30 it did not, and this branch could fire on an asset carrying a live short and an underivable
    // basis. The claim is therefore stated together with what it depends on, so a stale snapshot cannot
    // launder an omission into a conclusion.
    return { asset, ...aliasFields, covered: false, reason: 'no_ledger_history',
      note: 'The ledger tracks this asset but holds no position row, no round trip and no open future in it. That is a genuine flat, not a gap — but it is stated, not inferred from an absent row. It holds only for a snapshot generated on or after 2026-07-30, when the exporter began emitting a row for every replayed asset including those with a zero live balance; on an older file an absent row may simply be an asset that was sold to exactly zero.' }
  }

  const tags = [...new Set(openDeals.map(d => d.tag).filter(Boolean))]
  const untagged = openDeals.filter(d => !d.tag).length
  const perfByTag = (snap.performance?.by_tag || []).filter(t => tags.includes(t.tag))

  return {
    asset,
    ...aliasFields,
    covered: true,
    position,
    custody: custodyForPosition(position, asset),
    basis: basisForPosition(position, asset),
    short: shortForPosition(position, asset),
    attribution: {
      tags,
      untagged_open_deals: untagged,
      note: untagged > 0
        ? `${untagged} open deal(s) carry no tag. Report the position as real but attribution UNTAGGED — a phase-dependent unlock precondition cannot resolve through an untagged holding.`
        : 'every open deal carries a phase tag',
    },
    open_deals: openDeals,
    closed_deals: closedDeals,
    fills: fills ? { fill_count_total: fills.fill_count_total, fills: fills.fills } : null,
    futures_positions: futures,
    funding,
    performance_by_tag: perfByTag,
  }
}

// ── tranche fill detection (report-machine/1 deployment.tranches[]) ─────────
// The predicate that decides whether a tranche is LIVE, and therefore whether
// its score unlock line, gate floor, stop band, size cap and ratchet are
// enforced at all. Until 2026-07-29 the linter asked `deployed === true ||
// typeof entry === 'number'` — a condition that had never once been true:
// across 39 machine-block reports, all 152/152 tranches encode `entry` as
// prose ("~65000 (MTM -1.2%)", "1640-1730 armed", "dry"). Every mechanical
// check downstream of it was unreachable code. Adding the numeric
// `entry_price` field (and keeping the prose `entry`, which carries real
// information — which zone, why blocked, blended MTM) is what makes the
// framework's own stop and cap discipline actually bind. This RELAXES nothing.

/** The numeric fill price of a tranche, or null if it has none. */
export function fillPrice(t) {
  if (!t) return null
  if (typeof t.entry_price === 'number' && Number.isFinite(t.entry_price)) return t.entry_price
  if (typeof t.entry === 'number' && Number.isFinite(t.entry)) return t.entry
  return null
}

/** Is this tranche live? `deployed:true` or any numeric fill price. */
export function trancheFilled(t) { return (t && t.deployed === true) || fillPrice(t) !== null }

/**
 * Does a PROSE `entry` describe an actual fill rather than a staged zone?
 *
 * Positive: a bare or ~approximate single price ("~65000 (MTM -1.2%)", "~4650"),
 * or the words MTM / blended, which only mean something against a real position.
 * Negative always wins: "unfilled", "dry", "frozen", "prospective", "armed" and
 * a price RANGE ("1640-1730 armed") are staged placeholders, not fills.
 *
 * Used to WARN (pre-epoch) or ERROR (on/after ENTRY_PRICE_EPOCH) that the
 * tranche needs a numeric `entry_price` so the mechanical checks can run.
 */
export function entryLooksLikeFill(entry) {
  if (typeof entry !== 'string') return { fill_like: false, reason: 'entry is not prose' }
  const neg = /\b(unfilled|dry|frozen|prospective|armed|staged|not filled|no fill)\b/i.exec(entry)
  if (neg) return { fill_like: false, reason: `staged/placeholder language ("${neg[1]}")` }
  const bare = /^\s*~?\s*\$?\s*[\d,]+(?:\.\d+)?\s*(?:\(|$)/.test(entry)
  const mtm = /\b(MTM|blended)\b/i.exec(entry)
  if (bare) return { fill_like: true, reason: 'entry opens with a single price, not a range' }
  if (mtm) return { fill_like: true, reason: `entry says "${mtm[1]}", which only has meaning against a real position` }
  return { fill_like: false, reason: 'no fill signature' }
}

// ── signal feed (A → B): report-machine/1 → signal-feed/1 ───────────────────
// Pure helpers behind tools/export-signals.mjs. Everything here is filesystem-
// free so selftest.mjs covers it; the script does the I/O.

export const SIGNAL_FEED_SCHEMA = 'signal-feed/1'

/**
 * The two schema epochs the report corpus straddles.
 *
 * `machineBlock` — before 2026-07-11 no report carried a ```json machine
 * block at all. Those reports are prose, and skipping them is CORRECT, not a
 * failure; `--strict` only fires on a report dated on/after this date.
 *
 * `discretionAndTwoChannel` — the FK Analyst Discretion Layer (D1–D6) and the
 * FR two-channel architecture (§4B) both shipped 2026-07-27. Reports predating
 * it have no `channel` and no `score.discretionary` — and those absences have
 * DEFINITIONALLY correct values rather than unknown ones, because neither
 * feature existed. See inferChannel() / inferDiscretion().
 *
 * `entryPrice` — the report-machine/1 extension that makes a fill machine-
 * visible (`deployed` / `entry_price`). Before it, the linter's fill predicate
 * was never once true across 152/152 tranches in 39 reports, so every score
 * unlock line, gate floor, stop band, size cap and ratchet was unreachable.
 * On/after it, a prose `entry` that looks like a fill is an ERROR.
 */
export const EPOCHS = {
  machineBlock: '2026-07-11',
  discretionAndTwoChannel: '2026-07-27',
  entryPrice: '2026-07-29',
  // companion_fr strict-shape validation (2026-08 toolchain-extension plan,
  // commit 14). Set to the ship date so no EXISTING report is retroactively
  // broken — every report in the corpus predates this epoch and keeps the
  // warn-only tolerant read; only a report dated on/after it must comply.
  companionFR: '2026-08-03',
  // Frozen non-crypto gate schema + pinned rounding (FR non-crypto
  // calibration). Set to the ship date on the same principle as every epoch
  // above: the four existing non-crypto reports predate it and keep the
  // warn-only read, so none is retroactively broken; only a report dated
  // on/after it must comply.
  nonCryptoSchema: '2026-08-05',
}
export const MACHINE_BLOCK_EPOCH = EPOCHS.machineBlock
export const DISCRETION_EPOCH = EPOCHS.discretionAndTwoChannel
export const ENTRY_PRICE_EPOCH = EPOCHS.entryPrice
export const NONCRYPTO_SCHEMA_EPOCH = EPOCHS.nonCryptoSchema
export const COMPANION_FR_EPOCH = EPOCHS.companionFR

/** Reports are timestamped in local EST/EDT per the repo's Output Convention. */
export const REPORT_ZONE = 'America/New_York'

/**
 * The filename IS the primary key. The machine block carries a `date` but no
 * time field, and (asset, framework, date) genuinely collides — btc/eth ×
 * fallen_knives on both 2026-07-14 and 2026-07-18. Scanning must therefore
 * filter on THIS regex, never on "contains a machine block": calibration_ledger.md
 * quotes the ```json machine fence in prose and a grep-first scanner ingests
 * the ledger as if it were a signal.
 */
export const REPORT_FILE_RE = /^([a-z0-9]+)_(fallen_knives|flying_rocket)_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})\.md$/

/** Offset of `zone` from UTC, in minutes, at instant `utcMs`. */
function zoneOffsetMinutes(utcMs, zone) {
  const dtf = new Intl.DateTimeFormat('en-US', {
    timeZone: zone, hour12: false,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
  const p = {}
  for (const { type, value } of dtf.formatToParts(new Date(utcMs))) p[type] = value
  const hour = p.hour === '24' ? 0 : Number(p.hour)
  const asIfUTC = Date.UTC(Number(p.year), Number(p.month) - 1, Number(p.day), hour, Number(p.minute), Number(p.second))
  return (asIfUTC - utcMs) / 60000
}

/**
 * "2026-07-11" + "10:30" in America/New_York → "2026-07-11T14:30:00Z".
 *
 * Solved by fixed point rather than a hardcoded −4/−5, so DST is handled by the
 * platform's tz database instead of by a rule this file would get wrong twice a
 * year. Returns null if the date/time are malformed or the runtime has no tz
 * data — a null instant is honest; a wrong one silently mis-sorts the feed.
 */
export function localToUtcISO(date, time, zone = REPORT_ZONE) {
  const dm = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(date))
  const tm = /^(\d{2}):(\d{2})$/.exec(String(time))
  if (!dm || !tm) return null
  const [Y, M, D] = [Number(dm[1]), Number(dm[2]), Number(dm[3])]
  const [h, mi] = [Number(tm[1]), Number(tm[2])]
  if (M < 1 || M > 12 || D < 1 || D > 31 || h > 23 || mi > 59) return null
  const target = Date.UTC(Y, M - 1, D, h, mi)
  // Reject a rolled-over calendar date (2026-02-30 → Mar 2).
  const probe = new Date(target)
  if (probe.getUTCFullYear() !== Y || probe.getUTCMonth() !== M - 1 || probe.getUTCDate() !== D) return null
  try {
    let t = target
    for (let i = 0; i < 3; i++) {
      const next = target - zoneOffsetMinutes(t, zone) * 60000
      if (next === t) break
      t = next
    }
    return new Date(t).toISOString().replace(/\.\d{3}Z$/, 'Z')
  } catch { return null }
}

/** Which epoch a report date falls in. */
export function schemaEpochOf(date) {
  const d = String(date)
  if (d >= EPOCHS.discretionAndTwoChannel) return 'discretion_and_two_channel'
  if (d >= EPOCHS.machineBlock) return 'machine_block'
  return 'pre_machine_block'
}

/**
 * Parse a report filename into its identity. `ok:false` means the file is not a
 * framework report and must be ignored — not skipped, not failed.
 */
export function reportFileMeta(name) {
  const m = REPORT_FILE_RE.exec(String(name))
  if (!m) return { ok: false, file: String(name), reason: 'filename does not match <asset>_<framework>_YYYYMMDD_HHMM.md' }
  const [, asset, framework, Y, M, D, hh, mm] = m
  const date = `${Y}-${M}-${D}`
  const local_time = `${hh}:${mm}`
  const at_utc = localToUtcISO(date, local_time)
  if (!at_utc) return { ok: false, file: String(name), reason: `filename encodes an impossible date/time (${date} ${local_time})` }
  return {
    ok: true,
    file: String(name),
    asset: asset.toUpperCase(),
    framework,
    date,
    local_time,
    zone: REPORT_ZONE,
    at_utc,
    schema_epoch: schemaEpochOf(date),
  }
}

/**
 * The rubric discriminator. Channel B reuses Channel A's five leg KEYS for a
 * completely different rubric, so no consumer may read a leg without first
 * knowing which rubric produced it. `none` is a stand-down, whose legs were
 * still scored under §4A.
 */
export function signalRubric(framework, channel) {
  if (framework === 'fallen_knives') return 'FK/1'
  if (framework !== 'flying_rocket') return null
  return channel === 'B' ? 'FR-B/1' : 'FR-A/1'
}

const LEG_SPECS = {
  'FK/1': [
    { ordinal: 1, block_key: 'sentiment', rubric_name: 'sentiment', min: 0, max: 5 },
    { ordinal: 2, block_key: 'momentum', rubric_name: 'momentum', min: 0, max: 4 },
    { ordinal: 3, block_key: 'valuation', rubric_name: 'valuation', min: -2, max: 5 },
    { ordinal: 4, block_key: 'capitulation', rubric_name: 'capitulation', min: 0, max: 3 },
    { ordinal: 5, block_key: 'holder', rubric_name: 'holder_behavior', min: 0, max: 3 },
  ],
  'FR-A/1': [
    { ordinal: 1, block_key: 'euphoria', rubric_name: 'euphoria', min: 0, max: 5 },
    { ordinal: 2, block_key: 'momentum', rubric_name: 'momentum', min: 0, max: 4 },
    { ordinal: 3, block_key: 'valuation', rubric_name: 'valuation', min: 0, max: 5 },
    { ordinal: 4, block_key: 'distribution', rubric_name: 'distribution', min: 0, max: 3 },
    { ordinal: 5, block_key: 'vulnerability', rubric_name: 'vulnerability', min: 0, max: 3 },
  ],
  // §4B — same block keys, different questions. This mapping is the whole
  // reason legs are emitted as an ordered array of named objects rather than
  // an object keyed by block_key: there must be no representation of the feed
  // in which `euphoria` silently means rally extension.
  'FR-B/1': [
    { ordinal: 1, block_key: 'euphoria', rubric_name: 'rally_extension', min: 0, max: 5 },
    { ordinal: 2, block_key: 'momentum', rubric_name: 'local_exhaustion', min: 0, max: 4 },
    { ordinal: 3, block_key: 'valuation', rubric_name: 'resistance_confluence', min: 0, max: 5 },
    { ordinal: 4, block_key: 'distribution', rubric_name: 'bear_structure_integrity', min: 0, max: 3 },
    { ordinal: 5, block_key: 'vulnerability', rubric_name: 'relative_sentiment', min: 0, max: 3 },
  ],
}

/** The ordered leg specification for a rubric, or [] if the rubric is unknown. */
export function legSpec(rubric) { return (LEG_SPECS[rubric] || []).map(l => ({ ...l })) }

/**
 * Channel, resolved rather than guessed.
 *
 * An absent `channel` on a pre-2026-07-27 Flying Rocket report is not unknown —
 * Channel B did not exist, so the score was necessarily computed under §4A.
 * Emitting "unknown" would lose that certainty and force every consumer to
 * re-derive it from a date they may not have.
 */
export function inferChannel(framework, channel, date) {
  if (framework === 'fallen_knives')
    return { channel: null, inferred: false, basis: 'Fallen Knives has no channel dimension' }
  if (['A', 'B', 'none'].includes(channel))
    return { channel, inferred: false, basis: 'declared in the machine block' }
  if (String(date) < EPOCHS.discretionAndTwoChannel)
    return {
      channel: 'A', inferred: true,
      basis: `report dated ${date} predates the two-channel architecture (${EPOCHS.discretionAndTwoChannel}); Channel B did not exist, so the score was computed under the §4A rubric`,
    }
  return {
    channel: null, inferred: false,
    basis: `channel is required on/after ${EPOCHS.discretionAndTwoChannel} but is absent or invalid (${JSON.stringify(channel)}) — lint-report.mjs errors on this`,
  }
}

/**
 * The mechanical/discretionary split, resolved the same way.
 *
 * Before the Analyst Discretion Layer shipped, discretion was structurally
 * impossible: there was no term to take. So `discretionary` is 0 and
 * `mechanical` equals `raw` — a fact, not a default.
 */
export function inferDiscretion(score, date) {
  const S = score || {}
  const pre = String(date) < EPOCHS.discretionAndTwoChannel
  const hasD = typeof S.discretionary === 'number'
  const hasM = typeof S.mechanical === 'number'
  const basis = pre
    ? `report dated ${date} predates the Analyst Discretion Layer (${EPOCHS.discretionAndTwoChannel}); no discretionary term existed, so discretion was structurally 0 and mechanical = raw`
    : 'declared in the machine block'
  return {
    discretionary: hasD ? S.discretionary : (pre ? 0 : null),
    discretionary_inferred: !hasD && pre,
    mechanical: hasM ? S.mechanical : (pre && typeof S.raw === 'number' ? S.raw : null),
    mechanical_inferred: !hasM && pre && typeof S.raw === 'number',
    basis: (!hasD || !hasM) && pre ? basis : 'declared in the machine block',
  }
}

/**
 * Gate numbers 1–9 as a bitmask (gate n → bit n−1), so Phase 4 can store an
 * exactly-queryable integer instead of an array column. Invalid numbers are
 * dropped, not thrown on — the linter is the place that errors on them.
 */
export function gateMask(passed) {
  let mask = 0
  for (const g of passed || []) if (Number.isInteger(g) && g >= 1 && g <= 9) mask |= 1 << (g - 1)
  return mask
}

/**
 * The score-axis unlock ladder that applied to this report, plus the highest
 * phase the score alone reached. Channel-aware: Channel B's ladder is shifted
 * +2 and has no p3 at all.
 */
export function unlockFor(framework, channel, { adjusted = null, mechanical = null } = {}) {
  const ladderName = framework === 'fallen_knives' ? 'FK' : channel === 'B' ? 'FR-B' : 'FR-A'
  const ladder = framework === 'fallen_knives' ? FK_SCORE_UNLOCK : frUnlockLadder(channel)
  const mech = typeof mechanical === 'number' ? mechanical : adjusted
  const order = ['p1a', 'p1b', 'p2', 'p3']
  let highest = null
  for (const p of order) {
    const line = ladder[p]
    if (line == null) continue
    const read = p === 'p3' ? mech : adjusted
    if (typeof read === 'number' && read >= line) highest = p
  }
  return {
    ladder: ladderName,
    p1a: ladder.p1a ?? null,
    p1b: ladder.p1b ?? null,
    p2: ladder.p2 ?? null,
    p3: ladder.p3 ?? null,
    p3_note: ladder.p3 == null
      ? 'Phase 3 is unreachable in Channel B at any score (§4B/§6)'
      : 'Phase 3 reads the MECHANICAL score — no analyst channel reaches it',
    highest_phase_unlocked_by_score: highest,
  }
}

/**
 * Canonical JSON: object keys sorted recursively, arrays in order, 2-space
 * indent, trailing newline. signal-feed.json is COMMITTED, so an unstable key
 * order would turn every regeneration into a diff of the whole file.
 */
export function canonicalJSON(value) {
  const canon = v => {
    if (Array.isArray(v)) return v.map(canon)
    if (v && typeof v === 'object') {
      const out = {}
      for (const k of Object.keys(v).sort()) out[k] = canon(v[k])
      return out
    }
    return v
  }
  return JSON.stringify(canon(value), null, 2) + '\n'
}

/**
 * Is the new feed materially different from the one on disk? `generated_at`
 * changes on every run by construction and must not count as a change, or the
 * committed file churns for nothing.
 */
export function feedChanged(prevText, next) {
  if (!prevText) return { changed: true, reason: 'no existing feed' }
  let prev
  try { prev = JSON.parse(prevText) } catch { return { changed: true, reason: 'existing feed is not valid JSON' } }
  const strip = o => { const { generated_at, ...rest } = o || {}; return rest }
  const same = canonicalJSON(strip(prev)) === canonicalJSON(strip(next))
  return { changed: !same, reason: same ? 'identical except generated_at' : 'content differs' }
}

/**
 * What counts as the snapshot's DIGESTED content — i.e. what a `tools/
 * snapshot.mjs` run_id/sha256 is actually keyed on. `fetched_at` and each
 * asset block's `errors[]` are stripped: a transient venue timeout, or the
 * few seconds between two fetches, must not fork the run id for otherwise
 * identical data. Hashing itself stays in snapshot.mjs (this file has no
 * crypto import — NO network, NO filesystem is the whole point of lib.mjs);
 * this function only defines the payload that goes INTO the hash.
 */
export function snapshotDigestPayload(snapshot) {
  const stripVolatile = block => {
    if (!block || typeof block !== 'object') return block
    const { fetched_at, errors, ...rest } = block
    return rest
  }
  const out = {}
  for (const key of Object.keys(snapshot).sort()) out[key] = stripVolatile(snapshot[key])
  return canonicalJSON(out)
}

// ── trading-day calendar ─────────────────────────────────────────────────────
// US EQUITY market holidays only — crypto trades every day, weekends and
// holidays included. Source: NYSE holiday calendar (nyse.com/markets/hours-
// calendars), cross-checked here by direct weekday computation rather than
// copied from memory. When an observed holiday falls on a weekend (July 4,
// 2026 is a Saturday), the OBSERVED weekday is listed, not the calendar date —
// that is the date the market is actually closed.

/** Sourced 2025-2027 (checkpoint dates in this repo's reports fall in 2026). */
export const US_MARKET_HOLIDAYS = [
  '2025-01-01', '2025-01-20', '2025-02-17', '2025-04-18', '2025-05-26', '2025-06-19',
  '2025-07-04', '2025-09-01', '2025-11-27', '2025-12-25',
  '2026-01-01', '2026-01-19', '2026-02-16', '2026-04-03', '2026-05-25', '2026-06-19',
  '2026-07-03', '2026-09-07', '2026-11-26', '2026-12-25',
  '2027-01-01', '2027-01-18', '2027-02-15', '2027-03-26', '2027-05-31', '2027-06-18',
  '2027-07-05', '2027-09-06', '2027-11-25', '2027-12-24',
]
const US_MARKET_HOLIDAY_SET = new Set(US_MARKET_HOLIDAYS)

/** ISO weekday name for a 'YYYY-MM-DD' date, UTC (avoids local-tz off-by-one). */
export function weekdayOf(dateStr) {
  return new Date(`${dateStr}T00:00:00Z`).toLocaleDateString('en-US', { weekday: 'long', timeZone: 'UTC' })
}

/**
 * Is `dateStr` a trading day? `assetClass: 'crypto'` is ALWAYS true — crypto
 * trades 7 days/week, 365 days/year, holidays included. `'equity'` (default)
 * excludes weekends and US_MARKET_HOLIDAYS. This split exists because a
 * calendar checkpoint ("N trading days from now") means something different
 * for the two asset classes on the same calendar date — Good Friday is the
 * sharpest case: equities closed, crypto open.
 */
export function isTradingDay(dateStr, { assetClass = 'equity' } = {}) {
  if (assetClass === 'crypto') return true
  const day = new Date(`${dateStr}T00:00:00Z`).getUTCDay()
  if (day === 0 || day === 6) return false
  return !US_MARKET_HOLIDAY_SET.has(dateStr)
}

function addDaysISO(dateStr, n) {
  const d = new Date(`${dateStr}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}

/**
 * The next `n` trading days STRICTLY AFTER `fromDateStr` (not including it).
 * A checkpoint's weekday-verified landing date must be computed with this —
 * never with raw calendar-day arithmetic — before any distance/likelihood
 * language is written about it.
 */
export function nextNTradingDays(fromDateStr, n, { assetClass = 'equity' } = {}) {
  const out = []
  let cur = fromDateStr
  while (out.length < n) {
    cur = addDaysISO(cur, 1)
    if (isTradingDay(cur, { assetClass })) out.push(cur)
  }
  return out
}

/** Count of trading days strictly between two dates (exclusive both ends). */
export function tradingDaysBetween(fromDateStr, toDateStr, { assetClass = 'equity' } = {}) {
  if (toDateStr <= fromDateStr) return 0
  let count = 0, cur = fromDateStr
  while (true) {
    cur = addDaysISO(cur, 1)
    if (cur >= toDateStr) break
    if (isTradingDay(cur, { assetClass })) count++
  }
  return count
}

export const _internal = { round2 }
