import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import test from 'node:test'

import {
  FOUR_HOURS,
  acquireAuthoritativeStaging,
  hash,
  makeFiveYearAuthoritativePlan,
  makePredictorRegistry,
  ownHash,
  verifyAuthoritativeStaging,
  withHash,
} from '../tools/strategy-research-v5-data.mjs'
import {
  authoritativeArtifactBuild,
  canonicalFamilyCustodyRoot,
  canonicalExposureHeadPath,
  authoritativeExperimentFreeze,
  authoritativeFeatureBuild,
  authoritativeMetadataBuild,
  authoritativeOpportunityEnvelope,
  authoritativeResearchInit,
  authoritativeSearchGenetic,
  runAuthoritativeV5Cli,
} from '../tools/strategy-research-v5-authoritative.mjs'
import { evaluateSignalPredicateV5, makeEvaluatorSpecV5 } from '../tools/strategy-evaluator-v5.mjs'
import { makeCandidateSetV5, normalizeGeneSpace } from '../tools/strategy-research-v5.mjs'
import { makeV2Definition } from '../tools/strategy-research-v2.mjs'
import { makeAcceptanceContract, makeTrainingSelectionPolicy } from '../tools/strategy-research-v3.mjs'
import {
  readBehaviorDefinitionRegistryFile,
  readExposureHeadFile,
  readGeneticCheckpointFile,
  validateBehaviorDefinitionRegistry,
  validateGeneticArtifact,
  withHash as statisticalWithHash,
} from '../tools/strategy-research-v5-statistical.mjs'

const HOUR = 60 * 60 * 1000
const MINUTE = 60 * 1000
const jsonBytes = value => Buffer.from(`${JSON.stringify(value, null, 2)}\n`)
const jsonlRows = path => readFileSync(path, 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line))

