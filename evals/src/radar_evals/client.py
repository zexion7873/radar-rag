"""The service as the harness sees it: two HTTP endpoints, validated on the way in."""

from types import TracebackType
from typing import Self

import httpx
from pydantic import TypeAdapter

from radar_evals.models import SearchHit, SyncResponse

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
        resp.raise_for_status()
        return SyncResponse.model_validate(resp.json())

    def search(self, q: str, top_k: int) -> list[SearchHit]:
        resp = self._http.post("/search", json={"q": q, "topK": top_k})
        resp.raise_for_status()
        return _HITS.validate_python(resp.json())

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
