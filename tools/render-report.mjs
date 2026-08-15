// Deterministic report-machine/2 -> report-markdown/1 renderer.
// The JSON document is the source of truth. The prose view below is deliberately
// assembled from named fields so the reading view is useful to a person while
// the canonical machine payload remains available at the end for tooling.
//
//   node tools/render-report.mjs reports/<stem>.json --mode full --out reports/<stem>.md
//   node tools/render-report.mjs reports/<stem>.json --mode summary
import { readFileSync, writeFileSync, renameSync, unlinkSync, existsSync, mkdirSync } from 'node:fs'
import { basename, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  canonicalReportPayload, loadAndValidateReport, isInsideReports, reportStem,
} from './report-contract.mjs'

const MARKS = {
  AVAILABLE: '✅',
  PASS: '✅',
  PASSED: '✅',
  AUTHORIZED: '✅',
  ACTIVE: '🔵',
  CHECKED: '✅',
  CONSISTENT: '✅',
  FRESH: '✅',
  LIT: '✅',
  LIVE: '✅',
  OPEN: '🔵',
  REGISTERED: '✅',
  RECONCILED: '✅',
  LOCKED: '🔒',
  FROZEN: '🔒',
  DRY: '○',
  HOLD: '⏸️',
  UNKNOWN: '❔',
  DATA_LIMITED: '⚠️',
  STALE: '⚠️',
  EXPIRED: '⚠️',
  UNTAGGED: '⚠️',
  NOT_APPLICABLE: '—',
  NOT_COVERED: '—',
  FAIL: '❌',
  FAILED: '❌',
  VETO: '⛔',
}

