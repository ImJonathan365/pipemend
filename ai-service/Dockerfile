FROM python:3.14-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.12.19 /uv /uvx /bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

WORKDIR /app

# Dependencies first: this layer stays cached while only sources change.
# --frozen makes the build fail if uv.lock is stale, so an image is never produced from an
# unpinned resolution. --no-dev keeps pytest, ruff and mypy out of the runtime.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

COPY app/ app/

# ----- runtime stage -----
FROM python:3.14-slim AS runtime

# Non-root, per NFR-14.
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin pipemend

WORKDIR /app
COPY --from=build --chown=10001:10001 /app /app

# Use the virtualenv built in the previous stage; uv itself is not shipped in the runtime image.
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

USER 10001
EXPOSE 8000

# NFR-10: exactly one Uvicorn worker. The LRU cache and the daily call counter live in process
# memory, so more workers would make cache hits and the daily cap unpredictable.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
