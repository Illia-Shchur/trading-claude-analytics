#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ISOLATED_ROOT=/Users/eternal/.codex/worktrees/research-performance-20260908
RESEARCH_JAR=${1:-"$ISOLATED_ROOT/analytics-research/target/analytics-research-1.0.0-SNAPSHOT.jar"}
EXEC_JAR=${2:-"$ISOLATED_ROOT/analytics-cli/target/analytics-cli-1.0.0-SNAPSHOT-exec.jar"}
OUTPUT_DIR=${3:-"$REPO_ROOT/.report-run/performance-20260908/bounded-scale"}
PLAN_PATH=${4:-"$REPO_ROOT/.report-run/performance-20260908/full-geometry-development-probe-plan.json"}
HARNESS_SOURCE="$REPO_ROOT/.report-run/performance-20260908/BoundedScaleHarness.java"
HARNESS_CLASSES="$OUTPUT_DIR/harness-classes"
EXEC_ROOT="$OUTPUT_DIR/packaged-exec"
MEASURE_CONFIG="$OUTPUT_DIR/measure-command.json"
MEASURE_OBSERVER="$REPO_ROOT/.report-run/performance-20260908/measure-command.py"

if [ ! -f "$RESEARCH_JAR" ]; then
  echo "missing packaged research JAR: $RESEARCH_JAR" >&2
  exit 2
fi
if [ ! -f "$EXEC_JAR" ]; then
  echo "missing packaged executable JAR: $EXEC_JAR" >&2
  exit 2
fi
if [ ! -f "$PLAN_PATH" ]; then
  echo "missing frozen probe plan: $PLAN_PATH" >&2
  exit 2
fi

rm -rf "$HARNESS_CLASSES" "$EXEC_ROOT"
mkdir -p "$HARNESS_CLASSES" "$EXEC_ROOT" "$OUTPUT_DIR/tmp"
unzip -q -o "$EXEC_JAR" -d "$EXEC_ROOT"
if [ ! -f "$EXEC_ROOT/BOOT-INF/classpath.idx" ]; then
  echo "packaged executable has no BOOT-INF/classpath.idx: $EXEC_JAR" >&2
  exit 2
fi

# Keep the research module JAR explicit so BuildIdentityService hashes the
# isolated packaged evaluator. Every dependency comes from this executable's
# recorded Spring Boot classpath order; target/classes and Maven caches are
# intentionally absent. The extracted root supplies the package marker.
CLASSPATH="$HARNESS_CLASSES:$EXEC_ROOT:$RESEARCH_JAR"
while IFS= read -r classpath_line; do
  relative=$(printf '%s\n' "$classpath_line" | sed 's/^- "//; s/"$//')
  case "$relative" in
    BOOT-INF/lib/analytics-research-*.jar) continue ;;
  esac
  CLASSPATH="$CLASSPATH:$EXEC_ROOT/$relative"
done < "$EXEC_ROOT/BOOT-INF/classpath.idx"

{
  echo "research_jar=$RESEARCH_JAR"
  shasum -a 256 "$RESEARCH_JAR"
  echo "executable_jar=$EXEC_JAR"
  shasum -a 256 "$EXEC_JAR"
  echo "plan_file=$PLAN_PATH"
  shasum -a 256 "$PLAN_PATH"
} > "$OUTPUT_DIR/packaged-artifact-sha256.log"

javac --release 21 -cp "$CLASSPATH" -d "$HARNESS_CLASSES" "$HARNESS_SOURCE" \
  > "$OUTPUT_DIR/harness-compile.log" 2>&1

python3 - "$MEASURE_CONFIG" "$REPO_ROOT" "$OUTPUT_DIR" "$PLAN_PATH" "$CLASSPATH" <<'PY'
import json
import sys

config_path, repo, output, plan, classpath = sys.argv[1:]
config = {
    "argv": [
        "/usr/bin/time", "-l", "java", "-Xmx2g", "-XX:ActiveProcessorCount=2",
        "-Djava.io.tmpdir=" + output + "/tmp", "-cp", classpath,
        "com.tradinganalytics.research.v5.BoundedScaleHarness", repo, output, plan,
    ],
    "cwd": repo,
    "timeout_seconds": 900,
    "measurement_output": output + "/bounded-scale-measurement.json",
}
with open(config_path, "w", encoding="utf-8") as handle:
    json.dump(config, handle, indent=2)
    handle.write("\n")
PY

python3 "$MEASURE_OBSERVER" "$MEASURE_CONFIG" \
  > "$OUTPUT_DIR/measure-command.stdout.log" 2>&1
