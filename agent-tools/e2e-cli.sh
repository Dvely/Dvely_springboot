#!/usr/bin/env bash
#
# Drives the real CLI against a running Qeploy, the way CI and a terminal would.
#
# Unit tests prove the command logic; this proves the parts only a real server and a real terminal
# can settle — that the authorization layers refuse what they should, that the exit codes a pipeline
# branches on are the ones we document, and that a prompt with nobody to answer it fails fast
# instead of hanging.
#
# Deliberately never completes a deployment: it checks that the layers refuse or that the target is
# absent, which is the property that matters.
#
#   QEPLOY_E2E_URL=http://localhost:8099 \
#   QEPLOY_E2E_READ_TOKEN=qp_... QEPLOY_E2E_WRITE_TOKEN=qp_... ./e2e-cli.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="node $HERE/packages/cli/src/bin.js"
URL="${QEPLOY_E2E_URL:-http://localhost:8099}"
READ_TOKEN="${QEPLOY_E2E_READ_TOKEN:?READ 스코프 토큰이 필요합니다}"
WRITE_TOKEN="${QEPLOY_E2E_WRITE_TOKEN:-}"
export QEPLOY_API_URL="$URL"

fails=0
check() { # check <설명> <기대코드> <실제코드>
  if [ "$2" = "$3" ]; then printf 'OK  %s\n' "$1"
  else printf '!!  %s — 기대 %s, 실제 %s\n' "$1" "$2" "$3"; fails=$((fails + 1)); fi
}

run() { QEPLOY_TOKEN="$1" $BIN "${@:2}" >/dev/null 2>&1; echo $?; }

echo "── 읽기 (READ 토큰)"
check "목록 조회는 성공한다"            0 "$(run "$READ_TOKEN" projects)"
check "잘못된 ID 는 사용법 오류"        2 "$(run "$READ_TOKEN" project abc)"
check "알 수 없는 명령은 사용법 오류"   2 "$(run "$READ_TOKEN" nope)"

echo
echo "── 스코프 (서버가 강제한다)"
check "READ 토큰의 배포는 인증 실패로 끝난다" 3 "$(run "$READ_TOKEN" deploy 999999 --yes)"
check "잘못된 토큰도 인증 실패"               3 "$(run 'qp_definitely-not-a-real-token' projects)"

echo
echo "── 비대화형"
# A prompt written to a CI log has nobody to answer it; it must fail fast, not hang.
QEPLOY_TOKEN="$READ_TOKEN" timeout 15 $BIN deploy 1 </dev/null >/dev/null 2>&1
check "--yes 없이 파이프로 실행하면 즉시 거절" 2 "$?"

echo
echo "── 기동 가드"
QEPLOY_TOKEN= $BIN projects >/dev/null 2>&1
check "토큰 없으면 사용법 오류" 2 "$?"

if [ -n "$WRITE_TOKEN" ]; then
  echo
  echo "── 쓰기 (WRITE 토큰) — 인가는 통과하고 대상이 없어 실패해야 한다"
  check "배포는 403 이 아니라 대상 없음으로 실패" 1 "$(run "$WRITE_TOKEN" deploy 999999 --yes)"
  check "env:set 도 마찬가지"                     1 \
    "$(run "$WRITE_TOKEN" env:set 999999 A=b --scope PRODUCTION --yes)"
  check "스코프 없는 env:set 은 사용법 오류"      2 "$(run "$WRITE_TOKEN" env:set 1 A=b --yes)"
fi

echo
[ "$fails" -eq 0 ] && echo "전체 통과" || echo "실패 ${fails}건"
exit $((fails > 0))
