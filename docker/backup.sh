#!/usr/bin/env bash
#
# Backs up everything that cannot be rebuilt from something else.
#
#   ./docker/backup.sh                 # writes ./backups/<timestamp>
#   ./docker/backup.sh /mnt/backups    # writes /mnt/backups/<timestamp>
#
# What is backed up, and what is deliberately not:
#
#   postgres   yes - the only copy of orders, users, catalogue and the delivery log
#   minio      yes - the only copy of product images
#   elastic    no  - derived from catalog and rebuildable with the reindex command, so backing it
#                    up would store a second copy of data that is already stored properly
#   redis      no  - guest carts and rate-limit counters. Losing a cart is a bad afternoon for a
#                    shopper; carrying a stale one across a restore is worse. Trending counts go
#                    with it and rebuild themselves from the next day's orders.
#   kafka      no  - a transport, not a store. Anything that mattered is in an outbox table.
#
# Each database is dumped separately, which means five slightly different points in time. That is
# not a flaw to fix here: services own their data and there is no cross-database transaction to be
# consistent with. It does mean a restore can resurrect an outbox row whose event was already
# published - which is exactly why every consumer in this system deduplicates.

set -euo pipefail

cd "$(dirname "$0")/.."

DEST_ROOT="${1:-./backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="${DEST_ROOT}/${STAMP}"

DATABASES=(auth catalog orders media notification)
BUCKETS=(aura-media aura-quarantine)

PG_USER="$(docker compose exec -T postgres printenv POSTGRES_USER | tr -d '\r')"

# mc ships inside the MinIO image, but its preconfigured "local" alias carries no credentials -
# it exists for the health check, and anything that reads objects gets "Access Denied". Point a
# real alias at the server using the credentials the container already holds.
mc_alias() {
    docker compose exec -T minio sh -c \
        'mc alias set aura http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null'
}


echo "==> Backing up to ${DEST}"
mkdir -p "${DEST}/postgres" "${DEST}/minio"

# Roles and their passwords live outside any single database. Without them a restore onto a fresh
# cluster produces databases nothing can log in to.
echo "  globals"
docker compose exec -T postgres pg_dumpall -U "${PG_USER}" --globals-only \
    > "${DEST}/postgres/globals.sql"

for db in "${DATABASES[@]}"; do
    echo "  database ${db}"
    # Custom format rather than plain SQL: it can be restored selectively, in parallel, and
    # pg_restore --list can prove the file is readable without restoring it.
    docker compose exec -T postgres pg_dump -U "${PG_USER}" --format=custom --compress=6 "${db}" \
        > "${DEST}/postgres/${db}.dump"
done

mc_alias
for bucket in "${BUCKETS[@]}"; do
    echo "  bucket ${bucket}"
    docker compose exec -T minio mc mirror --quiet --overwrite "aura/${bucket}" "/tmp/backup/${bucket}" >/dev/null
    docker compose cp "minio:/tmp/backup/${bucket}" "${DEST}/minio/" 2>/dev/null || mkdir -p "${DEST}/minio/${bucket}"
    docker compose exec -T minio rm -rf "/tmp/backup/${bucket}" || true
done

# A backup that cannot be read is not a backup. Proving each dump is well-formed here means the
# discovery happens now rather than during an incident.
#
# In a throwaway container with the directory mounted, rather than by piping the file into the
# running one: a custom-format archive has to be seekable to list, and a pipe is not. Reading it
# back the way a restore would is also a stronger check than reading it the way only this script
# would.
echo "==> Verifying"
PG_IMAGE="$(docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["postgres"]["image"])')"
for db in "${DATABASES[@]}"; do
    if ! docker run --rm -v "$(cd "${DEST}/postgres" && pwd):/backup:ro" "${PG_IMAGE}" \
            pg_restore --list "/backup/${db}.dump" > /dev/null; then
        echo "  ${db}.dump is not a readable dump" >&2
        exit 1
    fi
    echo "  ${db}.dump readable ($(du -h "${DEST}/postgres/${db}.dump" | cut -f1))"
done

# Written to a temporary name first. Redirecting straight into checksums.txt creates the file
# before find walks the tree, so it hashes its own empty self - and every later verification then
# fails on a backup that is perfectly good. That is the worst kind of bug in a restore path: it
# refuses the one thing you need at the moment you need it.
(cd "${DEST}" && find . -type f ! -name manifest.txt ! -name checksums.txt.tmp \
    -exec sha256sum {} + > checksums.txt.tmp && mv checksums.txt.tmp checksums.txt)

cat > "${DEST}/manifest.txt" <<EOF
aura backup
taken       ${STAMP}
postgres    $(docker compose exec -T postgres postgres --version | tr -d '\r')
databases   ${DATABASES[*]}
buckets     ${BUCKETS[*]}
files       $(wc -l < "${DEST}/checksums.txt")
EOF

echo "==> Done"
cat "${DEST}/manifest.txt"
