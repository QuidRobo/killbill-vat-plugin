#!/usr/bin/env bash
#
# Compiles the whole plugin against hand-written Kill Bill API stubs and runs the logic
# assertions, using nothing but a JDK.
#
# Why this exists: the real build needs Maven Central, and some CI and developer environments
# cannot reach it. This catches type errors, wrong method names and bad call shapes offline.
# It is NOT a substitute for `mvn clean install` against the real jars, and it cannot catch
# behavioural differences.
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

echo "Compiling plugin sources against stubs..."
javac -nowarn -d "$OUT" \
  $(find "$HERE/stubs" "$ROOT/vat-plugin/src/main/java" "$ROOT/vat-invoice-formatter/src/main/java" -name '*.java') \
  "$HERE/VatCoreTest.java"

echo "Running assertions..."
java -cp "$OUT" VatCoreTest
