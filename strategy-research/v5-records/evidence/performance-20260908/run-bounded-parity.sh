#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ISOLATED_ROOT=/Users/eternal/.codex/worktrees/research-performance-20260908
RESEARCH_JAR=${1:-"$ISOLATED_ROOT/analytics-research/target/analytics-research-1.0.0-SNAPSHOT.jar"}
EXEC_JAR=${2:-"$ISOLATED_ROOT/analytics-cli/target/analytics-cli-1.0.0-SNAPSHOT-exec.jar"}
OUTPUT_DIR=${3:-"$REPO_ROOT/.report-run/performance-20260908/bounded-parity"}
HARNESS_SOURCE="$REPO_ROOT/.report-run/performance-20260908/BoundedParityHarness.java"
HARNESS_CLASSES="$OUTPUT_DIR/harness-classes"
EXEC_ROOT="$OUTPUT_DIR/packaged-exec"

if [ ! -f "$RESEARCH_JAR" ]; then
  echo "missing packaged research JAR: $RESEARCH_JAR" >&2
  exit 2
fi
if [ ! -f "$EXEC_JAR" ]; then
  echo "missing packaged executable JAR: $EXEC_JAR" >&2
  exit 2
fi

rm -rf "$HARNESS_CLASSES" "$EXEC_ROOT"
mkdir -p "$HARNESS_CLASSES" "$EXEC_ROOT"
unzip -q -o "$EXEC_JAR" -d "$EXEC_ROOT"
if [ ! -f "$EXEC_ROOT/BOOT-INF/classpath.idx" ]; then
  echo "packaged executable has no BOOT-INF/classpath.idx: $EXEC_JAR" >&2
  exit 2
fi

# Keep the research module JAR explicit so BuildIdentityService hashes the
# isolated packaged evaluator. Every dependency comes from the same packaged
# executable, in its recorded Spring Boot classpath.idx order. In particular,
# target/classes and the ambient Maven repository never enter this classpath.
# The package marker lives at the executable's root META-INF; include the
# extracted root so BuildIdentityService can load it while the evaluator class
# itself still resolves from the explicit research module JAR below.
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
} > "$OUTPUT_DIR/packaged-artifact-sha256.log"

javac --release 21 -cp "$CLASSPATH" -d "$HARNESS_CLASSES" "$HARNESS_SOURCE" \
  > "$OUTPUT_DIR/harness-compile.log" 2>&1
/usr/bin/time -l java -Xmx2g -XX:ActiveProcessorCount=2 -cp "$CLASSPATH" \
  com.tradinganalytics.research.v5.BoundedParityHarness "$REPO_ROOT" "$OUTPUT_DIR" \
  > "$OUTPUT_DIR/harness.stdout.log" 2> "$OUTPUT_DIR/harness.time.log"
