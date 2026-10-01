#!/usr/bin/env sh
# Logical backup of the game database. Usage: DATABASE_URL=... ./scripts/backup-db.sh [outdir]
# Each step is checked on its own (no pipeline), so a failed dump can never be published as a backup.
set -eu
OUT="${1:-backups}"
mkdir -p "$OUT"
FILE="$OUT/golge-kuklaci-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
TMP="$(mktemp "$OUT/.dump-XXXXXX")"
trap 'rm -f "$TMP" "$TMP.gz"' EXIT
pg_dump --no-owner --no-privileges --file="$TMP" "$DATABASE_URL"
gzip -c "$TMP" > "$TMP.gz"
mv "$TMP.gz" "$FILE"
echo "backup written: $FILE"
# Restore: gunzip -c "$FILE" | psql -v ON_ERROR_STOP=1 "$DATABASE_URL"
