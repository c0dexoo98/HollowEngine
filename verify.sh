#!/usr/bin/env bash
# HollowEngine 2 — full CI: build all modules, selftest, 5 regression suites.
set -u
JAVAC=${JAVAC:-/home/z/my-project/tools/jdk-21.0.13+9/bin/javac}
JAVA=${JAVA:-/home/z/my-project/tools/jdk-21.0.13+9/bin/java}
cd "$(dirname "$0")"

rc=0
bold()  { printf '\033[1m%s\033[0m\n' "$1"; }
green() { printf '\033[0;32mOK\033[0m  %s\n' "$1"; }
red()   { printf '\033[0;31mFAIL\033[0m  %s\n' "$1"; rc=1; }

bold "== build (engine + editor + cli)"
mkdir -p build-out/engine build-out/editor build-out/cli build-out/verify
SRCS=$(find engine/src editor/src cli/src -name '*.java')
$JAVAC -encoding UTF-8 -nowarn -d build-out/engine $(find engine/src -name '*.java') 2>/dev/null \
  && $JAVAC -encoding UTF-8 -nowarn -cp build-out/engine -d build-out/editor $(find editor/src -name '*.java') 2>/dev/null \
  && $JAVAC -encoding UTF-8 -nowarn -cp build-out/engine:build-out/editor -d build-out/cli $(find cli/src -name '*.java') 2>/dev/null \
  && green "build" || red "build"
[ $rc -eq 0 ] || { bold "VERIFY: FAILED"; exit 1; }

CP="build-out/cli:build-out/engine:build-out/editor"
bold "== selftest"
out=$($JAVA -cp "$CP" com.hollow.cli.Main selftest 2>&1) \
  && echo "$out" | tail -1 && green "selftest" || { echo "$out" | tail -3; red "selftest"; }

bold "== regression suites"
for s in VerifyH2Core VerifyH2Script VerifyH2Scene VerifyH2Render VerifyH2Editor VerifyH2Physics; do
  $JAVAC -encoding UTF-8 -nowarn -cp build-out/engine:build-out/editor -d build-out/verify verify/$s.java 2>/dev/null
  out=$($JAVA -cp build-out/verify:$CP "$s" 2>&1)
  if echo "$out" | grep -q "ALL .* TESTS PASSED"; then
    n=$(echo "$out" | grep -oE "ALL [0-9]+" | grep -oE "[0-9]+")
    green "$s ($n)"
  else
    echo "$out" | grep "^FAIL" | head -5
    red "$s"
  fi
done

bold "== smoke: project lifecycle"
SMOKE=$(mktemp -d)
$JAVA -cp "$CP" com.hollow.cli.Main new "$SMOKE/game" >/dev/null 2>&1 && \
$JAVA -cp "$CP" com.hollow.cli.Main render "$SMOKE/game" >/dev/null 2>&1 && \
$JAVA -cp "$CP" com.hollow.cli.Main run "$SMOKE/game" --frames 30 2>/dev/null | grep -q "spin: started" && \
[ -s "$SMOKE/game/frame.png" ] \
  && green "new/render/run/png" || red "smoke lifecycle"
rm -rf "$SMOKE"

if [ $rc -eq 0 ]; then bold "VERIFY: ALL GREEN"; else bold "VERIFY: FAILED"; fi
exit $rc
