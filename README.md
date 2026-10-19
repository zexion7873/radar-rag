<div align="center">

# 📡 Radar RAG

[![CI](https://github.com/zexion7873/radar-rag/actions/workflows/ci.yml/badge.svg)](https://github.com/zexion7873/radar-rag/actions/workflows/ci.yml)

<img src="docs/assets/social-card.png" width="640" alt="Radar RAG's social card: the radar mark, the name Radar RAG, and the line: Ask the radar. Get the citations.">

**Ask the GitHub Radar archive a question, in Chinese or English, and get an answer grounded
in the weekly trending rows and the blog archive, with citations back to the rows it used.**

[![Live site](https://img.shields.io/badge/live-whyisthistrending.vercel.app%2Fask-black?style=flat)](https://whyisthistrending.vercel.app/ask)
[![Java 25](https://img.shields.io/badge/Java-25-orange?style=flat)](#-prerequisites)
[![Evals: gated in CI](https://img.shields.io/badge/evals-gated%20in%20CI-brightgreen?style=flat)](#-results)
[![License: MIT](https://img.shields.io/github/license/zexion7873/radar-rag?style=flat)](LICENSE)

<sub>The live page's first question after a quiet spell waits 10–15 s for a cold start.</sub>

No embedding API. No vector database service. No PyTorch. One JVM, one pgvector table, and a Python
harness that gates every pull request.

</div>

---

## 🌐 What it gives you

A standalone Java / Spring Boot service that adds LLM-powered intelligence on top of the
[GitHub Radar](https://whyisthistrending.vercel.app) Notion archive that a set of Claude Code routines
writes every week ([case study](https://github.com/zexion7873/github-radar-ui/blob/main/docs/case-study.md)). **P0** stands up the
skeleton: pgvector, Notion ingestion of the **Trending** table, and a semantic `/search`
endpoint. **P1** adds metadata-filtered search. **P2** adds a RAG `/ask` endpoint —
grounded Q&A with citations. **P3** adds a Python eval harness with CI gates, and **P4** traces every
request into Langfuse, and **P5** adds the Blog archive (see [docs/stack-plan.md](docs/stack-plan.md)).

> Not a proxy in front of Notion — it exposes *new* capabilities (semantic search and
> grounded Q&A) that the read-mostly [`github-radar-ui`](https://github.com/zexion7873/github-radar-ui) cannot do.

- `POST /sync` — pull the Trending and Blog archives from Notion and replace each one's rows in
  pgvector: one document per Notion row (a repo's week, or a post), keyed by the Notion page id. Each
  source refreshes on its own and reports `{ingested, sources, failed}`; any failed source makes it
  a 502, with the others still synced. The Loot ledgers stay out: the UI keeps them behind a login.
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

---

## 🧭 How it fits together

```mermaid
flowchart LR
  notion[(Notion<br/>Trending + Blog)] -->|POST /sync| ingest[Ingest]
  ingest -->|embed| onnx[OnnxEmbeddingModel<br/>multilingual, in-process]
  onnx --> pg[(pgvector)]
  user([caller]) -->|POST /search| pg
  user -->|POST /ask| retrieve[Retrieve top 5]
  retrieve --> pg
  retrieve -->|rows as citable documents| claude[Claude Opus 5.5]
  claude -->|answer + citations| user
  evals[Python eval harness<br/>CI gates] -.->|HTTP, black box| user
  claude -.->|OTLP traces| langfuse[(Langfuse)]
```

---

## 📋 Prerequisites

- Java 25 or newer (CI builds on Temurin 25)
- Docker (for the pgvector Postgres)
- The **Notion integration token** already shared into the archive tables

Embeddings run **locally in-process** (ONNX `paraphrase-multilingual-MiniLM-L12-v2`, 384-dim) — no
API key. `scripts/fetch-models.sh` downloads the model (~480 MB) once, at a pinned Hugging Face
revision with a sha256 check; the service loads it from `models/` and will not start without it.

Every external service the code calls, runs on or builds from is listed in
[docs/external-services.md](docs/external-services.md) (in Chinese).

---

## 🏃 Run it yourself

> [!NOTE]
> A clone runs without access to the Notion archive. Start the service with
> `NOTION_TOKEN=dummy NOTION_BASE_URL=http://127.0.0.1:8765`, and a retrieval eval (see Evals below)
> syncs the frozen Trending and Blog fixtures from a local stub, as CI's retrieval gate does;
> `/search` then answers over them, and `/ask` too with an Anthropic key. A real Notion token is
> needed only to sync the live tables.

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

# The same answer as it is written (Server-Sent Events)
curl -N -X POST localhost:8080/ask \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream, application/json' \
  -d '{"q":"which trending repos are about agent skills, and what do they do?"}'
```

`/search` returns each hit's document `id`, `text`, `metadata` (source/repo/week/category/language/url),
and a similarity `score`. `/ask` returns `{answer, citations, sources, usage}`. `sources` are the 5
retrieved rows (`id` / `repo` / `url` / `week` / retrieval `score`); `citations` are the sources the
answer cites, each with the passages it quoted (`citedText`); `usage` is the call's `model`,
`inputTokens` and `outputTokens` (thinking included). Both refuse a `q` over 1,000 characters, and
`/search` a `topK` over 50, with a 400.

A request whose `Accept` names `text/event-stream` gets the answer as Server-Sent Events, each `data` a
JSON object: `sources` (the retrieved rows, before the model call), `delta` (`{text}`, as the model
writes), then exactly one of `done` (`{answer, citations, usage}`; with `sources` it is the JSON answer)
or `error` (`{status, message}`, 502 or 503). `done` is the only success signal: a refusal is known only
at the end, after its text has streamed, so it ends in `error`. A failure before the model call (400,
403, 429) keeps its status; list `application/json` after `text/event-stream` to get its JSON body,
which an event-stream-only `Accept` gets empty. `*/*` and a missing `Accept` get JSON.

The OpenAPI spec is at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`, both generated from the
controllers. The prod profile turns both off.

### 🔒 Prod profile

`SPRING_PROFILES_ACTIVE=prod` is the public deployment's configuration; the image and CI run without it,
so the eval gates measure the service unthrottled and at medium effort. It adds:

- **`/sync` needs `Authorization: Bearer $SYNC_SECRET`.** Boot fails without `SYNC_SECRET`; set but
  empty, every call gets `401`.
- **Per-client rate limits.** `/ask`: 5 a minute and 20 a day; `/search`: 30 a minute; past either,
  `429` with `Retry-After`. The client is the right-most `X-Forwarded-For` entry (the address Cloud
  Run's front end appends), an IPv6 client its /64.
- **`/ask` needs a Cloudflare Turnstile token** (`X-Turnstile-Token`), checked with siteverify before
  any retrieval or model call: missing, rejected or issued on a host other than github-radar-ui's is
  `403`, siteverify unreachable is `503`. Without
  the prod profile there is no secret and nothing is checked, so local runs and the eval harness ask
  freely; `/config` returns Cloudflare's always-pass test site key.
- **`/ask` at low effort**, generic error bodies (no `message`), `INFO` logs, no Swagger UI or
  `/v3/api-docs`, and no fallback for `POSTGRES_PASSWORD`.

### 🚀 Deploy

A push to `main` that touches the service runs the Deploy workflow: it builds the image, pushes it to
Artifact Registry and deploys it to Cloud Run in asia-east1 (2 GiB, at most one instance, the prod
profile), then smoke-tests `/search`, `/sync` without the secret and Swagger. It signs in through
Workload Identity Federation, so no Google Cloud key is stored anywhere; every secret, the Neon JDBC
URL included, is read from Secret Manager at startup. A cleanup policy on the Artifact Registry
repository keeps the three most recent images and deletes the rest, so a rollback reaches back two
deploys. The Weekly sync workflow posts `/sync` on Mondays at 03:00 UTC, two hours after the Trending
routine writes the week's rows.

**[Ask the radar](https://whyisthistrending.vercel.app/ask)** is the live page, on
[github-radar-ui](https://github.com/zexion7873/github-radar-ui): a question in, an answer with its
cited trending repos and blog posts out, each linking to its page on that site. Every row's `id` is its
Notion page id, which the page links as `/trending/<id>` or `/blog/<id>`; any week's id of a repo
resolves. Its browser code calls
this service's `/ask` (CORS allows that one origin), and this service's root redirects there. The page
requests `/config` as it loads, so a cold start of about 10 s mostly passes while the visitor types.
Cloud Run's second-generation environment is pinned, because the first generation's sandbox tripled
the time spent loading the model. `/search` also answers curl; the live `/ask` needs the page's
Turnstile token:

```bash
curl -X POST https://radar-rag-50472171523.asia-east1.run.app/search \
  -H 'Content-Type: application/json' -d '{"q":"agent memory across sessions","topK":3}'
```

Each client gets 5 `/ask` a minute (20 a day) and 30 `/search` a minute; `/ask` questions and answers
are traced, as [PRIVACY.md](PRIVACY.md) describes.

### 🐳 In a container

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

---

## 🛠️ Develop

```bash
mvn -B verify   # unit and slice tests, then the integration tests; no tokens
```

CI runs the same on every pull request (one that touches only `.md` files or `docs/` skips it), beside
the eval jobs below. The integration tests start a pgvector container through Testcontainers, so
`verify` needs a running Docker; on macOS, Docker Desktop must expose its default socket, or set
`DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`. [CONTRIBUTING.md](CONTRIBUTING.md) has the traps
worth knowing first; [AGENTS.md](AGENTS.md) has the rest.

---

## 📏 Evals (`evals/`, Python)

A typed Python package (uv, pydantic, httpx, pytest, mypy strict, ruff) that tests the service as a
black box over HTTP. It serves frozen copies of the Notion Trending and Blog tables from a local
stub, so an eval needs no Notion token and does not move when the tables do. `/sync` replaces every
row of each source, so rows from an earlier live sync do not leak into an eval.

```bash
cd evals
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

# LLM eval (paid, ~$3 per full run): /ask on every golden item, code checks (no empty answer, no
# repo named or cited outside the retrieved rows), citation precision
# against the golden labels, then DeepEval metrics judged by claude-sonnet-5-5. The service and
# this command both need ANTHROPIC_API_KEY.
uv run radar-evals-llm --golden golden_v1.jsonl --fixture fixtures/trending.json \
  --fixture fixtures/blog.json --out results/llm-latest.json

# Re-capture a fixture (reads Notion; keeps only the columns the service parses). Re-capturing
# trending moves golden_v1's baseline.
NOTION_TOKEN=ntn_... uv run freeze-notion --source blog
```

CI runs the unit tests in `evals` and the live-service tests in `eval-retrieval`, against a
pgvector service container on the same tag as `docker-compose.yml`. The LLM eval runs in
`eval-llm.yml` only when the repo owner adds the `eval:llm` label to a PR or dispatches it; it reads
the key from the `ANTHROPIC_API_KEY` repository secret, and with the `LANGFUSE_PUBLIC_KEY` /
`LANGFUSE_SECRET_KEY` secrets each run is also a Langfuse experiment. It fails on any `/ask` error, empty answer,
repo named or cited outside the retrieved rows, judge call that raised (the run still scores every
other item and writes its results), and when a metric's mean is below 0.7.

---

## 🔭 Tracing (Langfuse)

With `LANGFUSE_PUBLIC_KEY` and `LANGFUSE_SECRET_KEY` set (and `LANGFUSE_BASE_URL` for a region other
than Japan), the service sends every request's spans to Langfuse over OTLP: the HTTP request, the
vector search, the chat client and the Claude call, which Langfuse shows as a generation with its
prompt, answer, token usage and cost. Without both keys nothing leaves the process.

When the same keys are set for `radar-evals-llm`, the run mirrors the golden set to the Langfuse
dataset `golden_v1`, runs it as an experiment, passes `traceparent` to `/ask` so the service's spans
nest under each item, and attaches the judge's scores and citation precision to each item. Such a
run needs the full golden set: a subset run against the fuller dataset is refused.

---

## 📊 Results

All numbers come from the CI runner: hit@5, recall@5 and MRR at the (url, week) level, except the
per-repo paragraph, which counts per url as the gate now does. Until the
golden_v2 paragraph they use golden_v1 (62 items, trending only), on its 42 answerable items unless
the column says otherwise.

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

**golden_v2, trending and blog together** (33 items: 12 zh-TW questions with literal English twins,
5 no-answer, 4 adversarial; every source queried): answerable hit@5 0.583, R@5 0.285, MRR 0.439,
against golden_v1's 0.786 on trending alone. Its questions are broader than golden_v1's ("are there
chips made for AI now?"), and for 10 of the 12 the post the question was written from was outside the
top 30; improving that ranking is the next step, and this gate measures it.

**One row per repo** (golden_v1, answerable): in 44 of 62 items the top 5 repeated a repo, one row
per week it charted. `/search` and `/ask` now return each url once, at its best-scoring week, and the
gate counts hits per url. hit@5 0.786 → 0.857, R@5 0.404 → 0.579, MRR 0.633 → 0.669, zh-TW hit@5
0.762 → 0.810, English 0.810 → 0.905: the slots a repeat held now go to other repos. golden_v2 stays at
0.583; blog posts never repeat.

**Sources in turns** (what `/ask` reads): the gate above queries golden_v1 on trending alone, but `/ask`
searches every source, and blog, with 3× trending's rows and broader text, took 60-67% of the five
slots. Over every source golden_v1's answerable hit@5 was 0.619, not 0.857. Each source is now searched
on its own and the rows taken in turns: golden_v1 over every source 0.619 → 0.738 (none lost), golden_v2
0.583 → 0.583 (one hit swapped), golden_v2 R@5 0.288 → 0.245, since questions labelled with several
posts get fewer blog slots. Chosen by a rule fixed before the runs, over two fixed quotas and a two-per-
source floor.

**Keywords for broad questions** (golden_v2, every source): the embedding reads 128 tokens of a blog
row, and 368 of 645 are longer; the mean blurs the one word a broad question turns on, so "are there
chips made for AI now?" put the chip post 31st among blog rows. Blog rows now gain up to 0.2 similarity
by their BM25 score against the question, and the keyword match's best rows join the candidates.
Measured locally against the committed baselines: golden_v2 answerable hit@5 0.583 → 0.750, R@5 0.245 →
0.358, MRR 0.439 → 0.507; golden_v1 unchanged in both source modes; one adversarial item lost (v2a1, its
post now behind two others naming the same model). The weight was chosen on golden_v2, so the gain is
optimistic ([M18](docs/stack-plan.md)).

**Repo questions lead with trending** (golden_v1, every source): most of its misses ask for a 套件,
and blog's most similar row led the turns and took 3 of the 5 slots, though only trending holds repos.
A question naming 套件, 函式庫, repo, package or library now takes trending first; 工具 / tools does
not, since golden_v1's topic questions say it too. Answerable hit@5 0.738 → 0.762, MRR 0.478 → 0.536,
no item lower; golden_v2 and golden_v3 unchanged. The word list was read off golden_v1, so the gain is
optimistic ([M19](docs/stack-plan.md)).

**Named repos come first** (reported on the live `/ask`): "「mattpocock/skills」是做什麼的？" answered that
the radar lacks the repo, though it was on the chart. The embedding blurs a repo name inside a question
and ranked it 22nd among trending repos; a repo the question names as owner/name is now looked up by
name and placed first. No golden question names one, so all three gated result sets are unchanged item
for item ([M20](docs/stack-plan.md)).

**Answer quality** (`/ask` on `claude-opus-5-5` with native citations, judged by
`claude-sonnet-5-5` through DeepEval; two runs on one commit, all 62 golden_v1 items, measured before
the blog ingest): no errors, empty answers, or repos named or cited outside the retrieved rows.

| metric | items | run 1 | run 2 |
|---|---:|---:|---:|
| Faithfulness | 42 answerable | 1.000 | 0.998 |
| AnswerRelevancy | 42 answerable | 0.904 | 0.915 |
| Attribution (GEval) | 42 answerable | 0.943 | 0.945 |
| Abstention (GEval) | 10 no-answer + 10 adversarial | 0.870 | 0.860 |
| Citation precision | items with labels and citations | 0.640 | 0.637 |

The judge agrees with a blind human grade on 11 of 12 items, in each of two hand-graded sets. The
means move by at most 0.011 between runs and single items by up to 0.27, so changes are compared on
means. A run costs about $3.3.

**After blog ingest, per-repo search and balanced sources**, with the prompt now asking for synthesis
rather than restatement (CI run 38072910360): Faithfulness 0.995, AnswerRelevancy 0.922, Attribution
0.890, Abstention 0.860, citation precision 0.385. Answers had restated their sources: 30.3% of answer
text was a run of 20+ characters copied from a retrieved row, now 20.5%, at about $4 a run.

---

## 🧠 Notes / decisions

- **Embeddings are local & keyless.** In-process ONNX (`paraphrase-multilingual-MiniLM-L12-v2`,
  384-dim, maxLength 128) through `OnnxEmbeddingModel`: ONNX Runtime plus the DJL tokenizer, with
  the mean pooling in Java, summed in PyTorch's order so the vectors stay bit-identical to Spring AI's
  transformers module (which needs PyTorch for that pooling alone). Chosen by the A/B under Results. After switching to another 384-d model, `POST /sync` re-embeds every row; a model with
  other dimensions also needs `spring.ai.vectorstore.pgvector.dimensions` changed and the
  `vector_store` table recreated (the embedding column is a fixed-width `vector(N)`).
- **Exact vector search.** `index-type: NONE`: at a few hundred rows an HNSW index buys no speed,
  and it applies metadata filters after its approximate scan, so a week-filtered `/search` could
  return fewer than `topK` rows. PgVectorStore never drops an existing index and creates the
  table only at startup, so a table created under the old HNSW setting needs, once: stop the
  service, `docker compose exec postgres psql -U radar -d radar -c 'DROP TABLE vector_store'`,
  start it again, then `POST /sync`.
- **Full-refresh re-sync.** `POST /sync` deletes a source's rows and adds its table's current rows
  in one transaction per source, so a row deleted in Notion does not linger and a failed fetch or
  embed leaves that source's old rows in place. Ids are Notion page ids: trending has one row per
  repo per week, blog one per post. A blog post's week is its publication date, or its archive date
  when it has none.
- **RAG is grounded, not filtered.** `/ask` retrieves exactly what `/search` with `topK` 5 and no
  `source` returns, one row per url, each source searched on its own and taken in turns, sends each row as an Anthropic citation document titled "repo week", with the
  repo's chart run (weeks charted, first and last) appended for the model only, and returns only
  the rows Claude cites. It takes only a question — no
  metadata-filter fields — so it has no SQL-filter input surface (unlike `/search`, which validates its filter values). The retriever keeps every
  candidate (similarity threshold 0) and bounds the context by `topK`.

---

## ⚠️ Known limitations

- **Without the prod profile `/sync` is open and Swagger UI is on**, which is fine on loopback, the
  default bind.
- **A person can still spend on `/ask`.** Turnstile stops scripts, not someone asking by hand; the
  per-client limit and the Anthropic workspace's monthly spend cap bound what that costs.
- **Two sources.** Trending and Blog. The Loot ledgers stay out: the UI shows them only after a
  login, and this service and repository are public.
- **Broad questions still miss some posts.** Keyword ranking lifted golden_v2's answerable hit@5 to
  0.750, but its weight and stop words were chosen on golden_v2, and a question still misses when its
  post never names the word it turns on (問卷 against a post about simulating people) or is described
  only in Chinese while the question is English.
- **Small golden sets.** golden_v1 has 62 items and golden_v2 33. The owner wrote the zh-TW
  questions (the English twins are literal translations) and confirmed every label from candidate
  lists. The retrieval gates catch any flipped hit, but the LLM metrics move by up to ~0.03 between
  identical runs, so only a drop beyond that reads as a regression.
- **Exact vector search.** Right for a few hundred rows; it scans every row, so a much larger archive
  needs an index and a filter strategy first.
- **amd64 image, 2 GiB.** The image targets Cloud Run's architecture, and ONNX Runtime keeps ~1.1 GB
  of the model in native memory.

---

## ⚖️ License

[MIT](LICENSE).

Unofficial personal project. Not affiliated with, endorsed by, or sponsored by
GitHub, Notion, Anthropic, Google Cloud or Cloudflare.
