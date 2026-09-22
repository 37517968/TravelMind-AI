#!/usr/bin/env sh
set -eu

: "${CONFIRM_RESTORE:?set CONFIRM_RESTORE=RESTORE_TO_EMPTY_TARGET}"
[ "$CONFIRM_RESTORE" = "RESTORE_TO_EMPTY_TARGET" ] || { echo "restore confirmation mismatch" >&2; exit 2; }
MYSQL_FILE="${1:?usage: restore-relational.sh mysql.sql.gz pgvector.dump}"
PGVECTOR_FILE="${2:?usage: restore-relational.sh mysql.sql.gz pgvector.dump}"
[ -f "$MYSQL_FILE" ] && [ -f "$PGVECTOR_FILE" ] || { echo "backup file missing" >&2; exit 2; }

: "${MYSQL_HOST:?MYSQL_HOST is required}"
: "${MYSQL_USER:?MYSQL_USER is required}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
: "${PGVECTOR_HOST:?PGVECTOR_HOST is required}"
: "${PGVECTOR_USERNAME:?PGVECTOR_USERNAME is required}"
: "${PGVECTOR_PASSWORD:?PGVECTOR_PASSWORD is required}"
: "${PGVECTOR_DATABASE:?PGVECTOR_DATABASE is required}"

gzip -dc "$MYSQL_FILE" | MYSQL_PWD="$MYSQL_PASSWORD" mysql \
  -h "$MYSQL_HOST" -u "$MYSQL_USER" "$MYSQL_DATABASE"
PGPASSWORD="$PGVECTOR_PASSWORD" pg_restore --exit-on-error --no-owner \
  -h "$PGVECTOR_HOST" -U "$PGVECTOR_USERNAME" -d "$PGVECTOR_DATABASE" "$PGVECTOR_FILE"
echo "relational restore completed; run knowledge index reconciliation before opening traffic"
