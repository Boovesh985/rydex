# syntax=docker/dockerfile:1
# The edge (ADR 0023): Caddy with the web app built in, from the repo root:
#   docker build -f infra/caddy/Dockerfile .
# The web app is built once on the build machine's platform (static files are portable). Its API is
# the same origin (VITE_API_URL empty), so the browser never needs CORS here.

FROM --platform=$BUILDPLATFORM node:24-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
ARG VITE_API_URL=
ARG VITE_WS_URL=
# the driver dashboard's "Simulate driving" switch (a demo without a phone on the road)
ARG VITE_ENABLE_SIMULATOR=true
ENV VITE_API_URL=${VITE_API_URL} VITE_WS_URL=${VITE_WS_URL} VITE_ENABLE_SIMULATOR=${VITE_ENABLE_SIMULATOR}
RUN npx vite build

FROM caddy:2.11-alpine
LABEL org.opencontainers.image.source="https://github.com/Boovesh985/rydex" \
      org.opencontainers.image.title="rydex-caddy" \
      org.opencontainers.image.licenses="MIT"
# non-root: Docker lets containers bind ports below 1024 (net.ipv4.ip_unprivileged_port_start=0), so
# the binary's own cap_net_bind_service goes: with every capability dropped it could not even start
RUN apk add --no-cache libcap \
    && setcap -r /usr/bin/caddy \
    && apk del libcap \
    && addgroup -S -g 10001 rydex \
    && adduser -S -D -H -u 10001 -G rydex -s /sbin/nologin rydex \
    && chown -R rydex:rydex /data /config
COPY infra/caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=web /web/dist /srv
USER 10001:10001
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=5 \
    CMD wget -q -O /dev/null --header 'Host: caddy' http://127.0.0.1/healthz || exit 1
