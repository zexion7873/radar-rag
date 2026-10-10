"""End to end against a running service whose NOTION_BASE_URL points at this stub's port."""

import os
from collections.abc import Iterator
from pathlib import Path

import pytest

from radar_evals.client import RadarClient
from radar_evals.models import NotionFixture, SyncResponse
from radar_evals.notion_stub import NotionStub

pytestmark = pytest.mark.service

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"


@pytest.fixture(scope="module")
def fixture_data() -> list[NotionFixture]:
    return [
        NotionFixture.model_validate_json((FIXTURES / f"{s}.json").read_text(encoding="utf-8"))
        for s in ("trending", "blog")
    ]


@pytest.fixture(scope="module")
def synced(fixture_data: list[NotionFixture]) -> Iterator[tuple[RadarClient, SyncResponse]]:
    url = os.environ.get("EVAL_SERVICE_URL")
    if not url:
        pytest.fail("EVAL_SERVICE_URL is not set: start the service against the Notion stub first")
    port = int(os.environ.get("EVAL_NOTION_STUB_PORT", "8765"))
    with NotionStub(fixture_data, port=port), RadarClient(url) as client:
        yield client, client.sync()


def test_sync_ingests_every_embeddable_row_of_each_source(
    synced: tuple[RadarClient, SyncResponse], fixture_data: list[NotionFixture]
) -> None:
    expected = {f.source: f.embeddable_count() for f in fixture_data}
    assert synced[1].failed == {}
    assert synced[1].sources == expected
    assert synced[1].ingested == sum(expected.values())


def test_search_returns_k_distinct_rows_from_the_fixtures(
    synced: tuple[RadarClient, SyncResponse], fixture_data: list[NotionFixture]
) -> None:
    hits = synced[0].search("coding agent", 5)
    assert len(hits) == 5
    assert len({h.id for h in hits}) == 5
    known = set().union(*(f.keys() for f in fixture_data))
    assert all(h.key in known for h in hits)


def test_a_source_filter_returns_that_source_only(
    synced: tuple[RadarClient, SyncResponse],
) -> None:
    hits = synced[0].search("coding agent", 5, source="blog")
    assert len(hits) == 5
    assert {h.metadata.source for h in hits} == {"blog"}
