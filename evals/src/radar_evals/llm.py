"""LLM eval: run the golden set through /ask, check each answer in code, score it with a judge.

The gate fails on any /ask error, empty answer, repo named outside the retrieved rows or citation
outside them, judge call that raised, and when a metric's mean falls below FLOOR. Judge scores are
noisy, so the floor is a coarse tripwire; the run-to-run noise band recorded in docs/stack-plan.md
is what later changes are judged against.
"""

import argparse
import asyncio
import os
import re
import sys
from collections import Counter
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Literal

import httpx
from pydantic import BaseModel

from radar_evals import golden
from radar_evals.client import RadarClient
from radar_evals.models import AskResponse, NotionFixture, RowKey
from radar_evals.notion_stub import NotionStub
from radar_evals.retrieval import (
    TOP_K,
    _git_sha,
    _sha256,
    fixture_stamp,
    load_fixtures,
    sync_mismatch,
)

JUDGE_MODEL = "claude-sonnet-5-5"
FLOOR = 0.7
# USD per token, from https://platform.claude.com/docs/en/about-claude/pricing
GENERATOR_PRICES: dict[str, tuple[float, float]] = {"claude-opus-5-5": (4.00e-6, 20.00e-6)}
# DeepEval 4.2.8's registry has no price for the judge; without this its reported cost is wrong.
JUDGE_PRICE = (2.00e-6, 10.00e-6)

type MetricName = Literal["faithfulness", "answer_relevancy", "attribution", "abstention"]

ATTRIBUTION_STEPS = [
    "Find each statement in the actual output that describes or recommends a specific repository.",
    "Check that each such statement names its repository and that the repository appears in the "
    "retrieval context.",
    "Check that what the statement says matches that repository's own entry in the retrieval "
    "context, not another repository's.",
    "Sentences that give general advice or caveats without describing a repository do not lower "
    "the score.",
    "Lower the score only for a repository described with another repository's details, or for a "
    "recommendation that never says which repository it means.",
]
ABSTENTION_STEPS = [
    "Decide whether the retrieval context holds a repository that really answers the input, and "
    "whether the input rests on a premise the retrieval context contradicts or does not support.",
    "If nothing in the retrieval context answers the input, the actual output must say so plainly.",
    "If the input rests on a false premise, the actual output must point it out instead of going "
    "along with it.",
    "Once it has said so, the actual output may list related repositories as alternatives, as long "
    "as it says they do not fully meet the need; this does not lower the score.",
    "Lower the score for presenting a repository as a match for a need it does not serve, for "
    "going along with a false premise, or for naming a repository not in the retrieval context.",
]

# A bare repo name counts as a mention only when no ordinary word could look like it.
_DISTINCTIVE = re.compile(r"[-_.0-9]|.[A-Z]")


def repo_aliases(fixtures: list[NotionFixture]) -> dict[str, str]:
    """Lower-cased spellings that name a fixture repo, mapped to its lower-cased owner/name.

    Every owner/name counts. A bare name counts when exactly one fixture repo has it and it is
    distinctive, so "skills" (four owners) and "codex" (an ordinary word) are never matched.
    """
    full_names = {p.plain_text("Repo").strip() for f in fixtures for p in f.pages} - {""}
    aliases = {name.lower(): name.lower() for name in full_names}
    bare_counts = Counter(name.rsplit("/", 1)[-1].lower() for name in full_names)
    for name in full_names:
        bare = name.rsplit("/", 1)[-1]
        if bare_counts[bare.lower()] == 1 and _DISTINCTIVE.search(bare):
            aliases[bare.lower()] = name.lower()
    return aliases


def mentioned_repos(answer: str, aliases: dict[str, str]) -> set[str]:
    # ASCII-only boundaries: zh-TW text runs straight into a repo name with no space, and \w
    # would treat the CJK character before it as part of the word.
    found = set()
    for alias, full in aliases.items():
        if re.search(rf"(?<![A-Za-z0-9_.-]){re.escape(alias)}(?![A-Za-z0-9_-])", answer, re.I):
            found.add(full)
    return found


def unsourced_repos(resp: AskResponse, aliases: dict[str, str]) -> list[str]:
    """Repos the answer names that /ask never retrieved: knowledge from outside the radar."""
    sourced = {s.repo.lower() for s in resp.sources if s.repo}
    return sorted(mentioned_repos(resp.answer, aliases) - sourced)


def uncited_repos(resp: AskResponse, aliases: dict[str, str]) -> list[str]:
    """Retrieved repos the answer names without citing, e.g. "the rest are unrelated: X, Y"."""
    sourced = {s.repo.lower() for s in resp.sources if s.repo}
    cited = {c.repo.lower() for c in resp.citations if c.repo}
    return sorted((mentioned_repos(resp.answer, aliases) & sourced) - cited)


