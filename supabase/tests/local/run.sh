#!/usr/bin/env bash
# Runs the complete Messenger backend against a throw-away PostgreSQL database:
#
#   1. the Supabase-compatible stub (roles, `auth`, `storage`, realtime publication),
#   2. every `supabase/migrations/*.sql` file in timestamp order,
#   3. the authorisation / RLS regression suites in `supabase/tests/*.sql`.
#
# Used by `.github/workflows/backend.yml` (PostgreSQL service container) and by developers who
# want to verify a migration before touching the shared production project. Nothing here talks to
# Supabase, so it can never modify Luma data or Messenger production rows.
#
# Usage:
#   DATABASE_URL=postgresql://postgres:postgres@localhost:5432/postgres ./supabase/tests/local/run.sh
#   ./supabase/tests/local/run.sh --with-embedded-pg     # spins up a local server via `pgserver`
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

if [[ "${1:-}" == "--with-embedded-pg" ]]; then
  exec python3 "$ROOT/supabase/tests/local/embedded_pg.py"
fi

DATABASE_URL="${DATABASE_URL:-postgresql://postgres:postgres@localhost:5432/postgres}"
PSQL="${PSQL:-psql}"

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

[[ -x "$(command -v "$PSQL" || true)" || -n "$(command -v "$PSQL" || true)" ]] \
  || fail "psql not found; set PSQL=/path/to/psql"

psql_run() {
  "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -X -q "$@"
}

step "PostgreSQL version"
psql_run -tAc "select version()"

step "Applying Supabase stub"
psql_run -f "$ROOT/supabase/tests/local/00_supabase_stub.sql"

step "Applying migrations"
shopt -s nullglob
migrations=("$ROOT"/supabase/migrations/*.sql)
[[ ${#migrations[@]} -gt 0 ]] || fail "no migrations found"
for file in "${migrations[@]}"; do
  printf '  • %s\n' "$(basename "$file")"
  psql_run -f "$file"
done

status=0
shopt -s nullglob
for suite in "$ROOT"/supabase/tests/*.sql; do
  step "Suite: $(basename "$suite")"
  # The suites wrap themselves in BEGIN/ROLLBACK; ON_ERROR_STOP makes a failed assertion fatal.
  if psql_run -f "$suite"; then
    printf '\033[32mPASS\033[0m %s\n' "$(basename "$suite")"
  else
    printf '\033[31mFAIL\033[0m %s\n' "$(basename "$suite")"
    status=1
  fi
done

step "Edge Functions name only existing tables, columns and RPC arguments"
if python3 "$ROOT/tools/check_edge_function_schema.py" --database-url "$DATABASE_URL" --psql "$PSQL"; then
  printf '\033[32mPASS\033[0m edge function schema contract\n'
else
  printf '\033[31mFAIL\033[0m edge function schema contract\n'
  status=1
fi

step "Migration idempotency (stub + migrations re-applied onto the same database)"
for file in "${migrations[@]}"; do
  psql_run -f "$file" || fail "migration $(basename "$file") is not idempotent"
done
psql_run -f "$ROOT/supabase/tests/local/00_supabase_stub.sql" || fail "stub is not idempotent"

if [[ $status -ne 0 ]]; then
  fail "one or more backend suites failed"
fi
printf '\n\033[32mAll backend suites passed.\033[0m\n'
