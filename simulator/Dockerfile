# syntax=docker/dockerfile:1
# The living city (ADR 0016) as a container, from the repo root: docker build -f simulator/Dockerfile .
# Laid out as in the repo so it finds the traffic map at /rydex/frontend/public/data/traffic-roads.json.
# Settings: RYDEX_API (one origin, e.g. http://caddy), OTP_SECRET (ride-service's), RYDEX_DRIVER_PASSWORD.

FROM python:3.12-slim-bookworm
LABEL org.opencontainers.image.source="https://github.com/Boovesh985/rydex" \
      org.opencontainers.image.title="rydex-simulator" \
      org.opencontainers.image.licenses="MIT"
RUN groupadd --system --gid 10001 rydex \
    && useradd --system --uid 10001 --gid rydex --no-create-home --shell /usr/sbin/nologin rydex
WORKDIR /rydex/simulator
COPY simulator/requirements.txt /tmp/requirements.txt
RUN --mount=type=cache,target=/root/.cache/pip pip install -r /tmp/requirements.txt
COPY frontend/public/data/traffic-roads.json /rydex/frontend/public/data/traffic-roads.json
COPY simulator/simulate.py ./
COPY simulator/rydex_sim rydex_sim
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
# stops on SIGINT: every simulated driver goes offline first
STOPSIGNAL SIGINT
ENTRYPOINT ["python", "simulate.py"]
CMD ["--city"]