const text = value => String(value ?? '—').replace(/```/g, '`\\`\\`').replace(/\r?\n/g, '\n> ')
const oneLine = value => String(value ?? '—').replace(/```/g, '`\\`\\`').replace(/\r?\n/g, '<br>')
  .replace(/\|/g, '\\|')
const hasValue = value => value !== null && value !== undefined && value !== ''

function human(value) {
  if (!hasValue(value)) return '—'
  return String(value)
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^./, character => character.toUpperCase())
}

const FIELD_LABELS = {
  all_time_high: 'All-time high',
  adr5: 'ADR-5',
  btc_rv30: 'BTC RV30',
  catastrophic_realized_pnl_after_0_1pct_fee_usd: 'Catastrophic realized P&L after 0.1% fee (USD)',
  compound_realized_pnl_after_0_1pct_fee_usd: 'Compound realized P&L after 0.1% fee (USD)',
  cot: 'COT managed-money net long',
  current_deepest_buy_floor: 'Current deepest buy floor',
  current_weight_pct: 'Current portfolio weight',
  data_as_of: 'Data as of',
  drawdown_pct: 'Drawdown from ATH',
  dry_powder_yield: 'Dry-powder yield',
  dry_powder_yield_pct: 'Dry-powder yield',
  exit_status: 'Exit status',
  futures: 'Open futures',
  gold_rv30: 'Gold RV30',
  gld_holdings: 'GLD holdings',
  locked_notional_usd: 'Locked notional (USD)',
  ma200d: '200-day MA',
  market_to_catastrophic_loss_pct_portfolio: 'Market to catastrophic loss (% of portfolio)',
  market_to_catastrophic_loss_usd: 'Market to catastrophic loss (USD)',
  market_to_compound_loss_pct_portfolio: 'Market to compound loss (% of portfolio)',
  market_to_compound_loss_usd: 'Market to compound loss (USD)',
  open_deals: 'Open deals',
  paxg_spot: 'PAXG spot',
  phase_eligibility_effect: 'Phase eligibility effect',
  pnl: 'P&L',
  position_reconciliation: 'Position reconciliation',
  potential_weight_if_buys_fill_pct: 'Potential portfolio weight if buys fill',
  remaining_after_both_paxg: 'Remaining after both PAXG trims',
  real_yield10y: '10-year real yield',
  sma200w: '200-week SMA',
  underlying_spot: 'Underlying spot',
  volume_flush: 'Volume flush',
  weekly_rsi: 'Weekly RSI-14',
  weekly_rsi14: 'Weekly RSI-14',
}

function fieldName(value) {
  const key = String(value ?? '')
  const normalized = key.toLowerCase().replace(/[-\s]+/g, '_')
  return FIELD_LABELS[normalized] || human(key).replace(/\bUsd\b/g, 'USD').replace(/\bPaxg\b/g, 'PAXG')
}

function status(value) {
  if (!hasValue(value)) return '—'
  return `${MARKS[String(value).toUpperCase()] || '•'} ${value}`
}

function formatNumber(value) {
  const raw = String(value)
  if (!/^-?\d+(?:\.\d+)?$/.test(raw)) return raw
  const [whole, fraction] = raw.split('.')
  const sign = whole.startsWith('-') ? '-' : ''
  const digits = sign ? whole.slice(1) : whole
  return `${sign}${digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${fraction === undefined ? '' : `.${fraction}`}`
}

function moneyValue(value) {
  const number = formatNumber(value)
  return number.startsWith('-') ? `-$${number.slice(1)}` : `$${number}`
}

function measurementValue(value, unit) {
  if (!hasValue(value)) return '—'
  if (typeof value === 'object') return friendlyValue(value)
  const number = formatNumber(value)
  const normalizedUnit = String(unit || '')
  if (/^percent(?:\s|$)/i.test(normalizedUnit) || /percent/i.test(normalizedUnit)) return `${number}%`
  if (/^USD$/i.test(normalizedUnit)) return moneyValue(value)
  if (/^USD\//i.test(normalizedUnit)) return `${moneyValue(value)}/${normalizedUnit.slice(4)}`
  return normalizedUnit ? `${number} ${normalizedUnit}` : number
}

function probabilityPercent(value) {
  if (!hasValue(value)) return '—'
  const percentage = Math.round(Number(value) * 10000) / 100
  return `${formatNumber(percentage)}%`
}

function scalar(value, key = '') {
  if (!hasValue(value)) return '—'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'number') return formatNumber(value)
  const keyName = String(key).toLowerCase()
  if (keyName === 'status' || keyName === 'state' || keyName === 'decision') return status(value)
  if (keyName.endsWith('_pct') || keyName.endsWith('percentage')) return `${formatNumber(value)}%`
  if (keyName.endsWith('_usd')) return moneyValue(value)
  return oneLine(value)
}

function friendlyValue(value, key = '') {
  if (!hasValue(value)) return '—'
  if (Array.isArray(value)) {
    if (!value.length) return 'None'
    return value.map(item => friendlyValue(item)).join('; ')
  }
  if (typeof value === 'object') {
    return Object.entries(value).map(([name, item]) => `${fieldName(name)}: ${friendlyValue(item, name)}`).join('; ')
  }
  return scalar(value, key)
}

function table(headers, rows) {
  const lines = [
    `| ${headers.join(' | ')} |`,
    `| ${headers.map(() => '---').join(' | ')} |`,
  ]
  for (const row of rows) lines.push(`| ${headers.map((_, index) => oneLine(row[index])).join(' | ')} |`)
  return lines
}

function objectTable(title, value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || !Object.keys(value).length) return [`### ${title}`, '', '- None recorded.', '']
  return [`### ${title}`, '', ...table(['Field', 'Value'], Object.entries(value).map(([key, item]) => [fieldName(key), friendlyValue(item, key)])), '']
}

function list(items, formatter = item => item) {
  if (!items?.length) return ['- None recorded.']
  return items.map(item => `- ${formatter(item)}`)
}

function sourceLinks(ids, sources) {
  if (!ids?.length) return '—'
  return ids.map(id => sources?.[id]?.url ? `[${id}](${sources[id].url})` : id).join(', ')
}

function measurementRow(name, value, sources) {
  if (!value) return [fieldName(name), '—', '—', '—', '—', '—']
  const note = value.note || value.rationale || '—'
  return [fieldName(name), measurementValue(value.value, value.unit), status(value.status), value.confidence || '—', value.as_of || '—', `${oneLine(note)}${value.source_ids?.length ? `<br>Sources: ${sourceLinks(value.source_ids, sources)}` : ''}`]
}

function actionFor(value) {
  if (!value) return 'Unavailable — evidence unavailable.'
  const label = value.value || 'UNSPECIFIED'
  const rationale = value.rationale || 'No rationale supplied.'
  return `**${label}** — ${text(rationale)}`
}

