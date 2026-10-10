# PostgreSQL 16 + PostGIS 3, multi-arch (amd64 + arm64).
# The official postgis/postgis image only publishes amd64, so we add PostGIS to the
# official postgres image from the PGDG apt repo it already ships with. See docs/adr/0003.
FROM postgres:16-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql-16-postgis-3 \
        postgresql-16-postgis-3-scripts \
    && rm -rf /var/lib/apt/lists/*

COPY initdb/ /docker-entrypoint-initdb.d/