def citation_precision(resp: AskResponse, gains: dict[RowKey, int]) -> float | None:
    """Share of cited rows the golden set labels relevant; None if nothing is cited or labelled."""
    cited = {k for c in resp.citations if (k := c.key) is not None}
    if not cited or not gains:
        return None
    return len(cited & gains.keys()) / len(cited)


# Long enough that a shared repo name or stock phrase does not count, short enough to catch a
# copied clause.
COPIED_SPAN = 20


def copied_share(answer: str, passages: list[str]) -> float | None:
    """Share of the answer's characters inside a run of COPIED_SPAN or more characters that appears
    verbatim in a retrieved row, whitespace collapsed; None for an empty answer."""
    text = " ".join(answer.split())
    if not text:
        return None
    windows = {
        p[i : i + COPIED_SPAN]
        for p in (" ".join(x.split()) for x in passages)
        for i in range(len(p) - COPIED_SPAN + 1)
    }
    covered = [False] * len(text)
    for i in range(len(text) - COPIED_SPAN + 1):
        if text[i : i + COPIED_SPAN] in windows:
            covered[i : i + COPIED_SPAN] = [True] * COPIED_SPAN
    return sum(covered) / len(text)


def generator_cost(resp: AskResponse) -> float:
    if resp.usage.model not in GENERATOR_PRICES:
        raise ValueError(f"no price for generator model {resp.usage.model!r}; add it")
    per_in, per_out = GENERATOR_PRICES[resp.usage.model]
    return resp.usage.input_tokens * per_in + resp.usage.output_tokens * per_out


class MetricResult(BaseModel):
    score: float
    reason: str | None
    cost: float


class ItemResult(BaseModel):
    id: str
    kind: str
    lang: str
    q: str
    error: str | None = None
    answer: str | None = None
    cited: list[str] = []
    unsourced: list[str] = []
    uncited: list[str] = []
    # Citation ids /ask returned that are not among the rows it retrieved; must stay empty.
    stray_citations: list[str] = []
    citation_precision: float | None = None
    copied_share: float | None = None
    generator_model: str | None = None
    generator_cost: float = 0.0
    metrics: dict[str, MetricResult] = {}
    # Metric name to the exception its judge call raised; such a metric is absent from metrics.
    judge_errors: dict[str, str] = {}
    trace_id: str | None = None


class RunResult(BaseModel):
    golden_file: str
    golden_sha256: str
    fixture_sha256: str
    judge_model: str
    git_sha: str
    floor: float
    items: list[ItemResult]
    means: dict[str, float]
    citation_precision: float | None
    citation_precision_n: int
    copied_share: float | None = None
    generator_cost: float
    judge_cost: float
    failures: list[str]
    langfuse_run_url: str | None = None


def ask_one(
    item: golden.GoldenItem,
    client: RadarClient,
    aliases: dict[str, str],
    headers: dict[str, str] | None = None,
) -> tuple[ItemResult, list[str] | None]:
    """One answer plus its retrieval context; /search with the same q and k=5 returns the rows
    /ask read (D1), which the judge needs because citations carry no row text."""
    base = ItemResult(id=item.id, kind=item.kind, lang=item.lang, q=item.q)
    try:
        resp = client.ask(item.q, headers)
    except httpx.HTTPStatusError as e:
        return base.model_copy(update={"error": f"HTTP {e.response.status_code}"}), None
    hits = client.search(item.q, TOP_K)
    sources = {s.id for s in resp.sources}
    if {h.id for h in hits} != sources:
        raise RuntimeError(f"{item.id}: /search and /ask retrieved different rows")
    texts = [h.text for h in hits]
    result = base.model_copy(
        update={
            "answer": resp.answer,
            "copied_share": copied_share(resp.answer, texts),
            "cited": sorted({c.repo for c in resp.citations if c.repo}),
            "unsourced": unsourced_repos(resp, aliases),
            "uncited": uncited_repos(resp, aliases),
            "stray_citations": [c.id for c in resp.citations if c.id not in sources],
            "citation_precision": citation_precision(resp, item.gains),
            "generator_model": resp.usage.model,
            "generator_cost": generator_cost(resp),
        }
    )
    return result, texts


def ask_items(
    items: list[golden.GoldenItem], client: RadarClient, aliases: dict[str, str]
) -> tuple[list[ItemResult], dict[str, list[str]]]:
    results: list[ItemResult] = []
    contexts: dict[str, list[str]] = {}
    for item in items:
        result, context = ask_one(item, client, aliases)
        results.append(result)
        if context is not None:
            contexts[item.id] = context
    return results, contexts


def metrics_for(kind: str) -> list[MetricName]:
    if kind == "answerable":
        return ["faithfulness", "answer_relevancy", "attribution"]
    return ["abstention"]


type Measure = Callable[[ItemResult, MetricName], Awaitable[MetricResult]]


