# syntax=docker/dockerfile:1
# ai-service (ADR 0022, 0023), from the repo root: docker build -f ai-service/Dockerfile .
# The app sits at /rydex/ai-service as in the repo, so it finds the traffic map at
# /rydex/frontend/public/data/traffic-roads.json without any setting.

FROM python:3.12-slim-bookworm AS deps
RUN python -m venv /opt/venv
COPY ai-service/requirements.txt /tmp/requirements.txt
RUN --mount=type=cache,target=/root/.cache/pip \
    /opt/venv/bin/pip install -r /tmp/requirements.txt

FROM python:3.12-slim-bookworm
LABEL org.opencontainers.image.source="https://github.com/Boovesh985/rydex" \
      org.opencontainers.image.title="rydex-ai-service" \
      org.opencontainers.image.licenses="MIT"
# LightGBM needs OpenMP at run time
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 rydex \
    && useradd --system --uid 10001 --gid rydex --no-create-home --shell /usr/sbin/nologin rydex
COPY --from=deps /opt/venv /opt/venv
WORKDIR /rydex/ai-service
COPY frontend/public/data/traffic-roads.json /rydex/frontend/public/data/traffic-roads.json
COPY ai-service/app app
COPY ai-service/training training
COPY ai-service/models models
USER 10001:10001
ENV PATH=/opt/venv/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
EXPOSE 8085
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8085/health', timeout=3)" || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8085", "--no-access-log"]
