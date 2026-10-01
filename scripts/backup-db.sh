#!/usr/bin/env sh
# Logical backup of the game database. Usage: DATABASE_URL=... ./scripts/backup-db.sh [outdir]
set -eu
OUT="${1:-backups}"
mkdir -p "$OUT"
FILE="$OUT/golge-kuklaci-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
pg_dump --no-owner --no-privileges "$DATABASE_URL" | gzip > "$FILE"
echo "backup written: $FILE"
# Restore: gunzip -c "$FILE" | psql "$DATABASE_URL"
