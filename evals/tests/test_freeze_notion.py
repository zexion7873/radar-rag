import json
from typing import Any

import httpx
import pytest

from radar_evals.freeze_notion import freeze
from radar_evals.models import SOURCES


def _raw_page(pid: str, repo: str) -> dict[str, Any]:
    run = {"type": "text", "annotations": {"bold": False}, "href": None}
    return {
        "object": "page",
        "id": pid,
        "created_by": {"object": "user", "id": "user-id-must-not-leak"},
        "properties": {
            "Repo": {"id": "a", "type": "title", "title": [run | {"plain_text": repo}]},
            "Week": {"id": "b", "type": "date", "date": {"start": "2026-09-21", "end": None}},
            "Stars/wk": {"id": "c", "type": "number", "number": 120},
            "Language": {
                "id": "d",
                "type": "rich_text",
                "rich_text": [run | {"plain_text": "Python"}],
            },
            "Category": {"id": "e", "type": "select", "select": {"id": "s", "name": "agents"}},
            "Link": {"id": "f", "type": "url", "url": f"https://github.com/{repo}"},
            "Description": {
                "id": "g",
                "type": "rich_text",
                "rich_text": [run | {"plain_text": "desc", "href": "https://link-must-not-leak"}],
            },
            "Comment": {"id": "h", "type": "rich_text", "rich_text": []},
            "Risk": {"id": "i", "type": "select", "select": {"name": "low"}},
        },
    }


def _notion(
    pages_by_cursor: dict[str | None, tuple[list[dict[str, Any]], str | None]],
) -> httpx.MockTransport:
    def handle(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/data_sources/ds/query"
        assert request.headers["Authorization"] == "Bearer tok"
        assert request.headers["Notion-Version"] == "2025-09-03"
        cursor = json.loads(request.content).get("start_cursor")
        results, next_cursor = pages_by_cursor[cursor]
        return httpx.Response(
            200,
            json={
                "results": results,
                "has_more": next_cursor is not None,
                "next_cursor": next_cursor,
            },
        )

    return httpx.MockTransport(handle)


def test_freeze_follows_the_cursor_strips_pages_and_sorts_them() -> None:
    transport = _notion(
        {None: ([_raw_page("b", "b/two")], "c2"), "c2": ([_raw_page("a", "a/one")], None)}
    )
    fixture = freeze(
        "tok", "trending", data_source_id="ds", base_url="https://notion.test", transport=transport
    )

    assert fixture.source == "trending"
    assert [p.id for p in fixture.pages] == ["a", "b"]
    first = fixture.pages[0]
    assert set(first.properties) == set(SOURCES["trending"].properties)
    assert first.plain_text("Repo") == "a/one"
    assert first.key(fixture.spec) == ("https://github.com/a/one", "2026-09-21")
    dumped = json.dumps(fixture.model_dump())
    assert "user-id-must-not-leak" not in dumped
    assert "link-must-not-leak" not in dumped


def test_a_page_missing_a_parsed_property_fails_the_capture() -> None:
    page = _raw_page("a", "a/one")
    del page["properties"]["Comment"]
    with pytest.raises(ValueError, match="Comment"):
        freeze(
            "tok",
            "trending",
            data_source_id="ds",
            base_url="https://notion.test",
            transport=_notion({None: ([page], None)}),
        )


def test_a_mention_run_fails_the_capture() -> None:
    page = _raw_page("a", "a/one")
    mention = {"type": "mention", "mention": {"type": "user"}, "plain_text": "@Someone"}
    page["properties"]["Comment"]["rich_text"] = [mention]
    with pytest.raises(ValueError, match="non-text runs"):
        freeze(
            "tok",
            "trending",
            data_source_id="ds",
            base_url="https://notion.test",
            transport=_notion({None: ([page], None)}),
        )
