# Radar Intelligence Service (P0–P2)

A standalone Java / Spring Boot service that adds LLM-powered intelligence on top of the
GitHub-radar Notion archive written by the `ai-assistant` routines. **P0** stands up the
skeleton: pgvector, Notion ingestion of the **Trending** table, and a semantic `/search`
endpoint. **P1** adds metadata-filtered search. **P2** adds a RAG `/ask` endpoint —
grounded Q&A with citations. Evals and tracing come in later phases (see [docs/stack-plan.md](docs/stack-plan.md)).

> Not a proxy in front of Notion — it exposes *new* capabilities (semantic search and
> grounded Q&A) that the pure-reader `github-radar-ui` cannot do.

## What P0–P2 gives you

- `POST /sync` — pull the Trending Archive from Notion and replace its rows in pgvector: one
  document per Notion row (one repo, one week), keyed by the Notion page id.
- `POST /search` — semantic search over the embedded rows, optionally filtered by metadata
  (`source` / `category` / `language` / `week` exact match, `stars_per_week` ≥ `minStars`).
- `POST /ask` — ask a question in natural language; the service retrieves the relevant radar
  rows from pgvector, has **Claude** answer from them, and returns the answer plus the source
  rows as **citations**. Needs `ANTHROPIC_API_KEY`; `/sync` and `/search` do not. Runs on
  `claude-opus-5-5`. A model refusal returns 502; an Anthropic failure returns 502 (client error,
  e.g. a bad key) or 503 (rate limit or overload, after one retry), without the upstream body.

## Prerequisites

- Java 21, and Maven running on it: `mvn -v` names the JVM. Homebrew's maven brings the newest
  JDK, where the Boot 3.4 test stack's Mockito cannot mock interfaces; set `JAVA_HOME` to 21.
- Docker (for the pgvector Postgres)
- The **Notion integration token** already shared into the archive tables

Embeddings run **locally in-process** (ONNX `paraphrase-multilingual-MiniLM-L12-v2`, 384-dim) — no
API key. `scripts/fetch-models.sh` downloads the model (~480 MB) once, at a pinned Hugging Face
revision with a sha256 check; the service loads it from `models/` and will not start without it.

## Run

```bash
# 0. Fetch the embedding model (once; re-runs skip files that already match)
scripts/fetch-models.sh

# 1. Start pgvector Postgres (creates the vector/hstore/uuid-ossp extensions via init.sql)
docker compose up -d

# 2. Provide the Notion token (P0 needs just this one)
export NOTION_TOKEN=ntn_...

# 3. Run the service (creates the vector_store table on first boot)
mvn spring-boot:run

# 4. Ingest the Trending table, then search it
curl -X POST localhost:8080/sync
curl -X POST localhost:8080/search \
  -H 'Content-Type: application/json' \
  -d '{"q":"agent frameworks and tool-use orchestration","topK":5}'

# P1: narrow the same search by metadata (all filter fields optional, AND-combined)
curl -X POST localhost:8080/search \
  -H 'Content-Type: application/json' \
  -d '{"q":"agent frameworks","topK":5,"language":"Python","minStars":500}'

# P2: grounded Q&A with citations (needs an Anthropic key)
export ANTHROPIC_API_KEY=sk-ant-...
curl -X POST localhost:8080/ask \
  -H 'Content-Type: application/json' \
  -d '{"q":"which trending repos are about agent skills, and what do they do?"}'
```

`/search` returns each hit's document `id`, `text`, `metadata` (source/repo/week/category/language/url),
and a similarity `score`. `/ask` returns `{answer, citations, usage}`, where each citation is a source
row (`id` / `repo` / `url` / `week` / retrieval `score`) and `usage` is the call's `model`,
`inputTokens` and `outputTokens` (thinking included).

## Test

```bash
mvn -B verify   # unit + slice tests, then AskFlowIT; no tokens. CI runs the same on every PR.
```

`AskFlowIT` starts a pgvector container through Testcontainers, so `verify` needs a running Docker.
On macOS, Docker Desktop must expose its default socket, or set
`DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`.

## Evals (`evals/`, Python)

A typed Python package (uv, pydantic, httpx, pytest, mypy strict, ruff) that tests the service as a
black box over HTTP. It serves a frozen copy of the Notion Trending table from a local stub, so an
eval needs no Notion token and does not move when the table does. `/sync` replaces every trending
row, so rows from an earlier live sync do not leak into an eval.

```bash
cd evals
uv sync
uv run ruff check && uv run ruff format --check && uv run mypy && uv run pytest   # unit tests

# Against a running service: start it with NOTION_TOKEN=dummy NOTION_BASE_URL=http://127.0.0.1:8765,
# then the tests start the stub on 8765, POST /sync from the fixture, and query /search.
EVAL_SERVICE_URL=http://localhost:8080 uv run pytest -m service

# Retrieval eval over the golden set (golden_v1.jsonl, 62 items). The first run writes the
# baseline with --write-baseline; later runs drop it and fail on any hit@5 hit→miss flip.
uv run radar-evals --golden golden_v1.jsonl --fixture fixtures/trending.json \
  --model-id paraphrase-multilingual-MiniLM-L12-v2 --out results/latest.json --baseline results/baseline.json --write-baseline

# LLM eval (paid, ~$2 per full run): /ask on every golden item, two code checks (no empty answer,
# no repo named outside the citations), then DeepEval metrics judged by claude-sonnet-5. The
# service and this command both need ANTHROPIC_API_KEY.
uv run radar-evals-llm --golden golden_v1.jsonl --fixture fixtures/trending.json --out results/llm-latest.json

# Re-capture the fixture (reads Notion; keeps only the columns the service parses).
NOTION_TOKEN=ntn_... uv run freeze-notion
```

