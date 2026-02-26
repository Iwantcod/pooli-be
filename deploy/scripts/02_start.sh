#!/usr/bin/env bash
set -euo pipefail

cd /srv/pooli

# 0) 배포 메타 로드 (필수)
if [ ! -f /srv/pooli/deploy/meta/runtime.env ]; then
  echo "[start] ERROR: /srv/pooli/deploy/meta/runtime.env not found"
  exit 1
fi

set -a
source /srv/pooli/deploy/meta/runtime.env
set +a

: "${AWS_REGION:=ap-northeast-2}"
: "${ECR_REGISTRY:?ECR_REGISTRY is required}"
: "${ECR_REPOSITORY:?ECR_REPOSITORY is required}"

# 1) 이미지 태그(sha) 로드
if [ ! -f /srv/pooli/deploy/meta/image_tag ]; then
  echo "[start] ERROR: /srv/pooli/deploy/meta/image_tag not found"
  exit 1
fi
IMAGE_TAG="$(cat /srv/pooli/deploy/meta/image_tag)"
export IMAGE_TAG

# 2) 번들 .env -> 운영 경로
if [ ! -f /srv/pooli/deploy/.env ]; then
  echo "[start] ERROR: /srv/pooli/deploy/.env not found"
  exit 1
fi

cp /srv/pooli/deploy/.env /srv/pooli/.env
chmod 600 /srv/pooli/.env

# 3) aws cli 존재 확인 (없으면 여기서 종료)
command -v aws >/dev/null 2>&1 || { echo "[start] ERROR: aws cli not installed"; exit 127; }

# 4) ECR 로그인
aws ecr get-login-password --region "${AWS_REGION}" \
| docker login --username AWS --password-stdin "${ECR_REGISTRY}"

# 5) compose 기동
docker compose -f /srv/pooli/docker-compose.yml pull
docker compose -f /srv/pooli/docker-compose.yml up -d --remove-orphans
docker ps