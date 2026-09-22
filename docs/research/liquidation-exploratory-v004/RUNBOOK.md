# Reproducing the unchanged-rule audit

The frozen policy, precommit and source manifests are immutable. This experiment is DEVELOPMENT research. Do not retune rules or describe this replay as forward testing.

## Inputs and executable

The durable local bundle is `/Users/eternal/Desktop/Trading Claude Analytics/.research-run/liquidation-exploratory-v004/`. It contains normalized inputs, archive/checksum receipts, the data freeze, immutable executable revisions, successful and failed run receipts, and verification evidence. Credentials are excluded. Input-manifest and data-freeze paths resolve relative to their bundle location; the command below uses repository-relative policy and bundle paths. Reproduce from the repository with the bundle at `.research-run/liquidation-exploratory-v004/`. The legacy-four comparison also needs the retained v003 bundle and its unchanged committed policy/freeze.

The final results and verification receipts identify the exact runnable CLI JAR and SHA-256. A research library JAR is not runnable. All run commands must supply the actual executor SHA and frozen policy/input/data SHA values; never disable freeze checks.

```sh
java -Xmx4g -jar <retained-executable.jar> strategy-research-v5 liquidation-hourly-run \
  --input .research-run/liquidation-exploratory-v004/input-manifest.json \
  --policy docs/research/liquidation-exploratory-v004/exploratory-policy.json \
  --policy-freeze docs/research/liquidation-exploratory-v004/FREEZE-MANIFEST.json \
  --data-freeze .research-run/liquidation-exploratory-v004/data-freeze.json \
  --expected-policy-byte-sha256 20e9b5be92ea486f45344f8dba669263760b9fc49fcdacf3b014562c2a840972 \
  --expected-input-byte-sha256 6838545b3a2cff527994d42871ab94251c805b70b2021f3144d72e6605ed37f2 \
  --expected-data-freeze-byte-sha256 fb8c6ba67633fa20cab615af708f9443c20f0a02750ffc422e662bd35a221804 \
  --expected-executor-byte-sha256 <retained-executable-sha256> \
  --run-root .research-run/liquidation-exploratory-v004/expanded-runs \
  --rerun-reason '<specific reason for this additional exposure>'
```

Every rerun is retained. Original-four equivalence compares all six account simulations, including doubled execution costs, and their positions, equity curves, events and fixed response diagnostics. Passive entry audit fields may differ between diagnostic repairs; trading outputs must not.

## Verification

Run the affected Java tests and the clean reactor install. Then evaluate changed production-code coverage against the reviewed base with explicit 80% line and branch floors:

```sh
./mvnw --batch-mode --no-transfer-progress clean install
python3 tools/check_new_code_coverage.py \
  --base d4e3e9b4924b24162a9db993ab6613c51387c3b2 \
  --report analytics-coverage/target/site/jacoco-aggregate/jacoco.xml \
  --minimum 80 --branch-minimum 80
python3 -m unittest tools.test_acquire_liquidation_exploratory_v004 tools.test_acquire_liquidation_exploratory_v003
```

For a small repair applied after a completed stable-source clean build, preserve that build receipt, run the affected tests against final source, regenerate aggregate coverage, and disclose that sequence. Do not edit freeze-sensitive Java source during an active clean test run.
