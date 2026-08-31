package com.tradinganalytics.core.lib;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Frozen inventory contract for the repository-wide Java facade of
 * {@code tools/lib.mjs} and the executable vectors in {@code tools/selftest.mjs}.
 *
 * <p>The implementation remains split along domain boundaries; this class owns
 * only the aggregate surface and selftest cardinalities used to prevent a
 * partial port from being mistaken for complete coverage.</p>
 */
public final class ToolchainSelftestContract {
    public static final int LIB_EXPORT_COUNT = 140;
    public static final int LIB_FUNCTION_EXPORT_COUNT = 101;
    public static final int LIB_VALUE_EXPORT_COUNT = 39;
    public static final int SELFTEST_EQ_COUNT = 543;
    public static final int SELFTEST_OK_COUNT = 367;
    public static final int SELFTEST_VECTOR_COUNT = 910;
    public static final int SELFTEST_UNIQUE_NAME_COUNT = 908;

    public static final Set<String> LIB_EXPORT_NAMES = Set.of(("""
            COMPANION_FR_EPOCH DISCRETION_EPOCH ENTRY_PRICE_EPOCH EPOCHS
            FK_D5_MAX_STOP_DISTANCE_PCT FK_DISCRETION FK_SCORE_UNLOCK FK_V_GATES
            FR_B_GATE_BASIS FR_CHANNEL_B FR_DISCRETION FR_GATE_FLOORS FR_MAX_PER_ASSET_PCT
            FR_MECH_STOP_PCT FR_MIN_STOP_ADR_MULT FR_NONCRYPTO_CLASS FR_NONCRYPTO_NA FR_S5
            FR_SCORE_UNLOCK FR_SCORE_UNLOCK_B GATE_MEASUREMENT_EPOCH LEDGER_ASSET_ALIASES
            MACHINE_BLOCK_EPOCH NONCRYPTO_SCHEMA_EPOCH POSITION_FRESHNESS POSITION_SNAPSHOT_SCHEMA
            REPORT_FILE_RE REPORT_PHASE_DECISIONS REPORT_PHASE_INSTRUMENT_CLASSES
            REPORT_PHASE_REGISTRY_SCHEMA REPORT_PHASE_REGISTRY_VERSION REPORT_ZONE ROUNDING
            SIGNAL_FEED_SCHEMA US_MARKET_HOLIDAYS _internal fk fr frB
            adr aggregateFlowRows aggregateValueSnapshots alignSeries applicableReportPhases basisBlock
            basisForPosition borrowBlock breadth200Block buildReportPhaseRegistry canonicalJSON
            canonicalReportPhaseTag ceilThresholds coinbasePremiumBlock consecutiveRun corrSurcharge
            correlationFromCloses correlationRegime custodyForPosition d5StopCheck dailyTrend
            deribitVolBlock discretionValid distributionStats drawdownPct entryLooksLikeFill evCheck
            feedChanged fillPrice fkPhasesUnlockedByScore fngStreak frChannel frCompanion frComposite
            frNonCryptoClass frPhasesUnlockedByScore frRatchetCheck frStallConfirmation frStopBand
            frThresholds frUnlockLadder fundingBlock gateMask inferChannel inferDiscretion isTradingDay
            legSpec localToUtcISO logReturns marketFlowBlock mechanicalScore median netLiquidity
            nextNTradingDays oi90dBlock oiWeightedFundingSnapshots onchainDistributionBlock pctChange
            pearson percentileRank positionForAsset positionFreshness positioningBlock
            positionSnapshotCheck positionSnapshotFreshness proximityPanel ratchetCheck realizedVol
            realizedVolBlock reportFileMeta reportPhaseRegistryIssues reportPhaseTagPrefix
            reportTagChannel resampleSnapshotsToCandles rollingBouncePct rollingDrawdownFromATH
            rollingRealizedVol rollingSMADistance rollingTrailingHighDistance rollingWilderRSI
            roundScore s5StopCheck schemaEpochOf sentimentProxyBlock shortEV shortForPosition
            signalRubric sma smaSlope snapshotDigestPayload spotPanel stablecoinBlock stdev
            stopCoherence tradingDaysBetween trancheFilled tripwireDiff unlockFor weekdayOf weightedEV
            wilderRSI
            """).trim().split("\\s+"));

    /** Java spellings retained where an established domain facade already owns the behavior. */
    public static final Map<String, String> EXPLICIT_FACADE_ALIASES = Map.ofEntries(
            Map.entry("buildReportPhaseRegistry", "ReportPhaseRegistry.build"),
            Map.entry("marketFlowBlock", "MarketFlowPanel.build"),
            Map.entry("pctChange", "MarketSeriesAnalytics.percentChange"),
            Map.entry("reportPhaseRegistryIssues", "ReportPhaseRegistry.issues"),
            Map.entry("rollingBouncePct", "MarketSeriesAnalytics.rollingBouncePercent"),
            Map.entry("rollingDrawdownFromATH", "MarketSeriesAnalytics.rollingDrawdownFromAth"),
            Map.entry("rollingSMADistance", "MarketSeriesAnalytics.rollingSmaDistance"),
            Map.entry("rollingWilderRSI", "MarketSeriesAnalytics.rollingWilderRsi"),
            Map.entry("shortEV", "ComputeMath.shortEv"),
            Map.entry("stdev", "ComputeMath.sampleStdev"),
            Map.entry("weightedEV", "ComputeMath.weightedEv"),
            Map.entry("wilderRSI", "ComputeMath.wilderRsi"));

    /** Domain owners which collectively implement the complete lib.mjs export surface. */
    public static final List<String> REPOSITORY_FACADE_OWNERS = List.of(
            "com.tradinganalytics.core.compute.ComputeMath",
            "com.tradinganalytics.core.lib.ToolchainSupport",
            "com.tradinganalytics.marketdata.FundingAnalytics",
            "com.tradinganalytics.marketdata.MarketContextAnalytics",
            "com.tradinganalytics.marketdata.MarketFlowAggregation",
            "com.tradinganalytics.marketdata.MarketFlowPanel",
            "com.tradinganalytics.marketdata.MarketSeriesAnalytics",
            "com.tradinganalytics.marketdata.SnapshotPanels",
            "com.tradinganalytics.reporting.ReportPhaseRegistry",
            "com.tradinganalytics.reporting.position.PositionSnapshots");

    public static final Map<String, Integer> SELFTEST_DUPLICATE_NAMES = Map.of(
            "FR-B has no Phase 3", 2,
            "...claiming nothing about where the coins are", 2);

    private ToolchainSelftestContract() {
    }
}