function frameworkLabel(value) {
  return { fallen_knives: 'Fallen Knives', flying_rocket: 'Flying Rocket' }[value] || human(value)
}

function maxForLeg(framework, key) {
  if (framework === 'flying_rocket') return { euphoria: 5, momentum: 4, valuation: 5, distribution: 3, vulnerability: 3 }[key] ?? '—'
  return { sentiment: 5, momentum: 4, valuation: 5, capitulation: 3, holder: 3 }[key] ?? '—'
}

function renderMarket(report) {
  const market = report.market || {}
  const metrics = Object.entries(market.metrics || {})
  const evidence = Object.entries(report.evidence || {})
  const lines = [
    '## 2. Market, evidence, and data quality',
    '',
    ...table(['Measure', 'Value', 'Status', 'Confidence', 'As of', 'Reading / source'], [
      measurementRow('Canonical spot', market.spot, report.sources),
      measurementRow('All-time high', market.ath, report.sources),
      measurementRow('Drawdown from ATH', market.drawdown_pct, report.sources),
      ...metrics.map(([name, value]) => measurementRow(name, value, report.sources)),
    ]),
    '',
    `**Regime:** ${status(market.regime?.label)}${market.regime ? ` — ${friendlyValue(market.regime)}` : ''}`,
    '',
    '### Spot reconciliation',
    '',
    `**${status(market.reconciliation?.status)}** — ${text(market.reconciliation?.method || 'No reconciliation method supplied.')}${hasValue(market.reconciliation?.spread_pct) ? `; spread ${formatNumber(market.reconciliation.spread_pct)}%` : ''}`,
    '',
    ...table(['Instrument', 'Value', 'State', 'Sources'], (market.reconciliation?.quotes || []).map(quote => {
      const quoteUnit = /^PAXG/i.test(String(quote.instrument || '')) ? 'USD/PAXG' : market.spot?.unit
      return [quote.instrument || '—', measurementValue(quote.value, quoteUnit), status(quote.state), sourceLinks(quote.source_ids, report.sources)]
    })),
    market.reconciliation?.note ? `\n> ${text(market.reconciliation.note)}` : '',
    '',
    '### Evidence inputs',
    '',
    ...table(['Input', 'Value', 'Status', 'Confidence', 'As of', 'Rationale / source'], evidence.map(([name, value]) => measurementRow(name, value, report.sources)),
    ),
    '',
    `**Data gaps:** ${report.data_gaps?.length || 0} · **stale inputs:** ${report.stale_inputs?.length || 0} · **out of scope:** ${report.out_of_scope?.length || 0}`,
    '',
  ]
  if (report.data_gaps?.length) {
    lines.push('**Data gaps**', '', ...list(report.data_gaps, gap => `**${gap.field}** — ${status(gap.status)} — ${gap.impact || 'Impact not stated.'}`), '')
  }
  if (report.stale_inputs?.length) lines.push('**Stale inputs**', '', ...list(report.stale_inputs, item => text(item)), '')
  if (report.out_of_scope?.length) lines.push('**Out of scope**', '', ...list(report.out_of_scope, item => text(item)), '')
  return lines
}

