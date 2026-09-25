"""End to end against a running service whose NOTION_BASE_URL points at this stub's port."""

import os
from collections.abc import Iterator
from pathlib import Path

import pytest

from radar_evals.client import RadarClient
from radar_evals.models import NotionFixture
from radar_evals.notion_stub import NotionStub

pytestmark = pytest.mark.service

FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "trending.json"


@pytest.fixture(scope="module")
def fixture_data() -> NotionFixture:
    return NotionFixture.model_validate_json(FIXTURE.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def synced(fixture_data: NotionFixture) -> Iterator[tuple[RadarClient, int]]:
    url = os.environ.get("EVAL_SERVICE_URL")
    if not url:
        pytest.fail("EVAL_SERVICE_URL is not set: start the service against the Notion stub first")
    port = int(os.environ.get("EVAL_NOTION_STUB_PORT", "8765"))
    with NotionStub(fixture_data, port=port), RadarClient(url) as client:
        yield client, client.sync().ingested


def test_sync_ingests_every_embeddable_fixture_row(
    synced: tuple[RadarClient, int], fixture_data: NotionFixture
) -> None:
    assert synced[1] == sum(1 for p in fixture_data.pages if p.embeddable)


def test_search_returns_k_distinct_rows_from_the_fixture(
    synced: tuple[RadarClient, int], fixture_data: NotionFixture
) -> None:
    hits = synced[0].search("coding agent", 5)
    assert len(hits) == 5
    assert len({h.id for h in hits}) == 5
    known = fixture_data.keys()
    assert all(h.key in known for h in hits)
