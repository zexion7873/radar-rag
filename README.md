# Radar Intelligence Service (P0–P5)

A standalone Java / Spring Boot service that adds LLM-powered intelligence on top of the
GitHub-radar Notion archive written by the `ai-assistant` routines. **P0** stands up the
skeleton: pgvector, Notion ingestion of the **Trending** table, and a semantic `/search`
endpoint. **P1** adds metadata-filtered search. **P2** adds a RAG `/ask` endpoint —
grounded Q&A with citations. **P3** adds the eval harness — a golden-set retrieval gate
(precision@k / recall@k / MRR) plus an LLM-as-judge check of `/ask`, wired into CI.
**P4** adds Langfuse tracing over OTLP. **P5** ingests the remaining archive tables —
Loot (Claude + Copilot) and Blog — so search and Q&A cover the whole radar.

> Not a proxy in front of Notion — it exposes *new* capabilities (semantic search and
> grounded Q&A) that the pure-reader `github-radar-ui` cannot do.

## What P0–P2 gives you

- `POST /sync` — pull all four archive tables from Notion (Trending, Loot×2, Blog), embed
  each row into pgvector (upsert). Returns per-table counts. Sources: `trending`,
  `loot-claude`, `loot-copilot`, `blog`.
- `POST /search` — semantic search over the embedded rows, optionally filtered by metadata
  (`source` / `category` / `language` / `week` exact match, `stars_per_week` ≥ `minStars`).
  The shared keys stretch per source: loot's `category` is its asset Type (skill/mcp/…),
  blog's `week` is its published date.
- `POST /ask` — ask a question in natural language; the service retrieves the relevant radar
  rows from pgvector, has **Claude** answer from them, and returns the answer plus the source
  rows as **citations**. Needs `ANTHROPIC_API_KEY`; `/sync` and `/search` do not.

## Prerequisites

- Java 21, Maven
- Docker (for the pgvector Postgres)
- The **Notion integration token** already shared into the archive tables

Embeddings run **locally in-process** (ONNX `all-MiniLM-L6-v2`, 384-dim) — no API key.
First boot downloads an ~80MB model, then everything runs inside the JVM.

## Run

```bash
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

`/search` returns each hit's `text`, `metadata` (source/repo/week/category/language/url), and a
similarity `score`. `/ask` returns `{answer, citations}`, where each citation is a source row
(`repo` / `url` / `week` / retrieval `score`).

## Evals (P3)

`mvn verify` runs the eval harness (Docker required — Testcontainers spins up a disposable
`pgvector/pgvector:pg16`; nothing touches your real store):

- **Retrieval gate** (`RetrievalEvalTest`, keyless): ingests a fixture corpus through the
  *production* row→Document mapping, runs a golden set of queries (English **and**
  Traditional Chinese, mirroring the real archive), and scores precision@5 / recall@5 / MRR.
  The build fails if the means drop below the calibrated floors, so an embedding-model swap
  or retrieval regression can't land silently. The report also splits means by query
  language — the before/after evidence for a future multilingual-embedding swap.
- **LLM-as-judge** (`AskJudgeEvalTest`): drives the real `/ask` flow and has Claude
  (Spring AI `RelevancyEvaluator`) judge each answer against the question and the retrieved
  context. Runs only when `ANTHROPIC_API_KEY` is set (locally or as a repo secret) and is
  skipped otherwise — the keyless gate above runs everywhere.

CI (`.github/workflows/ci.yml`) runs the same `mvn verify` on every PR and push to `main`,
caching the ~80MB ONNX embedding model between runs. The fixture corpus lives in
`src/test/resources/eval/corpus.json`, the golden set in `src/test/resources/eval/golden.json`;
its texts are deliberately Traditional Chinese to match the real archive.

> macOS + Docker Desktop: if Testcontainers can't find Docker (`Could not find a valid
> Docker environment`), either enable *Settings → Advanced → Allow the default Docker socket
> to be used*, or run with `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock mvn verify`.

## Tracing (P4)

Every request is traced (HTTP server span + Spring AI chat / vector-store observation
spans, sampling 1.0) and exported to **Langfuse** over OTLP with Basic auth:

```bash
export LANGFUSE_PUBLIC_KEY=pk-lf-...
export LANGFUSE_SECRET_KEY=sk-lf-...
# EU cloud is the default host; override for US cloud or self-hosted:
# export LANGFUSE_HOST=https://us.cloud.langfuse.com
```

Without the keys the span exporter is a no-op — the app boots and runs exactly as before
(same keyless stance as `/ask`). `LANGFUSE_OTLP_ENDPOINT` overrides the full traces URL,
which also lets you point the exporter at a plain OTel collector for debugging.

Spring AI 1.0.0 puts model name and token usage on the spans; prompt/completion *content*
is intentionally not exported (the framework moved content capture to logs at 1.0.0-RC1).

## Layout

```
src/main/java/com/radar/intel/
├── RadarIntelligenceApplication.java   # boot + @ConfigurationPropertiesScan
├── notion/
│   ├── NotionProperties.java           # radar.notion.* config (all four data-source ids)
│   ├── NotionClient.java               # resolve data source + paginated query (mirrors lib/notion.ts)
│   ├── NotionProps.java                # typed property extractors
│   ├── TrendingRow.java
│   ├── LootRow.java
│   └── BlogRow.java
├── ingest/
│   ├── TrendingIngestService.java      # Notion rows -> Document -> vectorStore.add (upsert by URL)
│   ├── LootIngestService.java          # Loot Claude/Copilot tables (source-prefixed ids)
│   ├── BlogIngestService.java          # Blog table (title identity, published -> week)
│   └── IngestController.java           # POST /sync (all four tables)
├── search/
│   └── SearchController.java           # POST /search
├── ask/
│   └── AskController.java              # POST /ask — RetrievalAugmentationAdvisor + Claude, with citations
├── tracing/
│   ├── LangfuseProperties.java         # langfuse.* config (keys, host, endpoint override)
│   └── LangfuseTracingConfig.java      # OTLP span exporter with Basic auth; no-op without keys
└── ApiErrorHandler.java                # surfaces upstream (Notion / Anthropic) failure causes

