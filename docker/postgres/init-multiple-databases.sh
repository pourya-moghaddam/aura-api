#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE auth;
    CREATE DATABASE catalog;
    CREATE DATABASE inventory;
    CREATE DATABASE orders;
EOSQL