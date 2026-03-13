#!/usr/bin/env bash
# Resets the valtimo and epistola databases and restarts affected services.
# Simulates what the Kubernetes CronJob does in the Helm chart.
#
# Usage: ./scripts/reset-databases.sh [profile]
#   profile: "server" or "containers" (default: "server")
#
# Prerequisites: docker compose services must be running.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="${SCRIPT_DIR}/../docker"
PROFILE="${1:-server}"

echo "=== Phase 1: Reset databases ==="
docker compose -f "${COMPOSE_DIR}/docker-compose.yml" --profile reset run --rm db-reset

echo ""
echo "=== Phase 2: Restart services ==="
SERVICES_TO_RESTART=""

case "$PROFILE" in
  server)
    SERVICES_TO_RESTART="epistola-server"
    ;;
  containers)
    SERVICES_TO_RESTART="valtimo-backend epistola-server"
    ;;
  *)
    echo "Unknown profile: ${PROFILE}. Use 'server' or 'containers'."
    exit 1
    ;;
esac

for SVC in $SERVICES_TO_RESTART; do
  if docker compose -f "${COMPOSE_DIR}/docker-compose.yml" --profile "${PROFILE}" ps --status running "${SVC}" 2>/dev/null | grep -q "${SVC}"; then
    echo "Restarting: ${SVC}"
    docker compose -f "${COMPOSE_DIR}/docker-compose.yml" --profile "${PROFILE}" restart "${SVC}"
  else
    echo "Skipping ${SVC} (not running)"
  fi
done

echo ""
echo "=== Reset complete ==="
echo "Services will re-run migrations on startup. Use 'docker compose logs -f' to monitor."
