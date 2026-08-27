# Base image pinned by digest, matching the MVP-0 discipline: a tag can move, a digest cannot, and
# the release pipeline scans and attests exactly these bytes.
FROM python:3.14-slim@sha256:ce40764625a4ff50df3548277632e7f96c4e77fe75fa848aae9885476e7df5a4 AS build

WORKDIR /build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1

# Dependency metadata first: this layer is rebuilt only when the pins actually change.
COPY ramals-ai/pyproject.toml ./
COPY ramals-ai/src ./src

# Installed WITH the provider extra. The extra is optional so that CI and a local checkout can run
# the whole gateway path on `ci-fake` without pulling a large SDK -- but this image is the
# deployable artifact, and a deployable artifact that cannot reach a provider can only ever serve
# the deterministic fake. Without it every live route fails at its first call with
# ROUTE_NOT_CONFIGURED, in production, on the first learner request that reaches an agent.
#
# The import in litellm_adapter stays lazy regardless, so a service configured for `ci-fake` still
# never imports it. Installing it costs image size; not installing it cost the ability to serve.
#
# scripts/ci/export-python-requirements.py already projects every optional group into the pip-audit
# input, so this adds no unaudited dependency.
RUN python -m pip install --prefix=/install ".[provider]"

FROM python:3.14-slim@sha256:ce40764625a4ff50df3548277632e7f96c4e77fe75fa848aae9885476e7df5a4

# Non-root, no login shell, no home directory to write into. The service needs none of them, and a
# process that cannot write is a process that cannot be persuaded to write something unexpected.
RUN groupadd --system --gid 10001 ramals \
 && useradd --system --uid 10001 --gid ramals --no-create-home --shell /usr/sbin/nologin ramals

COPY --from=build /install /usr/local

# Patch the base image's OS packages before anything else.
#
# python:3.14-slim currently ships fixable HIGH advisories in both util-linux and OpenSSL. The
# release scan runs with --ignore-unfixed, so these count: fixes exist and this pinned image does
# not have them. In particular, OpenSSL 3.5.6 carries CVE-2026-14456 and Debian publishes 3.5.7.
#
# Pinning the base by digest is what makes a build reproducible, and it is also what stops it
# picking up security updates on its own. The current digest IS the newest python:3.14-slim -- there
# is no rebuilt base to move to -- so the packages are upgraded here instead. When upstream rebuilds,
# this layer becomes a no-op rather than something that has to be remembered and removed.
#
# Only the affected util-linux and OpenSSL package families are upgraded. A blanket
# 'apt-get upgrade' would quietly change packages nobody reviewed, which is the opposite of what
# pinning a digest is for.
RUN apt-get update  && apt-get install --yes --no-install-recommends --only-upgrade       bsdutils libblkid1 liblastlog2-2 libmount1 libsmartcols1 libuuid1 login mount util-linux       libssl3t64 openssl openssl-provider-legacy  && rm -rf /var/lib/apt/lists/*

# Remove pip from the runtime image.
#
# Not a size optimisation. pip vendors its own copies of libraries, and those copies are what the
# release scan flags: msgpack and setuptools inside pip/_vendor, neither of which this service
# imports and neither of which any project dependency asks for. They arrive with the base image and
# there is no version of them to bump, because they are not dependencies -- they are pip's insides.
#
# Removing pip fixes that by deleting the thing that carries them, and it is the right answer
# independently of any advisory: a running service has no reason to install packages, and an image
# that ships an installer hands anyone who achieves code execution a way to fetch more code. The
# entrypoint is uvicorn and the healthcheck is python -c; neither needs pip.
#
# The glob avoids naming the Python version, so a base-image bump does not silently stop deleting
# anything.
RUN rm -rf /usr/local/lib/python*/site-packages/pip            /usr/local/lib/python*/site-packages/pip-*.dist-info            /usr/local/lib/python*/site-packages/pkg_resources            /usr/local/lib/python*/site-packages/setuptools            /usr/local/lib/python*/site-packages/setuptools-*.dist-info            /usr/local/bin/pip /usr/local/bin/pip[0-9]*

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
