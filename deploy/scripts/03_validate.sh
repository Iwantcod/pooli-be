#!/usr/bin/env bash

# 운영용 최소 검증

set -euo pipefail


for i in {1..30}; do
  if curl -fsS "http://127.0.0.1:8080/actuator/health" | grep -q '"status":"UP"'; then
    echo "[validate] health UP"
    exit 0
  fi
  echo "[validate] waiting... ($i)"
  sleep 2
done

echo "[validate] FAILED: port 8080 not listening"
docker logs --tail 200 pooli || true
exit 1