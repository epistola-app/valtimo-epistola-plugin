#!/usr/bin/env bash
set -euo pipefail

# Vendors the official Keycloak Helm chart into charts/valtimo-demo/charts for Helm 4 usage.
# Requires Helm 3 locally OR Docker/Podman to run a Helm 3 container.

CHART_VERSION=${CHART_VERSION:-26.1.0}
DEST_DIR="charts/valtimo-demo/charts"

mkdir -p "$DEST_DIR"

if command -v helm >/dev/null 2>&1; then
  HELM_VERSION=$(helm version --short || true)
  if [[ "$HELM_VERSION" =~ ^v3\. ]]; then
    echo "Using local Helm $HELM_VERSION to pull Keycloak $CHART_VERSION"
    helm pull oci://quay.io/keycloak/keycloak --version "$CHART_VERSION" -d "$DEST_DIR"
    exit 0
  fi
fi

RUNTIME=${RUNTIME:-docker}
if ! command -v "$RUNTIME" >/dev/null 2>&1; then
  echo "Error: neither Helm 3 nor $RUNTIME found. Install Helm 3 or Docker/Podman." >&2
  exit 1
fi

echo "Using $RUNTIME to run Helm 3 and pull Keycloak $CHART_VERSION"
"$RUNTIME" run --rm -v "$(pwd)":"/work" -w "/work" alpine/helm:3.14.2 \
  helm pull oci://quay.io/keycloak/keycloak --version "$CHART_VERSION" -d "$DEST_DIR"

echo "Vendored chart at $DEST_DIR/keycloak-$CHART_VERSION.tgz"
