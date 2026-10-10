import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from radar_evals import golden, retrieval
from radar_evals.client import RadarClient
from radar_evals.models import FrozenPage, NotionFixture, SyncResponse

A = ("https://github.com/a/one", "2026-09-21")
B = ("https://github.com/b/two", "2026-09-21")
X = ("https://github.com/x/noise", "2026-09-14")


def _hit(key: tuple[str, str] | None) -> dict[str, Any]:
    metadata: dict[str, Any] = {"source": "trending"}
    if key is not None:
        metadata |= {"url": key[0], "week": key[1]}
    return {
        "id": "00000000-0000-0000-0000-000000000000",
        "text": "t",
        "metadata": metadata,
        "score": 0.5,
    }


class FakeService:
    """Answers /sync and /search the way radar-rag does, from a query -> ranked keys table."""

    def __init__(self, answers: dict[str, list[tuple[str, str] | None]], ingested: int) -> None:
        self.answers = answers
        self.ingested = ingested
        self.sources: set[str | None] = set()

    def transport(self) -> httpx.MockTransport:
        def handle(request: httpx.Request) -> httpx.Response:
            if request.url.path == "/sync":
                return httpx.Response(
                    200,
                    json={
                        "ingested": self.ingested,
                        "sources": {"trending": self.ingested},
                        "failed": {},
                    },
                )
            body = json.loads(request.content)
            assert body["topK"] == retrieval.TOP_K
            self.sources.add(body.get("source"))
            return httpx.Response(200, json=[_hit(k) for k in self.answers[body["q"]]])

        return httpx.MockTransport(handle)


def _label(key: tuple[str, str]) -> dict[str, Any]:
    return {"url": key[0], "week": key[1], "grade": 1}


ITEMS = [
    golden.GoldenItem(
        id="zh-1",
        q="向量資料庫",
        lang="zh-TW",
        kind="answerable",
        labels=(golden.Label(**_label(A)),),
    ),
    golden.GoldenItem(
        id="en-1",
        q="terminal coding agent",
        lang="en",
        kind="answerable",
        labels=(golden.Label(**_label(B)),),
    ),
    golden.GoldenItem(id="none-1", q="量子電腦", lang="zh-TW", kind="no-answer"),
]
ANSWERS: dict[str, list[tuple[str, str] | None]] = {
    "向量資料庫": [X, A],
    "terminal coding agent": [None, B],
    "量子電腦": [X],
}


def test_run_items_scores_labelled_items_at_row_and_url_level() -> None:
    with RadarClient("http://svc", transport=FakeService(ANSWERS, 2).transport()) as client:
        results = {r.id: r for r in retrieval.run_items(ITEMS, client)}
    assert results["zh-1"].row is not None and results["zh-1"].row.mrr == 0.5
    assert results["zh-1"].script == "cjk-only"
    # A hit without url/week cannot match a label but still holds rank 1, so B is second.
    assert results["en-1"].retrieved == [None, B]
    assert results["en-1"].row is not None and results["en-1"].row.mrr == 0.5
    assert results["none-1"].row is None and results["none-1"].url is None


def test_aggregate_means_per_language_and_script() -> None:
    answers = ANSWERS | {"terminal coding agent": [X]}
    with RadarClient("http://svc", transport=FakeService(answers, 2).transport()) as client:
        agg = retrieval.aggregate(retrieval.run_items(ITEMS, client))
    assert agg["all"]["n"] == 2 and agg["all"]["hit"] == 0.5
    assert agg["lang:zh-TW"]["hit"] == 1.0
    assert agg["lang:en"]["hit"] == 0.0
    assert agg["script:cjk-only"]["n"] == 1
    assert agg["script:latin"]["n"] == 1


def test_aggregate_keeps_adversarial_items_out_of_language_and_script_groups() -> None:
    trap = golden.GoldenItem(
        id="adv-1",
        q="資料庫",
        lang="zh-TW",
        kind="adversarial",
        labels=(golden.Label(**_label(A)),),
    )
    answers = ANSWERS | {"資料庫": [A]}
    with RadarClient("http://svc", transport=FakeService(answers, 2).transport()) as client:
        agg = retrieval.aggregate(retrieval.run_items([*ITEMS, trap], client))
    assert agg["all"]["n"] == 3
    assert agg["kind:answerable"]["n"] == 2
    assert agg["kind:adversarial"]["n"] == 1 and agg["kind:adversarial"]["hit"] == 1.0
    assert agg["lang:zh-TW"]["n"] == 1
    assert agg["script:cjk-only"]["n"] == 1


def test_another_week_of_a_labelled_repo_is_a_hit_and_no_flip(
    tmp_path: Path, service: FakeService
) -> None:
    golden_path, fixture_path = _write_inputs(tmp_path, ITEMS)
    assert _main(tmp_path, golden_path, fixture_path, "--write-baseline") == 0
    service.answers["向量資料庫"] = [X, (A[0], "2026-09-14")]
    assert _main(tmp_path, golden_path, fixture_path) == 0
    written = retrieval.RunResult.model_validate_json((tmp_path / "results.json").read_text())
    zh = next(r for r in written.items if r.id == "zh-1")
    assert zh.url is not None and zh.url.hit == 1.0
    assert zh.row is not None and zh.row.hit == 0.0
    assert written.aggregates["lang:zh-TW"]["hit"] == 1.0


