#!/usr/bin/env bash
#
# Compiles the whole plugin against hand-written Kill Bill API stubs and runs the repository's
# real test classes, using nothing but a JDK.
#
# Why this exists: the real build needs Maven Central, and some CI and developer environments
# cannot reach it. This catches type errors, wrong method names and bad call shapes offline, and
# runs exactly the tests under src/test/java that `mvn test` runs, so the two cannot drift apart.
# It is NOT a substitute for `mvn clean install` against the real jars: the stubs mirror the API
# signatures, not the behaviour behind them.
#
# Stub signatures mirror killbill-api 0.54.0, killbill-plugin-api 0.27.3,
# killbill-base-plugin 5.1.9 and killbill-platform 0.41.18.
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/target"

rm -rf "$OUT" "$HERE/stubs"
mkdir -p "$OUT"

echo "Generating API stubs..."
python3 "$HERE/gen-stubs.py" "$HERE/stubs"

echo "Compiling plugin, formatter and tests against stubs..."
javac -nowarn -d "$OUT" \
  $(find "$HERE/stubs" "$HERE/testng" \
         "$ROOT/vat-plugin/src/main/java" "$ROOT/vat-plugin/src/test/java" \
         "$ROOT/vat-invoice-formatter/src/main/java" "$ROOT/vat-invoice-formatter/src/test/java" \
         -name '*.java') \
  "$HERE/TinyTestRunner.java"

# Every test class in the repository, discovered rather than listed, so a new one cannot be
# forgotten here.
TEST_CLASSES=$(for src in "$ROOT/vat-plugin/src/test/java" "$ROOT/vat-invoice-formatter/src/test/java"; do
  (cd "$src" && find . -name 'Test*.java')
done | sed -e 's|^\./||' -e 's|\.java$||' -e 's|/|.|g' | sort)

echo "Running $(echo "$TEST_CLASSES" | wc -l | tr -d ' ') test classes..."
java -cp "$OUT" TinyTestRunner $TEST_CLASSES
