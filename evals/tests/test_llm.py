import asyncio
import json
from typing import Any

import httpx
import pytest

from radar_evals import golden, llm
from radar_evals.client import RadarClient
from radar_evals.models import AskResponse, FrozenPage, NotionFixture

REPOS = [
    "microsoft/markitdown",
    "openai/codex",
    "anthropics/skills",
    "vercel-labs/skills",
    "AgriciDaniel/claude-obsidian",
    "comfyanonymous/ComfyUI",
]


def _page(repo: str) -> FrozenPage:
    return FrozenPage(
        id=repo, properties={"Repo": {"type": "title", "title": [{"plain_text": repo}]}}
    )


FIXTURE = NotionFixture(
    data_source_id="ds", captured_at="2026-10-09", pages=[_page(r) for r in REPOS]
)
ALIASES = llm.repo_aliases([FIXTURE])


def test_aliases_keep_full_names_and_only_distinctive_unique_bare_names() -> None:
    assert ALIASES == {
        "microsoft/markitdown": "microsoft/markitdown",
        "openai/codex": "openai/codex",
        "anthropics/skills": "anthropics/skills",
        "vercel-labs/skills": "vercel-labs/skills",
        "agricidaniel/claude-obsidian": "agricidaniel/claude-obsidian",
        "comfyanonymous/comfyui": "comfyanonymous/comfyui",
        "claude-obsidian": "agricidaniel/claude-obsidian",
        "comfyui": "comfyanonymous/comfyui",
    }


@pytest.mark.parametrize(
    ("answer", "expected"),
    [
        ("可以用ComfyUI來做", {"comfyanonymous/comfyui"}),
        ("看 microsoft/markitdown。", {"microsoft/markitdown"}),
        ("https://github.com/openai/codex", {"openai/codex"}),
        ("`claude-obsidian` 跟 Obsidian 整合", {"agricidaniel/claude-obsidian"}),
        ("codex 和 skills 都是普通的字", set()),
        ("claude-obsidian-x 是別的專案", set()),
        ("CI/CD and/or input/output", set()),
    ],
)
def test_mentions(answer: str, expected: set[str]) -> None:
    assert llm.mentioned_repos(answer, ALIASES) == expected


def _row(repo: str, week: str | None = None) -> dict[str, Any]:
    url = f"https://github.com/{repo}" if week else None
    return {"id": f"id-{repo}", "repo": repo, "url": url, "week": week, "score": 0.5}


def _ask(
    answer: str,
    cited: list[str],
    model: str = "claude-opus-5-5",
    retrieved: list[str] | None = None,
) -> dict[str, Any]:
    return {
        "answer": answer,
        "citations": [_row(r) | {"citedText": ["t"]} for r in cited],
        "sources": [_row(r) for r in (cited if retrieved is None else retrieved)],
        "usage": {"model": model, "inputTokens": 1000, "outputTokens": 500},
    }


def test_unsourced_and_uncited_split_named_repos_by_retrieval_and_citation() -> None:
    resp = AskResponse.model_validate(
        _ask(
            "ComfyUI 和 microsoft/markitdown，openai/codex 無關",
            ["comfyanonymous/ComfyUI"],
            retrieved=["comfyanonymous/ComfyUI", "openai/codex"],
        )
    )
    assert llm.unsourced_repos(resp, ALIASES) == ["microsoft/markitdown"]
    assert llm.uncited_repos(resp, ALIASES) == ["openai/codex"]


def test_uncited_compares_repo_names_case_insensitively() -> None:
    resp = AskResponse.model_validate(
        _ask(
            "ComfyUI 和 microsoft/markitdown",
            ["comfyanonymous/ComfyUI"],
            retrieved=["comfyanonymous/ComfyUI", "microsoft/markitdown"],
        )
    )
    assert llm.uncited_repos(resp, ALIASES) == ["microsoft/markitdown"]


def test_citation_precision_is_the_labelled_share_of_cited_rows() -> None:
    week = "2026-09-21"
    resp = AskResponse.model_validate(
        _ask("a", [])
        | {
            "citations": [
                _row("a/x", week) | {"citedText": ["t"]},
                _row("b/y", week) | {"citedText": ["t"]},
            ]
        }
    )
    gains = {("https://github.com/a/x", week): 2, ("https://github.com/c/z", week): 1}
    assert llm.citation_precision(resp, gains) == 0.5
    assert llm.citation_precision(resp, {}) is None
    assert llm.citation_precision(AskResponse.model_validate(_ask("a", [])), gains) is None


def test_generator_cost_uses_opus_5_5_prices() -> None:
    # 1000 x $4/MTok + 500 x $20/MTok
    assert llm.generator_cost(AskResponse.model_validate(_ask("a", []))) == pytest.approx(0.014)


def test_generator_cost_refuses_an_unpriced_model() -> None:
    with pytest.raises(ValueError, match="no price"):
        llm.generator_cost(AskResponse.model_validate(_ask("a", [], model="claude-opus-9")))


def _result(id: str, **scores: float) -> llm.ItemResult:
    return llm.ItemResult(
        id=id,
        kind="answerable",
        lang="zh-TW",
        q="q",
        answer="a",
        metrics={n: llm.MetricResult(score=s, reason=None, cost=0.0) for n, s in scores.items()},
    )


def test_means_average_only_items_that_carry_the_metric() -> None:
    results = [_result("a", faithfulness=1.0, abstention=0.2), _result("b", faithfulness=0.5)]
    assert llm.means(results) == {"abstention": 0.2, "faithfulness": 0.75}


