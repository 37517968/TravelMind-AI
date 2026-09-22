#!/usr/bin/env sh
set -eu

BACKUP_DIR="${1:?usage: backup-datastores.sh /absolute/backup/directory}"
case "$BACKUP_DIR" in
  /*) ;;
  *) echo "backup directory must be absolute" >&2; exit 2 ;;
esac

: "${MYSQL_HOST:?MYSQL_HOST is required}"
: "${MYSQL_USER:?MYSQL_USER is required}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
: "${PGVECTOR_HOST:?PGVECTOR_HOST is required}"
: "${PGVECTOR_USERNAME:?PGVECTOR_USERNAME is required}"
: "${PGVECTOR_PASSWORD:?PGVECTOR_PASSWORD is required}"
: "${PGVECTOR_DATABASE:?PGVECTOR_DATABASE is required}"
: "${REDIS_HOST:?REDIS_HOST is required}"

mkdir -p "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

MYSQL_PWD="$MYSQL_PASSWORD" mysqldump --single-transaction --routines --triggers \
  -h "$MYSQL_HOST" -u "$MYSQL_USER" "$MYSQL_DATABASE" \
  | gzip > "$BACKUP_DIR/mysql-$STAMP.sql.gz"

PGPASSWORD="$PGVECTOR_PASSWORD" pg_dump -Fc \
  -h "$PGVECTOR_HOST" -U "$PGVECTOR_USERNAME" -d "$PGVECTOR_DATABASE" \
  -f "$BACKUP_DIR/pgvector-$STAMP.dump"

# REDISCLI_AUTH 可选；redis-cli --rdb 生成一致性 RDB 快照，不在日志中暴露密码。
redis-cli -h "$REDIS_HOST" -p "${REDIS_PORT:-6379}" --rdb "$BACKUP_DIR/redis-$STAMP.rdb"

sha256sum "$BACKUP_DIR/mysql-$STAMP.sql.gz" "$BACKUP_DIR/pgvector-$STAMP.dump" \
  "$BACKUP_DIR/redis-$STAMP.rdb" > "$BACKUP_DIR/SHA256SUMS-$STAMP"
echo "backup completed: $STAMP"
