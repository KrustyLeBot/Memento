"""A fake Tuileries server for the tests (synthetic answers only): records every request with its headers, answers
from a path -> (status, content type, body) table, and checks Basic auth like the real one."""
from __future__ import annotations

import base64
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

USER, PASSWORD = "randonneur", "mot-de-passe-de-test"
WEBP = b"RIFF\x1a\x00\x00\x00WEBPVP8 " + b"\x00" * 14  # a WebP header: the bytes are passed through, never decoded
INFO = b'{"name":"Tuileries","version":"0.1.0","max_zoom":17,"layers":["ortho","dem","buildings","peaks"]}'


def build_meta(z: int, x0: int, y0: int, entries: list[tuple]) -> bytes:
    """A metatile as the contract (v1.1) lays it out; entries are (layer, z, x, y, status, format, encoding, data)."""
    import struct
    out = b"TUIM" + struct.pack(">BBBBIIH", 1, z, 4, 0, x0, y0, len(entries))
    for layer, ez, x, y, status, fmt, enc, data in entries:
        out += struct.pack(">BBIIBBBI", layer, ez, x, y, status, fmt, enc, len(data)) + data
    return out


class FakeTuileries:
    def __init__(self):
        self.requests: list[tuple[str, dict]] = []
        self.answers: dict[str, tuple[int, str, bytes]] = {"/api/info": (200, "application/json", INFO)}
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                fake.requests.append((self.path, {k.lower(): v for k, v in self.headers.items()}))
                expected = "Basic " + base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
                if self.headers.get("Authorization") != expected:
                    self._answer(401, "text/plain", b"auth", {"WWW-Authenticate": 'Basic realm="Tuileries"'})
                    return
                status, kind, body = fake.answers.get(self.path, (404, "text/plain", b"none"))
                self._answer(status, kind, body, {"Cache-Control": "private, max-age=86400"})

            def _answer(self, status, kind, body, headers):
                self.send_response(status)
                self.send_header("Content-Type", kind)
                self.send_header("Content-Length", str(len(body)))
                for k, v in headers.items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}/"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
