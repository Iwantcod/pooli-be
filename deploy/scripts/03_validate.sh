#!/usr/bin/env bash

# 운영용 최소 검증

set -euo pipefail

DG_NAME="${DEPLOYMENT_GROUP_NAME:-}"

log_common_diagnostics() {
  echo "[validate] diag timestamp=$(date -Iseconds)"
  echo "[validate] diag deployment_group=${DG_NAME}"
  echo "[validate] diag hostname=$(hostname)"
  echo "[validate] diag container status:"
  docker ps -a --filter "name=^/pooli$" --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' || true
  echo "[validate] diag container state:"
  docker inspect pooli --format 'status={{.State.Status}} running={{.State.Running}} exitCode={{.State.ExitCode}} startedAt={{.State.StartedAt}} finishedAt={{.State.FinishedAt}} restartCount={{.RestartCount}}' 2>/dev/null || true
  echo "[validate] diag SPRING_PROFILES_ACTIVE:"
  docker inspect pooli --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep '^SPRING_PROFILES_ACTIVE=' || true
  echo "[validate] diag listen :8080"
  (ss -lntp 2>/dev/null || netstat -lntp 2>/dev/null || true) | grep ':8080' || true
}

check_release_health() {
  local attempt="$1"
  local output http_status body body_preview

  output="$(curl -sS -m 3 -w $'\nHTTP_STATUS:%{http_code}\n' "http://127.0.0.1:8080/actuator/health" 2>&1 || true)"
  http_status="$(printf '%s\n' "$output" | awk -F: '/^HTTP_STATUS:/ {print $2}' | tail -n1 | tr -d '\r')"
  body="$(printf '%s\n' "$output" | sed '/^HTTP_STATUS:/d')"
  body_preview="$(printf '%s' "$body" | tr '\n' ' ' | sed 's/[[:space:]]\+/ /g' | cut -c1-240)"

  if [ "${http_status}" = "200" ] && printf '%s' "$body" | grep -q '"status":"UP"'; then
    echo "[validate] api health UP"
    return 0
  fi

  echo "[validate] release attempt=${attempt} http=${http_status:-N/A} body=${body_preview:-<empty>}"
  return 1
}

check_traffic_container() {
  local attempt="$1"
  local running_name profile_env

  running_name="$(docker ps --filter "name=^/pooli$" --filter "status=running" --format '{{.Names}}' | head -n1 || true)"
  profile_env="$(docker inspect pooli --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep '^SPRING_PROFILES_ACTIVE=' | head -n1 || true)"

  if [ "${running_name}" = "pooli" ] && [[ "${profile_env}" == *traffic* ]]; then
    echo "[validate] traffic container running with profile=traffic"
    return 0
  fi

  echo "[validate] traffic attempt=${attempt} running=${running_name:-<none>} profile=${profile_env:-<none>}"
  return 1
}

case "$DG_NAME" in
  pooli-release-group)
    for i in {1..30}; do
      if check_release_health "$i"; then
        exit 0
      fi
      sleep 2
    done

    echo "[validate] FAILED: api health endpoint not ready"
    log_common_diagnostics
    echo "[validate] diag final curl:"
    curl -sv --max-time 3 "http://127.0.0.1:8080/actuator/health" || true
    docker logs --tail 200 pooli || true
    exit 1
    ;;

  pooli-traffic-group)
    for i in {1..30}; do
      if check_traffic_container "$i"; then
        exit 0
      fi
      sleep 2
    done

    echo "[validate] FAILED: traffic container/profile not ready"
    log_common_diagnostics
    docker logs --tail 200 pooli || true
    exit 1
    ;;

  *)
    echo "[validate] ERROR: unknown DEPLOYMENT_GROUP_NAME=${DG_NAME}"
    exit 1
    ;;
esac
