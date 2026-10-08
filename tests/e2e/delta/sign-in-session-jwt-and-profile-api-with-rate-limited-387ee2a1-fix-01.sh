#!/usr/bin/env bash
# Delta test — fix-01: exactly one 422 entry per offending field on POST /api/auth/login
set -u
B="${1:-http://127.0.0.1:8001}"
fail=0

check() { # name json-body expected_entries
  local name="$1" body="$2" want="$3"
  local got
  got=$(curl -s -X POST "$B/api/auth/login" -H 'Content-Type: application/json' -d "$body" \
    | /workspace/.worktrees/sign-in-session-jwt-and-profile-api-with-rate-limited-387ee2a1/.venv/bin/python -c \
      'import sys,json; d=json.load(sys.stdin)["detail"]; print(len(d))')
  if [ "$got" = "$want" ]; then
    echo "PASS $name: $got entries"
  else
    echo "FAIL $name: expected $want entries, got $got"; fail=1
  fi
}

check "missing-email"        '{"password":"x"}' 1
check "missing-password"     '{"email":"a@b.com"}' 1
check "both-blank"           '{"email":"","password":""}' 2

exit $fail
