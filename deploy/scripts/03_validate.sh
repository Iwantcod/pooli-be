#!/usr/bin/env bash

# 운영용 최소 검증

set -euo pipefail

DG_NAME="${DEPLOYMENT_GROUP_NAME:-}"

case "$DG_NAME" in
  pooli-release-group)
    for i in {1..30}; do
      if curl -fsS "http://127.0.0.1:8080/actuator/health" | grep -q '"status":"UP"'; then
        echo "[validate] api health UP"
        exit 0
      fi
      echo "[validate] waiting api health... ($i)"
      sleep 2
    done

    echo "[validate] FAILED: api health endpoint not ready"
    docker logs --tail 200 pooli || true
    exit 1
    ;;

  pooli-traffic-group)
    for i in {1..30}; do
      if docker ps --filter "name=^/pooli$" --filter "status=running" --format '{{.Names}}' | grep -q '^pooli$'; then
        if docker inspect pooli --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -q '^SPRING_PROFILES_ACTIVE=.*traffic'; then
          echo "[validate] traffic container running with profile=traffic"
          exit 0
        fi
      fi
      echo "[validate] waiting traffic container/profile... ($i)"
      sleep 2
    done

    echo "[validate] FAILED: traffic container/profile not ready"
    docker logs --tail 200 pooli || true
    exit 1
    ;;

  *)
    echo "[validate] ERROR: unknown DEPLOYMENT_GROUP_NAME=${DG_NAME}"
    exit 1
    ;;
esac
