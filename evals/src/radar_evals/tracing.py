"""Langfuse side of the LLM eval: the golden set as a dataset, each run as an experiment, and the
judge's scores on each item's trace. The service's spans join the same trace through traceparent.
"""

from collections.abc import Callable
from typing import Any

from langfuse import Langfuse
from langfuse.api import DatasetItem
from opentelemetry.propagate import inject

from radar_evals import golden

DATASET = "golden_v1"


def dataset_item_id(item: golden.GoldenItem) -> str:
    return f"{DATASET}-{item.id}"


def mirror_golden(langfuse: Langfuse, items: list[golden.GoldenItem]) -> None:
    """Upserts every golden item, keyed by its id, so reruns update rather than duplicate."""
    langfuse.create_dataset(
        name=DATASET, description="radar-rag golden set (evals/golden_v1.jsonl)"
    )
    for item in items:
        langfuse.create_dataset_item(
            dataset_name=DATASET,
            id=dataset_item_id(item),
            input={"q": item.q},
            expected_output={"labels": [label.model_dump() for label in item.labels]},
            metadata={"golden_id": item.id, "kind": item.kind, "lang": item.lang},
        )


def traceparent() -> dict[str, str]:
    """W3C headers for the current span; its sampled flag makes the service keep its spans."""
    carrier: dict[str, str] = {}
    inject(carrier)
    return carrier


def run_experiment(
    langfuse: Langfuse,
    name: str,
    items: list[golden.GoldenItem],
    ask: Callable[[golden.GoldenItem, dict[str, str]], Any],
) -> tuple[dict[str, str], str | None]:
    """Runs `ask` once per item inside its experiment span; returns trace ids by golden id and the
    run's URL. Items run one at a time, as without Langfuse."""
    mirror_golden(langfuse, items)
    by_id = {item.id: item for item in items}

    def task(*, item: Any, **_: Any) -> Any:
        return ask(by_id[item.metadata["golden_id"]], traceparent())

    dataset = langfuse.get_dataset(DATASET)
    # run_experiment runs every dataset item, so a subset run would hit items it cannot answer.
    extra = {i.metadata["golden_id"] for i in dataset.items} - by_id.keys()
    if extra:
        raise ValueError(
            f"Langfuse dataset {DATASET} holds items this run lacks ({sorted(extra)}); "
            "run the full golden set, or unset the Langfuse keys for a subset"
        )
    result = dataset.run_experiment(name=name, task=task, max_concurrency=1)
    traces = {
        r.item.metadata["golden_id"]: r.trace_id
        for r in result.item_results
        if isinstance(r.item, DatasetItem) and r.trace_id
    }
    return traces, result.dataset_run_url


def report_scores(
    langfuse: Langfuse,
    trace_ids: dict[str, str],
    scores: dict[str, dict[str, tuple[float, str | None]]],
) -> None:
    """scores: golden id -> metric -> (value, reason)."""
    for item_id, metrics in scores.items():
        if item_id not in trace_ids:
            continue
        for name, (value, reason) in metrics.items():
            langfuse.create_score(
                trace_id=trace_ids[item_id], name=name, value=value, comment=reason
            )
    langfuse.flush()