async def score_items(
    results: list[ItemResult], measure: Measure, concurrency: int = 8
) -> list[ItemResult]:
    gate = asyncio.Semaphore(concurrency)

    async def score(r: ItemResult) -> ItemResult:
        if r.answer is None or not r.answer.strip():
            return r
        scored: dict[str, MetricResult] = {}
        errors: dict[str, str] = {}
        for name in metrics_for(r.kind):
            # Answers are already paid for: one malformed judge reply must not discard the run.
            try:
                async with gate:
                    scored[name] = await measure(r, name)
            except Exception as e:
                errors[name] = f"{type(e).__name__}: {e}"
        return r.model_copy(update={"metrics": scored, "judge_errors": errors})

    return list(await asyncio.gather(*(score(r) for r in results)))


async def judge_items(
    results: list[ItemResult], contexts: dict[str, list[str]], concurrency: int = 8
) -> list[ItemResult]:
    # Telemetry is read at import time, so deepeval is imported only after opting out.
    os.environ["DEEPEVAL_TELEMETRY_OPT_OUT"] = "1"
    from deepeval.metrics import AnswerRelevancyMetric, FaithfulnessMetric, GEval
    from deepeval.metrics.base_metric import BaseMetric
    from deepeval.models import AnthropicModel
    from deepeval.test_case import LLMTestCase, SingleTurnParams

    judge = AnthropicModel(
        model=JUDGE_MODEL, cost_per_input_token=JUDGE_PRICE[0], cost_per_output_token=JUDGE_PRICE[1]
    )
    params = [
        SingleTurnParams.INPUT,
        SingleTurnParams.ACTUAL_OUTPUT,
        SingleTurnParams.RETRIEVAL_CONTEXT,
    ]

    def build(name: MetricName) -> BaseMetric:
        # Metrics keep score, reason and cost on the instance, so each measurement gets its own.
        match name:
            case "faithfulness":
                # Only contradicted claims count against the answer. Penalising unverifiable ones
                # also hits "nothing on the radar does X", which the /ask prompt asks for.
                return FaithfulnessMetric(model=judge)
            case "answer_relevancy":
                return AnswerRelevancyMetric(model=judge)
            case "attribution":
                return GEval(
                    name="Attribution",
                    evaluation_params=params,
                    evaluation_steps=ATTRIBUTION_STEPS,
                    model=judge,
                )
            case "abstention":
                return GEval(
                    name="Abstention",
                    evaluation_params=params,
                    evaluation_steps=ABSTENTION_STEPS,
                    model=judge,
                )

    async def measure(r: ItemResult, name: MetricName) -> MetricResult:
        case = LLMTestCase(input=r.q, actual_output=r.answer, retrieval_context=contexts[r.id])
        metric = build(name)
        await metric.a_measure(case, _show_indicator=False)
        if metric.score is None or metric.evaluation_cost is None:
            raise RuntimeError(f"{r.id}: {name} returned no score or cost")
        return MetricResult(score=metric.score, reason=metric.reason, cost=metric.evaluation_cost)

    return await score_items(results, measure, concurrency)


def means(results: list[ItemResult]) -> dict[str, float]:
    by_metric: dict[str, list[float]] = {}
    for r in results:
        for name, m in r.metrics.items():
            by_metric.setdefault(name, []).append(m.score)
    return {name: sum(s) / len(s) for name, s in sorted(by_metric.items())}


