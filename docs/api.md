# API and operations

The service's endpoints, the `prod` profile, and how the image is built and deployed. The
[README](../README.md) covers running it locally.

## Endpoints

- `POST /sync` — pull the Trending and Blog archives from Notion and replace each one's rows in
  pgvector: one document per Notion row (a repo's week, or a post), keyed by the Notion page id. Each
  source refreshes on its own and reports `{ingested, sources, failed}`; any failed source makes it
  a 502, with the others still synced. A row's url is kept only when it is `http(s)`. A request
  carrying `Origin`, which every browser POST does, is `403` in every profile. The Loot ledgers stay
  out: the UI keeps them behind a login.
- `POST /search` — semantic search over the embedded rows, optionally filtered by metadata
  (`source` / `category` / `language` / `week` exact match, `stars_per_week` ≥ `minStars`). Each
  url comes back once, at its best-scoring row: a repo that charted for several weeks fills one
  slot, not several. Without a `source` it searches both sources in turns, as `/ask` does, and reads
  the question: a trending category named as one ("apps 類", "infra category") filters to it unless
  the request sets `category`, "最近" / "latest" ranks each source's last 28 days higher, blog
  posts that share the question's rarer words ("晶片", "chips") rank higher (the returned `score`
  stays the plain similarity), and a question asking for a repo ("套件", "package", "library") takes
  trending first. A repo the question names as owner/name ("mattpocock/skills", or its GitHub URL)
  comes first, with trending leading.
- `POST /ask` — ask a question in natural language; the service retrieves the relevant radar
  rows from pgvector, sends them to **Claude** as citable documents, and returns the answer plus
  the rows it actually cites as **citations**. Needs `ANTHROPIC_API_KEY`; `/sync` and `/search` do not. Runs on
  `claude-opus-5-5`. A model refusal returns 502; an Anthropic failure returns 502 (client error,
  e.g. a bad key) or 503 (rate limit or overload, after one retry), without the upstream body.
  With `Accept: text/event-stream` the same answer streams as Server-Sent Events.

## Responses

`/search` returns each hit's document `id`, `text`, `metadata` (source/repo/week/category/language/url),
and a similarity `score`. `/ask` returns `{answer, citations, sources, usage}`. `sources` are the 5
retrieved rows (`id` / `repo` / `url` / `week` / retrieval `score`); `citations` are the sources the
answer cites, each with the passages it quoted (`citedText`); `usage` is the call's `model`,
`inputTokens` and `outputTokens` (thinking included). Both refuse a body over 16 KiB (read no further),
a `q` over 1,000 characters, and `/search` a `topK` over 50, with a 400.

A request whose `Accept` names `text/event-stream` gets the answer as Server-Sent Events, each `data` a
JSON object: `sources` (the retrieved rows, before the model call), `delta` (`{text}`, as the model
writes), then exactly one of `done` (`{answer, citations, usage}`; with `sources` it is the JSON answer)
or `error` (`{status, message}`, 502 or 503). `done` is the only success signal: a refusal is known only
at the end, after its text has streamed, so it ends in `error`. A failure before the model call (400,
403, 429) keeps its status; list `application/json` after `text/event-stream` to get its JSON body,
which an event-stream-only `Accept` gets empty. `*/*` and a missing `Accept` get JSON.

```bash
curl -N -X POST localhost:8080/ask \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream, application/json' \
  -d '{"q":"which trending repos are about agent skills, and what do they do?"}'
```