test('physical BASE_ONLY price/funding research remains executable while optional market-flow is absent', async () => {
  const ignoredParent = resolve('.report-run')
  mkdirSync(ignoredParent, { recursive: true })
  const root = mkdtempSync(join(ignoredParent, 'v5-base-only-price-funding-'))
  const stagingRoot = join(root, 'staging')
  const parquetRoot = join(root, 'parquet')
  const recordRoot = join(root, 'records')
  const inputRoot = join(root, 'inputs')
  mkdirSync(inputRoot, { recursive: true })
  const writeJson = (name, value) => {
    const path = join(inputRoot, name)
    writeFileSync(path, jsonBytes(value))
    return path
  }
  let familyCustodyRoot = null

  try {
    const eventAt = Date.parse('2026-01-01T00:00:00.000Z')
    const signalEnd = eventAt + 8 * HOUR
    const fundingEnd = eventAt + 8 * HOUR
    const fundingSeriesEnd = signalEnd + FOUR_HOURS
    const capturedAt = '2026-08-24T12:00:00.000Z'
    const basePlan = makeFiveYearAuthoritativePlan({ asOf: capturedAt, rootReference: 'base-only-price-funding-fixture' })
    const signalTemplate = basePlan.series.find(series => series.asset === 'btc' && series.instrument === 'BINANCE_SPOT' && series.series_type === 'signal_bars')
    const unrelatedSignalTemplates = [
      basePlan.series.find(series => series.asset === 'btc' && series.instrument === 'BINANCE_USDM_PERPETUAL' && series.series_type === 'signal_bars'),
      basePlan.series.find(series => series.asset === 'eth' && series.instrument === 'BINANCE_SPOT' && series.series_type === 'signal_bars'),
    ]
    const fundingTemplate = basePlan.series.find(series => series.asset === 'btc' && series.series_type === 'funding_events')
    const metricTemplate = basePlan.series.find(series => series.asset === 'btc' && series.series_type === 'metrics_events' && series.interval === '4h')
    const signalSeries = {
      ...signalTemplate,
      start_at: new Date(eventAt).toISOString(),
      end_at: new Date(signalEnd).toISOString(),
      availability_cutoff_at: new Date(signalEnd + FOUR_HOURS).toISOString(),
      expected_event_count: 3,
    }
    const fundingSeries = {
      ...fundingTemplate,
      start_at: new Date(eventAt).toISOString(),
      end_at: new Date(fundingSeriesEnd).toISOString(),
      availability_cutoff_at: new Date(fundingSeriesEnd + MINUTE).toISOString(),
      cadence_segments: [],
      event_sequence_mode: true,
      event_driven: true,
      expected_step_ms: null,
      expected_event_count: null,
    }
    const unrelatedSignalSeries = unrelatedSignalTemplates.map(series => ({
      ...series,
      start_at: new Date(eventAt).toISOString(),
      end_at: new Date(signalEnd).toISOString(),
      availability_cutoff_at: new Date(signalEnd + FOUR_HOURS).toISOString(),
      expected_event_count: 3,
      required: false,
    }))
    const markSeries = basePlan.series.filter(series => series.series_type === 'mark_bars').map(series => ({
      ...series,
      start_at: new Date(eventAt).toISOString(),
      end_at: new Date(signalEnd).toISOString(),
      availability_cutoff_at: new Date(signalEnd + FOUR_HOURS).toISOString(),
      expected_event_count: 3,
    }))
    const metricSeries = {
      ...metricTemplate,
      start_at: new Date(eventAt).toISOString(),
      end_at: new Date(signalEnd).toISOString(),
      availability_cutoff_at: new Date(signalEnd + FOUR_HOURS).toISOString(),
      expected_event_count: 3,
      required: false,
    }
    const plan = withHash({ ...basePlan, series: [signalSeries, ...unrelatedSignalSeries, fundingSeries, ...markSeries, metricSeries] })

    let metricRequests = 0
    const response = (payload, { ok = true, status = 200 } = {}) => {
      const body = Buffer.from(JSON.stringify(payload))
      return {
        ok,
        status,
        headers: { get: name => String(name).toLowerCase() === 'date' ? capturedAt : null },
        arrayBuffer: async () => body,
      }
    }
    const fetchImpl = async requestUrl => {
      const url = new URL(requestUrl)
      if (url.hostname === 'data.binance.vision') {
        metricRequests++
        return response([], { ok: false, status: 404 })
      }
      const interval = url.searchParams.get('interval')
      const start = Number(url.searchParams.get('startTime') || eventAt)
      const end = Number(url.searchParams.get('endTime') || signalEnd)
      if (url.pathname.endsWith('/fundingRate')) {
        return response([
          { symbol: 'BTCUSDT', fundingTime: eventAt + 1, fundingRate: '0.00125', markPrice: '' },
          { symbol: 'BTCUSDT', fundingTime: fundingEnd + 1, fundingRate: '0.00050', markPrice: '' },
        ])
      }
      if (url.pathname.endsWith('/markPriceKlines')) {
        const step = interval === '1h' ? HOUR : FOUR_HOURS
        const first = interval === '1h' && start <= eventAt ? eventAt : start
        const last = interval === '1h' ? fundingEnd : signalEnd
        const rows = []
        for (let time = first; time <= last; time += step) rows.push([time, '123', '124', '122', '123', '1', time + step - 1, '123', 1, '1', '123', '0'])
        return response(rows)
      }
      if (url.pathname.endsWith('/klines')) {
        const step = interval === '1m' ? MINUTE : FOUR_HOURS
        const last = interval === '1m' ? end : signalEnd
        const closes = [100, 90, 100]
        const rows = []
        for (let time = start, index = 0; time <= last; time += step, index++) {
          const close = interval === '1m' ? 101 + index / 10 : closes[index]
          rows.push([time, String(close), String(close + 1), String(close - 1), String(close), '10', time + step - 1, '100000', 10, '5', '50000', '0'])
        }
        return response(rows)
      }
      throw new Error(`unexpected fixture endpoint ${url.pathname}`)
    }

    await assert.rejects(() => acquireAuthoritativeStaging({ plan, outputRoot: join(root, 'rejected-injected-production'), fetchImpl }), /rejects injected network transport/)
    const fixtureRoot = join(root, 'fixture-only-staging')
    const fixtureAcquisition = await acquireAuthoritativeStaging({ plan, outputRoot: fixtureRoot, outputRootReference: 'fixture-only-staging', fetchImpl, maxPages: 4, maxRows: 10_000, capturedAt, fixtureOnly: true })
    assert.throws(() => verifyAuthoritativeStaging({ manifest: fixtureAcquisition, root: fixtureRoot, plan, planSha256: plan.content_sha256 }), /fixture/i)
    const originalFetch = globalThis.fetch
    let acquisition
    try {
      globalThis.fetch = fetchImpl
      acquisition = await acquireAuthoritativeStaging({ plan, outputRoot: stagingRoot, outputRootReference: 'base-only-price-funding-staging', maxPages: 4, maxRows: 10_000 })
    } finally {
      globalThis.fetch = originalFetch
    }
    assert.equal(acquisition.fixture_only, false)
    assert.equal(acquisition.provenance, 'PUBLIC_ADAPTER_RECOMPUTED')
    assert.equal(acquisition.status, 'STAGING_COMPLETE', JSON.stringify({ unavailable_required: acquisition.unavailable_required, unavailable_optional: acquisition.unavailable_optional, captures: acquisition.captures?.map(row => ({ series_id: row.series_id, status: row.status, limitations: row.limitations })) }))
    assert.equal(acquisition.completion_scope, 'BASE_ONLY')
    assert.equal(acquisition.unavailable_required.length, 0)
    assert.equal(acquisition.unavailable_optional.length, 1)
    assert.match(acquisition.unavailable_optional[0], /metrics_events/)
    assert.ok(metricRequests > 0, 'the optional market-flow source was physically attempted and remained unavailable')

    const fundingCodeSha256 = hash('funding-signal-code')
    const fundingConfigSha256 = hash('funding-signal-config')
    const predictorRegistry = makePredictorRegistry({ predictors: [
      {
        id: 'price_close',
        scalar_type: 'number',
        source_field: 'close',
        source_family: 'price',
        source_timeframe: '4h',
        availability_derivation: 'completed_4h_close',
        pit_role: 'PREDICTOR',
        lookback_ms: 0,
        code_sha256: hash('price-close-code'),
        config_sha256: hash('price-close-config'),
      },
      {
        id: 'funding_signal',
        scalar_type: 'number',
        source_field: 'funding_rate',
        source_family: 'funding_events',
        source_timeframe: 'event',
        availability_derivation: 'latest_exact_settlement_before_completed_bar',
        pit_role: 'PREDICTOR',
        trade_scope: 'CONTEXT_ONLY',
        lookback_ms: 24 * HOUR,
        code_sha256: fundingCodeSha256,
        config_sha256: fundingConfigSha256,
        recipe: {
          module: 'builtin-pit-transform/1',
          kind: 'FIELD',
          source_field: 'funding_rate',
          source_series: 'funding_events',
          required_series_types: ['funding_events'],
          lookback_bars: 0,
          min_history: 1,
          window_policy: 'COMPLETED_OBSERVATIONS_ONLY',
          availability_policy: 'MAX_INPUT_AVAILABILITY',
          series_scope: 'SAME_ASSET_FUNDING_SERIES',
          asof_policy: 'LATEST_AVAILABLE_STRICTLY_BEFORE_DECISION',
          max_staleness_ms: 24 * HOUR,
          lag_bars: 0,
          resample_policy: 'LAST_AVAILABLE',
          context_only: true,
          current_observation_policy: 'INCLUDE_CURRENT_COMPLETED',
          excluded_window_bars: 0,
          module_code_sha256: fundingCodeSha256,
          module_config_sha256: fundingConfigSha256,
        },
      },
    ] })
    const precommitInput = {
      schema: 'strategy-precommit/1',
      precommit_id: 'base-only-price-funding',
      strategy_version: 'v5-base-only-fixture',
      created_at: '2026-01-01T00:00:00.000Z',
      stage: 'CORE_PREMISE',
      phenomenon: 'price strength conditioned on positive perpetual funding',
      economic_behavioral_mechanism: 'completed price and settled carry jointly identify the setup',
      participants: { forced_actor: 'leveraged traders', edge_provider: 'patient liquidity', edge_consumer: 'systematic crypto strategy' },
      persistence: 'short-lived after a completed signal bar',
      crowding_decay: 'decays as funding normalizes',
      direction: 'long',
      expression: 'BTC spot',
      holding_horizon: { min: 1, max: 3, unit: 'minutes' },
      expected_signal_frequency: { min: 0, max: 1, unit: 'fraction' },
      expected_win_rate: { min: 0, max: 1 },
      payoff: { average_win_r: { min: 1, max: 1 }, average_loss_r: { min: -1, max: -1 }, qualitative_shape: 'bounded target/stop fixture' },
      regimes: { expected_to_work: ['fixture'], expected_to_fail: ['fixture'] },
      failure_invalidation_mechanism: 'funding no longer conditions the opportunity set',
      required_inputs: [
        { input_id: 'completed-price', availability: { status: 'PIT' }, point_in_time: { status: 'PIT_SAFE' }, evidence_family: 'price', role: 'CORE' },
        { input_id: 'settled-funding', availability: { status: 'PIT' }, point_in_time: { status: 'PIT_SAFE' }, evidence_family: 'funding', role: 'CORE' },
      ],
      falsifier: { test: 'price/funding predicate produces no robust edge', null: 'no edge', rejection_thresholds: { minimum: 0 } },
      tradable_instrument_contract: { universe: 'CRYPTO_ONLY', instruments: [{ asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' }] },
      trade_assets: ['btc'],
      non_crypto_context_only: [],
      independence_replication_groups: [['btc']],
      role_of_composite_score: 'not used',
      status: 'FROZEN',
      content_sha256: null,
    }
    precommitInput.content_sha256 = ownHash(precommitInput)

    const planPath = writeJson('plan.json', plan)
    const acquisitionPath = writeJson('acquisition.json', acquisition)
    const predictorPath = writeJson('predictors.json', predictorRegistry)
    const precommitPath = writeJson('precommit.json', precommitInput)
    const authoredFeaturePath = writeJson('caller-features.json', [{ price_close: 999, funding_signal: 999 }])
    const featureOptions = {
      plan: planPath,
      acquisition: acquisitionPath,
      staging_root: stagingRoot,
      predictor_registry: predictorPath,
      precommit: precommitPath,
      record_root: recordRoot,
      requirements_out: join(root, 'price-funding-requirements.json'),
      coverage_out: join(root, 'price-funding-coverage.json'),
      out: join(root, 'price-funding-feature-source.json'),
      receipt: join(root, 'price-funding-feature-build-receipt.json'),
    }
    await assert.rejects(
      () => authoritativeFeatureBuild({ ...featureOptions, features: authoredFeaturePath }),
      /derives features internally|caller-authored/i,
    )

    const featureBuild = await authoritativeFeatureBuild(featureOptions)
    assert.equal(featureBuild.receipt.command, 'feature-build')
    assert.equal(featureBuild.receipt.status, 'COMPLETE')
    assert.equal(featureBuild.promoted_coverage.status, 'READY')
    assert.equal(featureBuild.promoted_coverage.optional_unavailable.length, 1)
    assert.match(featureBuild.promoted_coverage.optional_unavailable[0], /metrics_events/)
    const featureRows = jsonlRows(join(stagingRoot, featureBuild.feature_source.artifact.path))
    assert.ok(featureRows.every(row => row.asset === 'btc' && row.instrument === 'BINANCE_SPOT'), 'broad-plan ETH/perpetual rows must not enter the BTC-spot precommit')
    assert.deepEqual(featureRows.map(row => row.price_close), [100, 90, 100])
    assert.deepEqual(featureRows.map(row => row.funding_signal), [0.00125, 0.00125, 0.0005])
    assert.equal(featureRows[1].decision_time, new Date(fundingEnd).toISOString())
    assert.equal(featureRows[1].funding_signal, 0.00125, 'the funding event one millisecond after the decision must not leak into that decision')

    const metricRegistry = makePredictorRegistry({ predictors: [{
      id: 'open_interest',
      scalar_type: 'number',
      source_field: 'open_interest',
      source_family: 'metrics_events',
      source_timeframe: '4h',
      availability_derivation: 'completed_4h_metric_vintage',
      pit_role: 'PREDICTOR',
      trade_scope: 'CONTEXT_ONLY',
      lookback_ms: 0,
      code_sha256: hash('open-interest-code'),
      config_sha256: hash('open-interest-config'),
    }] })
    const metricRegistryPath = writeJson('metric-predictors.json', metricRegistry)
    await assert.rejects(
      () => authoritativeFeatureBuild({
        ...featureOptions,
        predictor_registry: metricRegistryPath,
        requirements_out: join(root, 'metric-requirements.json'),
        coverage_out: join(root, 'metric-coverage.json'),
        out: join(root, 'metric-feature-source.json'),
        receipt: join(root, 'metric-feature-build-receipt.json'),
      }),
      error => {
        assert.match(error.message, /coverage is blocked/i)
        assert.match(error.message, /metrics_events/i)
        return true
      },
    )

    const geneSpace = normalizeGeneSpace({
      genes: [{ name: 'funding_floor', type: 'continuous', min: 0.001, max: 0.002, step: 0.001, default: 0.001, usage: 'predicate:funding_signal:GTE' }],
    })
    const predicate = { all: [
      { predictor_id: 'price_close', op: 'GTE', value: 99 },
      { predictor_id: 'funding_signal', op: 'GTE', value: { $gene: 'funding_floor' } },
    ] }
    const evaluatorSpec = makeEvaluatorSpecV5({
      strategyFamily: 'base-only-price-funding',
      precommitSha256: precommitInput.content_sha256,
      geneSpace,
      predictorRegistry,
      predicate,
      candidateTemplate: {
        direction: 'long',
        instrument_type: 'spot',
        entry_policy: 'NEXT_BAR_OPEN',
        lifecycle_timeframe: '1m',
        max_lifecycle_ms: 3 * MINUTE,
        exit_policy: { type: 'TARGET_STOP', stop_price: 95, target_price: 110, collision_policy: 'ADVERSE_STOP_FIRST' },
        lifecycle: {
          max_lifecycle_ms: 3 * MINUTE,
          stop: { type: 'PERCENT', value: 0.05 },
          target: { type: 'R_MULTIPLE', multiple: 2 },
          partial_exits: [],
          trailing: null,
          sizing: { mode: 'FIXED_NOTIONAL', notional_usd: 100 },
          gap_policy: 'OPEN',
        },
        lifecycle_engine: 'strategy-v5-trade-lifecycle/1',
      },
      executionContract: {
        risk_convention: { mode: 'FIXED_RISK_BUDGET_USD', budget_usd: 10 },
        sizing_contract: { mode: 'FIXED_NOTIONAL_USD', notional_usd: 100, quantity_step: 0.001, min_notional_usd: 10 },
      },
    })
    const evaluatorPath = writeJson('evaluator-spec.json', evaluatorSpec)
    const metadataPolicy = withHash({
      schema: 'strategy-v5-spot-execution-policy/1',
      version: 1,
      status: 'FROZEN',
      created_at: capturedAt,
      plan_sha256: plan.content_sha256,
      precommit_sha256: precommitInput.content_sha256,
      evaluator_spec_sha256: evaluatorSpec.content_sha256,
      instrument: 'BINANCE_SPOT',
      research_window: { start_at: plan.window.start_at, end_at: plan.window.end_at },
      asset_contracts: [{
        asset: 'btc',
        symbol: 'BTCUSDT',
        contract_multiplier: 1,
        step_size: 0.001,
        min_qty: 0.001,
        max_qty: 100,
        min_notional: 5,
        max_notional: 1_000_000,
      }],
      cost_model: { taker_fee_rate: 0.001, slippage_bps: 2, impact_bps: 1 },
      outage_policy: 'FAIL',
      gap_policy: 'FILL_AT_OPEN',
      assumption_mode: 'RETROSPECTIVE_USER_BOUND_RESEARCH_ASSUMPTION',
      activation_eligible: false,
      limitations: ['NOT_HISTORICAL_BINANCE_FEE_OBSERVATIONS'],
    })
    const metadataPolicyPath = writeJson('spot-execution-policy.json', metadataPolicy)
    const metadataOptions = {
      plan: planPath,
      precommit: precommitPath,
      evaluator_spec: evaluatorPath,
      policy: metadataPolicyPath,
      output_root: join(root, 'metadata'),
      out: join(root, 'spot-metadata-bundle.json'),
      record_root: recordRoot,
      receipt: join(root, 'metadata-build-receipt.json'),
    }
    const metadataBuild = await authoritativeMetadataBuild(metadataOptions)
    assert.equal(metadataBuild.receipt.command, 'metadata-build')
    assert.equal(metadataBuild.receipt.status, 'COMPLETE')
    assert.equal(metadataBuild.metadata.contract_spec.evaluator_spec_sha256, evaluatorSpec.content_sha256)
    assert.equal(metadataBuild.metadata.contract_spec.records[0].step_size, 0.001)
    assert.equal(metadataBuild.metadata.fee_schedule.records[0].taker_fee_rate, 0.001)
    assert.equal(metadataBuild.metadata.execution_model.records[0].slippage_bps, 2)
    assert.equal(metadataBuild.metadata.execution_model.records[0].impact_bps, 1)
    assert.ok(metadataBuild.metadata.contract_spec.limitations.includes('NOT_ACTIVATION_EVIDENCE'))
    const repeatedMetadata = await authoritativeMetadataBuild(metadataOptions)
    assert.equal(repeatedMetadata.receipt.details.metadata_bundle_sha256, metadataBuild.receipt.details.metadata_bundle_sha256, 'an exact metadata-build rerun must be deterministic')

    const candidateSet = makeCandidateSetV5({
      geneSpace,
      candidates: [{ candidate_id: 'baseline', definition: { chromosome: { funding_floor: 0.001 }, predicate } }],
      precommitSha256: precommitInput.content_sha256,
      experimentSha256: hash('base-only-experiment'),
      objectiveContractSha256: hash('base-only-objective'),
      acceptanceSha256: hash('base-only-acceptance'),
      lineage: { fixture: 'physical-base-only-price-funding' },
      generator: 'FIXED_BASELINE',
    })
    const chromosome = candidateSet.candidates[0].definition.chromosome
    assert.deepEqual(featureRows.map(row => evaluateSignalPredicateV5(predicate, row, chromosome)), [true, false, false])
    assert.equal(evaluateSignalPredicateV5(predicate, { ...featureRows[0], funding_signal: 0.0005 }, chromosome), false, 'holding price fixed while lowering funding must switch the signal off')

    const genePath = writeJson('gene-space.json', geneSpace)
    const candidatePath = writeJson('candidates.json', candidateSet)
    const opportunityDomainPath = join(root, 'opportunity-domain.json')
    const opportunityEnvelopePath = join(root, 'opportunity-envelope.json')
    const opportunityHydrationPath = join(root, 'opportunity-hydration.json')
    const physicalEnvelopePath = join(root, 'physical-opportunity-envelope.json')
    const physicalHydrationPath = join(root, 'physical-opportunity-hydration.json')
    let opportunity
    try {
      globalThis.fetch = fetchImpl
      opportunity = await authoritativeOpportunityEnvelope({
        plan: planPath,
        acquisition: acquisitionPath,
        staging_root: stagingRoot,
        candidates: candidatePath,
        precommit: precommitPath,
        gene_space: genePath,
        predictor_registry: predictorPath,
        evaluator_spec: evaluatorPath,
        feature_source: featureBuild.feature_source_path,
        max_lifecycle_ms: 3 * MINUTE,
        hydrate: true,
        hydration_root: stagingRoot,
        max_pages: 4,
        max_rows: 10_000,
        record_root: recordRoot,
        domain_out: opportunityDomainPath,
        out: opportunityEnvelopePath,
        hydration_out: opportunityHydrationPath,
        physical_envelope_out: physicalEnvelopePath,
        physical_hydration_out: physicalHydrationPath,
        receipt: join(root, 'opportunity-envelope-receipt.json'),
      })
    } finally {
      globalThis.fetch = originalFetch
    }
    assert.equal(opportunity.receipt.command, 'opportunity-envelope')
    assert.equal(opportunity.receipt.status, 'COMPLETE')
    assert.equal(opportunity.envelope.windows.length, 1)
    assert.equal(opportunity.envelope.windows[0].episode_id, featureRows[0].episode_id)
    assert.equal(opportunity.physicalHydration.status, 'STAGING_COMPLETE')
    const hydrationInventory = opportunity.hydration.partition_inventory[0]
    const hydratedPhysicalRows = jsonlRows(join(stagingRoot, hydrationInventory.partition_path))
    const eventMillis = value => typeof value === 'number' ? value : Date.parse(value)
    assert.equal(Date.parse(hydrationInventory.min_event_time), eventMillis(hydratedPhysicalRows[0].event_time))
    assert.equal(Date.parse(hydrationInventory.max_event_time), eventMillis(hydratedPhysicalRows.at(-1).event_time))

    const config = withHash({
      schema: 'strategy-v5-config-fixture/1',
      version: 1,
      name: 'base-only-price-funding-config',
      execution_capacity_contract: { participation_cap: 0.5, order_notional_usd: 100, liquidity_source: 'BOUND_COMPLETED_BAR_QUOTE_VOLUME' },
      execution_liquidity_contract: { model: 'BOUND_COMPLETED_BAR_QUOTE_VOLUME', order_notional_usd: 100, observed_impact_bps: 0 },
    })
    const configPath = writeJson('config.json', config)
    const artifactBuild = await authoritativeArtifactBuild({
      plan: planPath,
      acquisition: acquisitionPath,
      physical_hydration: physicalHydrationPath,
      physical_envelope: physicalEnvelopePath,
      opportunity_domain: opportunityDomainPath,
      opportunity_envelope: opportunityEnvelopePath,
      opportunity_hydration: opportunityHydrationPath,
      feature_source: featureBuild.feature_source_path,
      staging_root: stagingRoot,
      parquet_root: parquetRoot,
      predictor_registry: predictorPath,
      precommit: precommitPath,
      gene_space: genePath,
      evaluator_spec: evaluatorPath,
      config: configPath,
      record_root: recordRoot,
      source_bundle_out: join(root, 'source-bundle.json'),
      staging_out: join(root, 'separated-staging.json'),
      out: join(root, 'separated-parquet.json'),
      receipt: join(root, 'artifact-build-receipt.json'),
    })
    assert.equal(artifactBuild.receipt.command, 'artifact-build')
    assert.equal(artifactBuild.receipt.status, 'COMPLETE')
    assert.equal(artifactBuild.parquet_manifest.envelope_sha256, opportunity.envelope.content_sha256)
    assert.equal(artifactBuild.parquet_manifest.artifacts.feature.row_count, 1)
    assert.equal(artifactBuild.parquet_manifest.artifacts.label.row_count, 1)
    assert.equal(artifactBuild.parquet_manifest.artifacts.execution.row_count, 1)
    assert.equal(artifactBuild.parquet_manifest.artifacts.mark.row_count, 0, 'spot research must not fabricate a derivative mark series')
    assert.equal(artifactBuild.parquet_manifest.storage_role, 'AUTHORITATIVE')
    const stagedExecutionRows = jsonlRows(join(stagingRoot, artifactBuild.staging_manifest.artifacts.execution.path))
    assert.equal(stagedExecutionRows[0].capacity_inputs.source, 'BOUND_COMPLETED_BAR_QUOTE_VOLUME')

    familyCustodyRoot = canonicalFamilyCustodyRoot(evaluatorSpec.strategy_family)
    const canonicalHeadPath = canonicalExposureHeadPath(evaluatorSpec.strategy_family)
    const researchInitOptions = {
      plan: planPath,
      parquet_manifest: join(root, 'separated-parquet.json'),
      parquet_root: parquetRoot,
      predictor_registry: predictorPath,
      evaluator_spec: evaluatorPath,
      precommit: precommitPath,
      gene_space: genePath,
      timeframe_requirements: featureOptions.requirements_out,
      opportunity_domain: opportunityDomainPath,
      opportunity_envelope: opportunityEnvelopePath,
      opportunity_hydration: opportunityHydrationPath,
      hydration_root: stagingRoot,
      exposure_head_out: canonicalHeadPath,
      out: join(root, 'statistical-genesis.json'),
      record_root: recordRoot,
      receipt: join(root, 'research-init-receipt.json'),
    }
    await assert.rejects(() => authoritativeResearchInit({ ...researchInitOptions, episodes: [{ forged: true }] }), /rejects caller-supplied episodes/i)
    const researchInit = await authoritativeResearchInit(researchInitOptions)
    assert.equal(researchInit.receipt.command, 'research-init')
    assert.equal(researchInit.receipt.status, 'COMPLETE')
    assert.equal(researchInit.artifact.metadata.artifact_role, 'GENESIS')
    assert.equal(researchInit.artifact.episodes.length, 1)
    assert.equal(researchInit.artifact.episodes[0].eligible, true)
    assert.deepEqual(researchInit.artifact.episodes[0].candidate_returns, {})
    assert.equal(researchInit.exposure_head.cumulative_k, 0)
    assert.equal(researchInit.exposure_head.dataset_sha256, artifactBuild.parquet_manifest.dataset_root_sha256)
    const repeatedInit = await authoritativeResearchInit(researchInitOptions)
    assert.equal(repeatedInit.exposure_head.content_sha256, researchInit.exposure_head.content_sha256, 'an exact rerun may reopen but never reset the cumulative head')

    const definition = makeV2Definition({
      precommit: precommitInput,
      strategy_id: 'base-only-price-funding',
      version: 'v001',
      stage: 'CORE_PREMISE',
      hypothesis_family: evaluatorSpec.strategy_family,
      candidate_template: evaluatorSpec.candidate_template,
      feature_contract: {
        series: [{
          series_id: 'btc-spot-price-funding-4h',
          asset: 'btc',
          asset_class: 'crypto',
          timeframe: '4h',
          context_only: false,
          tradable: true,
          point_in_time: { status: 'PIT_SAFE' },
        }],
        inputs: precommitInput.required_inputs,
      },
      tradable_instrument_contract: precommitInput.tradable_instrument_contract,
    })
    const definitionPath = writeJson('definition-v002.json', definition)
    const acceptanceContract = makeAcceptanceContract()
    const experimentPolicy = withHash({
      schema: 'strategy-v5-experiment-policy/1',
      version: 1,
      status: 'FROZEN',
      experiment_id: 'base-only-price-funding-development',
      created_at: '2026-01-01T00:00:00.000Z',
      stage: 'CORE_PREMISE',
      evidence_phase: 'DEVELOPMENT',
      acceptance_contract: acceptanceContract,
      chronology: {
        timezone: 'UTC',
        bar_convention: 'COMPLETED_4H_BOUNDARY_NEXT_1M_OPEN',
        seeds: [11, 23, 47],
        development_window: {
          start_at: new Date(eventAt).toISOString(),
          end_at: new Date(eventAt + 5 * HOUR).toISOString(),
        },
        monitoring_window: {
          start_at: new Date(eventAt + 6 * HOUR).toISOString(),
          end_at: new Date(eventAt + 7 * HOUR).toISOString(),
        },
      },
      portfolio_policy: { initial_equity: 10_000, max_concurrent: 1 },
      training_selection_policy: makeTrainingSelectionPolicy(),
    })
    const experimentPolicyPath = writeJson('experiment-policy.json', experimentPolicy)
    const experimentPath = join(root, 'experiment-v3.json')
    const experimentFreezeOptions = {
      precommit: precommitPath,
      definition: definitionPath,
      opportunity_envelope: opportunityEnvelopePath,
      candidates: candidatePath,
      parquet_manifest: join(root, 'separated-parquet.json'),
      evaluator_spec: evaluatorPath,
      metadata: metadataOptions.out,
      metadata_root: metadataOptions.output_root,
      experiment_policy: experimentPolicyPath,
      out: experimentPath,
      record_root: recordRoot,
      receipt: join(root, 'experiment-freeze-receipt.json'),
    }

    assert.throws(
      () => authoritativeExperimentFreeze({ ...experimentFreezeOptions, precommit_sha256: precommitInput.content_sha256 }),
      /rejects caller-supplied.*lineage is recomputed/i,
    )
    assert.throws(
      () => authoritativeExperimentFreeze({ ...experimentFreezeOptions, metrics: [{ caller_supplied: true }] }),
      /caller-supplied statistical output is rejected/i,
    )
    const tamperedPolicyPath = writeJson('tampered-experiment-policy.json', { ...experimentPolicy, experiment_id: 'tampered-without-rehash' })
    assert.throws(
      () => authoritativeExperimentFreeze({ ...experimentFreezeOptions, experiment_policy: tamperedPolicyPath }),
      /content hash is missing or tampered/i,
    )
    const wrongDefinition = structuredClone(definition)
    wrongDefinition.precommit.sha256 = hash('different-precommit')
    wrongDefinition.content_sha256 = ownHash(wrongDefinition)
    const wrongDefinitionPath = writeJson('wrong-definition.json', wrongDefinition)
    assert.throws(
      () => authoritativeExperimentFreeze({ ...experimentFreezeOptions, definition: wrongDefinitionPath }),
      /definition precommit hash mismatch/i,
    )
    const wrongCandidate = structuredClone(candidateSet)
    wrongCandidate.experiment_sha256 = hash('different-candidate-lineage')
    wrongCandidate.lineage.experiment_sha256 = wrongCandidate.experiment_sha256
    wrongCandidate.content_sha256 = ownHash(wrongCandidate)
    const wrongCandidatePath = writeJson('wrong-candidates.json', wrongCandidate)
    assert.throws(
      () => authoritativeExperimentFreeze({ ...experimentFreezeOptions, candidates: wrongCandidatePath }),
      /candidate set differs from the opportunity envelope/i,
    )

    const experimentFreeze = await runAuthoritativeV5Cli('experiment-freeze', experimentFreezeOptions)
    assert.equal(experimentFreeze.receipt.command, 'experiment-freeze')
    assert.equal(experimentFreeze.receipt.status, 'COMPLETE')
    assert.equal(experimentFreeze.experiment.schema, 'strategy-experiment/3')
    assert.equal(experimentFreeze.experiment.precommit_sha256, precommitInput.content_sha256)
    assert.equal(experimentFreeze.experiment.definition_sha256, definition.content_sha256)
    assert.equal(experimentFreeze.experiment.candidate_set_sha256, candidateSet.content_sha256)
    assert.equal(experimentFreeze.experiment.data_manifest_sha256, artifactBuild.parquet_manifest.content_sha256)
    assert.equal(experimentFreeze.experiment.feature_set_sha256, artifactBuild.parquet_manifest.artifacts.feature.sha256)
    assert.equal(experimentFreeze.experiment.label_set_sha256, artifactBuild.parquet_manifest.artifacts.label.sha256)
    assert.equal(experimentFreeze.experiment.executor_sha256, experimentFreeze.executor_identity.content_sha256)
    assert.deepEqual(experimentFreeze.experiment.required_assets, [{ asset: 'btc', asset_class: 'crypto', instrument: 'BINANCE_SPOT' }])
    const repeatedFreeze = authoritativeExperimentFreeze(experimentFreezeOptions)
    assert.equal(repeatedFreeze.experiment.content_sha256, experimentFreeze.experiment.content_sha256, 'experiment freeze must be deterministic')

    const checkpointPath = join(root, 'checkpoints', 'base-only-genetic-checkpoint.json')
    const searchOptions = {
      artifact: researchInitOptions.out,
      exposure_head: researchInitOptions.exposure_head_out,
      plan: planPath,
      acquisition: acquisitionPath,
      parquet_manifest: join(root, 'separated-parquet.json'),
      parquet_root: parquetRoot,
      predictor_registry: predictorPath,
      evaluator_spec: evaluatorPath,
      definition: definitionPath,
      experiment: experimentPath,
      precommit: precommitPath,
      gene_space: genePath,
      timeframe_requirements: featureOptions.requirements_out,
      metadata: metadataOptions.out,
      metadata_root: metadataOptions.output_root,
      checkpoint: checkpointPath,
      cache_root: join(root, 'search-cache'),
      opportunity_domain: opportunityDomainPath,
      opportunity_envelope: opportunityEnvelopePath,
      opportunity_hydration: opportunityHydrationPath,
      hydration_root: stagingRoot,
      workers: 2,
      record_root: recordRoot,
      out: join(root, 'genetic-run.json'),
      exposure_out: join(root, 'exposure-after-search.json'),
      candidate_out: join(root, 'genetic-candidates.json'),
      receipt: join(root, 'search-genetic-receipt.json'),
    }
    const swappedMetadataPolicy = withHash({
      ...metadataPolicy,
      cost_model: { ...metadataPolicy.cost_model, taker_fee_rate: 0.002 },
    })
    const swappedMetadataOptions = {
      ...metadataOptions,
      policy: writeJson('swapped-spot-execution-policy.json', swappedMetadataPolicy),
      output_root: join(root, 'metadata-swapped'),
      out: join(root, 'spot-metadata-bundle-swapped.json'),
      receipt: join(root, 'metadata-build-swapped-receipt.json'),
    }
    await authoritativeMetadataBuild(swappedMetadataOptions)
    await assert.rejects(
      () => authoritativeSearchGenetic({ ...searchOptions, metadata: swappedMetadataOptions.out, metadata_root: swappedMetadataOptions.output_root }),
      /executor lineage/i,
      'changing the frozen cost policy must require a different experiment identity',
    )
    const search = await authoritativeSearchGenetic(searchOptions)
    assert.equal(search.receipt.command, 'search-genetic')
    assert.equal(search.receipt.status, 'COMPLETE')
    assert.equal(search.run.config.mode, 'AUTHORITATIVE')
    assert.deepEqual(search.run.config.seeds, [11, 23, 47])
    assert.equal(search.run.seed_runs.length, 3)
    assert.ok(search.run.seed_runs.every(row => row.generations_completed >= 10), 'every frozen seed must complete at least ten generations')
    assert.equal(search.selected, null, 'one development episode cannot pass the frozen selection constraints')
    const infeasibleStable = search.confirmation.find(row => row.fitness.feasible === false && search.run.seed_stability.stable_aliases.includes(row.behavior_alias_sha256))
    assert.ok(infeasibleStable, 'the tiny smoke must exercise the hard-feasibility selection boundary')
    const forgedInfeasibleSelection = statisticalWithHash({
      ...search.run,
      selected_behavior_alias_sha256: infeasibleStable.behavior_alias_sha256,
      selected_seed_count: 2,
      selected: {
        behavior_sha256: infeasibleStable.behavior_sha256,
        behavior_alias_sha256: infeasibleStable.behavior_alias_sha256,
        chromosome: infeasibleStable.chromosome,
        fitness: infeasibleStable.fitness,
      },
    })
    assert.throws(() => validateGeneticArtifact(forgedInfeasibleSelection), /hard-feasibility gate/)

    const checkpoint = readGeneticCheckpointFile(checkpointPath)
    assert.equal(checkpoint.checkpoint_status, 'COMPLETE')
    assert.equal(checkpoint.seed_index, 3)
    assert.equal(checkpoint.exposure_predecessor_sha256, researchInit.exposure_head.content_sha256)
    const persistedHead = readExposureHeadFile(researchInitOptions.exposure_head_out)
    assert.equal(persistedHead.content_sha256, search.exposureHead.content_sha256)
    assert.equal(search.run.exposure_head_sha256, persistedHead.content_sha256)
    assert.ok(persistedHead.exposure_attempt_k >= search.run.evaluation_attempt_k)
    const registryHeadPath = join(familyCustodyRoot, 'behavior-definitions', 'behavior-definition-registry-head.json')
    const registryHead = readBehaviorDefinitionRegistryFile(registryHeadPath)
    validateBehaviorDefinitionRegistry(registryHead, { exposureHead: persistedHead })
    assert.equal(registryHead.entries.length, persistedHead.entries.length)
    assert.equal(registryHead.snapshot_content_sha256, search.behaviorDefinitionRegistry.content_sha256)
    assert.deepEqual(
      registryHead.entries.map(row => row.behavior_sha256).sort(),
      persistedHead.entries.map(row => row.behavior_sha256).sort(),
      'behavior registry and cumulative exposure HEAD must contain the same aliases',
    )
    await assert.rejects(
      () => authoritativeResearchInit({
        ...researchInitOptions,
        exposure_head_out: join(root, 'RESET-HEAD.json'),
        out: join(root, 'reset-genesis.json'),
        receipt: join(root, 'reset-genesis-receipt.json'),
      }),
      /exposure HEAD path is not canonical/i,
      'a second path must never reset cumulative family exposure after search',
    )
    const alternateRecordRootInit = await authoritativeResearchInit({
      ...researchInitOptions,
      record_root: join(root, 'records-2'),
      out: join(root, 'records-2-genesis.json'),
      receipt: join(root, 'records-2-genesis-receipt.json'),
    })
    assert.equal(alternateRecordRootInit.exposure_head_path, canonicalHeadPath)
    assert.equal(alternateRecordRootInit.exposure_head.cumulative_k, persistedHead.cumulative_k, 'changing an output record root must not reset cumulative family K')
  } finally {
    rmSync(root, { recursive: true, force: true })
    if (familyCustodyRoot) rmSync(familyCustodyRoot, { recursive: true, force: true })
  }
})