def mean_or_none(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def gate_failures(results: list[ItemResult], metric_means: dict[str, float]) -> list[str]:
    out = [f"{r.id}: /ask {r.error}" for r in results if r.error]
    out += [
        f"{r.id}: empty answer" for r in results if r.answer is not None and not r.answer.strip()
    ]
    out += [
        f"{r.id}: names repos it did not retrieve: {', '.join(r.unsourced)}"
        for r in results
        if r.unsourced
    ]
    out += [
        f"{r.id}: cites rows it did not retrieve: {', '.join(r.stray_citations)}"
        for r in results
        if r.stray_citations
    ]
    out += [
        f"{r.id}: {name} judge failed: {err}"
        for r in results
        for name, err in r.judge_errors.items()
    ]
    out += [
        f"{name} mean {mean:.3f} < {FLOOR}" for name, mean in metric_means.items() if mean < FLOOR
    ]
    return out


def summary(result: RunResult) -> str:
    lines = [
        f"### LLM eval — judge `{result.judge_model}` @ `{result.git_sha[:7]}`, "
        f"floor {result.floor}",
        "",
        "| metric | n | mean |",
        "|---|---:|---:|",
    ]
    for name, mean in result.means.items():
        n = sum(1 for r in result.items if name in r.metrics)
        lines.append(f"| {name} | {n} | {mean:.3f} |")
    if result.citation_precision is not None:
        lines.append(
            f"| citation precision | {result.citation_precision_n} | "
            f"{result.citation_precision:.3f} |"
        )
    uncited = sum(1 for r in result.items if r.uncited)
    judge_errors = sum(len(r.judge_errors) for r in result.items)
    copied = (
        []
        if result.copied_share is None
        else [
            f"Answer text copied verbatim from the retrieved rows ({COPIED_SPAN}+ character runs): "
            f"{result.copied_share:.3f} (not gated).",
            "",
        ]
    )
    if result.langfuse_run_url:
        lines += ["", f"Langfuse experiment: {result.langfuse_run_url}"]
    lines += [
        "",
        f"Answers naming a retrieved repo without citing it: {uncited} (not gated).",
        "",
        f"Judge calls that raised: {judge_errors} (each fails the gate; means cover scored items).",
        "",
        *copied,
        f"Cost: generator ${result.generator_cost:.2f} + judge ${result.judge_cost:.2f} "
        f"= ${result.generator_cost + result.judge_cost:.2f}",
        "",
    ]
    if result.failures:
        lines.append(f"**{len(result.failures)} failure(s)**:")
        lines += [f"- {f}" for f in result.failures]
    else:
        lines.append(
            "Gate passed: no errors, empty answers, unretrieved repos or judge failures; "
            "every mean ≥ floor."
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the LLM eval against a live service.")
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument(
        "--fixture",
        type=Path,
        action="append",
        required=True,
        help="one per source the stub serves",
    )
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--service-url", default=os.environ.get("EVAL_SERVICE_URL", "http://localhost:8080")
    )
    parser.add_argument(
        "--stub-port", type=int, default=int(os.environ.get("EVAL_NOTION_STUB_PORT", "8765"))
    )
    args = parser.parse_args(argv)
    if not os.environ.get("ANTHROPIC_API_KEY"):
        parser.error("ANTHROPIC_API_KEY is not set; the judge needs it")

    items = golden.load(args.golden)
    fixtures = load_fixtures(args.fixture)
    aliases = repo_aliases(fixtures)

    git_sha = _git_sha()
    langfuse = None
    if os.environ.get("LANGFUSE_PUBLIC_KEY") and os.environ.get("LANGFUSE_SECRET_KEY"):
        from langfuse import Langfuse

        langfuse = Langfuse()

    run_url = None
    # /ask can take minutes on a hard question; the client's 300 s timeout covers it.
    with NotionStub(fixtures, port=args.stub_port), RadarClient(args.service_url) as client:
        if mismatch := sync_mismatch(client.sync(), fixtures):
            print(mismatch, file=sys.stderr)
            return 1
        if langfuse is None:
            answered, contexts = ask_items(items, client, aliases)
        else:
            from radar_evals import tracing

            asked: dict[str, tuple[ItemResult, list[str] | None]] = {}

            def ask(item: golden.GoldenItem, headers: dict[str, str]) -> str | None:
                asked[item.id] = ask_one(item, client, aliases, headers)
                return asked[item.id][0].answer or asked[item.id][0].error

            trace_ids, run_url = tracing.run_experiment(
                langfuse, f"llm-eval {git_sha[:7]}", items, ask
            )
            answered = [
                asked[i.id][0].model_copy(update={"trace_id": trace_ids.get(i.id)}) for i in items
            ]
            contexts = {i.id: c for i in items if (c := asked[i.id][1]) is not None}

    results = asyncio.run(judge_items(answered, contexts))
    if langfuse is not None:
        tracing.report_scores(
            langfuse,
            {r.id: r.trace_id for r in results if r.trace_id},
            {
                r.id: {n: (m.score, m.reason) for n, m in r.metrics.items()}
                | (
                    {"citation_precision": (r.citation_precision, None)}
                    if r.citation_precision is not None
                    else {}
                )
                for r in results
            },
        )
    metric_means = means(results)
    precisions = [r.citation_precision for r in results if r.citation_precision is not None]
    result = RunResult(
        golden_file=args.golden.name,
        golden_sha256=_sha256(args.golden),
        fixture_sha256=fixture_stamp(args.fixture),
        judge_model=JUDGE_MODEL,
        git_sha=git_sha,
        floor=FLOOR,
        items=results,
        means=metric_means,
        citation_precision=mean_or_none(precisions),
        citation_precision_n=len(precisions),
        copied_share=mean_or_none([r.copied_share for r in results if r.copied_share is not None]),
        generator_cost=sum(r.generator_cost for r in results),
        judge_cost=sum(m.cost for r in results for m in r.metrics.values()),
        failures=gate_failures(results, metric_means),
        langfuse_run_url=run_url,
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(result.model_dump_json(indent=1) + "\n", encoding="utf-8")

    text = summary(result)
    print(text)
    if step_summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write(text)
    return 1 if result.failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
