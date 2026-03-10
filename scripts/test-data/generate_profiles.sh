#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/scripts/test-data/generate_test_data_csv.py"

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 n100 [n10000 n100000 n1000000]"
  exit 1
fi

for profile in "$@"; do
  case "$profile" in
    n100) n=100 ;;
    n10000) n=10000 ;;
    n100000) n=100000 ;;
    n1000000) n=1000000 ;;
    *)
      echo "Unsupported profile: $profile"
      exit 1
      ;;
  esac

  echo "Generating profile $profile ..."
  python3 "$SCRIPT" --n "$n"
done
