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

cleanup_old_pooli_images() {
  local repo image tag
  repo="${ECR_REGISTRY}/${ECR_REPOSITORY}"

  echo "[start] cleanup old images for repo=${repo} keep_tag=${IMAGE_TAG}"
  docker images --format '{{.Repository}}:{{.Tag}}' "${repo}" \
    | while read -r image; do
        [ -z "${image}" ] && continue
        tag="${image##*:}"
        if [ "${tag}" = "${IMAGE_TAG}" ] || [ "${tag}" = "<none>" ]; then
          continue
        fi
        echo "[start] removing old image: ${image}"
        docker image rm -f "${image}" || true
      done

  # dangling 레이어 정리
  docker image prune -f || true
}

# 0.5) 배포 그룹 이름으로 실행 프로파일을 결정
DG_NAME="${DEPLOYMENT_GROUP_NAME:-}"
case "$DG_NAME" in
  pooli-release-group) TARGET_PROFILE="api" ;;
  pooli-traffic-group) TARGET_PROFILE="traffic" ;;
  *)
    echo "[start] ERROR: unknown DEPLOYMENT_GROUP_NAME=${DG_NAME}"
    exit 1
    ;;
esac
echo "[start] deploymentGroup=${DG_NAME} profile=${TARGET_PROFILE}"

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

# 2.5) DG 기준 프로파일을 .env에 강제 주입
TMP_ENV="$(mktemp)"
awk -v profile="${TARGET_PROFILE}" '
  BEGIN { updated = 0 }
  {
    gsub(/\r$/, "", $0)
    if ($0 ~ /^SPRING_PROFILES_ACTIVE=/) {
      print "SPRING_PROFILES_ACTIVE=" profile
      updated = 1
      next
    }
    print
  }
  END {
    if (updated == 0) {
      print "SPRING_PROFILES_ACTIVE=" profile
    }
  }
' /srv/pooli/.env > "${TMP_ENV}"
mv "${TMP_ENV}" /srv/pooli/.env
chmod 600 /srv/pooli/.env
echo "[start] enforced profile in /srv/pooli/.env:"
grep '^SPRING_PROFILES_ACTIVE=' /srv/pooli/.env || true

# 3) aws cli 존재 확인 (없으면 여기서 종료)
command -v aws >/dev/null 2>&1 || { echo "[start] ERROR: aws cli not installed"; exit 127; }

# 4) ECR 로그인
echo "[start] ecr login registry=${ECR_REGISTRY} region=${AWS_REGION}"
if ! ECR_PASSWORD="$(aws ecr get-login-password --region "${AWS_REGION}")"; then
  echo "[start] ERROR: failed to get ECR auth token. Check IAM permission: ecr:GetAuthorizationToken"
  aws sts get-caller-identity || true
  exit 1
fi

if ! printf '%s' "${ECR_PASSWORD}" | docker login --username AWS --password-stdin "${ECR_REGISTRY}"; then
  echo "[start] ERROR: docker login failed for ${ECR_REGISTRY}"
  exit 1
fi

# 5) compose 기동
cleanup_old_pooli_images
docker compose -f /srv/pooli/docker-compose.yml pull
docker compose -f /srv/pooli/docker-compose.yml up -d --remove-orphans --force-recreate

for i in {1..20}; do
  CONTAINER_PROFILE="$(docker inspect pooli --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep '^SPRING_PROFILES_ACTIVE=' | head -n1 || true)"
  if [[ "${CONTAINER_PROFILE}" == *"${TARGET_PROFILE}"* ]]; then
    echo "[start] container profile verified: ${CONTAINER_PROFILE}"
    break
  fi

  if [ "$i" -eq 20 ]; then
    echo "[start] ERROR: profile mismatch expected=${TARGET_PROFILE} actual=${CONTAINER_PROFILE:-<none>}"
    exit 1
  fi
  sleep 1
done

docker ps
