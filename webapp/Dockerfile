# Mémento web. The build context is the REPOSITORY ROOT (the server imports the Windows client's core, the map
# page is web/map, the logo is branding/):
#     docker build -f webapp/Dockerfile -t memento-web .

# ---- the UI: Svelte + Vite, built once; .gz siblings so the server never compresses at request time ----
FROM node:22.12.0-bookworm-slim@sha256:35531c52ce27b6575d69755c73e65d4468dba93a25644eed56dc12879cae9213 AS ui
WORKDIR /src/webapp/ui
COPY webapp/ui/package.json webapp/ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY webapp/ui/ ./
COPY branding/ /src/branding/
RUN npm run build \
 && find dist -type f \( -name '*.js' -o -name '*.css' -o -name '*.svg' -o -name '*.html' \) -exec gzip -9 -k {} +

# ---- the server: python slim (glibc: musl's allocator holds more memory) ----
FROM python:3.12.8-slim-bookworm@sha256:2199a62885a12290dc9c5be3ca0681d367576ab7bf037da120e564723292a2f0
ARG BUILD=dev
LABEL org.opencontainers.image.source="https://github.com/KrustyLeBot/Memento" \
      org.opencontainers.image.title="memento-web" \
      org.opencontainers.image.description="Mémento (notes, agenda, activités, contacts) in a browser, on the synced folder"
# MALLOC_ARENA_MAX: two glibc arenas instead of one per thread keep the idle footprint small.
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 MALLOC_ARENA_MAX=2 \
    MEMENTO_DATA=/data MEMENTO_CONFIG=/config MEMENTO_PORT=8080 MEMENTO_BUILD=$BUILD
WORKDIR /app
# the system zone database: TZ must drive local times (file dates, floating agenda times) as on the PC
RUN apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/*
COPY webapp/server/requirements.txt ./
RUN pip install --no-cache-dir --no-compile -r requirements.txt
# the Windows client's pure modules, used as is (memento_win/core imports no Qt)
COPY windows/memento_win/__init__.py memento_win/__init__.py
COPY windows/memento_win/core/ memento_win/core/
COPY web/map/ web/map/
COPY webapp/server/memento_web/ memento_web/
COPY --from=ui /src/webapp/ui/dist/ static/
# bytecode compiled now, so a read-only root filesystem costs nothing at run time
RUN python -m compileall -q /app /usr/local/lib/python3.12 \
 && useradd --uid 1000 --user-group --no-create-home --shell /usr/sbin/nologin memento \
 && mkdir -p /data /config && chown memento:memento /data /config
# The default user is unprivileged; docker-compose.example.yml explains when to run as the data's owner instead.
USER memento
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=4)"]
CMD ["python", "-m", "memento_web"]
