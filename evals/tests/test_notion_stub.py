import httpx

from radar_evals.models import FrozenPage, NotionFixture
from radar_evals.notion_stub import NotionStub


def _fixture(n: int, ds: str = "ds") -> NotionFixture:
    pages = [FrozenPage(id=f"{ds}{i:03d}", properties={}) for i in range(n)]
    return NotionFixture(data_source_id=ds, captured_at="2026-09-25", pages=pages)


def test_query_pages_through_the_fixture_like_notion() -> None:
    sizes: list[int] = []
    seen: list[str] = []
    with NotionStub([_fixture(250)]) as stub, httpx.Client(base_url=stub.base_url) as http:
        cursor = None
        while True:
            body: dict[str, object] = {"page_size": 100}
            if cursor:
                body["start_cursor"] = cursor
            page = http.post("/data_sources/ds/query", json=body).json()
            sizes.append(len(page["results"]))
            seen += [p["id"] for p in page["results"]]
            if not page["has_more"]:
                assert page["next_cursor"] is None
                break
            cursor = page["next_cursor"]
    assert sizes == [100, 100, 50]
    assert seen == [f"ds{i:03d}" for i in range(250)]


def test_a_chunked_request_body_is_read() -> None:
    # The service's JDK HttpClient sends POST bodies chunked, with no Content-Length.
    body = b'{"page_size": 100, "start_cursor": "100"}'
    with NotionStub([_fixture(150)]) as stub, httpx.Client(base_url=stub.base_url) as http:
        page = http.post("/data_sources/ds/query", content=iter([body[:10], body[10:]])).json()
    assert [p["id"] for p in page["results"]] == [f"ds{i:03d}" for i in range(100, 150)]


def test_only_the_configured_data_source_resolves() -> None:
    with NotionStub([_fixture(1)]) as stub, httpx.Client(base_url=stub.base_url) as http:
        assert http.get("/data_sources/ds").json() == {"object": "data_source", "id": "ds"}
        assert http.get("/data_sources/other").status_code == 404
        assert http.get("/databases/ds").status_code == 404
        assert http.post("/data_sources/other/query", json={}).status_code == 404


def test_each_fixture_answers_under_its_own_data_source() -> None:
    with (
        NotionStub([_fixture(2, "tr"), _fixture(3, "bl")]) as stub,
        httpx.Client(base_url=stub.base_url) as http,
    ):
        assert http.get("/data_sources/bl").json()["id"] == "bl"
        tr = http.post("/data_sources/tr/query", json={}).json()["results"]
        bl = http.post("/data_sources/bl/query", json={}).json()["results"]
    assert [p["id"] for p in tr] == ["tr000", "tr001"]
    assert [p["id"] for p in bl] == ["bl000", "bl001", "bl002"]
