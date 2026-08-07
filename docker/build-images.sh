#!/usr/bin/env bash
#
# Builds a container image for every service from the single parameterized Dockerfile.
#
#   ./docker/build-images.sh            # tag :dev
#   TAG=1.2.0 ./docker/build-images.sh  # tag :1.2.0
#
# `docker buildx bake` would do this with more parallelism, but buildx is a separate plugin that
# is not always installed. Plain `docker build` uses BuildKit by default on Docker 23+, so the
# ~/.m2 cache mount in the Dockerfile still applies and the first build warms it for the rest.

set -euo pipefail

cd "$(dirname "$0")/.."

TAG="${TAG:-dev}"
REGISTRY="${REGISTRY:-aura}"

SERVICES=(
    discovery-service
    gateway-service
    auth-service
    catalog-service
    notification-service
)

for service in "${SERVICES[@]}"; do
    echo "==> Building ${REGISTRY}/${service}:${TAG}"
    DOCKER_BUILDKIT=1 docker build \
        --build-arg "SERVICE=${service}" \
        --tag "${REGISTRY}/${service}:${TAG}" \
        .
done

echo "==> Done"
docker images --filter "reference=${REGISTRY}/*:${TAG}"
