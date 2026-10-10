from pathlib import Path
from typing import Any

import pytest
from pydantic import ValidationError

from radar_evals import golden
from radar_evals.models import FrozenPage, NotionFixture

LABEL = {"url": "https://github.com/a/one", "week": "2026-09-21", "grade": 2}


def _item(**over: Any) -> golden.GoldenItem:
    base: dict[str, Any] = {
        "id": "zh-1",
        "q": "向量資料庫",
        "lang": "zh-TW",
        "kind": "answerable",
        "labels": [LABEL],
    }
    return golden.GoldenItem.model_validate(base | over)


@pytest.mark.parametrize(
    ("q", "script"),
    [
        ("向量資料庫相似度搜尋", "cjk-only"),
        ("2026 年最熱門的專案", "cjk-only"),
        ("有哪些 agent 框架？", "mixed"),
        ("agent frameworks", "latin"),
    ],
)
def test_script_is_derived_from_the_question(q: str, script: str) -> None:
    assert golden.script_of(q) == script


def test_label_rules_follow_the_item_kind() -> None:
    with pytest.raises(ValidationError, match="needs at least one label"):
        _item(labels=[])
    with pytest.raises(ValidationError, match="cannot carry labels"):
        _item(kind="no-answer")
    with pytest.raises(ValidationError, match="labelled twice"):
        _item(labels=[LABEL, LABEL | {"grade": 1}])
    assert _item(kind="adversarial", labels=[]).labels == ()


def test_pairs_must_point_back_cross_languages_and_share_labels() -> None:
    zh = _item(id="zh-1", pair="en-1")
    en = _item(id="en-1", q="vector database", lang="en", pair="zh-1")
    golden.validate_set([zh, en])
    with pytest.raises(ValueError, match="does not point back"):
        golden.validate_set([zh, en.model_copy(update={"pair": None})])
    with pytest.raises(ValueError, match="cross languages"):
        golden.validate_set([zh, en.model_copy(update={"lang": "zh-TW"})])
    with pytest.raises(ValueError, match="different labels"):
        golden.validate_set(
            [
                zh,
                _item(
                    id="en-1",
                    q="vector database",
                    lang="en",
                    pair="zh-1",
                    labels=[LABEL | {"grade": 1}],
                ),
            ]
        )
    with pytest.raises(ValueError, match="duplicate golden ids"):
        golden.validate_set([zh, zh])


def test_load_reads_one_item_per_line(tmp_path: Path) -> None:
    path = tmp_path / "golden.jsonl"
    path.write_text(
        _item().model_dump_json() + "\n\n" + _item(id="zh-2").model_dump_json() + "\n",
        encoding="utf-8",
    )
    assert [i.id for i in golden.load(path)] == ["zh-1", "zh-2"]


def test_rotten_labels_are_the_ones_missing_from_the_fixture() -> None:
    page = FrozenPage(
        id="p1",
        properties={
            "Link": {"type": "url", "url": "https://github.com/a/one"},
            "Week": {"type": "date", "date": {"start": "2026-09-21"}},
        },
    )
    fixture = NotionFixture(data_source_id="ds", captured_at="2026-09-25", pages=[page])
    stale = LABEL | {"week": "2026-09-14"}
    items = [_item(), _item(id="zh-2", labels=[LABEL, stale])]
    assert golden.rotten_labels(items, fixture.keys()) == [
        ("zh-2", ("https://github.com/a/one", "2026-09-14"))
    ]


GOLDEN_V1 = Path(__file__).resolve().parents[1] / "golden_v1.jsonl"
FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "trending.json"


def test_golden_v1_labels_all_exist_in_the_fixture() -> None:
    fixture = NotionFixture.model_validate_json(FIXTURE.read_text(encoding="utf-8"))
    assert golden.rotten_labels(golden.load(GOLDEN_V1), fixture.keys()) == []


def test_golden_v1_has_enough_cjk_only_items_for_the_embedding_ab() -> None:
    zh = [i for i in golden.load(GOLDEN_V1) if i.kind == "answerable" and i.lang == "zh-TW"]
    assert sum(i.script == "cjk-only" for i in zh) >= 10
