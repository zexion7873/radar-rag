"""LLM eval: run the golden set through /ask, check each answer in code, score it with a judge.

The gate fails on any /ask error, empty answer or uncited repo mention, and when a metric's mean
falls below FLOOR. Judge scores are noisy, so the floor is a coarse tripwire; the run-to-run noise
band recorded in docs/stack-plan.md (M5) is what later changes are judged against.
"""

import argparse
import asyncio
import os
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Literal

import httpx
from pydantic import BaseModel

from radar_evals import golden
from radar_evals.client import RadarClient
from radar_evals.models import AskResponse, NotionFixture
from radar_evals.notion_stub import NotionStub
from radar_evals.retrieval import TOP_K, _git_sha, _sha256

JUDGE_MODEL = "claude-sonnet-5"
FLOOR = 0.7
# USD per token, from https://platform.claude.com/docs/en/about-claude/pricing
GENERATOR_PRICES: dict[str, tuple[float, float]] = {"claude-opus-5-5": (4.00e-6, 20.00e-6)}

type MetricName = Literal["faithfulness", "answer_relevancy", "attribution", "abstention"]

ATTRIBUTION_STEPS = [
    "List each recommendation or factual claim the actual output makes about a repository.",
    "For each claim, check that the actual output names the repository it is about.",
    "Check that each named repository appears in the retrieval context and that the claim matches "
    "that repository's own entry, not another repository's.",
    "Lower the score for claims tied to no named repository and for any claim that attributes one "
    "repository's description to another.",
]
ABSTENTION_STEPS = [
    "Decide whether the retrieval context holds a repository that really answers the input, and "
    "whether the input rests on a premise the retrieval context contradicts or does not support.",
    "If nothing in the retrieval context answers the input, the actual output should say plainly "
    "that the radar has nothing relevant, and must not present unrelated repositories as matches.",
    "If the input rests on a false premise, the actual output should correct it, using the "
    "retrieval context where it can, instead of going along with it.",
    "Lower the score for recommending a repository for a need it does not serve, and for any "
    "repository or fact that is not in the retrieval context.",
]

# A bare repo name counts as a mention only when no ordinary word could look like it.
_DISTINCTIVE = re.compile(r"[-_.0-9]|.[A-Z]")


def repo_aliases(fixture: NotionFixture) -> dict[str, str]:
    """Lower-cased spellings that name a fixture repo, mapped to its lower-cased owner/name.

    Every owner/name counts. A bare name counts when exactly one fixture repo has it and it is
    distinctive, so "skills" (four owners) and "codex" (an ordinary word) are never matched.
    """
    full_names = {p.plain_text("Repo").strip() for p in fixture.pages} - {""}
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


def uncited_repos(resp: AskResponse, aliases: dict[str, str]) -> list[str]:
    cited = {c.repo.lower() for c in resp.citations if c.repo}
    return sorted(mentioned_repos(resp.answer, aliases) - cited)


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
    uncited: list[str] = []
    generator_model: str | None = None
    generator_cost: float = 0.0
    metrics: dict[str, MetricResult] = {}


class RunResult(BaseModel):
    golden_file: str
    golden_sha256: str
    fixture_sha256: str
    judge_model: str
    git_sha: str
    floor: float
    items: list[ItemResult]
    means: dict[str, float]
    generator_cost: float
    judge_cost: float
    failures: list[str]


def ask_items(
    items: list[golden.GoldenItem], client: RadarClient, aliases: dict[str, str]
) -> tuple[list[ItemResult], dict[str, list[str]]]:
    """Answers plus each item's retrieval context; /search with the same q and k=5 returns the
    rows /ask read (D1), which the judge needs because citations carry no row text."""
    results: list[ItemResult] = []
    contexts: dict[str, list[str]] = {}
    for item in items:
        base = ItemResult(id=item.id, kind=item.kind, lang=item.lang, q=item.q)
        try:
            resp = client.ask(item.q)
        except httpx.HTTPStatusError as e:
            results.append(base.model_copy(update={"error": f"HTTP {e.response.status_code}"}))
            continue
        hits = client.search(item.q, TOP_K)
        if {h.id for h in hits} != {c.id for c in resp.citations}:
            raise RuntimeError(f"{item.id}: /search and /ask retrieved different rows")
        contexts[item.id] = [h.text for h in hits]
        results.append(
            base.model_copy(
                update={
                    "answer": resp.answer,
                    "cited": sorted({c.repo for c in resp.citations if c.repo}),
                    "uncited": uncited_repos(resp, aliases),
                    "generator_model": resp.usage.model,
                    "generator_cost": generator_cost(resp),
                }
            )
        )
    return results, contexts


