"""Retrieval eval: sync from the frozen fixture, run the golden set through /search, gate on flips.

The gate fails when an item that hit at k in the baseline misses now. Exact vector search over a
frozen fixture with a pinned model is deterministic, so a flip is a real regression, not noise.
"""

import argparse
import hashlib
import os
import subprocess
import sys
from collections.abc import Hashable, Iterable
from pathlib import Path

from pydantic import BaseModel

from radar_evals import golden, metrics
from radar_evals.client import RadarClient
from radar_evals.models import NotionFixture, RowKey
from radar_evals.notion_stub import NotionStub

# /ask retrieves with topK 5 and no filter, so /search at k=5 measures exactly what /ask reads.
TOP_K = 5


class Scores(BaseModel):
    hit: float
    precision: float
    recall: float
    mrr: float
    ndcg: float


def score[K: Hashable](ranked: list[K], gains: dict[K, int], k: int = TOP_K) -> Scores:
    return Scores(
        hit=metrics.hit_at_k(ranked, gains, k),
        precision=metrics.precision_at_k(ranked, gains, k),
        recall=metrics.recall_at_k(ranked, gains, k),
        mrr=metrics.reciprocal_rank(ranked, gains, k),
        ndcg=metrics.ndcg_at_k(ranked, gains, k),
    )


class ItemResult(BaseModel):
    id: str
    lang: str
    script: str
    kind: str
    retrieved: list[RowKey | None]
    row: Scores | None
    url: Scores | None


class RunResult(BaseModel):
    golden_file: str
    golden_sha256: str
    fixture_sha256: str
    model_id: str
    git_sha: str
    top_k: int
    items: list[ItemResult]
    aggregates: dict[str, dict[str, float]]


def run_items(items: list[golden.GoldenItem], client: RadarClient) -> list[ItemResult]:
    results = []
    for item in items:
        hits = client.search(item.q, TOP_K)
        retrieved = [h.key for h in hits]
        # An unkeyed hit still holds its rank; a unique sentinel makes it a non-relevant row.
        ranked = [k if k is not None else (f"#unkeyed-{i}", "") for i, k in enumerate(retrieved)]
        row = url = None
        if item.labels:
            row = score(ranked, item.gains)
            urls, url_gains = metrics.by_url(ranked, item.gains)
            url = score(urls, url_gains)
        results.append(
            ItemResult(
                id=item.id,
                lang=item.lang,
                script=item.script,
                kind=item.kind,
                retrieved=retrieved,
                row=row,
                url=url,
            )
        )
    return results


def aggregate(results: Iterable[ItemResult]) -> dict[str, dict[str, float]]:
    """Mean row-level scores per group; items without labels have no retrieval score."""
    groups: dict[str, list[Scores]] = {}
    for r in results:
        if r.row is None:
            continue
        for group in ("all", f"lang:{r.lang}", f"script:{r.script}"):
            groups.setdefault(group, []).append(r.row)
    out: dict[str, dict[str, float]] = {}
    for group, scores in sorted(groups.items()):
        n = len(scores)
        out[group] = {"n": float(n)} | {
            field: sum(getattr(s, field) for s in scores) / n for field in Scores.model_fields
        }
    return out


def flips(baseline: RunResult, current: RunResult) -> list[str]:
    """Items that hit at k in the baseline and miss now; raises if the runs are not comparable."""
    for stamp in ("golden_sha256", "fixture_sha256", "model_id", "top_k"):
        if getattr(baseline, stamp) != getattr(current, stamp):
            raise ValueError(
                f"baseline {stamp} differs from this run; rewrite it with --write-baseline"
            )
    before = {r.id: r.row.hit for r in baseline.items if r.row is not None}
    return [
        r.id
        for r in current.items
        if r.row is not None and before.get(r.id) == 1.0 and r.row.hit == 0.0
    ]


def summary(result: RunResult, flipped: list[str]) -> str:
    lines = [
        f"### Retrieval eval — `{result.model_id}` @ `{result.git_sha[:7]}`, k={result.top_k}",
        "",
        "| group | n | hit | P | R | MRR | nDCG |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for group, s in result.aggregates.items():
        lines.append(
            f"| {group} | {int(s['n'])} | {s['hit']:.3f} | {s['precision']:.3f} | "
            f"{s['recall']:.3f} | {s['mrr']:.3f} | {s['ndcg']:.3f} |"
        )
    lines.append("")
    lines.append(
        f"**{len(flipped)} hit→miss flip(s)**: {', '.join(flipped)}"
        if flipped
        else "No hit→miss flips."
    )
    return "\n".join(lines) + "\n"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _git_sha() -> str:
    return subprocess.run(
        ["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True
    ).stdout.strip()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the retrieval eval against a live service.")
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--model-id", required=True, help="embedding model the service runs")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--write-baseline", action="store_true")
    parser.add_argument(
        "--service-url", default=os.environ.get("EVAL_SERVICE_URL", "http://localhost:8080")
    )
    parser.add_argument(
        "--stub-port", type=int, default=int(os.environ.get("EVAL_NOTION_STUB_PORT", "8765"))
    )
    args = parser.parse_args(argv)
    if args.write_baseline and args.baseline is None:
        parser.error("--write-baseline needs --baseline")
    if args.baseline is not None and not args.write_baseline and not args.baseline.exists():
        parser.error(f"{args.baseline} does not exist; create it with --write-baseline")

    items = golden.load(args.golden)
    fixture = NotionFixture.model_validate_json(args.fixture.read_text(encoding="utf-8"))
    rotten = golden.rotten_labels(items, fixture)
    if rotten:
        print(
            f"{len(rotten)} label(s) point at rows absent from the fixture: {rotten}",
            file=sys.stderr,
        )
        return 1

    with NotionStub(fixture, port=args.stub_port), RadarClient(args.service_url) as client:
        expected = sum(1 for p in fixture.pages if p.embeddable)
        ingested = client.sync().ingested
        if ingested != expected:
            print(f"/sync ingested {ingested} rows, the fixture has {expected}", file=sys.stderr)
            return 1
        results = run_items(items, client)

    result = RunResult(
        golden_file=args.golden.name,
        golden_sha256=_sha256(args.golden),
        fixture_sha256=_sha256(args.fixture),
        model_id=args.model_id,
        git_sha=_git_sha(),
        top_k=TOP_K,
        items=results,
        aggregates=aggregate(results),
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(result.model_dump_json(indent=1) + "\n", encoding="utf-8")

    flipped: list[str] = []
    if args.write_baseline:
        args.baseline.write_text(result.model_dump_json(indent=1) + "\n", encoding="utf-8")
    elif args.baseline is not None:
        baseline = RunResult.model_validate_json(args.baseline.read_text(encoding="utf-8"))
        try:
            flipped = flips(baseline, result)
        except ValueError as e:
            print(str(e), file=sys.stderr)
            return 1

    text = summary(result, flipped)
    print(text)
    if step_summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write(text)
    return 1 if flipped else 0


if __name__ == "__main__":
    raise SystemExit(main())