def test_gate_fails_on_errors_empty_answers_uncited_repos_and_low_means() -> None:
    results = [
        llm.ItemResult(id="e", kind="answerable", lang="en", q="q", error="HTTP 502"),
        llm.ItemResult(id="b", kind="answerable", lang="en", q="q", answer="  "),
        llm.ItemResult(id="u", kind="answerable", lang="en", q="q", answer="x", unsourced=["a/b"]),
        _result("ok"),
    ]
    assert llm.gate_failures(results, {"faithfulness": 0.69, "attribution": 0.7}) == [
        "e: /ask HTTP 502",
        "b: empty answer",
        "u: names repos it did not retrieve: a/b",
        "faithfulness mean 0.690 < 0.7",
    ]


SCORES = {
    ("a", "faithfulness"): 1.0,
    ("a", "answer_relevancy"): 0.8,
    ("a", "attribution"): 0.9,
    ("b", "faithfulness"): 0.6,
    ("b", "attribution"): 0.7,
}


async def _measure(r: llm.ItemResult, name: llm.MetricName) -> llm.MetricResult:
    if (r.id, name) == ("b", "answer_relevancy"):
        json.loads("{} x")  # what trim_and_load_json raised on a malformed judge reply
    return llm.MetricResult(score=SCORES[(r.id, name)], reason=None, cost=0.01)


def _unscored(id: str) -> llm.ItemResult:
    return llm.ItemResult(id=id, kind="answerable", lang="en", q="q", answer="x")


def test_a_judge_failure_is_recorded_and_gated_while_the_rest_are_scored() -> None:
    results = asyncio.run(llm.score_items([_unscored("a"), _unscored("b")], _measure))
    assert [sorted(r.metrics) for r in results] == [
        ["answer_relevancy", "attribution", "faithfulness"],
        ["attribution", "faithfulness"],
    ]
    error = "JSONDecodeError: Extra data: line 1 column 4 (char 3)"
    assert [r.judge_errors for r in results] == [{}, {"answer_relevancy": error}]
    metric_means = llm.means(results)
    assert metric_means == pytest.approx(
        {"answer_relevancy": 0.8, "attribution": 0.8, "faithfulness": 0.8}
    )
    failures = llm.gate_failures(results, metric_means)
    assert failures == [f"b: answer_relevancy judge failed: {error}"]
    run = llm.RunResult(
        golden_file="g",
        golden_sha256="s",
        fixture_sha256="s",
        judge_model=llm.JUDGE_MODEL,
        git_sha="0123456789",
        floor=llm.FLOOR,
        items=results,
        means=metric_means,
        citation_precision=None,
        citation_precision_n=0,
        generator_cost=0.0,
        judge_cost=0.05,
        failures=failures,
    )
    text = llm.summary(run)
    assert "| answer_relevancy | 1 | 0.800 |" in text
    assert "Judge calls that raised: 1 " in text
    assert "**1 failure(s)**" in text


def _service(asks: dict[str, httpx.Response], search_ids: dict[str, list[str]]) -> RadarClient:
    def handle(request: httpx.Request) -> httpx.Response:
        q = json.loads(request.content)["q"]
        if request.url.path == "/ask":
            return asks[q]
        hits = [
            {"id": i, "text": f"row {i}", "metadata": {"source": "trending"}, "score": 0.5}
            for i in search_ids[q]
        ]
        return httpx.Response(200, json=hits)

    return RadarClient("http://svc", transport=httpx.MockTransport(handle))


ITEMS = [
    golden.GoldenItem(id="n1", q="音樂", lang="zh-TW", kind="no-answer"),
    golden.GoldenItem(id="n2", q="加密貨幣", lang="zh-TW", kind="no-answer"),
]


def test_ask_items_records_http_errors_and_keeps_the_rows_ask_read() -> None:
    client = _service(
        {
            "音樂": httpx.Response(200, json=_ask("沒有相關的", ["openai/codex"])),
            "加密貨幣": httpx.Response(502, json={"error": "llm declined the request"}),
        },
        {"音樂": ["id-openai/codex"]},
    )
    results, contexts = llm.ask_items(ITEMS, client, ALIASES)
    assert [(r.id, r.error, r.answer) for r in results] == [
        ("n1", None, "沒有相關的"),
        ("n2", "HTTP 502", None),
    ]
    assert results[0].generator_cost == pytest.approx(0.014)
    assert contexts == {"n1": ["row id-openai/codex"]}


def test_ask_items_flags_citations_outside_the_retrieved_rows() -> None:
    client = _service(
        {
            "音樂": httpx.Response(
                200, json=_ask("x", ["openai/codex", "x/other"], retrieved=["openai/codex"])
            )
        },
        {"音樂": ["id-openai/codex"]},
    )
    results, _ = llm.ask_items(ITEMS[:1], client, ALIASES)
    assert results[0].stray_citations == ["id-x/other"]
    assert llm.gate_failures(results, {}) == ["n1: cites rows it did not retrieve: id-x/other"]


def test_ask_items_refuses_when_search_and_ask_retrieve_different_rows() -> None:
    client = _service(
        {"音樂": httpx.Response(200, json=_ask("x", ["openai/codex"]))}, {"音樂": ["other"]}
    )
    with pytest.raises(RuntimeError, match="different rows"):
        llm.ask_items(ITEMS[:1], client, ALIASES)


def test_copied_share_counts_only_runs_of_twenty_or_more_characters() -> None:
    twenty = "0123456789ABCDEFGHIJ"
    assert llm.copied_share(twenty + "-" * 10, ["xx" + twenty + "yy"]) == pytest.approx(20 / 30)
    assert llm.copied_share(twenty[:19] + "-" * 11, [twenty[:19]]) == 0.0
    # Whitespace is collapsed on both sides, so a reflowed copy still counts.
    assert llm.copied_share("0123456789  ABCDEFGHIJ", ["0123456789\nABCDEFGHIJ"]) == 1.0
    assert llm.copied_share("   ", ["anything"]) is None
