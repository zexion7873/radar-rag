# Radar Intelligence Service (P0)

A standalone Java / Spring Boot service that adds LLM-powered intelligence on top of the
GitHub-radar Notion archive written by the `ai-assistant` routines. **P0** stands up the
skeleton: pgvector, Notion ingestion of the **Trending** table, and a semantic `/search`
endpoint. RAG `/ask`, evals, and tracing come in later phases (see the design spec).

> Not a proxy in front of Notion — it exposes *new* capabilities (semantic search now,
> grounded Q&A later) that the pure-reader `github-radar-ui` cannot do.

## What P0 gives you

- `POST /sync` — pull the Trending Archive from Notion, embed each row into pgvector (upsert).
- `POST /search` — semantic search over the embedded rows.

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
```

`/search` returns each hit's `text`, `metadata` (source/repo/week/category/language/url), and a
similarity `score`.

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
└── search/
    └── SearchController.java           # POST /search
```

## Notes / decisions

- **Embeddings are local & keyless.** In-process ONNX (`all-MiniLM-L6-v2`, 384-dim) via
  `spring-ai-starter-model-transformers`. If you later switch embedding models (Ollama, OpenAI,
  Voyage), keep `spring.ai.vectorstore.pgvector.dimensions` in sync and recreate the
  `vector_store` table (the embedding column is a fixed-width `vector(N)`).
- **Idempotent re-sync.** Documents use the repo URL as a stable id, so `POST /sync` upserts
  rather than duplicating.
- **Spring AI moves fast.** Versions/artifact ids match the reference docs at scaffold time —
  verify against `start.spring.io` / the current reference when you build.

## Next (from the spec)

P1 metadata-filtered search · P2 RAG `/ask` with citations · P3 eval harness (precision@k +
LLM-as-judge) + CI gate · P4 Langfuse tracing · P5 Blog/Loot ingest + "Ask the radar" in the UI.
