#!/usr/bin/env bash
# HollowEngine v2.6.0 — one-shot CI: build + selftest + regression suites + demo asserts.
# Usage: bash verify.sh [jdk-bin-dir]
# Everything must be green; exit code != 0 on any failure.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JDK="${1:-/home/z/my-project/tools/jdk-21.0.13+9/bin}"
JAVA="$JDK/java"; JAVAC="$JDK/javac"
OUT="$ROOT/build-out"
VDIR="${VERIFY_DIR:-$ROOT/verify}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BOLD='\033[1m'; NC='\033[0m'
FAILED=0
step(){ echo -e "\n${BOLD}== $1 ==${NC}"; }
ok(){ echo -e "${GREEN}OK${NC}  $1"; }
bad(){ echo -e "${RED}FAIL${NC} $1"; FAILED=1; }

[ -x "$JAVA" ] || { echo "no java at $JAVA (pass jdk dir as arg1)"; exit 2; }

# ---------- 1) build ----------
step "build (engine + cli)"
mkdir -p "$OUT/engine" "$OUT/cli" "$OUT/stubs"
STUBSRC="${HOLLOW_STUB:-/home/z/my-project/tools/android-stub/android/opengl/GLES30.java}"
if [ -f "$STUBSRC" ]; then
  "$JAVAC" -encoding UTF-8 -d "$OUT/stubs" "$STUBSRC" || { bad "stubs"; exit 1; }
else
  echo "(no android stub at $STUBSRC — building without; engine may fail if it imports GLES30)"
fi
ESRC=$(find "$ROOT/engine/src/main/java" -name '*.java')
"$JAVAC" -encoding UTF-8 -nowarn -d "$OUT/engine" -cp "$OUT/stubs" $ESRC 2>/tmp/verify_engine_err.txt \
  || { bad "engine compile"; head -30 /tmp/verify_engine_err.txt; exit 1; }
CSRC=$(find "$ROOT/cli/src/main/java" -name '*.java')
"$JAVAC" -encoding UTF-8 -nowarn -d "$OUT/cli" -cp "$OUT/engine:$OUT/stubs" $CSRC 2>/tmp/verify_cli_err.txt \
  || { bad "cli compile"; head -30 /tmp/verify_cli_err.txt; exit 1; }
ok "build"

# ---------- 2) selftest (all batches incl. v2.6 net/nav) ----------
step "selftest"
if timeout 300 "$JAVA" -cp "$OUT/engine:$OUT/cli" com.hollow.cli.Main selftest > /tmp/verify_selftest.log 2>&1; then
  ok "selftest ($(grep -c '^PASS' /tmp/verify_selftest.log) checks)"
else
  bad "selftest"; grep -E 'FAIL|RESULT' /tmp/verify_selftest.log | head -20
fi

# ---------- 3) regression suites ----------
step "regression suites (math/infra/audio/physics/net/nav/gameplay/platform + bench)"
mkdir -p "$OUT/verify"
for suite in VerifyMath2 VerifyInfra VerifyAudio VerifyPhysics VerifyNet VerifyNav VerifyGameplay VerifyPlatform VerifyAaa VerifyV5 VerifyV6 VerifyV7 VerifyV8 VerifyV9 VerifyV10 BenchCore; do
  src="$VDIR/$suite.java"
  [ -f "$src" ] || { bad "$suite (missing $src)"; continue; }
  if "$JAVAC" -encoding UTF-8 -nowarn -cp "$OUT/engine" -d "$OUT/verify" "$src" 2>/tmp/verify_${suite}.err.txt \
     && timeout 120 "$JAVA" -cp "$OUT/verify:$OUT/engine" "$suite" > /tmp/verify_${suite}.log 2>&1; then
    if grep -q "ALL .* TESTS PASSED\|BENCH DONE" /tmp/verify_${suite}.log; then
      ok "$suite ($(grep -c '^PASS' /tmp/verify_${suite}.log) checks)"
    else
      bad "$suite (no success banner)"; tail -5 /tmp/verify_${suite}.log
    fi
  else
    bad "$suite"; head -12 /tmp/verify_${suite}.err.txt; tail -8 /tmp/verify_${suite}.log 2>/dev/null
  fi
done

# ---------- 4) demo scene asserts ----------
step "demo asserts"
for demo in demo-fps engine-tests; do
  if timeout 120 "$JAVA" -cp "$OUT/engine:$OUT/cli" com.hollow.cli.Main run -p "$demo" --ticks 300 --assert tests.json > /tmp/verify_demo.log 2>&1 \
     && grep -q "RESULT: OK" /tmp/verify_demo.log; then
    ok "$demo ($(grep -c '^PASS' /tmp/verify_demo.log) asserts)"
  else
    bad "$demo"; tail -8 /tmp/verify_demo.log
  fi
done

# ---------- summary ----------
echo
if [ $FAILED -eq 0 ]; then
  echo -e "${GREEN}${BOLD}VERIFY: ALL GREEN${NC}"
  exit 0
else
  echo -e "${RED}${BOLD}VERIFY: FAILURES ABOVE${NC}"
  exit 1
fi