CI runs the unit tests in `evals` and the live-service tests in `eval-retrieval`, against a
pgvector service container on the same tag as `docker-compose.yml`. The LLM eval runs in
`eval-llm.yml` only when the repo owner adds the `eval:llm` label to a PR or dispatches it; it reads
the key from the `ANTHROPIC_API_KEY` repository secret. It fails on any `/ask` error, empty answer or
uncited repo, and when a metric's mean is below 0.7.

## Layout

```
src/main/java/com/radar/intel/
├── RadarIntelligenceApplication.java   # boot + @ConfigurationPropertiesScan
├── notion/
│   ├── NotionProperties.java           # radar.notion.* config
│   ├── NotionClient.java               # resolve data source + paginated query (mirrors lib/notion.ts)
│   ├── NotionProps.java                # typed property extractors
│   └── TrendingRow.java
├── ingest/
│   ├── TrendingIngestService.java      # Notion rows -> Documents; /sync replaces the trending rows
│   └── IngestController.java           # POST /sync
├── search/
│   └── SearchController.java           # POST /search
├── ask/
│   └── AskController.java              # POST /ask — RetrievalAugmentationAdvisor + Claude, with citations
└── ApiErrorHandler.java                # surfaces upstream (Notion / Anthropic) failure causes
```

## Results

All numbers come from the CI runner over the 62-item golden set: hit@5, recall@5 and MRR at the
(url, week) level, on the 42 answerable items unless the column says otherwise.

**Bug 1, one row per repo per week** (MiniLM, ids keyed by Notion page id instead of repo url):
a fixture sync stores 190 rows instead of 111, and recall@5 rises from 0.126 to 0.166.

**Embedding A/B** (decided by a rule fixed before any run: a multilingual model ships only if it
turns at least 2 more zh-TW items into hits than the control, and mE5 only if it beats paraphrase
by at least 2):

| Model | hit@5 | R@5 | MRR | zh-TW hit@5 | pure-CJK hit@5 | English hit@5 |
|---|---:|---:|---:|---:|---:|---:|
| all-MiniLM-L6-v2 (before) | 0.476 | 0.166 | 0.352 | 0.238 (5/21) | 0.273 | 0.714 |
| **paraphrase-multilingual-MiniLM-L12-v2** (shipped) | **0.786** | **0.404** | 0.633 | **0.762 (16/21)** | **0.818** | **0.810** |
| multilingual-e5-small | 0.738 | 0.355 | **0.649** | 0.714 (15/21) | 0.636 | 0.762 |

MiniLM's English WordPiece vocabulary turns 76% of the corpus's CJK characters into `[UNK]`; both
multilingual models close the English-minus-Chinese hit@5 gap from 0.476 to 0.048. mE5 ranks the
first hit higher (MRR) but finds one fewer zh-TW item, so paraphrase ships.

## Notes / decisions

- **Embeddings are local & keyless.** In-process ONNX (`paraphrase-multilingual-MiniLM-L12-v2`,
  384-dim, maxLength 128) via `spring-ai-starter-model-transformers`, chosen by the A/B under
  Results. After switching to another 384-d model, `POST /sync` re-embeds every row; a model with
  other dimensions also needs `spring.ai.vectorstore.pgvector.dimensions` changed and the
  `vector_store` table recreated (the embedding column is a fixed-width `vector(N)`).
- **Exact vector search.** `index-type: NONE`: at a few hundred rows an HNSW index buys no speed,
  and it applies metadata filters after its approximate scan, so a week-filtered `/search` could
  return fewer than `topK` rows. PgVectorStore never drops an existing index and creates the
  table only at startup, so a table created under the old HNSW setting needs, once: stop the
  service, `docker compose exec postgres psql -U radar -d radar -c 'DROP TABLE vector_store'`,
  start it again, then `POST /sync`.
- **Full-refresh re-sync.** `POST /sync` deletes the trending rows and adds the table's current
  rows in one transaction, so a row deleted in Notion does not linger and a failed embed leaves the
  old rows in place. Ids are Notion page ids: the table has one row per repo per week.
- **Spring AI moves fast.** Versions/artifact ids match the reference docs at scaffold time —
  verify against `start.spring.io` / the current reference when you build.
- **RAG is grounded, not filtered.** `/ask` retrieves from the same pgvector store `/search` uses
  (`RetrievalAugmentationAdvisor` + `VectorStoreDocumentRetriever`) and returns the retrieved rows
  as citations. It takes only a question — no metadata-filter fields — so it has no SQL-filter
  input surface (unlike `/search`, which validates its filter values). The retriever keeps every
  candidate (similarity threshold 0) and bounds the context by `topK`.

## Next

~~P1 metadata-filtered search~~ (done) · ~~P2 RAG `/ask` with citations~~ (done) · P3 eval
harness (precision@k + LLM-as-judge) + CI gate · P4 Langfuse tracing · P5 Blog/Loot ingest +
an "Ask the radar" page served by this service (github-radar-ui only links to it, so it stays a
pure Notion reader).

The target stack and the milestone order (M0–M11, each with a checkable done-when) are in
[docs/stack-plan.md](docs/stack-plan.md).