src/test/java/com/radar/intel/eval/     # P3 eval harness (golden set under src/test/resources/eval/)
├── EvalSupport.java                    # Testcontainers pgvector + fixture ingest via the prod mapping
├── RetrievalEvalTest.java              # precision@5 / recall@5 / MRR gate (keyless)
└── AskJudgeEvalTest.java               # LLM-as-judge over /ask (needs ANTHROPIC_API_KEY, else skipped)
```

## Notes / decisions

- **Embeddings are local & keyless.** In-process ONNX (`all-MiniLM-L6-v2`, 384-dim) via
  `spring-ai-starter-model-transformers`. If you later switch embedding models (Ollama, OpenAI,
  Voyage), keep `spring.ai.vectorstore.pgvector.dimensions` in sync and recreate the
  `vector_store` table (the embedding column is a fixed-width `vector(N)`).
- **Idempotent re-sync.** Each document's id is a stable name-based UUID, so `POST /sync`
  upserts rather than duplicating. Trending keys on the repo URL; loot and blog prefix the
  key with their source (`loot-claude|…`, `blog|…`) so a URL that appears in more than one
  table stays one document per table. Loot, which is one row per candidate per *week*
  upstream, is collapsed to each candidate's most recent week before embedding.
- **Spring AI moves fast.** Versions/artifact ids match the reference docs at scaffold time —
  verify against `start.spring.io` / the current reference when you build.
- **RAG is grounded, not filtered.** `/ask` retrieves from the same pgvector store `/search` uses
  (`RetrievalAugmentationAdvisor` + `VectorStoreDocumentRetriever`) and returns the retrieved rows
  as citations. It takes only a question — no metadata-filter fields — so it has no SQL-filter
  input surface (unlike `/search`, which validates its filter values). Because the embeddings are
  English-centric over a partly Traditional-Chinese corpus, the retriever uses a low similarity
  threshold and bounds context by `topK`.

## Next (from the spec)

~~P1 metadata-filtered search~~ (done) · ~~P2 RAG `/ask` with citations~~ (done) · ~~P3 eval
harness (precision@k + LLM-as-judge) + CI gate~~ (done) · ~~P4 Langfuse tracing~~ (done) ·
~~P5 Blog/Loot ingest~~ (done; the "Ask the radar" box lives in github-radar-ui).
