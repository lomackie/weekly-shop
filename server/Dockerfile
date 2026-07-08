# The base image version must match the `playwright` version in uv.lock:
# the image ships the browser builds that exact Playwright release expects.
FROM mcr.microsoft.com/playwright/python:v1.61.0-noble

COPY --from=ghcr.io/astral-sh/uv:0.9 /uv /usr/local/bin/uv

WORKDIR /app

# Dependencies first so code edits don't bust the layer cache.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY weekly_shop ./weekly_shop
COPY seed ./seed
RUN uv sync --frozen --no-dev

ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8000
CMD ["uvicorn", "weekly_shop.main:app", "--host", "0.0.0.0", "--port", "8000"]