function renderScoreAndGates(report) {
  const score = report.score || {}
  const gates = report.gates || {}
  const framework = report.identity?.framework
  const legRows = Object.entries(score.legs || {}).map(([key, value]) => [fieldName(key), value, maxForLeg(framework, key), 'Mechanical component'])
  const passed = gates.passed || []
  const na = gates.na || []
  const gateRows = Array.from({ length: 9 }, (_, index) => {
    const number = index + 1
    const state = na.includes(number) ? 'N/A' : passed.includes(number) ? 'PASSED' : 'NOT PASSED'
    return [number, status(state), gates.measurement_basis?.[String(number)] || 'Measurement basis not supplied.']
  })
  return [
    '## 3. Score and confirmation gates',
    '',
    ...table(['Component', 'Score', 'Maximum', 'Interpretation'], legRows),
    '',
    ...table(['Total', 'Value', 'Meaning'], [
      ['Mechanical score', score.mechanical ?? '—', 'Legs plus penalties'],
      ['Raw score', score.raw ?? '—', `Mechanical plus discretion (${score.discretion ?? '—'})`],
      ['Adjusted score', `**${score.adjusted ?? '—'}/20**`, 'Decision score'],
      ['Rounding', score.rounding || '—', 'Pinned convention'],
    ]),
    '',
    score.penalties?.length ? `**Penalties:** ${score.penalties.join(', ')}` : '**Penalties:** none',
    '',
    score.caps?.length ? '### Caps, ceilings, and line-state constraints\n\n' + table(['Field', 'Cap / value', 'Reason'], score.caps.map(cap => {
      const capValue = cap.cap ?? cap.current_cap ?? (typeof cap.value !== 'object' ? cap.value : '—')
      const capReason = cap.reason || cap.derivation || (cap.value && typeof cap.value === 'object' ? friendlyValue(cap.value) : '—')
      return [fieldName(cap.field), capValue, capReason]
    })) .join('\n') : '',
    '',
    `### Confirmation gates — ${passed.length}/${gates.active ?? '—'} active passed`,
    '',
    ...table(['#', 'State', 'Measurement / relight path'], gateRows),
    '',
    '### Unlock thresholds',
    '',
    ...table(['Phase', 'Score / gate threshold'], Object.entries(gates.thresholds || {}).map(([phase, threshold]) => [phase.toUpperCase(), threshold])),
    '',
    gates.alt_reading ? `**Alternate reading:** correlation ${gates.alt_reading.corr ?? '—'}; surcharge ${gates.alt_reading.corr_surcharge ? 'active' : 'off'}; [V] gates ${gates.alt_reading.v_count ?? '—'}.` : '',
    gates.alt_reading?.binding_axis ? `\n**Binding axis:** ${Object.entries(gates.alt_reading.binding_axis).map(([phase, reading]) => `${phase}: ${oneLine(reading)}`).join(' · ')}` : '',
    '',
  ]
}

function renderEV(report) {
  const ev = report.ev || {}
  return [
    '## 4. Probability matrix and expected value',
    '',
    ...table(['Scenario', 'Probability', 'Low', 'High', 'Midpoint', 'Rationale'], (ev.scenarios || []).map(scenario => [
      scenario.name || '—', probabilityPercent(scenario.probability), measurementValue(scenario.low, report.market?.spot?.unit), measurementValue(scenario.high, report.market?.spot?.unit), measurementValue(scenario.mid, report.market?.spot?.unit), scenario.rationale || '—',
    ])),
    '',
    ...table(['EV field', 'Value'], [
      ['Arithmetic status', status(ev.arithmetic_status)],
      ['Probability sum', ev.probability_sum ?? '—'],
      ['Stated EV', measurementValue(ev.stated_ev, report.market?.spot?.unit)],
      ['EV versus spot', hasValue(ev.vs_spot_pct) ? `${formatNumber(ev.vs_spot_pct)}%` : '—'],
    ]),
    ev.note ? `\n> ${text(ev.note)}` : '',
    '',
  ]
}

function renderDeployment(report) {
  const deployment = report.deployment || {}
  return [
    '## 5. Deployment strategy',
    '',
    `**Deployed:** ${formatNumber(deployment.deployed_pct ?? '—')}% · **dry powder:** ${formatNumber(deployment.dry_pct ?? '—')}% · **throttle released:** ${deployment.throttle_released ? 'yes' : 'no'}`,
    '',
    ...table(['Phase', 'Size', 'State', 'Deployed', 'Entry', 'Stop', 'Prior stop', 'Time stop', 'Prior time stop', 'Channel', 'Channel regime', 'Canonical tag', 'Decision rationale'], (deployment.tranches || []).map(tranche => [
      tranche.phase || '—', hasValue(tranche.pct) ? `${formatNumber(tranche.pct)}%` : '—', status(tranche.state), tranche.deployed ? 'yes' : 'no', tranche.entry_price ?? '—', tranche.stop ?? '—', tranche.prior_stop ?? '—', tranche.time_stop ?? '—', tranche.prior_time_stop ?? '—', tranche.channel ?? '—', tranche.channel_regime ?? '—', tranche.tag ?? '—', tranche.rationale || '—',
    ])),
    '',
  ]
}

