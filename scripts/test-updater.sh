#!/usr/bin/env bash
# Pure Java behavioral regression tests; never invokes Gradle or Android builds.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
classes="$(mktemp -d "${TMPDIR:-/tmp}/sovietgram-updater-test.XXXXXX")"
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" \
  "$root/TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/remote/IndependentVersionComparator.java" \
  "$root/tests/updater/VersionComparatorTest.java"
java -Xmx64m -cp "$classes" VersionComparatorTest
