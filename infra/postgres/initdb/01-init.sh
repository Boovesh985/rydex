#!/bin/bash
# Runs once, on first start with an empty data directory.
# One database, one schema + login role per service: a service can only touch its own tables.
#
# Idempotent, so it can also be re-run against an existing volume when a service is added:
#   docker compose -f infra/docker-compose.yml exec -T postgres bash -s < infra/postgres/initdb/01-init.sh
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-'EOSQL'
	CREATE EXTENSION IF NOT EXISTS postgis;
EOSQL

for svc in ride driver tracking payment ai; do
  pw_var="$(echo "$svc" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
  pw="${!pw_var:-${svc}_local_pw}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
       -v role="${svc}_svc" -v schema="$svc" -v pw="$pw" <<-'EOSQL'
	SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'pw')
	WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role') \gexec
	CREATE SCHEMA IF NOT EXISTS :"schema" AUTHORIZATION :"role";
	ALTER ROLE :"role" SET search_path = :"schema", public;
EOSQL
  echo "role ${svc}_svc with schema ${svc} is ready"
done