function renderPosition(report) {
  const position = report.position || {}
  const basis = position.basis || {}
  const custody = position.custody || {}
  const pnl = position.pnl || {}
  const attribution = position.attribution || {}
  const lines = [
    '## 6. Position, custody, and execution controls',
    '',
    ...table(['Position field', 'Value'], [
      ['Status', status(position.status)],
      ['Asset', position.asset || report.identity?.asset || '—'],
      ['Quantity', position.quantity ?? '—'],
      ['Dry powder', hasValue(position.dry_powder) ? moneyValue(position.dry_powder) : '—'],
      ['Basis reliable', basis.reliable === undefined ? '—' : basis.reliable ? 'yes' : 'no'],
      ['Average cost', hasValue(basis.avg_cost_usd) ? moneyValue(basis.avg_cost_usd) : '—'],
      ['Total cost basis', hasValue(basis.total_cost_usd) ? moneyValue(basis.total_cost_usd) : '—'],
      ['Custody', status(custody.status)],
      ['Attribution', status(attribution.status)],
      ['Active tags', attribution.active_tags?.join(', ') || attribution.tags?.join(', ') || 'None'],
    ]),
    '',
    ...(custody && Object.keys(custody).length ? objectTable('Custody reconciliation', custody) : []),
    ...(basis && Object.keys(basis).length ? objectTable('Cost basis', basis) : []),
    ...(attribution && Object.keys(attribution).length ? objectTable('Phase attribution', attribution) : []),
    ...(pnl && Object.keys(pnl).length ? objectTable('Position P&L', pnl) : []),
    position.reconciliation ? `> **Position reconciliation:** ${text(position.reconciliation)}` : '',
    '',
  ]
  if (position.futures?.length) lines.push('### Open futures', '', ...table(['Symbol', 'Side', 'Quantity', 'Entry', 'Mark', 'Unrealized P&L'], position.futures.map(future => [future.symbol || '—', future.side || future.position_side || '—', future.quantity || '—', future.entry_price || '—', future.mark_price || '—', future.unrealized_pnl || '—'])), '')
  else lines.push('### Open futures', '', '- None recorded.', '')
  return lines
}

function renderPositionControls(report) {
  const controls = report.position_controls
  if (!controls) return ['### Position controls', '', '- Not supplied.', '']
  const lines = [
    '### Position controls',
    '',
    ...table(['Control status', 'Required', 'Primary action'], [[status(controls.status), controls.required ? 'yes' : 'no', actionFor(controls.action)]]),
    '',
  ]
  if (controls.selection) lines.push(...objectTable('Selected control plan', controls.selection))
  if (controls.veto) lines.push(...objectTable('Veto state', controls.veto))
  if (controls.ratchet) lines.push(...objectTable('Ratchet ledger', controls.ratchet))
  if (controls.risk) lines.push(...objectTable('Risk and concentration', controls.risk))
  if (controls.execution_audit) lines.push(...objectTable('Execution audit', controls.execution_audit))
  if (controls.liquidation_zone) lines.push(...objectTable('Liquidation zone', controls.liquidation_zone))
  if (controls.pnl) lines.push(...objectTable('Control-level P&L', controls.pnl))
  if (controls.candidate) {
    lines.push(...objectTable('Candidate board summary', { data_as_of: controls.candidate.data_as_of, phrase: controls.candidate.phrase, primary_action: controls.candidate.primary_action }))
    if (controls.candidate.board?.length) lines.push('#### Candidate board', '', ...table(['Candidate', 'Score', 'Veto', 'Dimensions', 'Reason'], controls.candidate.board.map(candidate => [candidate.candidate, candidate.score ?? '—', candidate.veto ? 'yes' : 'no', friendlyValue(candidate.dimensions), candidate.reason || '—'])), '')
  }
  if (controls.venue_order) {
    lines.push(...objectTable('Venue order state', { locked_notional_usd: controls.venue_order.locked_notional_usd, orders_changed: controls.venue_order.orders_changed, current_protective_sell: controls.venue_order.current_protective_sell, recommended_sequence: controls.venue_order.recommended_sequence }))
    if (controls.venue_order.current_buy_orders?.length) lines.push('#### Current buy orders', '', ...table(['Side', 'Type', 'Price', 'Quantity', 'Notional'], controls.venue_order.current_buy_orders.map(order => [order.side, order.type, hasValue(order.price) ? moneyValue(order.price) : '—', order.quantity, hasValue(order.notional_usd) ? moneyValue(order.notional_usd) : '—'])), '')
  }
  if (controls.ladder) {
    lines.push(...objectTable('Trim / exit ladder', { status: controls.ladder.status, quantity_check: controls.ladder.quantity_check, remaining_after_both_paxg: controls.ladder.remaining_after_both_paxg }))
    if (controls.ladder.alerts_only?.length) lines.push('#### Alert-only levels', '', ...table(['Price', 'Reason'], controls.ladder.alerts_only.map(alert => [alert.price, alert.reason])), '')
    if (controls.ladder.targets?.length) lines.push('#### Conditional targets', '', ...table(['Condition', 'Execution', 'Quantity', 'Target price', 'Expected P&L', 'Share', 'Price note'], controls.ladder.targets.map(target => [target.condition, target.execution, target.quantity_paxg || target.target_quantity || '—', hasValue(target.target_price_usd) ? moneyValue(target.target_price_usd) : '—', hasValue(target.expected_realized_pnl_after_0_1pct_fee_usd) ? moneyValue(target.expected_realized_pnl_after_0_1pct_fee_usd) : '—', target.position_share_pct ? `${target.position_share_pct}%` : '—', target.price_note || '—'])), '')
  }
  return lines
}

