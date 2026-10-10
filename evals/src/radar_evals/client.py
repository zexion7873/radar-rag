"""The service as the harness sees it: its HTTP endpoints, validated on the way in."""

from types import TracebackType
from typing import Self

import httpx
from pydantic import TypeAdapter

from radar_evals.models import AskResponse, SearchHit, SyncResponse

_HITS = TypeAdapter(list[SearchHit])


class RadarClient:
    def __init__(
        self,
        base_url: str,
        *,
        transport: httpx.BaseTransport | None = None,
        timeout: float = 300.0,
    ) -> None:
        # /sync embeds the whole table in-process, so the timeout covers a cold model load.
        self._http = httpx.Client(base_url=base_url, transport=transport, timeout=timeout)

    def sync(self) -> SyncResponse:
        resp = self._http.post("/sync")
        # A 502 still carries the per-source result: what synced, and why the rest failed.
        if resp.status_code != httpx.codes.BAD_GATEWAY:
            resp.raise_for_status()
        return SyncResponse.model_validate(resp.json())

    def search(self, q: str, top_k: int, source: str | None = None) -> list[SearchHit]:
        body: dict[str, str | int] = {"q": q, "topK": top_k}
        if source is not None:
            body["source"] = source
        resp = self._http.post("/search", json=body)
        resp.raise_for_status()
        return _HITS.validate_python(resp.json())

    def ask(self, q: str, headers: dict[str, str] | None = None) -> AskResponse:
        resp = self._http.post("/ask", json={"q": q}, headers=headers)
        resp.raise_for_status()
        return AskResponse.model_validate(resp.json())

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()
