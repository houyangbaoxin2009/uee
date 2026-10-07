#!/usr/bin/env bash
#
# Checks that the game tests that ran are the game tests that exist.
#
# Why this exists: the game test server ends with a line like
#
#     All 20 required tests passed :)
#
# and that sentence is true whatever the number is. It has been seen saying 7 and 10 instead of 20,
# while describing itself as successful — once when two Gradle invocations overlapped, and once under
# a full rebuild. A smaller number is not a failure to the runner, so a green run says only that
# everything that ran passed, and says nothing about how much ran.
#
# So the count is compared with the number of test methods in the suite. Run the tests with this, or
# run them however you like and pass the log as the first argument.

set -euo pipefail

SUITE="neoforge/src/main/java/org/uee/neoforge/gametest/UeeGameTests.java"
LOG="${1:-}"

if [ -z "$LOG" ]; then
    LOG="$(mktemp)"
    echo "Running the game tests; the log goes to $LOG"
    ./gradlew :neoforge:runGameTestServer --console=plain | tee "$LOG"
fi

EXPECTED="$(grep -cE 'public static void [A-Za-z0-9_]+\(GameTestHelper' "$SUITE")"
RAN="$(grep -aoE '[0-9]+ GAME TESTS COMPLETE' "$LOG" | grep -aoE '^[0-9]+' | head -1 || true)"

if [ -z "$RAN" ]; then
    echo "FAIL: the log has no 'GAME TESTS COMPLETE' line, so no batch finished:"
    echo "      $LOG"
    exit 1
fi

if [ "$RAN" != "$EXPECTED" ]; then
    echo "FAIL: $RAN game tests ran but the suite defines $EXPECTED."
    echo "      Everything that ran passed, which is not the same as everything running."
    echo "      The usual causes are two Gradle invocations overlapping, or a full rebuild while the"
    echo "      test discovery reads the class output. Run it alone and without --rerun-tasks."
    exit 1
fi

echo "OK: all $EXPECTED game tests ran."
