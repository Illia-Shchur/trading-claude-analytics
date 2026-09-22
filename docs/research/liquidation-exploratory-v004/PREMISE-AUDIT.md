# Premise fidelity audit — unchanged rules

The user describes market behaviour after sharp liquidation-driven moves, using OI and later price structure to choose continuation or reversal, staged entries, and a maximum 60-day lifecycle. The implemented strategy is a narrower delayed retest interpretation. This audit does not revise thresholds or choose rules by historical profit.

## Source findings

| Layer | Implemented requirement | Implication for the premise |
|---|---|---|
| Observation | Completed daily Binance liquidation total, usable at bucket start +48h; strictly above the previous 90-day side-specific 95th percentile | Tests delayed stress response, not immediate cascade timing or a market-wide liquidation map. |
| Event geometry | Largest absolute H4 body must move at least 2 pre-event H4 ATR with at least 5% base-quantity OI decline, or 72h move at least 4 ATR with the OI decline; fast takes precedence if both qualify, and chosen geometry must break prior range | Large liquidations alone are insufficient; multiple conditions must align in the chosen interval. Another intraday candle is not searched if the largest body fails. |
| Branch | Two consecutive post-availability H4 closes choose continuation or reversal; subsequent opposite structure cancels the setup | Conditional branches exist, but the event cannot switch branch after the first choice. |
| First entry | H1 bar touches boundary ±0.25 ATR, closes on trade side and beyond preceding H1 extreme; execution at decision +1h within 0.5 ATR chase cap | The entry is a specific retest-and-break pattern, not any confirmed pullback. |
| Initial stop | Three-H1-bar structural extreme plus 0.1 pre-event ATR buffer | A 60-day ceiling does not imply a wide swing stop or a minimum duration. Stops may end positions within hours. |
| Reversal exit | Frozen prior-range midpoint; execution requires net reward at least net risk after costs | Small/late remaining recoveries may be excluded even if a broad opposite-shock response exists. |
| Addition | Five-H4-bar pivot whose center starts after the last fill, favorable H4, then H1 confirmation; one frozen zone per tranche | Pivot confirmation takes more than 12h after fill; short-lived positions cannot add. |
| Trail versus addition | Continuation trails three H4 bars; a frozen pivot made unsafe by the stop remains invalid | A strong move can tighten the stop before a permitted addition retest. Later pivots do not replace the locked zone. |
| Macro | Only gates later tranches | Cannot explain zero first-entry trades; absent additions provide no macro-effect evidence. |

These restrictions are substantially present in the frozen v001 specification; they are not automatically coding errors. Independent review found no reversed long/short inequality or impossible reversal rule. Counts from the passive trace will identify which restrictions actually bind.

## Open contract ambiguity

The v001 specification says skip a reversal whose midpoint was already reached. The pre-fill code checks the current confirmation bar/current close, but does not latch an earlier midpoint touch followed by retracement. This is a potential specification mismatch. The unchanged-rule audit retains existing behavior and flags it for a separately tested correction; it is not an explanation established for the sparse results.

## Interpretation

Broad 1/3/7-day opposite-shock responses cannot validate this narrower entry-and-exit strategy. More correlated assets expand coverage but do not multiply independent evidence automatically. Gate counts must distinguish source rows, stress sides, unique stress days, admitted setups, repeated hourly checks, intents, execution attempts and filled positions. Different filter counts can overlap and must not be added as unique lost opportunities.

No rule is relaxed in this study. A later redesign should be driven by which behaviour the user intends to trade, frozen before evaluating its returns, and retain the full exposure history.

## Original four: measured attrition

The unchanged original-four audit reproduces all 110 numeric comparison checks against v003. Within the modeled-availability decision window:

| Step | Unique count |
|---|---:|
| Daily liquidation stress dates across assets | 676 |
| Qualified price/OI events | 88 |
| Admitted setups after occupied-asset suppression | 73 |
| Setups with an H1 entry confirmation | 7 |
| Filled positions | 5 |

Price/OI geometry removes 588 of 676 stress dates (about 87%); the 88 surviving events include 15 suppressed by an occupied asset. The 73 admitted setups select 66 continuations (46 shorts, 20 longs) and seven reversals (six longs, one short). Of these setups, 57 expire without entry confirmation, 11 invalidate the chosen branch without rerouting, and five become filled positions. The two confirmed but unfilled setups are included in these final dispositions, not an extra count.

Repeated hourly tests are not unique opportunities: 4,306 hourly checks first fail the zone-touch requirement, versus 17 first failing the trade-side close and 71 first failing the preceding-hour break. Two checks are suppressed because an intent is already pending. Several failures can occur on the same bar; all-failure totals must not be added to first-failure totals.

The seven confirmed setups emit ten fill attempts. All five rejected attempts fail the maximum-chase-distance cap; rejection attempts can repeat within a setup. Two reversal longs did confirm (ETH on January 26, 2023 and AAVE on July 7, 2024), but both failed the next-hour chase cap; the midpoint reward/risk test was not their observed execution blocker. All five filled positions are short continuations. Four positions never form an eligible post-fill pivot before closing; the longest BTC position forms its frozen pivot but later loses the safe addition zone to the trailing stop. No addition reaches the macro gate.

This identifies two distinct restrictions: price/OI qualification is stringent, and the surviving events rarely revisit the narrow frozen boundary zone after delayed observation and H4 confirmation. It does not show that removing either condition would improve returns.

An additional price-only attribution calculation measures the absolute gap from the second H4 confirmation close to the frozen boundary, divided by pre-event H4 ATR. The median is **4.26 ATR for the 66 continuations** and **2.09 ATR for the seven reversals**, while the entry zone extends only **0.25 ATR** on either side of the boundary. At branch confirmation, 64/66 continuations and 6/7 reversals lie outside that zone. This uses no subsequent returns and does not test an alternative rule. It reinforces that the specified entry requires a substantial revisit after confirmation.

## Expanded nine: measured attrition

The expanded unchanged-rule replay contains 1,407 stress asset-days, 179 qualified events, 150 admitted setups, 12 confirmed setups and eight fills from 16 attempts. Geometry rejects 1,228 dates; 115 setups expire and 27 invalidate their branch. All eight fills are short continuations; no additions qualify. Median confirmation distance to the boundary is 4.15 ATR for 137 continuations and 1.40 ATR for 13 reversals, with 134 and 11 respectively outside the ±0.25 ATR zone. The broader universe confirms the same restrictive interpretation; it does not validate a replacement rule. See `RESULTS.md` and `results.json` for all variants, costs and dependence.