function renderRiskControls(report) {
  const controls = report.risk_controls
  if (!controls) return []
  const lines = ['### Framework risk controls', '']
  for (const [name, value] of Object.entries(controls)) {
    if (value && typeof value === 'object') lines.push(...objectTable(fieldName(name), value))
    else lines.push(...table(['Field', 'Value'], [[fieldName(name), friendlyValue(value, name)]]), '')
  }
  return lines
}

function renderNarrative(report) {
  const narrative = report.narrative || {}
  const argumentsBlock = narrative.arguments || {}
  const lines = [
    '## 7. Analyst rationale',
    '',
    `**Summary:** ${text(narrative.summary)}`,
    '',
    `**Bull case:** ${text(narrative.bull_case)}`,
    '',
    `**Bear case:** ${text(narrative.bear_case)}`,
    '',
    `**Rationale:** ${text(narrative.rationale)}`,
    '',
    `**Primary action:** ${actionFor(narrative.primary_action)}`,
    '',
  ]
  const namedArguments = Object.entries(argumentsBlock).filter(([, value]) => !Array.isArray(value))
  if (namedArguments.length) lines.push('### Decision-support arguments', '', ...table(['Argument', 'Reading'], namedArguments.map(([key, value]) => [fieldName(key), value])), '')
  if (argumentsBlock.discretion_ledger?.length) lines.push('### Discretion ledger', '', ...table(['Date', 'Channel', 'Call', 'Size', 'Stop', 'Falsifier', 'Status', 'P&L'], argumentsBlock.discretion_ledger.map(entry => [entry.date, entry.channel, entry.call, entry.size, entry.stop, entry.falsifier, status(entry.status), entry.pnl])), '')
  return lines
}

function renderCompanion(report) {
  const companion = report.companion_framework || {}
  const validation = report.cross_validation || {}
  return [
    '## 8. Companion framework and cross-validation',
    '',
    ...table(['Check', 'Status', 'Score / relationship', 'Reading'], [
      ['Companion framework', status(companion.status), `${companion.framework || '—'}${hasValue(companion.score) ? ` · ${companion.score}/20` : ''}${hasValue(companion.gates) ? ` · ${companion.gates} gates` : ''}`, companion.rationale || '—'],
      ['Cross-validation', status(validation.status), validation.relationship || '—', validation.rationale || '—'],
    ]),
    '',
  ]
}