def _write_inputs(tmp_path: Path, items: list[golden.GoldenItem]) -> tuple[Path, Path]:
    pages = [
        FrozenPage(
            id=f"p{i}",
            properties={
                "Repo": {"type": "title", "title": [{"plain_text": key[0]}]},
                "Link": {"type": "url", "url": key[0]},
                "Week": {"type": "date", "date": {"start": key[1]}},
            },
        )
        for i, key in enumerate([A, B, X])
    ]
    fixture = NotionFixture(data_source_id="ds", captured_at="2026-09-25", pages=pages)
    fixture_path = tmp_path / "fixture.json"
    fixture_path.write_text(fixture.model_dump_json(), encoding="utf-8")
    golden_path = tmp_path / "golden.jsonl"
    golden_path.write_text("\n".join(i.model_dump_json() for i in items) + "\n", encoding="utf-8")
    return golden_path, fixture_path


@pytest.fixture
def service(monkeypatch: pytest.MonkeyPatch) -> FakeService:
    fake = FakeService(dict(ANSWERS), ingested=3)
    monkeypatch.setattr(
        retrieval, "RadarClient", lambda url: RadarClient(url, transport=fake.transport())
    )
    monkeypatch.delenv("GITHUB_STEP_SUMMARY", raising=False)
    return fake


def _main(tmp_path: Path, golden_path: Path, fixture_path: Path, *extra: str) -> int:
    return retrieval.main(
        [
            "--golden", str(golden_path),
            "--fixture", str(fixture_path),
            "--model-id", "all-MiniLM-L6-v2",
            "--out", str(tmp_path / "results.json"),
            "--baseline", str(tmp_path / "baseline.json"),
            "--stub-port", "0",
            *extra,
        ]
    )  # fmt: skip


def test_main_gates_on_a_hit_turning_into_a_miss(tmp_path: Path, service: FakeService) -> None:
    golden_path, fixture_path = _write_inputs(tmp_path, ITEMS)
    assert _main(tmp_path, golden_path, fixture_path, "--write-baseline") == 0
    assert _main(tmp_path, golden_path, fixture_path) == 0
    service.answers["向量資料庫"] = [X]
    assert _main(tmp_path, golden_path, fixture_path) == 1
    written = retrieval.RunResult.model_validate_json((tmp_path / "results.json").read_text())
    assert written.model_id == "all-MiniLM-L6-v2" and len(written.git_sha) == 40


def test_main_refuses_a_baseline_from_another_golden_set(
    tmp_path: Path, service: FakeService
) -> None:
    golden_path, fixture_path = _write_inputs(tmp_path, ITEMS)
    assert _main(tmp_path, golden_path, fixture_path, "--write-baseline") == 0
    golden_path, _ = _write_inputs(tmp_path, ITEMS[:2])
    assert _main(tmp_path, golden_path, fixture_path) == 1


def test_main_stops_on_rotten_labels_and_on_a_short_sync(
    tmp_path: Path, service: FakeService
) -> None:
    rotten = ITEMS[0].model_copy(
        update={"labels": (golden.Label(url=A[0], week="2020-01-06", grade=1),)}
    )
    golden_path, fixture_path = _write_inputs(tmp_path, [rotten])
    assert _main(tmp_path, golden_path, fixture_path, "--write-baseline") == 1

    golden_path, fixture_path = _write_inputs(tmp_path, ITEMS)
    service.ingested = 2
    assert _main(tmp_path, golden_path, fixture_path, "--write-baseline") == 1
    assert not (tmp_path / "baseline.json").exists()


def test_one_fixture_stamps_with_its_file_hash(tmp_path: Path) -> None:
    path = tmp_path / "trending.json"
    path.write_text("{}", encoding="utf-8")
    # sha256("{}"): the stamp a baseline recorded before blog existed must still match.
    expected = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
    assert retrieval.fixture_stamp([path]) == expected
    other = tmp_path / "blog.json"
    other.write_text("[]", encoding="utf-8")
    assert retrieval.fixture_stamp([path, other]) == retrieval.fixture_stamp([other, path])
    assert retrieval.fixture_stamp([path, other]) != expected


def test_sync_mismatch_names_a_failed_or_short_source() -> None:
    fixture = NotionFixture(source="blog", data_source_id="ds", captured_at="d", pages=[])
    ok = SyncResponse(ingested=0, sources={"blog": 0}, failed={})
    failed = SyncResponse(ingested=0, sources={}, failed={"blog": "upstream 404"})
    short = SyncResponse(ingested=0, sources={"blog": 0, "trending": 3}, failed={})
    assert retrieval.sync_mismatch(ok, [fixture]) is None
    assert "upstream 404" in (retrieval.sync_mismatch(failed, [fixture]) or "")
    assert "trending" in (retrieval.sync_mismatch(short, [fixture]) or "")


def test_main_queries_only_the_named_source(tmp_path: Path, service: FakeService) -> None:
    golden_path, fixture_path = _write_inputs(tmp_path, ITEMS)
    assert (
        _main(tmp_path, golden_path, fixture_path, "--write-baseline", "--source", "trending") == 0
    )
    assert service.sources == {"trending"}


def test_main_rejects_a_missing_baseline_before_syncing(
    tmp_path: Path, service: FakeService
) -> None:
    golden_path, fixture_path = _write_inputs(tmp_path, ITEMS)
    with pytest.raises(SystemExit):
        _main(tmp_path, golden_path, fixture_path)
    assert not (tmp_path / "results.json").exists()
