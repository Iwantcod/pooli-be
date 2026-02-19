#!/usr/bin/env bash

# .env 생성 + ECR 로그인 + compose up

set -euo pipefail

cd /srv/pooli

# 1) 번들에 포함된 deploy/.env 를 운영 경로로 복사
if [ ! -f /srv/pooli/deploy/.env ]; then
  echo "[start] ERROR: /srv/pooli/deploy/.env not found"
  exit 1
fi

cp /srv/pooli/deploy/.env /srv/pooli/.env
chmod 600 /srv/pooli/.env

# 2) .env에서 ECR_REGISTRY, AWS_REGION 등을 쓰는 구성이라면 로드(선택)
#    - PROD_ENV_FILE 안에 ECR_REGISTRY/ECR_REPOSITORY/AWS_REGION 넣어두는 걸 권장
set -a
source /srv/pooli/.env || true
set +a

: "${AWS_REGION:=ap-northeast-2}"
: "${ECR_REGISTRY:?ECR_REGISTRY is required in /srv/pooli/.env}"

aws ecr get-login-password --region "${AWS_REGION}" \
| docker login --username AWS --password-stdin "${ECR_REGISTRY}"

# 3) 최신 이미지 pull & 기동
docker compose -f /srv/pooli/docker-compose.yml down || true
docker compose -f /srv/pooli/docker-compose.yml pull
docker compose -f /srv/pooli/docker-compose.yml up -d --remove-orphans

docker ps