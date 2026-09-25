"""A local stand-in for the two Notion endpoints NotionClient calls, served from a fixture."""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import TracebackType
from typing import Any, Self

from radar_evals.models import NotionFixture

MAX_PAGE_SIZE = 100


def _handler(fixture: NotionFixture) -> type[BaseHTTPRequestHandler]:
    pages = [p.model_dump() for p in fixture.pages]

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args: Any) -> None:
            pass

        def _send(self, status: int, body: dict[str, Any]) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _not_found(self) -> None:
            self._send(404, {"object": "error", "status": 404, "code": "object_not_found"})

        def do_GET(self) -> None:
            if self.path == f"/data_sources/{fixture.data_source_id}":
                self._send(200, {"object": "data_source", "id": fixture.data_source_id})
            else:
                self._not_found()

        def _read_body(self) -> bytes:
            # The service's JDK HttpClient sends POST bodies chunked. An unread body loses the
            # cursor, and closing on it resets the connection before the client reads the reply.
            if "chunked" not in self.headers.get("Transfer-Encoding", "").lower():
                return self.rfile.read(int(self.headers.get("Content-Length", "0")))
            chunks = []
            while size := int(self.rfile.readline().split(b";")[0], 16):
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                pass
            return b"".join(chunks)

        def do_POST(self) -> None:
            raw = self._read_body()
            if self.path != f"/data_sources/{fixture.data_source_id}/query":
                self._not_found()
                return
            body = json.loads(raw or b"{}")
            size = min(int(body.get("page_size", MAX_PAGE_SIZE)), MAX_PAGE_SIZE)
            start = int(body.get("start_cursor") or 0)
            end = start + size
            has_more = end < len(pages)
            self._send(
                200,
                {
                    "object": "list",
                    "results": pages[start:end],
                    "has_more": has_more,
                    "next_cursor": str(end) if has_more else None,
                },
            )

    return Handler


class NotionStub:
    """Serves the fixture on a background thread; port 0 picks a free port."""

    def __init__(self, fixture: NotionFixture, host: str = "127.0.0.1", port: int = 0) -> None:
        self._server = ThreadingHTTPServer((host, port), _handler(fixture))
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        host, port = self._server.server_address[:2]
        return f"http://{host!s}:{port}"

    def __enter__(self) -> Self:
        self._thread.start()
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join()
