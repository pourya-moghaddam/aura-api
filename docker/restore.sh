#!/usr/bin/env bash
#
# Restores a backup taken by backup.sh.
#
#   ./docker/restore.sh ./backups/20260814T120000Z
#
# This stops the application services first and starts them again afterwards. That is not
# politeness: restoring underneath a running service gives it a connection to a database that is
# being dropped and recreated, and the result is neither the old data nor the new.
#
# Elasticsearch is not restored - it is not backed up. After a restore of catalog, run the reindex
# command so search reflects the products that now exist:
#
#   curl -X POST localhost:8085/api/control/search/reindex -H "Authorization: Bearer <admin token>"

set -euo pipefail

cd "$(dirname "$0")/.."

BACKUP="${1:-}"
if [[ -z "${BACKUP}" || ! -d "${BACKUP}" ]]; then
    echo "usage: $0 <backup-directory>" >&2
    exit 1
fi

DATABASES=(auth catalog orders media notification)
BUCKETS=(aura-media aura-quarantine)
APP_SERVICES=(gateway-service auth-service catalog-service order-service media-service
              search-service notification-service)

PG_USER="$(docker compose exec -T postgres printenv POSTGRES_USER | tr -d '\r')"

# mc ships inside the MinIO image, but its preconfigured "local" alias carries no credentials -
# it exists for the health check, and anything that reads objects gets "Access Denied". Point a
# real alias at the server using the credentials the container already holds.
mc_alias() {
    docker compose exec -T minio sh -c \
        'mc alias set aura http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null'
}


echo "==> Checking the backup before touching anything"
if [[ -f "${BACKUP}/checksums.txt" ]]; then
    (cd "${BACKUP}" && sha256sum --quiet --check checksums.txt)
    echo "  checksums match"
fi
for db in "${DATABASES[@]}"; do
    [[ -f "${BACKUP}/postgres/${db}.dump" ]] || { echo "  missing ${db}.dump" >&2; exit 1; }
done

echo "==> Stopping application services"
docker compose stop "${APP_SERVICES[@]}" >/dev/null

for db in "${DATABASES[@]}"; do
    echo "==> Restoring ${db}"
    # Stopping the services is not quite enough: a connection can outlive the container by a few
    # seconds, and DROP DATABASE fails while any session is attached.
    docker compose exec -T postgres psql -U "${PG_USER}" -d postgres -v ON_ERROR_STOP=1 -q <<SQL
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
 WHERE datname = '${db}' AND pid <> pg_backend_pid();
SQL
    docker compose exec -T postgres psql -U "${PG_USER}" -d postgres -v ON_ERROR_STOP=1 -q \
        -c "DROP DATABASE IF EXISTS ${db};" -c "CREATE DATABASE ${db};"

    # Copied in rather than piped: a custom-format archive is read by seeking around it, and a
    # pipe cannot be seeked. Piping it appears to work and then fails partway through a large
    # restore, which is the worst possible moment to find out.
    docker compose cp "${BACKUP}/postgres/${db}.dump" "postgres:/tmp/${db}.dump"
    # --exit-on-error, so a half-restored database is a failure rather than something that looks
    # like it worked until the first query hits a missing table.
    docker compose exec -T postgres pg_restore -U "${PG_USER}" -d "${db}" --exit-on-error \
        "/tmp/${db}.dump"
    docker compose exec -T postgres rm -f "/tmp/${db}.dump"
    echo "  ${db} restored"
done

for bucket in "${BUCKETS[@]}"; do
    if [[ -d "${BACKUP}/minio/${bucket}" ]]; then
        echo "==> Restoring bucket ${bucket}"
        mc_alias
        docker compose exec -T minio mc mb --ignore-existing "aura/${bucket}" >/dev/null
        docker compose cp "${BACKUP}/minio/${bucket}" "minio:/tmp/restore-${bucket}"
        # --remove as well as --overwrite: a restore puts the bucket back as it was, which means
        # objects created after the backup go away. Leaving them would be a merge, not a restore,
        # and would resurrect images for products the database no longer knows about.
        docker compose exec -T minio mc mirror --quiet --overwrite --remove \
            "/tmp/restore-${bucket}" "aura/${bucket}" >/dev/null
        docker compose exec -T minio rm -rf "/tmp/restore-${bucket}" || true
        echo "  ${bucket} restored"
    fi
done

echo "==> Starting application services"
docker compose start "${APP_SERVICES[@]}" >/dev/null

echo "==> Done. Search still holds the pre-restore index - reindex when the services are healthy."
