#!/usr/bin/env sh
# Restore rehearsal: back up DATABASE_URL, restore into a scratch database, compare row counts.
# Usage: DATABASE_URL=... SCRATCH_DATABASE_URL=... ./scripts/restore-check.sh
set -eu
# Safety: the scratch target is wiped, so it must be a different database than the source, whatever the URLs look
# like. Identity = cluster system identifier + database name (works for aliases, IP vs hostname, other users).
ident() { psql -tA "$1" -c "select (select system_identifier from pg_control_system()) || '/' || current_database()"; }
SRC_ID="$(ident "$DATABASE_URL")"
DST_ID="$(ident "$SCRATCH_DATABASE_URL")"
if [ -z "$SRC_ID" ] || [ "$SRC_ID" = "$DST_ID" ]; then
  echo "refusing: SCRATCH_DATABASE_URL is the same database as DATABASE_URL ($SRC_ID)"; exit 2
fi
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
"$(dirname "$0")/backup-db.sh" "$DIR" >/dev/null
FILE="$(ls "$DIR"/*.sql.gz)"
# Decompress (checked) before touching the scratch database.
gunzip -c "$FILE" > "$DIR/restore.sql"
psql -q -v ON_ERROR_STOP=1 "$SCRATCH_DATABASE_URL" -c 'DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;'
psql -q -v ON_ERROR_STOP=1 -f "$DIR/restore.sql" "$SCRATCH_DATABASE_URL" >/dev/null
FAIL=0
for t in User Run Transaction InventoryItem UpgradeLevel DailyState MissionProgress DuelMatch GameEvent PushToken; do
  a=$(psql -tA "$DATABASE_URL" -c "select count(*) from \"$t\"")
  b=$(psql -tA "$SCRATCH_DATABASE_URL" -c "select count(*) from \"$t\"")
  [ "$a" = "$b" ] && echo "ok   $t $a" || { echo "FAIL $t $a != $b"; FAIL=1; }
done
exit $FAIL
