#!/usr/bin/env sh
# Restore rehearsal: back up DATABASE_URL, restore into a scratch database, compare row counts.
# Usage: DATABASE_URL=... SCRATCH_DATABASE_URL=... ./scripts/restore-check.sh
set -eu
DIR="$(mktemp -d)"
"$(dirname "$0")/backup-db.sh" "$DIR" >/dev/null
FILE="$(ls "$DIR"/*.sql.gz)"
psql -q "$SCRATCH_DATABASE_URL" -c 'DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;'
gunzip -c "$FILE" | psql -q -v ON_ERROR_STOP=1 "$SCRATCH_DATABASE_URL" >/dev/null
FAIL=0
for t in User Run Transaction InventoryItem UpgradeLevel DailyState MissionProgress DuelMatch GameEvent; do
  a=$(psql -tA "$DATABASE_URL" -c "select count(*) from \"$t\"")
  b=$(psql -tA "$SCRATCH_DATABASE_URL" -c "select count(*) from \"$t\"")
  [ "$a" = "$b" ] && echo "ok   $t $a" || { echo "FAIL $t $a != $b"; FAIL=1; }
done
rm -rf "$DIR"
exit $FAIL
