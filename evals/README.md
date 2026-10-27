# radar-evals

A typed Python package (uv, pydantic, httpx, pytest, mypy strict, ruff) that tests the radar-rag
service as a black box over HTTP. It serves frozen copies of the Notion Trending and Blog tables from
a local stub, so an eval needs no Notion token and does not move when the tables do. `/sync` replaces
every row of each source, so rows from an earlier live sync do not leak into an eval. The results each
gate has measured are in [docs/results.md](../docs/results.md).

## Commands

Run from this directory.

```bash
uv sync
uv run ruff check && uv run ruff format --check && uv run mypy && uv run pytest   # unit tests

# Against a running service: start it with NOTION_TOKEN=dummy NOTION_BASE_URL=http://127.0.0.1:8765,
# then the tests start the stub on 8765, POST /sync from the fixture, and query /search.
EVAL_SERVICE_URL=http://localhost:8080 uv run pytest -m service

# Retrieval eval over the golden set (golden_v1.jsonl, 62 items). The first run writes the
# baseline with --write-baseline; later runs drop it and fail on any hit@5 hit→miss flip.
# golden_v1 labels trending rows, so it queries --source trending.
uv run radar-evals --golden golden_v1.jsonl --fixture fixtures/trending.json \
  --fixture fixtures/blog.json --source trending \
  --model-id paraphrase-multilingual-MiniLM-L12-v2 --out results/latest.json --baseline results/baseline.json --write-baseline

# golden_v1 again over every source, as /ask searches; blog rows count as misses, so a lower bound.
uv run radar-evals --golden golden_v1.jsonl --fixture fixtures/trending.json --fixture fixtures/blog.json \
  --model-id paraphrase-multilingual-MiniLM-L12-v2 --out results/latest_v1_all.json --baseline results/baseline_v1_all.json

# golden_v2 (33 items, trending and blog labels) queries every source, with its own baseline.
uv run radar-evals --golden golden_v2.jsonl --fixture fixtures/trending.json --fixture fixtures/blog.json \
  --model-id paraphrase-multilingual-MiniLM-L12-v2 --out results/latest_v2.json --baseline results/baseline_v2.json

# golden_v3 (4 category and recency questions, M17) runs in CI as a report, not a gate: every item
# hits at 5, so read its precision and nDCG, not its flips.
uv run radar-evals --golden golden_v3.jsonl --fixture fixtures/trending.json --fixture fixtures/blog.json \
  --model-id paraphrase-multilingual-MiniLM-L12-v2 --out results/latest_v3.json

# LLM eval (paid, about $4.4 per full run): /ask on every golden item, code checks (no empty answer,
# no repo named or cited outside the retrieved rows), citation precision
# against the golden labels, then DeepEval metrics judged by claude-sonnet-5-5. The service and
# this command both need ANTHROPIC_API_KEY.
uv run radar-evals-llm --golden golden_v1.jsonl --fixture fixtures/trending.json \
  --fixture fixtures/blog.json --out results/llm-latest.json

# Re-capture a fixture (reads Notion; keeps only the columns the service parses). Re-capturing
# trending moves golden_v1's baseline.
NOTION_TOKEN=ntn_... uv run freeze-notion --source blog
```

## In CI

CI runs the unit tests in `evals` and the live-service tests in `eval-retrieval`, against a
pgvector service container on the same tag as `docker-compose.yml`. The LLM eval runs in
`eval-llm.yml` only when the repo owner adds the `eval:llm` label to a PR or dispatches it; it reads
the key from the `ANTHROPIC_API_KEY` repository secret, and with the `LANGFUSE_PUBLIC_KEY` /
`LANGFUSE_SECRET_KEY` secrets each run is also a Langfuse experiment. It fails on any `/ask` error, empty answer,
repo named or cited outside the retrieved rows, judge call that raised (the run still scores every
other item and writes its results), and when a metric's mean is below 0.7.

## Langfuse experiments

When `LANGFUSE_PUBLIC_KEY` and `LANGFUSE_SECRET_KEY` are set for `radar-evals-llm`, the run mirrors
the golden set to the Langfuse dataset `golden_v1`, runs it as an experiment, passes `traceparent` to
`/ask` so the service's spans nest under each item, and attaches the judge's scores and citation
precision to each item. Such a run needs the full golden set: a subset run against the fuller dataset
is refused. The service traces only when it has the same keys; see the README's Tracing section.
