# Radar Intelligence Service (P0–P2)

A standalone Java / Spring Boot service that adds LLM-powered intelligence on top of the
GitHub-radar Notion archive written by the `ai-assistant` routines. **P0** stands up the
skeleton: pgvector, Notion ingestion of the **Trending** table, and a semantic `/search`
endpoint. **P1** adds metadata-filtered search. **P2** adds a RAG `/ask` endpoint —
grounded Q&A with citations. Evals and tracing come in later phases (see [docs/stack-plan.md](docs/stack-plan.md)).

> Not a proxy in front of Notion — it exposes *new* capabilities (semantic search and
> grounded Q&A) that the pure-reader `github-radar-ui` cannot do.

## What P0–P2 gives you

- `POST /sync` — pull the Trending Archive from Notion, embed each row into pgvector (upsert).
- `POST /search` — semantic search over the embedded rows, optionally filtered by metadata
  (`source` / `category` / `language` / `week` exact match, `stars_per_week` ≥ `minStars`).
- `POST /ask` — ask a question in natural language; the service retrieves the relevant radar
  rows from pgvector, has **Claude** answer from them, and returns the answer plus the source
  rows as **citations**. Needs `ANTHROPIC_API_KEY`; `/sync` and `/search` do not.

## Prerequisites

- Java 21, and Maven running on it: `mvn -v` names the JVM. Homebrew's maven brings the newest
  JDK, where the Boot 3.4 test stack's Mockito cannot mock interfaces; set `JAVA_HOME` to 21.
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

## Test

```bash
mvn -B verify   # unit + slice tests; needs no Docker and no tokens. CI runs the same on every PR.
```

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
│   ├── TrendingIngestService.java      # Notion rows -> Document -> vectorStore.add (upsert by URL)
│   └── IngestController.java           # POST /sync
├── search/
│   └── SearchController.java           # POST /search
├── ask/
│   └── AskController.java              # POST /ask — RetrievalAugmentationAdvisor + Claude, with citations
└── ApiErrorHandler.java                # surfaces upstream (Notion / Anthropic) failure causes
```

## Notes / decisions

- **Embeddings are local & keyless.** In-process ONNX (`all-MiniLM-L6-v2`, 384-dim) via
  `spring-ai-starter-model-transformers`. If you later switch embedding models (Ollama, OpenAI,
  Voyage), keep `spring.ai.vectorstore.pgvector.dimensions` in sync and recreate the
  `vector_store` table (the embedding column is a fixed-width `vector(N)`).
- **Exact vector search.** `index-type: NONE`: at a few hundred rows an HNSW index buys no speed,
  and it applies metadata filters after its approximate scan, so a week-filtered `/search` could
  return fewer than `topK` rows. PgVectorStore never drops an existing index and creates the
  table only at startup, so a table created under the old HNSW setting needs, once: stop the
  service, `docker compose exec postgres psql -U radar -d radar -c 'DROP TABLE vector_store'`,
  start it again, then `POST /sync`.
- **Idempotent re-sync.** Documents use the repo URL as a stable id, so `POST /sync` upserts
  rather than duplicating.
- **Spring AI moves fast.** Versions/artifact ids match the reference docs at scaffold time —
  verify against `start.spring.io` / the current reference when you build.
- **RAG is grounded, not filtered.** `/ask` retrieves from the same pgvector store `/search` uses
  (`RetrievalAugmentationAdvisor` + `VectorStoreDocumentRetriever`) and returns the retrieved rows
  as citations. It takes only a question — no metadata-filter fields — so it has no SQL-filter
  input surface (unlike `/search`, which validates its filter values). Because the embeddings are
  English-centric over a partly Traditional-Chinese corpus, the retriever uses a low similarity
  threshold and bounds context by `topK`.

## Next

~~P1 metadata-filtered search~~ (done) · ~~P2 RAG `/ask` with citations~~ (done) · P3 eval
harness (precision@k + LLM-as-judge) + CI gate · P4 Langfuse tracing · P5 Blog/Loot ingest +
an "Ask the radar" page served by this service (github-radar-ui only links to it, so it stays a
pure Notion reader).

The target stack and the milestone order (M0–M11, each with a checkable done-when) are in
[docs/stack-plan.md](docs/stack-plan.md).
