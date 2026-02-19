#!/usr/bin/env bash

# 운영용 최소 검증

set -euo pipefail

# 운영에서는 actuator로 검증하는 걸 권장하지만,
# 초기 세팅 단계라면 "포트 리슨"만 확인하는 형태로도 시작 가능
for i in {1..30}; do
  if ss -lnt | grep -q ':8080'; then
    echo "[validate] port 8080 is listening"
    exit 0
  fi
  echo "[validate] waiting... ($i)"
  sleep 2
done

echo "[validate] FAILED: port 8080 not listening"
docker logs --tail 200 pooli || true
exit 1