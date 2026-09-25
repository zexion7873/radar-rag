"""Capture the Trending table from Notion into a fixture the stub can serve.

Keeps each page's id and only the properties NotionClient reads, and each text run only as
`plain_text`. A mention or equation run fails the capture: its plain_text is a user's name, a
page title or a preview URL, which must not reach a committed fixture unreviewed.
Usage: NOTION_TOKEN=... uv run freeze-notion [--data-source ID] [--out PATH]
"""

import argparse
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx

from radar_evals.models import FrozenPage, NotionFixture

NOTION_API = "https://api.notion.com/v1"
NOTION_VERSION = "2025-09-03"
TRENDING_DATA_SOURCE = "f67aaa24-d5f2-415c-9358-c7d9d2f9713e"
# Must match what NotionClient.fetchTrending reads; a column the parser needs but the fixture
# dropped would make the stubbed /sync silently ingest blanks.
PARSED_PROPERTIES = (
    "Repo",
    "Week",
    "Stars/wk",
    "Language",
    "Category",
    "Link",
    "Description",
    "Comment",
)
DEFAULT_OUT = Path(__file__).resolve().parents[2] / "fixtures" / "trending.json"


def _strip_property(name: str, prop: dict[str, Any]) -> dict[str, Any]:
    kind = prop.get("type")
    if not isinstance(kind, str) or kind not in prop:
        raise ValueError(f"property {name!r} has no value for its type {kind!r}")
    value = prop[kind]
    if kind in ("title", "rich_text"):
        foreign = sorted({run.get("type") for run in value} - {"text"})
        if foreign:
            raise ValueError(f"property {name!r} holds non-text runs {foreign}")
        value = [{"plain_text": run.get("plain_text", "")} for run in value]
    return {"type": kind, kind: value}


def strip_page(page: dict[str, Any]) -> FrozenPage:
    props = page["properties"]
    missing = [p for p in PARSED_PROPERTIES if p not in props]
    if missing:
        raise ValueError(f"page {page['id']} lacks parsed properties {missing}")
    return FrozenPage(
        id=page["id"],
        properties={p: _strip_property(p, props[p]) for p in PARSED_PROPERTIES},
    )


def freeze(
    token: str,
    data_source_id: str,
    *,
    base_url: str = NOTION_API,
    transport: httpx.BaseTransport | None = None,
) -> NotionFixture:
    headers = {"Authorization": f"Bearer {token}", "Notion-Version": NOTION_VERSION}
    pages: list[FrozenPage] = []
    with httpx.Client(
        base_url=base_url, headers=headers, transport=transport, timeout=60.0
    ) as http:
        cursor: str | None = None
        while True:
            body: dict[str, Any] = {"page_size": 100}
            if cursor:
                body["start_cursor"] = cursor
            resp = http.post(f"/data_sources/{data_source_id}/query", json=body)
            resp.raise_for_status()
            payload = resp.json()
            pages.extend(strip_page(p) for p in payload["results"])
            cursor = payload.get("next_cursor") if payload.get("has_more") else None
            if cursor is None:
                break
    # Notion's query order is unspecified; sorting keeps re-captures diffable.
    pages.sort(key=lambda p: p.id)
    return NotionFixture(
        data_source_id=data_source_id,
        captured_at=datetime.now(UTC).date().isoformat(),
        pages=pages,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0] if __doc__ else None)
    parser.add_argument("--data-source", default=TRENDING_DATA_SOURCE)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)
    token = os.environ.get("NOTION_TOKEN", "")
    if not token:
        print("NOTION_TOKEN is not set", file=sys.stderr)
        return 2
    fixture = freeze(token, args.data_source)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(fixture.model_dump(), ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )
    print(f"wrote {len(fixture.pages)} pages to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
