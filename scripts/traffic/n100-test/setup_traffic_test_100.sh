#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# 100-line traffic policy test setup
# -----------------------------------------------------------------------------
# This script prepares a deterministic start point by running:
# 1) Redis FLUSHALL (cache Redis)
# 2) MongoDB cleanup: traffic_deduct_done_log.deleteMany({})
# 3) MySQL setup SQL apply
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
SETUP_SQL_FILE="$SCRIPT_DIR/setup_traffic_test_100.sql"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: env file not found: $ENV_FILE"
  exit 1
fi

if [[ ! -f "$SETUP_SQL_FILE" ]]; then
  echo "ERROR: setup sql file not found: $SETUP_SQL_FILE"
  exit 1
fi

for cmd in mysql redis-cli mongosh; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: required command is missing: $cmd"
    exit 1
  fi
done

# Load .env as plain KEY=VALUE pairs without shell evaluation.
load_env_file() {
  local env_path="$1"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "${line//[[:space:]]/}" ]] && continue
    [[ "${line:0:1}" == "#" ]] && continue

    if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      local key="${BASH_REMATCH[1]}"
      local value="${BASH_REMATCH[2]}"
      value="${value%$'\r'}"

      if [[ "$value" =~ ^\"(.*)\"$ ]]; then
        value="${BASH_REMATCH[1]}"
      elif [[ "$value" =~ ^\'(.*)\'$ ]]; then
        value="${BASH_REMATCH[1]}"
      fi

      export "$key=$value"
    fi
  done < "$env_path"
}

load_env_file "$ENV_FILE"

if [[ -z "${DB_URL:-}" || -z "${DB_USERNAME:-}" || -z "${DB_PASSWORD:-}" ]]; then
  echo "ERROR: DB_URL / DB_USERNAME / DB_PASSWORD must be set in $ENV_FILE"
  exit 1
fi

if [[ -z "${CACHE_REDIS_HOST:-}" || -z "${CACHE_REDIS_PORT:-}" ]]; then
  echo "ERROR: CACHE_REDIS_HOST / CACHE_REDIS_PORT must be set in $ENV_FILE"
  exit 1
fi

DB_URL_NO_PREFIX="${DB_URL#jdbc:mysql://}"
DB_HOST_PORT_DB="${DB_URL_NO_PREFIX%%\?*}"
DB_HOST_PORT="${DB_HOST_PORT_DB%%/*}"
DB_NAME="${DB_HOST_PORT_DB#*/}"
DB_HOST="${DB_HOST_PORT%%:*}"
DB_PORT_PART="${DB_HOST_PORT#*:}"
DB_PORT="${DB_PORT_PART:-3306}"
if [[ "$DB_HOST_PORT" == "$DB_PORT_PART" ]]; then
  DB_PORT="3306"
fi

MONGO_URI_EFFECTIVE="${LOCAL_MONGO_URI:-${MONGO_URI:-}}"
MONGO_DB_NAME="${MONGO_DB_NAME:-pooli}"

redis_cmd=(
  redis-cli
  -h "$CACHE_REDIS_HOST"
  -p "$CACHE_REDIS_PORT"
  --no-auth-warning
)
if [[ -n "${CACHE_REDIS_PASSWORD:-}" ]]; then
  redis_cmd=(
    redis-cli
    -h "$CACHE_REDIS_HOST"
    -p "$CACHE_REDIS_PORT"
    -a "$CACHE_REDIS_PASSWORD"
    --no-auth-warning
  )
fi

mongo_eval() {
  local js="$1"
  if [[ -n "$MONGO_URI_EFFECTIVE" ]]; then
    mongosh "$MONGO_URI_EFFECTIVE" --quiet --eval "$js"
  else
    mongosh --quiet --eval "$js"
  fi
}

echo "==============================================="
echo "Traffic 100 Setup (Flush + Mongo clear + SQL)"
echo "env_file                : $ENV_FILE"
echo "setup_sql               : $SETUP_SQL_FILE"
echo "mysql                   : $DB_HOST:$DB_PORT/$DB_NAME"
echo "redis(cache)            : $CACHE_REDIS_HOST:$CACHE_REDIS_PORT"
echo "mongo_db                : $MONGO_DB_NAME"
echo "==============================================="

echo "[1/3] Redis FLUSHALL"
"${redis_cmd[@]}" FLUSHALL >/dev/null
echo "  done"

echo "[2/3] Mongo cleanup (traffic_deduct_done_log.deleteMany({}))"
mongo_eval "const d=db.getSiblingDB('${MONGO_DB_NAME}'); const r=d.traffic_deduct_done_log.deleteMany({}); print(r.deletedCount);" >/dev/null
echo "  done"

echo "[3/3] Apply setup sql"
MYSQL_PWD="$DB_PASSWORD" mysql \
  --default-character-set=utf8mb4 \
  --batch --raw \
  -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" "$DB_NAME" \
  < "$SETUP_SQL_FILE"
echo "  done"

echo
echo "Setup completed."
echo "Run next:"
echo "  k6 run scripts/traffic/k6_traffic_test_100.js"
echo "  k6 run scripts/traffic/k6_traffic_test_100_speed_exact.js"
echo "  scripts/traffic/verify_policy_consistency_100_suite.sh"