def metrics_for(kind: str) -> list[MetricName]:
    if kind == "answerable":
        return ["faithfulness", "answer_relevancy", "attribution"]
    return ["abstention"]


async def judge_items(
    results: list[ItemResult], contexts: dict[str, list[str]], concurrency: int = 8
) -> list[ItemResult]:
    # Telemetry is read at import time, so deepeval is imported only after opting out.
    os.environ["DEEPEVAL_TELEMETRY_OPT_OUT"] = "1"
    from deepeval.metrics import AnswerRelevancyMetric, FaithfulnessMetric, GEval
    from deepeval.metrics.base_metric import BaseMetric
    from deepeval.models import AnthropicModel
    from deepeval.test_case import LLMTestCase, SingleTurnParams

    judge = AnthropicModel(model=JUDGE_MODEL)
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

    gate = asyncio.Semaphore(concurrency)

    async def score(r: ItemResult) -> ItemResult:
        if r.answer is None or not r.answer.strip():
            return r
        case = LLMTestCase(input=r.q, actual_output=r.answer, retrieval_context=contexts[r.id])
        scored: dict[str, MetricResult] = {}
        for name in metrics_for(r.kind):
            metric = build(name)
            async with gate:
                await metric.a_measure(case, _show_indicator=False)
            if metric.score is None or metric.evaluation_cost is None:
                raise RuntimeError(f"{r.id}: {name} returned no score or cost")
            scored[name] = MetricResult(
                score=metric.score, reason=metric.reason, cost=metric.evaluation_cost
            )
        return r.model_copy(update={"metrics": scored})

    return list(await asyncio.gather(*(score(r) for r in results)))


def means(results: list[ItemResult]) -> dict[str, float]:
    by_metric: dict[str, list[float]] = {}
    for r in results:
        for name, m in r.metrics.items():
            by_metric.setdefault(name, []).append(m.score)
    return {name: sum(s) / len(s) for name, s in sorted(by_metric.items())}


def gate_failures(results: list[ItemResult], metric_means: dict[str, float]) -> list[str]:
    out = [f"{r.id}: /ask {r.error}" for r in results if r.error]
    out += [
        f"{r.id}: empty answer" for r in results if r.answer is not None and not r.answer.strip()
    ]
    out += [f"{r.id}: uncited {', '.join(r.uncited)}" for r in results if r.uncited]
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
    lines += [
        "",
        f"Cost: generator ${result.generator_cost:.2f} + judge ${result.judge_cost:.2f} "
        f"= ${result.generator_cost + result.judge_cost:.2f}",
        "",
    ]
    if result.failures:
        lines.append(f"**{len(result.failures)} failure(s)**:")
        lines += [f"- {f}" for f in result.failures]
    else:
        lines.append("Gate passed: no errors, empty answers or uncited repos; every mean ≥ floor.")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the LLM eval against a live service.")
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
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
    fixture = NotionFixture.model_validate_json(args.fixture.read_text(encoding="utf-8"))
    aliases = repo_aliases(fixture)

    # /ask can take minutes on a hard question; the client's 300 s timeout covers it.
    with NotionStub(fixture, port=args.stub_port), RadarClient(args.service_url) as client:
        expected = sum(1 for p in fixture.pages if p.embeddable)
        ingested = client.sync().ingested
        if ingested != expected:
            print(f"/sync ingested {ingested} rows, the fixture has {expected}", file=sys.stderr)
            return 1
        answered, contexts = ask_items(items, client, aliases)

    results = asyncio.run(judge_items(answered, contexts))
    metric_means = means(results)
    result = RunResult(
        golden_file=args.golden.name,
        golden_sha256=_sha256(args.golden),
        fixture_sha256=_sha256(args.fixture),
        judge_model=JUDGE_MODEL,
        git_sha=_git_sha(),
        floor=FLOOR,
        items=results,
        means=metric_means,
        generator_cost=sum(r.generator_cost for r in results),
        judge_cost=sum(m.cost for r in results for m in r.metrics.values()),
        failures=gate_failures(results, metric_means),
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
