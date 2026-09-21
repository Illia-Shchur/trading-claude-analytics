# Verification — 2026-09-20

- All reactor test reports: 1569 tests, 0 failures, 0 errors, 1 skipped.
- Changed production code versus `4385b39`: **92.06% line coverage** (916/995) and **80.76% branch coverage** (554/686). Explicit 80%/80% thresholds passed.
- Real authenticated Coinalyze acquisition: 5,997 normalized daily records. Credential scan found no supplied key in changed files.
- Final executable replay: byte-identical output to the retained first actual-data diagnostic.
- Independent calculation reproduced every asset's eligible-day and long/short liquidation stress counts.
- Frozen specification/precommit byte hashes and durable copies remain unchanged.
- Independent read-only review found one output-overwrite issue, fixed with CREATE_NEW and regression-tested; no unresolved material finding after final review.

The clean reactor command was `./mvnw --batch-mode --no-transfer-progress clean install`. Its final run passed the data and research modules, then identified a CLI help-text expectation missing the new command. After that test-only correction, `./mvnw --batch-mode --no-transfer-progress install -rf :analytics-cli` passed the remaining CLI, compatibility and coverage modules. The aggregate report was regenerated across the whole reactor with `./mvnw --batch-mode --no-transfer-progress -pl analytics-coverage -am -DskipTests verify`; this reused the completed test execution data. An earlier run invalidated by overlapping compiler processes was discarded.

Coverage command:

```sh
python3 tools/check_new_code_coverage.py --base 4385b39 \
  --minimum 80 --branch-minimum 80 \
  --report analytics-coverage/target/site/jacoco-aggregate/jacoco.xml
```

These checks establish importer/diagnostic behavior, not historical point-in-time provenance, the trading strategy's profitability, or a qualified 60-day staged executor.