The OpenAPI spec is at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`, both generated from the
controllers. The prod profile turns both off.

## Prod profile

`SPRING_PROFILES_ACTIVE=prod` is the public deployment's configuration; the image and CI run without it,
so the eval gates measure the service unthrottled and at medium effort. Without it the service answers
only a `Host` of `localhost`, `127.0.0.1` or `[::1]` and refuses any other with `403`, which keeps a
DNS-rebinding page off a dev run's `/ask`. The profile drops that check and adds:

- **`/sync` needs `Authorization: Bearer $SYNC_SECRET`.** Boot fails without `SYNC_SECRET`; set but
  empty, every call gets `401`.
- **Rate limits.** Per client, `/ask`: 5 a minute and 20 a day; `/search`: 30 a minute. The /64s of one
  IPv6 /48 share `/ask` 10 a minute and 50 a day and `/search` 60 a minute, and all clients together
  get 100 `/ask` a day, spent only by a question that passed the Turnstile check below. Past any of
  them, `429` with `Retry-After`. The client is the right-most
  `X-Forwarded-For` entry (the address Cloud Run's front end appends), an IPv6 client its /64.
- **`/ask` needs a Cloudflare Turnstile token** (`X-Turnstile-Token`), checked with siteverify before
  any retrieval or model call: missing, rejected or issued on a host other than github-radar-ui's is
  `403`, siteverify unreachable is `503`. Without
  the prod profile there is no secret and nothing is checked, so local runs and the eval harness ask
  freely; `/config` returns Cloudflare's always-pass test site key.
- **`/ask` at low effort**, generic error bodies (no `message`), `INFO` logs, no Swagger UI or
  `/v3/api-docs`, and no fallback for `POSTGRES_PASSWORD`.

## Deploy

A push to `main` that touches the service runs the Deploy workflow: it builds the image with no layer
cache (any job on `main` can write the Actions cache), pushes it to
Artifact Registry and deploys it to Cloud Run in asia-east1 (2 GiB, at most one instance, the prod
profile), then smoke-tests `/search`, `/sync` without the secret and Swagger. It signs in through
Workload Identity Federation, so no Google Cloud key is stored anywhere; every secret, the Neon JDBC
URL included, is read from Secret Manager at startup. A cleanup policy on the Artifact Registry
repository keeps the three most recent images and deletes the rest, so a rollback reaches back two
deploys. The Weekly sync workflow posts `/sync` on Mondays at 03:00 UTC, two hours after the Trending
routine writes the week's rows.

The live page, **[Ask the radar](https://whyisthistrending.vercel.app/ask)**, is on
[github-radar-ui](https://github.com/zexion7873/github-radar-ui). Every row's `id` is its Notion page
id, which the page links as `/trending/<id>` or `/blog/<id>`; any week's id of a repo resolves. Its
browser code calls this service's `/ask` (CORS allows that one origin), and this service's root
redirects there. The page requests `/config` as it loads, so a cold start of about 10 s mostly passes
while the visitor types. Cloud Run's second-generation environment is pinned, because the first
generation's sandbox tripled the time spent loading the model. The public deployment sets no Langfuse
keys, so visitors' questions are not traced ([PRIVACY.md](../PRIVACY.md)).

## In a container

The image fetches the model itself and carries everything the service loads, so it downloads nothing
at runtime. CI's retrieval gate runs this image. Compose builds and runs it beside Postgres, reading
`.env` (quoted values included):

```bash
docker compose --profile app up --build
```

Without `--profile app`, `docker compose up -d` starts Postgres alone, for `mvn spring-boot:run`.
The container gets 2 GiB and no swap, as on Cloud Run: ONNX Runtime holds the model natively
(~1.1 GB) and the JVM about 0.3 GB, so a boot needing more fails locally first. The image starts from
an AOT cache made by a training run at build time, inside the image, because the cache only loads on
the JVM that trained it.

## Retrieval details

- **Embeddings.** In-process ONNX (`paraphrase-multilingual-MiniLM-L12-v2`, 384-dim, maxLength 128)
  through `OnnxEmbeddingModel`: ONNX Runtime plus the DJL tokenizer, with the mean pooling in Java,
  summed in PyTorch's order so the vectors stay bit-identical to Spring AI's transformers module
  (which needs PyTorch for that pooling alone). After switching to another 384-d model, `POST /sync`
  re-embeds every row; a model with other dimensions also needs
  `spring.ai.vectorstore.pgvector.dimensions` changed and the `vector_store` table recreated (the
  embedding column is a fixed-width `vector(N)`).
- **Full-refresh re-sync.** `POST /sync` deletes a source's rows and adds its table's current rows
  in one transaction per source, so a row deleted in Notion does not linger and a failed fetch or
  embed leaves that source's old rows in place. Ids are Notion page ids: trending has one row per
  repo per week, blog one per post. A blog post's week is its publication date, or its archive date
  when it has none.
- **RAG is grounded, not filtered.** `/ask` retrieves exactly what `/search` with `topK` 5 and no
  `source` returns, one row per url, each source searched on its own and taken in turns, sends each
  row as an Anthropic citation document titled "repo week", with the repo's chart run (weeks charted,
  first and last) appended for the model only, and returns only the rows Claude cites. It takes only
  a question — no metadata-filter fields — so it has no SQL-filter input surface (unlike `/search`,
  which validates its filter values). The retriever keeps every candidate (similarity threshold 0)
  and bounds the context by `topK`.