function renderWatchlist(report) {
  const lines = [
    '## 9. Watchlist, events, falsifiers, and changes',
    '',
    '### Watchlist',
    '',
    ...table(['Item', 'Status', 'Trigger'], (report.watchlist || []).map(item => [item.item, status(item.status), item.trigger])),
    '',
    '### Events',
    '',
    ...table(['Date / time', 'Event', 'Status', 'Impact'], (report.events || []).map(event => [event.as_of, event.name, status(event.status), event.impact])),
    '',
    '### Falsifiers',
    '',
    ...table(['Claim', 'Condition', 'Status'], (report.falsifiers || []).map(item => [item.claim, item.condition, status(item.status)])),
    '',
    '### Change log',
    '',
    ...table(['Field', 'Previous', 'Current', 'Reason'], (report.change_log || []).map(change => [fieldName(change.field), friendlyValue(change.previous, 'previous'), friendlyValue(change.current, 'current'), change.reason])),
    '',
  ]
  return lines
}

function renderSubstitutionsAndSources(report) {
  const lines = [
    '## 10. Substitutions, source register, and provenance',
    '',
    '### Asset substitutions',
    '',
    ...table(['Field', 'Original', 'Substitute', 'Reason'], (report.substitutions || []).map(item => [fieldName(item.field), item.original, item.substitute, item.rationale])),
    '',
    '### Sources',
    '',
    ...table(['ID', 'Name', 'Kind', 'As of', 'Retrieved', 'Note / link'], Object.entries(report.sources || {}).map(([id, source]) => [id, source.name, source.kind, source.as_of, source.retrieved_at, `${source.note || '—'}${source.url ? `<br>[Open source](${source.url})` : ''}`])),
    '',
    '### Report timestamps',
    '',
    ...table(['Timestamp', 'Value'], Object.entries(report.timestamps || {}).map(([key, value]) => [fieldName(key), value])),
    '',
    '### Run provenance',
    '',
    ...table(['Field', 'Value'], [
      ['Report ID', report.report_id],
      ['Report filename', report.identity?.filename],
      ['Run ID', report.run?.run_id],
      ['Snapshot ID', report.run?.snapshot_id],
      ['Prior report', report.run?.prior_report_id || 'None'],
      ['Prior report hash', report.run?.prior_report_sha256 || 'None'],
    ]),
    '',
    ...(report.run?.tool_hashes ? ['#### Tool hashes', '', ...table(['Tool', 'Hash'], Object.entries(report.run.tool_hashes).map(([tool, hash]) => [tool, hash])), ''] : []),
  ]
  return lines
}

function renderTagging(report) {
  const tagging = report.tagging || {}
  return [
    '## 11. Phase registry and canonical tags',
    '',
    ...table(['Phase', 'Decision', 'Canonical tag', 'Instrument class'], (tagging.entries || []).map(entry => [entry.phase, status(entry.decision), entry.canonical_tag, entry.instrument_class])),
    '',
    `**Registry:** ${tagging.schema || '—'} · ${status(tagging.status)} · instrument class ${tagging.instrument_class || '—'}`,
    `**Active tags:** ${tagging.active_tags?.join(', ') || 'None'}`,
    `**Reserved tags:** ${tagging.reserved_tags?.join(', ') || 'None'}`,
    '',
  ]
}

export function renderSummary(report) {
  const identity = report.identity || {}
  const actionLine = actionFor(report.verdict?.primary_action)
  return [
    `# ${identity.asset || 'Unknown asset'} ${frameworkLabel(identity.framework)} — ${identity.date || 'date unavailable'} ${identity.local_time || ''}`.trim(),
    '',
    `**${report.verdict?.status || 'UNKNOWN'}:** ${text(report.verdict?.statement)}`,
    '',
    `- Score: **${report.score?.adjusted ?? '—'}/20** (mechanical ${report.score?.mechanical ?? '—'})`,
    `- Gates: **${report.gates?.passed?.length ?? 0}/${report.gates?.active ?? '—'}** passed`,
    `- Position: **${report.position?.status || '—'}**; controls **${report.position_controls?.status || '—'}**`,
    `- Primary action: ${actionLine}`,
    '',
    text(report.narrative?.summary),
  ].join('\n') + '\n'
}

