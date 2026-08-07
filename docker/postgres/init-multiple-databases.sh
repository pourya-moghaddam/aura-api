#!/bin/bash
set -e

# One database per service. Sharing a database would let services read each other's tables, which
# is the boundary violation that turns a set of services back into a distributed monolith.
#
# `inventory` is deliberately absent: stock lives in catalog's database, in its own tables, because
# a seller defines a variant and its stock in one transaction.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE auth;
    CREATE DATABASE catalog;
    CREATE DATABASE orders;
    CREATE DATABASE media;
    CREATE DATABASE notification;
EOSQL
