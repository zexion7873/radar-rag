# Privacy

This describes the service as the code runs it. It is not deployed publicly yet; whoever runs it is the
operator of the data below, with their own Notion, Anthropic and Langfuse accounts.

There are no accounts, no cookies and no analytics. The service keeps no record of who asked what.

## Where a question goes

- **`POST /search`.** The query is embedded on the machine running the service and compared with the
  stored rows. It leaves the machine only inside a Langfuse trace (below), where the vector-store span
  records the query text.
- **`POST /ask`.** The question, together with the five archive rows retrieved for it, is sent to
  Anthropic's API to generate the answer, under the operator's API key and Anthropic's terms for it.
- **Langfuse, only when both Langfuse keys are set.** Each request is traced to Langfuse Cloud. A
  `/search` trace carries the query text; an `/ask` trace carries the question, the full prompt sent to
  Claude (the question and the retrieved rows), the answer, token counts and timings. Without both
  keys nothing is exported.

## What it stores

| Where | Contents |
|---|---|
| Postgres (`vector_store`) | The Trending Archive rows synced from Notion, and their embeddings. Never questions or answers. |
| Application log | Startup, and Notion and Anthropic failures with the upstream error. Questions and answers are not logged. |
| Process memory, prod profile only | For rate limiting: each recent client's IP address (an IPv6 client's /64) with its request counters, at most 10,000 clients. Never logged or written anywhere, and gone on restart. |
| Langfuse, if enabled | The traces above, kept for the Langfuse plan's retention (30 days on the free plan). |

The archive rows are public GitHub repositories' names, descriptions and statistics, plus short
comments written by the upstream routines. They hold no personal data.

## Removing it

Drop the `vector_store` table, or the database volume (`docker compose down -v`). Langfuse traces are
deleted from the Langfuse project, or expire with its retention.

## Questions

Open an issue at <https://github.com/zexion7873/radar-rag/issues>, or report a vulnerability privately as
described in [SECURITY.md](SECURITY.md).
