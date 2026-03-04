#!/usr/bin/env bash
set -euo pipefail

mkdir -p /srv/pooli
chmod 755 /srv/pooli

echo "[prepare] ok"
docker --version
docker compose version || true

# AWS CLI v2 설치 (없을 때만)
if ! command -v aws >/dev/null 2>&1; then
  echo "[prepare] installing AWS CLI v2..."

  apt-get update -y
  apt-get install -y unzip curl

  TMP_DIR="$(mktemp -d)"
  cd "$TMP_DIR"
  curl -sSLo awscliv2.zip "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip"
  unzip -q awscliv2.zip
  ./aws/install --update

  rm -rf "$TMP_DIR"
fi

aws --version