#!/bin/bash
# Runs once, on first start with an empty data directory.
# One database, one schema + login role per service: a service can only touch its own tables.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-'EOSQL'
	CREATE EXTENSION IF NOT EXISTS postgis;
EOSQL

for svc in ride driver payment ai; do
  pw_var="$(echo "$svc" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
  pw="${!pw_var:-${svc}_local_pw}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
       -v role="${svc}_svc" -v schema="$svc" -v pw="$pw" <<-'EOSQL'
	CREATE ROLE :"role" LOGIN PASSWORD :'pw';
	CREATE SCHEMA IF NOT EXISTS :"schema" AUTHORIZATION :"role";
	ALTER ROLE :"role" SET search_path = :"schema", public;
EOSQL
  echo "created role ${svc}_svc with schema ${svc}"
done