export function renderFull(report) {
  const identity = report.identity || {}
  const verdict = report.verdict || {}
  const score = report.score || {}
  const gates = report.gates || {}
  const position = report.position || {}
  const decisionRows = [
    ['Asset / framework', `${identity.asset || '—'} · ${frameworkLabel(identity.framework)}`],
    ['Report time', `${identity.date || '—'} ${identity.local_time || ''} (${identity.timezone || 'timezone unavailable'})`.trim()],
    ['Verdict', `${status(verdict.status)} — ${verdict.statement || 'No statement supplied.'}`],
    ['Adjusted score', `**${score.adjusted ?? '—'}/20** (mechanical ${score.mechanical ?? '—'}, raw ${score.raw ?? '—'})`],
    ['Confirmation gates', `${gates.passed?.length ?? 0}/${gates.active ?? '—'} active passed`],
    ['Position', `${status(position.status)} · ${position.quantity ?? 'quantity unavailable'} ${position.asset || identity.asset || ''}`.trim()],
    ['Deployment', `${report.deployment?.deployed_pct ?? '—'}% deployed · ${report.deployment?.dry_pct ?? '—'}% dry`],
    ['Primary action', actionFor(verdict.primary_action)],
  ]
  return [
    `# ${identity.asset || 'Unknown asset'} — ${frameworkLabel(identity.framework)} — ${identity.date || 'date unavailable'} ${identity.local_time || ''}`.trim(),
    '',
    '## 1. Decision snapshot',
    '',
    ...table(['Decision field', 'Reading'], decisionRows),
    '',
    ...renderMarket(report),
    ...renderScoreAndGates(report),
    ...renderEV(report),
    ...renderDeployment(report),
    ...renderPosition(report),
    ...renderPositionControls(report),
    ...renderRiskControls(report),
    ...renderNarrative(report),
    ...renderCompanion(report),
    ...renderWatchlist(report),
    ...renderSubstitutionsAndSources(report),
    ...renderTagging(report),
    '## 12. Canonical machine payload',
    '',
    'The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.',
    '',
    '```json machine',
    canonicalReportPayload(report),
    '```',
    '',
  ].join('\n')
}

function main() {
  const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const argv = process.argv.slice(2)
  const input = argv[0]
  const modeIndex = argv.indexOf('--mode')
  const mode = modeIndex >= 0 ? argv[modeIndex + 1] : 'full'
  const outIndex = argv.indexOf('--out')
  const out = outIndex >= 0 && argv[outIndex + 1] ? resolve(REPO, argv[outIndex + 1]) : null
  if (!input || !['full', 'summary'].includes(mode)) {
    console.error('usage: node tools/render-report.mjs <report.json> --mode full|summary [--out reports/<stem>.md]')
    process.exit(1)
  }
  let loaded
  try { loaded = loadAndValidateReport(resolve(input)) } catch (error) {
    console.error(`FAIL — ${error.message}`)
    process.exit(1)
  }
  if (!loaded.ok) {
    for (const error of loaded.errors) console.error(`ERROR ${error}`)
    console.error(`FAIL — ${loaded.errors.length} validation error(s)`)
    process.exit(1)
  }
  const rendered = mode === 'summary' ? renderSummary(loaded.report) : renderFull(loaded.report)
  if (mode === 'summary' && out) {
    console.error('FAIL — summary mode writes to stdout; omit --out')
    process.exit(1)
  }
  if (mode === 'full' && !out) { console.log(rendered); return }
  if (mode === 'summary') { process.stdout.write(rendered); return }
  if (!isInsideReports(out, REPO) || !out.endsWith('.md')) {
    console.error(`FAIL — refusing to write outside reports/ or to a non-Markdown path: ${out}`)
    process.exit(1)
  }
  if (basename(out) !== `${reportStem(input)}.md`) {
    console.error(`FAIL — output filename ${basename(out)} does not pair with ${basename(input)}`)
    process.exit(1)
  }
  mkdirSync(dirname(out), { recursive: true })
  const temp = `${out}.tmp-${process.pid}`
  try {
    writeFileSync(temp, rendered, 'utf8')
    renameSync(temp, out)
  } catch (error) {
    try { if (existsSync(temp)) unlinkSync(temp) } catch { /* best effort */ }
    console.error(`FAIL — atomic write failed: ${error.message}`)
    process.exit(1)
  }
  console.log(`RENDERED ${out}`)
}

if (fileURLToPath(import.meta.url) === resolve(process.argv[1] || '')) main()
