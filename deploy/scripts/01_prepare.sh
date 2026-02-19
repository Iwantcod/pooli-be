#!/usr/bin/env bash
set -euo pipefail

mkdir -p /srv/pooli
chmod 755 /srv/pooli

echo "[prepare] ok"
docker --version
docker compose version || true
aws --version