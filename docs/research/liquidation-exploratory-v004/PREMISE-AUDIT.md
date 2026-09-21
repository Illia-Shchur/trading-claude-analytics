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
