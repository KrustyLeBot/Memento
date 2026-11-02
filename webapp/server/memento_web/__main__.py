"""`python -m memento_web`: checks the configuration, then serves on 0.0.0.0:$MEMENTO_PORT (8080)."""
from __future__ import annotations

import os
import sys

import uvicorn

from .config import ConfigError, from_env


def main() -> int:
    # New files and folders as the WebDAV server writes them (644 / 755), whoever runs the container.
    os.umask(0o022)
    try:
        config = from_env()
    except ConfigError as error:
        print(f"memento-web : démarrage refusé. {error}", file=sys.stderr, flush=True)
        return 2
    from .app import create_app
    app = create_app(config)
    print(f"memento-web: serving {config.data} for {config.user}", flush=True)
    uvicorn.run(app, host=os.environ.get("MEMENTO_HOST", "0.0.0.0"), port=int(os.environ.get("MEMENTO_PORT", "8080")),
                proxy_headers=False, server_header=False, timeout_graceful_shutdown=5)
    return 0


if __name__ == "__main__":
    sys.exit(main())
