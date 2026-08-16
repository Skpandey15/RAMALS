# Base image pinned by digest, matching the MVP-0 discipline: a tag can move, a digest cannot, and
# the release pipeline scans and attests exactly these bytes.
FROM python:3.14-slim@sha256:ce40764625a4ff50df3548277632e7f96c4e77fe75fa848aae9885476e7df5a4 AS build

WORKDIR /build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1

# Dependency metadata first: this layer is rebuilt only when the pins actually change.
COPY ramals-ai/pyproject.toml ./
COPY ramals-ai/src ./src
RUN python -m pip install --prefix=/install .

FROM python:3.14-slim@sha256:ce40764625a4ff50df3548277632e7f96c4e77fe75fa848aae9885476e7df5a4

# Non-root, no login shell, no home directory to write into. The service needs none of them, and a
# process that cannot write is a process that cannot be persuaded to write something unexpected.
RUN groupadd --system --gid 10001 ramals \
 && useradd --system --uid 10001 --gid ramals --no-create-home --shell /usr/sbin/nologin ramals

COPY --from=build /install /usr/local

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    RAMALS_AI_SERVICE_NAME=ramals-ai

USER 10001:10001
EXPOSE 8000

# Liveness only. Readiness is a deployment concern (Doc 06 §4): the orchestrator decides whether to
# route traffic, and ramals-ai being out of service must never make the deterministic core unready.
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health/live', timeout=2).status==200 else 1)"]

ENTRYPOINT ["python", "-m", "uvicorn", "ramals_ai.main:create_app", "--factory", \
            "--host", "0.0.0.0", "--port", "8000"]
