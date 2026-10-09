from typing import Any, cast

from langfuse import Langfuse
from opentelemetry.sdk.trace import TracerProvider

from radar_evals import golden, tracing


def test_traceparent_carries_the_current_span_with_the_sampled_flag() -> None:
    tracer = TracerProvider().get_tracer("test")
    with tracer.start_as_current_span("item") as span:
        headers = tracing.traceparent()
    ctx = span.get_span_context()
    version, trace_id, span_id, flags = headers["traceparent"].split("-")
    assert (version, trace_id, span_id) == ("00", f"{ctx.trace_id:032x}", f"{ctx.span_id:016x}")
    # W3C trace-flags is a bit field; bit 0 is "sampled" (newer SDKs also set bit 1, "random").
    assert int(flags, 16) & 0x01


def test_traceparent_is_empty_outside_a_span() -> None:
    assert tracing.traceparent() == {}


class FakeLangfuse:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def create_dataset(self, **kwargs: Any) -> None:
        self.calls.append(("dataset", kwargs))

    def create_dataset_item(self, **kwargs: Any) -> None:
        self.calls.append(("item", kwargs))

    def create_score(self, **kwargs: Any) -> None:
        self.calls.append(("score", kwargs))

    def flush(self) -> None:
        self.calls.append(("flush", {}))


ITEM = golden.GoldenItem(
    id="q001",
    q="向量資料庫",
    lang="zh-TW",
    kind="answerable",
    labels=(golden.Label(url="https://github.com/a/b", week="2026-09-21", grade=2),),
)


def test_mirror_golden_upserts_each_item_under_a_stable_id() -> None:
    fake = FakeLangfuse()
    tracing.mirror_golden(cast(Langfuse, fake), [ITEM])
    assert fake.calls[1] == (
        "item",
        {
            "dataset_name": "golden_v1",
            "id": "golden_v1-q001",
            "input": {"q": "向量資料庫"},
            "expected_output": {
                "labels": [{"url": "https://github.com/a/b", "week": "2026-09-21", "grade": 2}]
            },
            "metadata": {"golden_id": "q001", "kind": "answerable", "lang": "zh-TW"},
        },
    )


def test_report_scores_skips_items_without_a_trace_and_flushes() -> None:
    fake = FakeLangfuse()
    tracing.report_scores(
        cast(Langfuse, fake),
        {"q001": "t1"},
        {"q001": {"faithfulness": (0.9, "ok")}, "q002": {"faithfulness": (0.1, None)}},
    )
    assert fake.calls == [
        ("score", {"trace_id": "t1", "name": "faithfulness", "value": 0.9, "comment": "ok"}),
        ("flush", {}),
    ]
