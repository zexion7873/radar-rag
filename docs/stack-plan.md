# radar-rag target tech stack

Written 2026-09-25. Versions, prices and free-tier limits are as checked on that date; re-check one
before acting on it.

## How this plan was checked

Three independent research passes produced the layer recommendations: **retrieval** (embedding,
vector store, generation), **eval-obs** (harness, metrics, CI gate, tracing) and **platform**
(runtime, tests, packaging, hosting, public-demo safety). Where they disagreed, the resolution is in
the conflicts table under section 2. A separate adversarial review then re-checked 21 load-bearing
claims against live sources (15 upheld, 4 weakened, 2 refuted); its corrections are applied in the
text below.

The Spring AI 1.0.0 behaviour the plan relies on was re-read from the 1.0.0 jars:
- the bundled all-MiniLM-L6-v2 vocab holds 244 single CJK characters (是 / 模 / 型 / 開 / 發 are
  missing) and truncates and pads at 128 tokens. Tokenizing the 190-row Trending dump with it turns
  8,310 of 10,913 CJK characters (76%) into `[UNK]` (the retrieval pass's measurement);
- `TransformersEmbeddingModel` loads the model through `Resource.getContentAsByteArray()`, one on-heap
  array;
- passages are embedded from `Document.getText()`: the `EmbeddingModel` batch default maps
  `Document::getText`, and `TransformersEmbeddingModel` does not override it;
- retry defaults: 10 attempts, 2 s x 5 backoff capped at 180 s, `onClientErrors` false.

## Fixed constraints

Decided before this plan; they are its inputs, not its outputs. A flip-when names the evidence that
would reopen one.

| # | Constraint | Flip-when |
|---|---|---|
| D1 | The service stays Java (Spring Boot + Spring AI). The eval harness is a separate typed Python package (uv + pytest) that tests the service as a black box over HTTP. `/search` with `topK: 5` is exactly `/ask`'s retrieval, so retrieval metrics need no LLM. | The target roles turn out to require a Python service; the Boot 4.1 upgrade is stuck for more than 2 days; `/ask` on Opus 5.5 returns 4xx or an empty answer; Langfuse spans stay null on Boot 4.1 even with `ChatModelCompletionContentObservationFilter`; github-radar-ui drops its pure-reader contract. |
| D2 | Evals first, on the current Spring AI 1.0.0. Then the upgrade to Spring AI 2.0.1 / Boot 4.1, with the eval gate as the regression net. | — |
| D3 | `/ask` runs on `claude-opus-5-5`. | — |
| D4 | DeepEval is the eval library (RAGAS has had no release since 2026-01). The LLM judge is pinned to `claude-sonnet-5`: a different model from the Opus 5.5 generator, at half its price ($2/$10 vs $4/$20 per MTok). DeepEval 4.2.6 lists Sonnet 5 at a stale $3/$15, so the harness overrides the per-token costs. | — |
| D5 | The CI fixture is the frozen raw Notion rows (pre-embedding JSON), never a pgvector dump, which would lock the embedding model. Relevance labels are at (url, week). | — |
| D6 | The three radar repos stay separate. github-radar-ui is a pure Notion reader: it calls nothing but Notion. | — |
| D7 | There is no OpenAI key, and Anthropic has no embeddings API, so embeddings run locally or come from a third-party provider. | — |

## Known bugs (2026-09-25)

1. The document id is `nameUUIDFromBytes(url)`, but the Trending table has one row per repo per week,
   so a repo's weeks overwrite each other (190 rows collapse to 111).
2. Spring AI 1.x seeds a default temperature of 0.8, and Sonnet 5 / Opus 5.5 reject any sampling
   parameter with a 400. Deleting `temperature` from the YAML does not remove it.
3. `/ask` reads only the first `Generation`. Thinking blocks arrive as their own Generations, so a
   thinking model yields an empty answer with HTTP 200.
4. `max-tokens: 1024` counts thinking tokens too.

## 1. Verdict

Keep the Java 21 / Spring AI / pgvector service. Change it in this order: build the typed Python eval harness and its CI gates, fix /ask on Spring AI 1.0.0 and make one live Opus 5.5 call, swap the English-only embedding for a multilingual in-process model that the harness picks, and only then upgrade to Spring Boot 4.1.1 / Spring AI 2.0.1, with the gates as the safety net. After the upgrade come Langfuse tracing, a Cloud Run + Neon deploy, and an "Ask the radar" page that radar-rag serves itself. Nothing is added for scale: no reranker, hybrid search, HNSW index, Kubernetes or IaC. The biggest unknown is whether Spring AI 1.0.0 can call Opus 5.5 at all, so M1 tests that right after the retrieval harness: if it fails, a D1 flip-when fires.

## 2. Target stack

| Layer | Before M0 | Target | Runner-up (why it lost) | Phase | Skill area | Source |
|---|---|---|---|---|---|---|
| JDK | Java 21 | Stay on Java 21 through the upgrade. Move to Java 25 LTS as its own step after the upgrade passes the gates, before deploy. Java 25 is required for the Spring Boot AOT cache, the cheapest cold-start fix. | Bump Java inside the upgrade PR. Lost because one gate run would test two changes, and Java 21 is LTS until 2029-12-31. | deploy prep (M9) | cloud | [Temurin EOL](https://endoflife.date/eclipse-temurin), [AOT cache](https://docs.spring.io/spring-boot/how-to/aot-cache.html) |
| Framework | Boot 3.4.1 + Spring AI 1.0.0. Boot 3.4 open-source support ended 2025-12-31. | Boot 4.1.1 + Spring AI 2.0.1, the latest GA releases. Skip the milestones 2.1.0-M1 and 4.2.0-M2. Never deploy publicly on 1.0.0, because Boot 3.4 open-source support ended 2025-12-31. CVE-2026-47852 and CVE-2026-59294 also hit the transformers module, and only 2.0.1 fixes them on the open-source line. Neither is reachable here: one needs a local attacker, the other attacker-controlled model URIs, and `file:` URIs skip the cache path both exploit. | Stay on 1.x. Lost because the 1.0.10 and 1.1.9 fixes are Enterprise-only. | upgrade (M6), after the P3 gates (D2) | enables D2 | [Spring AI BOM](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml), [Boot parent](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml), [Boot EOL](https://endoflife.date/spring-boot), [CVE-2026-47852](https://spring.io/security/cve-2026-47852/), [CVE-2026-59294](https://spring.io/security/cve-2026-59294/), [2.0.1 release](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/), [#6465](https://github.com/spring-projects/spring-ai/issues/6465) |
| Embedding model | all-MiniLM-L6-v2. Its WordPiece vocab turns 8,310 of 10,913 CJK characters (76%) into [UNK]. | A 384-d multilingual in-process ONNX model, chosen by a three-arm A/B: control, paraphrase-multilingual-MiniLM-L12-v2, multilingual-e5-small. Expected winner: mE5-small (MIT, mean pooling, 512 tokens), using the fp32 `model.onnx` from a pinned HF revision, checked by sha256 and loaded through a `file:` URI. Its `query: ` / `passage: ` prefixes live in a ~20-line `@Primary` EmbeddingModel decorator. It overrides both `embed(String)`, the query path, and `embed(List<Document>, EmbeddingOptions, BatchingStrategy)`, the passage path, which embeds `Document.getText()` on 1.0.0. Dimensions and the table schema stay the same. | paraphrase-multilingual-MiniLM-L12-v2. Lost because it is a symmetric paraphrase model with max_seq_length 128 and no retrieval-benchmark results. It stays in the A/B as the config-only fallback. | P3 (M4) | evals, RAG | local tokenizer measurement; [e5 files](https://huggingface.co/api/models/intfloat/multilingual-e5-small/tree/main/onnx), [e5 pooling](https://huggingface.co/intfloat/multilingual-e5-small/raw/main/1_Pooling/config.json), [e5 prefixes](https://huggingface.co/intfloat/multilingual-e5-small), [MIRACL](https://arxiv.org/html/2402.05672), [paraphrase cfg](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/raw/main/sentence_bert_config.json), [ONNX props](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html) |
| Vector store | `pgvector/pgvector:pg16`, a floating tag that moved from 0.8.0 to 0.8.6. HNSW, cosine, 384-d. | Pin `pgvector/pgvector:0.8.6-pg16` and stay on Postgres 16, supported to 2028-11-09. Set `index-type: NONE` for exact search with perfect recall. With HNSW, pgvector filters after the index scan, so week-filtered queries can return fewer than topK rows. Exact search also makes Neon's pgvector 0.8.0 give the same results as 0.8.6 in CI. Version tags get re-pushed when the base image updates, so pin by digest if the gate must be byte-reproducible. | Keep HNSW and add `hnsw.iterative_scan=relaxed_order`. Lost because it keeps approximate recall inside the metric being measured and is no faster at 190 rows. | pre-P3 (M0) | RAG | [pgvector README](https://github.com/pgvector/pgvector/blob/master/README.md), [tags](https://hub.docker.com/v2/repositories/pgvector/pgvector/tags?page_size=40&name=pg16), [Spring AI pgvector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html), [PG versioning](https://www.postgresql.org/support/versioning/) |
| Keyword / hybrid (CJK) | none | None. The CJK gap comes from the tokenizer, not from missing keyword search. Only if golden-set queries for exact repo names fail: first try ILIKE or pg_trgm on the ASCII repo names, merged by RRF. | pg_bigm. Lost because it needs a custom image and solves what the multilingual embedding already fixes. | P3, only if triggered | — | [pg_bigm](https://github.com/pgbigm/pg_bigm/blob/REL1_0_STABLE/docs/pg_bigm_en.md) |
| Reranking | none | None. Add one only if the harness shows high recall@20 but low nDCG@5 / P@5. | bge-reranker-v2-m3 via ONNX. Lost because Spring AI has no ONNX cross-encoder (unverified), and a reranker adds little for 5 candidates. | P3, only if triggered | — | local measurement (rows: median 86 tokens, max 160) |
| Document id and sync | `nameUUIDFromBytes(url)`, so 190 rows collapse to 111. Upsert only, so rows deleted in Notion stay as ghosts. | Use the Notion page id as the id. Keep url and week in metadata. /sync becomes a full refresh per source: delete by filter, then add, both inside one `@Transactional`. Keep one row per repo per week. P5 adds `source` = `blog` / `loot-<target>` to the same table and embeds only short fields. | `nameUUIDFromBytes(source+url+week)`. Lost because it has to encode each table's uniqueness rule and it would quietly merge duplicate Repo+Week rows in the Notion table, which must surface as a count mismatch instead. | P3 (M3, after the first baseline) | evals, RAG | local dump measurement; javap of `PgVectorStore` 1.0.0 (`ON CONFLICT (id) DO UPDATE`, `doDelete(Filter.Expression)`) |
| /ask generation | Sonnet 5, max-tokens 1024, temperature 0.2 plus a seeded 0.8 default that gets a 400. Reads only `getResult()`. Never run live. | Model `claude-opus-5-5` (D3). Own `AnthropicChatModel` bean with no temperature. The answer joins every Generation whose metadata has neither `signature` nor `data`. `max-tokens: 16000`. Thinking omitted, which means adaptive on Opus 5.5. On 2.0.1, set `.effort(MEDIUM)` explicitly. | Call the raw anthropic-java SDK. Lost because it drops ChatClient, the advisors and the Micrometer observations that P4 depends on. | pre-P3 (M1) | evals, RAG | [effort](https://platform.claude.com/docs/en/build-with-claude/effort), [Spring AI Anthropic](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html), [pricing](https://platform.claude.com/docs/en/about-claude/pricing); 1.0.0 facts from javap |
| Citations | Every retrieved row is returned as a citation. | P3: unchanged, because the rows serve as retrieval_context for faithfulness. After the upgrade: send each row as an `AnthropicCitationDocument` and return only the rows Claude actually cites, plus `cited_text`, which is not billed as output. Never combine this with `output_config.format` (400). | `search_result` blocks. Lost because Spring AI 2.0.1 does not expose them, so they would need the raw SDK. | upgrade (M7) | evals, RAG | [citations](https://platform.claude.com/docs/en/build-with-claude/citations), [search results](https://platform.claude.com/docs/en/build-with-claude/search-results), [Spring AI Anthropic](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html) |
| Python harness packaging (D1) | none | An `evals/` uv project inside radar-rag: Python 3.13, committed `uv.lock`. Dependencies: `deepeval>=4.2.6,<5`, `anthropic`, `httpx>=0.28,<1`, `pydantic>=2.11,<3`. Dev: pytest, mypy `strict` with `pydantic.mypy`, ruff. Pydantic models at the HTTP responses and the durable files. Markers `service` (needs a running service) and `llm`, with default addopts `-m 'not service and not llm'`. HTTP tests use `httpx.MockTransport`. No CLI framework. | pyright. Lost because its PyPI wrapper downloads Node, and mypy is the checker postings name. ty is deferred (still Beta). | P3 (M2) | Python, evals | [deepeval PyPI](https://pypi.org/pypi/deepeval/json), [pyright](https://pypi.org/project/pyright/), [pydantic mypy](https://pydantic.dev/docs/validation/latest/integrations/dev-tools/mypy/), [ty](https://pypi.org/project/ty/), [httpx](https://pypi.org/project/httpx/) |
| Eval metrics | none | Retrieval: hit@5, P@5, R@5, MRR and nDCG@5 (graded 0/1/2), in ~40 lines of own code, each computed at (url, week) and url-deduped. Always call `/search` with `topK: 5`, because it defaults to 10. Generation: DeepEval `FaithfulnessMetric(penalize_ambiguous_claims=True)` and `AnswerRelevancyMetric` on answerable items, plus two GEval metrics with fixed `evaluation_steps` (Attribution, Abstention). Two code checks: a non-empty answer, and zero uncited repo mentions. | ranx plus all five DeepEval RAG metrics. Lost because of heavy dependencies and no release since 2025-08, and Contextual* need a reference answer per item and re-measure the labels with a noisy judge. | P3 (M2, M5) | evals | [deepeval PyPI](https://pypi.org/pypi/deepeval/json), [GEval](https://deepeval.com/docs/metrics-llm-evals), [Faithfulness](https://deepeval.com/docs/metrics-faithfulness), [ranx](https://pypi.org/pypi/ranx/json), [SearchRequest](https://github.com/spring-projects/spring-ai/blob/v1.0.0/spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/SearchRequest.java) |
| LLM judge (D4) | — | `AnthropicModel('claude-sonnet-5', generation_kwargs={'max_tokens': 4096}, cost_per_input_token=2e-6, cost_per_output_token=1e-5)`. max_tokens is set because DeepEval defaults to 1024. Costs are set because DeepEval's registry lists Sonnet 5 at a stale $3/$15. `DEEPEVAL_TELEMETRY_OPT_OUT=1`. | claude-opus-5. Lost because at $5/$25 it costs more than the Opus 5.5 generator. | P3 (M5) | evals | [pricing](https://platform.claude.com/docs/en/about-claude/pricing), [AnthropicModel](https://github.com/confident-ai/deepeval/blob/python-v4.2.6/deepeval/models/llms/anthropic_model.py), [registry](https://github.com/confident-ai/deepeval/blob/python-v4.2.6/deepeval/models/llms/constants.py), [telemetry](https://github.com/confident-ai/deepeval/blob/python-v4.2.6/deepeval/telemetry/__init__.py) |
| Golden set | none | `golden_v1.jsonl`, 62 items: 42 answerable (21 zh-TW/English pairs that share labels), 10 no-answer and 10 adversarial. The zh-TW questions are hand-written; the English twins are literal translations of them (Decision 2). An adversarial item is labelled with the row that corrects its false premise, and left unlabelled when the premise names something the fixture lacks. Labels are `(url, week, grade 1 or 2)`. A label-rot test checks every label exists in the fixture. Each result records the golden file and its sha256, the fixture sha256, the model id and the git sha. | ~200 goldens from DeepEval's Synthesizer. Lost because synthetic questions copy their source row's wording and hide the zh-TW/English gap. | P3 (labelling can start now) | evals | unverified (sizing is a design judgement) |
| Notion fixture (D5) | NotionClient hard-codes `https://api.notion.com/v1`. | `uv run freeze-notion` (`radar_evals.freeze_notion`) captures the raw Notion page JSON, stripped at capture to the page id plus the properties the parser reads, so the file is safe to publish. It is served by a ~30-line stdlib `http.server` stub that the harness owns, paginated at 100 rows per page. A new `radar.notion.base-url` property points the service at it. | A WireMock container (platform lens). Overruled; see C1. | P3 (M0 property, M2 stub) | evals | local `NotionClient.java`, `application.yml` |
| Java tests | none | Four classes, written to need only mechanical changes at Boot 4: `@MockitoBean`, explicit `@AutoConfigureMockMvc`, no TestRestTemplate. `@WebMvcTest` still moves package and starter (see M6). (a) TrendingIngestServiceTest. (b) NotionClient pagination against WireMock. (c) AskFlowIT: Testcontainers pgvector, WireMock standing in for Anthropic, and a mocked EmbeddingModel. (d) SearchController `@WebMvcTest`. Tools: WireMock standalone 3.13.2, and Testcontainers overridden to 1.21.4 until the upgrade. | MockRestServiceServer. Lost because 2.0.1 moves Anthropic calls onto the SDK's own HTTP client, so this mock would stop intercepting exactly at the upgrade it is meant to guard. | pre-P3 / P3 (M0, M1, M3) | regression net for D2 | [WireMock](https://repo1.maven.org/maven2/org/wiremock/wiremock-standalone/maven-metadata.xml), [TC #11210](https://github.com/testcontainers/testcontainers-java/issues/11210), [Boot 4.1.1 BOM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom), [Boot 4 migration](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) |
| CI | Only the two Claude review workflows. | `ci.yml` job `build`: setup-java@v6, Temurin 21, `mvn -B verify`. Job `evals`: `uv sync --locked`, ruff, mypy and the unit tests. Job `eval-retrieval`: a `services.postgres` container, the jar, the cached model and `~/.djl.ai`, then the stub-backed live-service tests and, once golden_v1 exists, the `radar-evals` gate with its step summary. Neither needs a secret, so both run on Dependabot PRs. Separately, `eval-llm.yml` runs on the `eval:llm` label or `workflow_dispatch`. It reads the Anthropic key from a repository secret behind an `if: github.actor == github.repository_owner` guard, and never uses `pull_request_target`. A GitHub Environment is not an option while the repo is private, because on GitHub Free environment secrets exist only in public repos. `.github/dependabot.yml` arrives in M0. | LLM runs on push to main (platform lens) and nightly LLM runs. Lost; see C3. | pre-P3 build (M0); P3 evals (M2, M5) | evals | [Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions), [setup-java](https://github.com/actions/setup-java/releases), [service containers](https://docs.github.com/en/actions/tutorials/use-containerized-services/create-postgresql-service-containers), [DJL cache](https://docs.djl.ai/master/docs/development/cache_management.html) |
| Result tracking | none | P3: a committed `evals/results/baseline.json`. It changes only when the author of a PR runs `--write-baseline` in that same PR. The step summary lists which queries went from hit to miss. P4: the golden set is mirrored to a Langfuse dataset, and LLM runs go through `dataset.run_experiment`. | Langfuse for both gates, or Confident AI. Lost because Langfuse on every PR would put keys into PR CI and spend units on runs with no LLM, and Confident AI would be a second vendor. | P3 (M2) + P4 (M8) | evals, observability | [experiments](https://langfuse.com/docs/evaluation/experiments/experiments-via-sdk), [datasets](https://langfuse.com/docs/evaluation/experiments/datasets), [langfuse PyPI](https://pypi.org/pypi/langfuse/json) |
| Observability (P4) | None: no actuator, no tracing. | Langfuse Cloud Hobby (50k units per month, 30 days of data), JP region (`jp.cloud.langfuse.com`). On Boot 4.1, `spring-boot-starter-opentelemetry` sends OTLP over HTTP to `/api/public/otel/v1/traces`, with Basic auth and `x-langfuse-ingestion-version: 4`, sampling 1.0. Prompt and completion text needs your own ~30-line `ChatModelCompletionContentObservationFilter` that loops over all generations. The harness injects `traceparent`, so the Java spans nest under each eval item. Keep `http.server.requests` enabled. | Self-hosted Langfuse (stack sized at 4 cores / 16 GiB), Phoenix (does not map gen_ai.*), OpenLIT (self-hosted ClickHouse), Arconia (hides the 30 lines you must be able to explain). | P4 (M8, after the upgrade) | observability | [Langfuse pricing](https://langfuse.com/pricing), [Langfuse OTel](https://langfuse.com/integrations/native/opentelemetry), [Spring AI guide](https://langfuse.com/integrations/frameworks/spring-ai), [Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html), [self-host](https://langfuse.com/self-hosting/deployment/docker-compose), [Phoenix #10622](https://github.com/Arize-ai/phoenix/issues/10622) |
| Packaging | No Dockerfile. The model downloads at first boot from the unpinned `main` branch; DJL separately fetches ~173 MB of libtorch. | A multi-stage Dockerfile with a layered jar on `eclipse-temurin:25-jre` (glibc, not Alpine). The M4 model is baked in with the same fetch script and a sha256 check, referenced by `file:` URIs, which also skips the ResourceCacheService CVE surface. libtorch is pre-populated and `-Dai.djl.offline=true` is set. An AOT-cache training run uses a profile with no DB contact. Set `-XX:MaxRAMPercentage` to about 55. The transformers module loads the whole ~470 MB model as one on-heap `byte[]` before ONNX Runtime makes a native copy, so the JVM's default 25% heap on a 2 GiB container would OOM at boot. | Buildpacks (the training run contacts the DB; Paketo #581) and Jib (no training run). | deploy prep (M9) | cloud | [ONNX docs](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html), [DJL PyTorch](https://docs.djl.ai/master/engines/pytorch/pytorch-engine/index.html), [DJL offline](https://docs.djl.ai/master/docs/demos/development/fatjar/index.html), [transformers 2.0.1 POM](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-transformers/2.0.1/spring-ai-transformers-2.0.1.pom), [Cloud Run FS](https://docs.cloud.google.com/run/docs/container-contract), [Paketo #581](https://github.com/paketo-buildpacks/spring-boot/issues/581), [Jib #4417](https://github.com/GoogleContainerTools/jib/issues/4417) |
| Hosting | local only | Cloud Run in asia-east1 (Taiwan): request billing, min 0 / max 1 instances, CPU boost, memory set from M9's measurement. Neon Free in aws-ap-southeast-1 (Singapore), which has pgvector 0.8.0, hstore and uuid-ossp. Deploy through google-github-actions/auth v3 with Workload Identity Federation. The weekly /sync runs from an Actions cron. At 2 GiB, the 360,000 GiB-second free grant is about 50 instance-hours a month, and asia-east1 to Neon Singapore egress is billed (cents). Whether the free tier covers asia-east1 is unverified. | Azure Container Apps + Neon. It has the same free grant and lost only on tie-breakers. It flips if a count of Taiwan job postings favours Azure. Other hosts are in section 4. | deploy (M10), never on 1.0.0 | cloud | [Cloud Run free tier](https://docs.cloud.google.com/free/docs/free-cloud-features), [autoscaling](https://docs.cloud.google.com/run/docs/about-instance-autoscaling), [CPU boost](https://docs.cloud.google.com/run/docs/configuring/services/cpu), [Neon plans](https://neon.com/docs/introduction/plans), [Neon extensions](https://neon.com/docs/extensions/pg-extensions), [Neon regions](https://neon.com/docs/introduction/regions), [ACA billing](https://learn.microsoft.com/en-us/azure/container-apps/billing), [WIF auth](https://github.com/google-github-actions/auth) |
| Public-demo safety | /sync is an open POST, `include-message: always`, DEBUG logging, no spend caps. | A dedicated Console workspace with a monthly spend limit. An in-app daily dollar cap is deferred; see the do-not-add list. Bucket4j 8.20.0 rate-limits per IP, keyed on the right-most X-Forwarded-For entry. Cloudflare Turnstile verified server-side. A prod profile: no error messages, generic upstream errors, INFO logs, POSTGRES_PASSWORD required. /sync needs a shared secret with a constant-time compare. Answers are rendered as text. No CORS config. | Cloud Armor or API Gateway. Lost because they need an external load balancer, which is over the budget. | deploy (M10) | observability | [workspaces](https://platform.claude.com/docs/en/manage-claude/workspaces), [Bucket4j](https://repo1.maven.org/maven2/com/bucket4j/bucket4j_jdk17-core/maven-metadata.xml), [Turnstile](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/) |
| P5 "Ask the radar" (D6) | The README says "in the UI", which breaks D6. | radar-rag serves `static/index.html`: vanilla JS, Turnstile, citations as links, and 3-5 example answers cached server-side. github-radar-ui adds only an outbound link, which it already does for repos and blogs. Change the README's P5 line to "served by radar-rag; the UI links out". | Pre-computed answers written back to Notion. Lost because they are not interactive and add a new writer to guard. A separate frontend or the UI calling radar-rag lost too; the latter breaks D6. | P5 (M11) | RAG | local README, github-radar-ui's `Dashboard.tsx` / `BlogList.tsx` |
| Visibility and README | Private. No diagram, demo link or eval results. | Before the flip: a gitleaks scan of the full history, and keep claude.yml's write-access gate (never add `allowed_non_write_users`). README: a Mermaid diagram, an eval table produced by CI, the workflow status badge, and a known-limitations note. Timing: Decision 3. | Flip now. Lost because the first public impression would be zero tests and an /ask that has never run. | P3 (after M4) | evals, Python, RAG | [secret scanning](https://docs.github.com/en/code-security/secret-scanning/introduction/about-secret-scanning), [claude-code-action security](https://github.com/anthropics/claude-code-action/blob/main/docs/security.md) |

### Conflicts between lenses and how they were resolved

| # | Topic | Lens positions | Resolution | Why |
|---|---|---|---|---|
| C1 | Notion stub for the Python gate | Platform: WireMock in compose, shared with the Java tests. Eval-obs: stdlib stub owned by the harness. | **Eval-obs for the Python gate; platform's WireMock kept for the Java tests only.** | Notion pagination is computed from the fixture. WireMock would need pre-split page files, a second copy of the fixture. The stub reads the one fixture file and runs under `uv run pytest` with no container. |
| C2 | What the eval job runs | Platform: the Docker image, so the image under test is the one that ships. Eval-obs: the jar. | **Jar in P3; switch to the image at M9.** Platform overruled on timing only. | No Dockerfile or deploy target exists until after the upgrade, and the M4 A/B needs a different model config per arm. Platform's principle applies once there is an image to ship. |
| C3 | When the LLM gate runs | Platform: on push to main and on dispatch. Eval-obs: on the `eval:llm` label and on dispatch. | **Eval-obs.** | A push-to-main run happens after the merge, too late to block it, and spends $4.5-8.5 (estimate) on every merge, docs included. |
| C4 | When to fix bug 1 | Retrieval: before P3. Eval-obs: let golden v1 fail on it first. | **Fix it in M3, right after the first baseline.** Retrieval overruled on timing; its page-id key scheme is kept. | The gate then records a measured before/after, a strong interview story. Nothing is deployed, so the delay costs nothing. |
| C5 | Pinning the model | Eval-obs: pin the default URI to the v1.0.0 tag. Platform: bake the model from a pinned HF revision with sha256. Retrieval: replace the model. | **One fetch script: pinned HF revision, sha256 check, `file:` URI**, used locally, in CI and in the image. | It covers all three concerns and skips ResourceCacheService, which is the CVE surface on 1.0.0. |
| C6 | Deploy sizing | Platform sized 1 GiB and two images inside the 0.5 GB free registry tier, based on the ~90 MB MiniLM. Retrieval's mE5-small is ~470 MB fp32. | **Expect 2 GiB and ~1 GB images; measure in M9.** | This follows from the model swap. Neither lens checked the combination. |
| C7 | Does Spring AI 2.0.1 send temperature by default? | Retrieval: default unset. Platform: the docs table lists 1.0. Both cite the same page. | **Unresolved on paper. AskFlowIT's request-body assertion settles it in M6.** Keep the M1 custom bean until it passes without it. | If 2.0.1 sends temperature, Opus 5.5 returns 400, and a D1 flip-when is at stake. |
| C8 | D4's premise | Eval-obs: DeepEval 4.2.6 now registers claude-opus-5-5. | **D4 stands (judge pinned to claude-sonnet-5); only its written rationale changes**: cost ($2/$10), and keeping the judge a different model from the generator. | D4 has no flip-when, so nothing fired. |

## 3. Milestones (execution order)

M2 runs before M1 (decided 2026-09-25); otherwise this is the order. D2 holds: M2-M5 (evals on 1.0.0) come before M6 (upgrade). Each bug is fixed where it first blocks something: bugs 2-4 block any live answer (M1), and bug 1 blocks the (url, week) labels once the gate exists (M3).

### M0: Housekeeping and a build job (pre-P3, ~1 day)
**Status: done.** The "Before M0" column above is the state this milestone started from.

Changes:
- Pin compose to `pgvector/pgvector:0.8.6-pg16`. Set `spring.ai.vectorstore.pgvector.index-type: NONE`. Drop `vector_store` once while the service is stopped, then start it: `CREATE INDEX IF NOT EXISTS` never removes the HNSW index, and the table is created only at startup.
- Add the `radar.notion.base-url` property (default `https://api.notion.com/v1`), and a NotionClient pagination test against WireMock 3.13.2 serving two pre-split pages.
- Add a SearchController `@WebMvcTest`: a quote or backslash in a filter returns 400. This guard stays through the upgrade.
- Add `ci.yml` job `build`: setup-java@v6, Temurin 21, `cache: maven`, `mvn -B verify`.
- Add `.github/dependabot.yml` for maven and github-actions. Until M6 it ignores every Spring AI update (the bug list and M1's fixes are pinned to 1.0.0 behaviour) and Spring Boot minor and major bumps. Without it, M2's Dependabot criterion can never be met.

Done when:
- `mvn -B verify` prints `BUILD SUCCESS` with `Failures: 0, Errors: 0`, and the `build` job is green on the PR.
- After `/sync`, `docker compose exec postgres psql -U radar -d radar -tAc "select indexname from pg_indexes where tablename='vector_store'"` prints only `vector_store_pkey`.

### M1: /ask works on 1.0.0, with a first live Opus 5.5 call (pre-P3, ~1 day)
Changes:
- You create a Console workspace `radar-eval` with a monthly spend limit (Decision 1) and put its key in the local `.env`. The Default Workspace cannot carry limits.
- Bug 2: your own `AnthropicChatModel` bean whose default options have no temperature. Delete `temperature: 0.2` from the YAML.
- Bug 3: build the answer by joining the Generations whose metadata has neither `signature` nor `data`.
- Bug 4: `max-tokens: 16000`. Also set `model: claude-opus-5-5`, with thinking omitted.
- Set `spring.ai.retry.max-attempts: 2`. The 1.0.0 default is 10 attempts with 2 s x 5 backoff capped at 3 min, so one 529 can hold /ask for about 20 minutes. Decide whether 429 joins `on-http-codes` (retried, maps to 503) or stays a client error (maps to 502).
- Change ApiErrorHandler now, not at M10. It returns `"llm upstream: " + e.getMessage()`, and 1.0.0 builds that message as `HTTP <code> - <upstream body>`, so the upstream body leaks today.
- Add `AskFlowIT`: `@SpringBootTest`, Testcontainers pgvector via `@ServiceConnection`, WireMock as Anthropic through `spring.ai.anthropic.base-url`, and `@MockitoBean EmbeddingModel` with fixed vectors. It asserts:
  - the request body has no `temperature` key;
  - a `[thinking, text]` response yields the text;
  - `max_tokens` is 16000;
  - upstream 400 maps to 502, 529 maps to 503 after at most 2 attempts, 429 maps per the decision above, and no body echoes the upstream text.
- Override `testcontainers.version` to 1.21.4, and add `docker-java.properties` `api.version=1.44` if Docker 29 refuses the connection.
- Add `org.testcontainers:postgresql` and `spring-boot-testcontainers` (test). All ITs share one pgvector container, pinned to `0.8.6-pg16`, so Spring's cached context survives across test classes (PR #4's pattern).
- Update the README: from here on `mvn -B verify` needs Docker. On macOS, Docker Desktop must expose its default socket (or set `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`).

Done when:
- `curl -s -o ask.json -w '%{http_code}\n' -X POST localhost:8080/ask -H 'content-type: application/json' -d '{"q":"最近有哪些 coding agent 相關的 repo？"}'` prints `200`.
- `jq -r .answer ask.json` prints non-empty text.
- `jq '.citations | length' ask.json` prints `5`.
- `mvn -B verify` is green with AskFlowIT.
- **Stop condition:** if the live call still returns 4xx or an empty answer after these fixes, D1's flip-when "Opus 5.5 /ask returns 4xx or empty" has fired. Re-plan M3 onward; M2's harness is black-box and carries over.

### M2: Harness, fixture, golden v1 and the retrieval gate (P3, ~3 days, plus labelling)
**Runs before M1** (decided 2026-09-25): the retrieval gate needs no LLM key, M1 waits on one, and a black-box harness survives whatever M1's stop condition triggers.

**Status: in progress.** Done: the `evals/` package (client, pydantic models, metrics, golden schema, Notion stub, `freeze-notion`, the `radar-evals` CLI with the flip gate), the frozen fixture (190 rows, 2026-09-25), `id` on `SearchHit` / `Citation`, CI jobs `evals` and `eval-retrieval` (added; the live-service job runs sync + search), and `golden_v1.jsonl` (62 items, 11 of the 21 zh-TW answerable cjk-only; the `evals` job checks its labels against the fixture). Open: the first `baseline.json`, and running the CLI gate in `eval-retrieval`.

Changes:
- Create the `evals/` uv project, `freeze-notion` (fixture stripped at capture), and the stdlib Notion stub. The stub reads chunked request bodies: the service's JDK HttpClient sends them without a Content-Length, and an unread body both loses the cursor and resets the connection.
- Write the metrics module and its unit tests with expected values worked out by hand. Example: relevant rows at ranks 2 and 4 out of 5, with 3 relevant rows in total, gives P@5 0.4, R@5 0.667, MRR 0.5 and nDCG@5 ≈ 0.498 (binary gains). The tests also cover one URL returned under two sources, which must not push recall above 1.0 (PR #6 hit this).
- Write `golden_v1.jsonl` (Decision 2) with pydantic validation, the label-rot test, and a stamp on every result.
- Each golden item carries `lang` (zh-TW / en); its script (cjk-only / mixed / latin) is derived from the question text, so it cannot drift from it. At least 10 of the zh-TW answerable items are cjk-only. On MiniLM (PR #4's CI run), mixed-script zh queries containing "agent", "AI" or "LLM" scored R@5 1.00 and pure-CJK ones 0.00, and 188 of the 190 archive descriptions contain Latin letters. Without cjk-only items, M4 has no room to show a difference.
- Add an `id` field to `SearchHit` and to `/ask`'s `Citation`. Neither exposes the document id today, so id checks would otherwise need direct Postgres access, which breaks D1's black box.
- The Notion stub must also answer `GET /data_sources/{id}`. `NotionClient` calls it first and silently falls back to `/databases/{id}` inside `catch (Exception ignored)`.
- Faithfulness needs the row text as retrieval_context, and `Citation` carries none. The harness makes a parallel `/search` call with the same `q` and `topK: 5`, relying on D1's equivalence.
- Add `uv` for `evals/` to `dependabot.yml`.
- Add `ci.yml` job `eval-retrieval`, with env `NOTION_TOKEN=dummy`, an empty Anthropic key, and `radar.notion.base-url` pointing at the stub.
- `eval-retrieval` fails when any golden item flips from hit to miss at hit@5 against `baseline.json`, unless the same PR rewrites the baseline. Exact search with a pinned model is deterministic, so a flip is real. Baselines are written on the x86 CI runner.
- Pin the control-arm model to the spring-ai v1.0.0 commit in `application.yml`: the default URIs follow spring-ai's `main` branch, and the jar's own `model.onnx` is only a Git LFS pointer. CI caches it and `~/.djl.ai` (DJL downloads the PyTorch native libraries at the first embed) with actions/cache@v6, keyed on `pom.xml` and `application.yml`. Give `ci.yml` a `concurrency` block that cancels in-progress PR runs.
- Commit the first `baseline.json` while the ids are still url-keyed. This is on purpose: it is the "before" number for M3.

Done when:
- `cd evals && uv run ruff check && uv run ruff format --check && uv run mypy && uv run pytest` prints `All checks passed!`, `... files already formatted`, `Success: no issues found ...`, and a green pytest run with the `llm` tests deselected (no tokens spent).
- `POST /sync` against the stub returns `{"ingested": <fixture row count>}`.
- `eval-retrieval` is green on a PR, the step summary shows the baseline-vs-PR table, and it is also green on the next Dependabot PR, which proves it needs no secrets.

### M3: Bug 1 fixed, with a measured before/after (P3, ~0.5 day)
Changes:
- `TrendingRow` gains `pageId`, and the Document id becomes the page id.
- /sync runs `delete(source == 'trending')` and then `add` inside one `@Transactional`. That PgVectorStore's JdbcTemplate joins the transaction is unverified; the tests below settle it.
- Add TrendingIngestServiceTest: two weeks of the same url produce two distinct ids. Extract the row-to-Document mapping into a static `toDocument(TrendingRow)` first (PR #4 did this cleanly), so the test needs no Spring context.
- Add two Java IT assertions (Testcontainers pgvector). They cannot live in the Python gate, because `/sync` returns rows ingested, not the table size:
  - the number of distinct ids equals the number of distinct (url, week) pairs in the fixture, which also catches Notion twins;
  - a re-sync from the fixture minus one row drops the count by exactly 1, so no ghost rows remain.
- Rewrite `baseline.json` in the same PR.

Done when:
- `docker compose exec postgres psql -U radar -d radar -tAc "select count(*), count(distinct metadata->>'url') from vector_store"` prints the fixture's row count and distinct-url count (`190|111` on the 2026-09-23 data). Before the fix it prints `111|111`.
- The PR's step summary shows recall@5 at the (url, week) level above the M2 baseline. Record the before/after pair for the README.

### M4: Multilingual embedding A/B (P3, ~1 day)
Changes:
- Smoke test first: boot the mE5-small arm, and a zh-TW `/search` must return 5 hits with distinct scores. This proves DJL can load the XLM-R-family `tokenizer.json`.
- Write the fetch script (pinned HF revision, sha256, `file:` URIs), and cache the ~470 MB model in CI.
- Create three config profiles, each with its own `spring.ai.vectorstore.pgvector.table-name`. The multilingual arms get tokenizer options padding, truncation and maxLength 512.
- Add the ~20-line `@Primary` prefix decorator (both embed paths, see the Embedding row), used by the mE5 arm only.

Done when:
- Three results JSON files share golden_sha256 and fixture_sha256 and differ only in model_id.
- At least 2 more zh-TW answerable queries flip from miss to hit (hit@5) versus control. Recall@5 is fractional per query with multi-row labels, so it cannot express "2 of 21". Every arm reports its English-minus-zh-TW gap, and results split by script; the flips are expected among the cjk-only items.
- mE5-small ships only if it beats paraphrase-multilingual by at least 2 zh-TW queries on hit@5. Otherwise ship paraphrase-multilingual and delete the decorator.
- The winner's baseline is committed, and the README's "English-centric embeddings" limitation is replaced by the A/B table.

**V (optional, Decision 3):** gitleaks over the full history reports 0 findings, then `gh repo view zexion7873/radar-rag --json visibility -q .visibility` prints `PUBLIC`.

### M5: LLM gate (P3, ~1 day, about $10-20 of runs)
Changes:
- Add `eval-llm.yml`: runs on the `eval:llm` label and `workflow_dispatch`, reads a repository secret behind an owner-only `if:` guard, and has a concurrency group that cancels in-progress runs.
- Wire up the DeepEval metrics and the judge from the stack table; the gate checks aggregates in our own code.
- Add the two code checks: non-empty answers, and zero uncited repo mentions.
- Write DeepEval's reported cost into the step summary.

Done when:
- Two `gh workflow run eval-llm.yml` runs on the same commit both pass, with 0 empty answers and 0 uncited repo mentions. The measured cost per run replaces the $4.5-8.5 estimate.
- The per-metric difference between the two runs is recorded as the noise band. M6 is judged against it.
- You hand-grade 12 items and the judge agrees on at least 10. This threshold is a design choice, not a benchmark; below it, rewrite the GEval `evaluation_steps`.

### M6: Upgrade to Boot 4.1.1 and Spring AI 2.0.1 (upgrade, 1-2 days; more than 2 days fires D1)
Changes:
- starter-web becomes starter-webmvc, Jackson 3 (`tools.jackson`), and the flattened `spring.ai.anthropic.chat.*` properties.
- ApiErrorHandler catches `com.anthropic.errors.*`, which 2.0.1 no longer translates.
- Set `.effort(OutputConfig.Effort.MEDIUM)` explicitly. Opus 5.5 already defaults to medium, and 1.0.0 has no effort control at all.
- Re-check retry: on 2.0.1 the Anthropic calls go through the anthropic-java SDK, whose own `maxRetries` (default 2) sits under Spring AI's retry.
- Testcontainers 2.0.5 and JUnit 6 now come from the Boot BOM, so drop the M1 override.
- Add `spring-boot-starter-webmvc-test` (test scope) and move `@WebMvcTest` imports to `org.springframework.boot.webmvc.test.autoconfigure`.
- Delete the Spring Boot and Spring AI `ignore` entries from `.github/dependabot.yml`.

Done when:
- `mvn -B verify` is green. AskFlowIT's body assertion settles C7.
- `mvn -B dependency:tree -Dincludes=org.springframework.ai:spring-ai-transformers` shows `2.0.1`, which closes both CVEs.
- Retrieval: max per-row cosine drift across the fixture stays under 1e-4, and any rank swap is between rows whose scores differ by less than that. Exact equality is too strict, because onnxruntime goes 1.19.2 to 1.21.1 and DJL 0.32 to 0.36 underneath. One labelled LLM run stays within the M5 noise band.

### M7: Native citations (upgrade, ~0.5 day)
Changes:
- Call `VectorStoreDocumentRetriever` directly (topK 5, threshold 0.0).
- Send each retrieved row as `AnthropicCitationDocument...plainText(...).title(repo + " " + week).citationsEnabled(true)`, and map each returned citation back to its (url, week).
- Add a citation-precision metric to the harness.

Done when:
- Across the golden run, every returned citation is one of the 5 retrieved rows (0 violations).
- Citation precision is shown in the step summary.
- `/search` with `topK: 5` returns the same 5 ids as /ask's retrieval on all 62 items, so D1's equivalence still holds.

### M8: P4 Langfuse tracing (P4, ~1 day)
Content on spans also works on 1.0.x; P4 waits for the upgrade because of D2's order, not a platform limit.

Changes:
- Add `spring-boot-starter-opentelemetry` with the OTLP endpoint `https://<region>.cloud.langfuse.com/api/public/otel/v1/traces`, a Basic auth header and `x-langfuse-ingestion-version: 4`. On Boot 4.1.1 the headers go under `management.opentelemetry.tracing.export.otlp.headers.*`, and the endpoint property takes the full `/v1/traces` path.
- Set sampling to 1.0.
- Write the content observation filter.
- Keep `http.server.requests` on.
- Without Langfuse keys, tracing is a no-op, because `eval-retrieval` runs keyless. Port PR #5's `LangfuseProperties` record (keys, host, endpoint override) and its exporter bean: a no-op without keys, a WARN when only one is set, Basic auth derived from the two keys. The bean needs a compile-scope `io.opentelemetry:opentelemetry-exporter-otlp`, which the starter brings only at runtime.
- Set `management.otlp.metrics.export.enabled: false`. The starter also brings `micrometer-registry-otlp`, which otherwise pushes metrics to `localhost:4318` on every run.
- The harness's `traceparent` carries the sampled flag (`01`). Boot 4.1's default sampler is parent-based, so an unsampled parent drops the Java spans despite probability 1.0.
- The harness injects `traceparent`, mirrors the golden set to a Langfuse dataset, and calls `dataset.run_experiment(name=f"{sha}-{model}", ...)`. Add `langfuse>=4,<5`.

Done when:
- One experiment shows 62 items in the Langfuse UI.
- In 3 opened traces, the Java spans (http.server, chat_client, vector_store, chat model) nest under the Python item span, and the generation has non-null input, output and token usage.
- If input and output stay null even with the filter, D1's flip-when "Langfuse spans null on Boot 4.1" has fired.

### M9: Java 25 and the deployable image (deploy prep, ~1.5 days)
Changes:
- Bump Java 25 on its own first, and get the gates green.
- Build the Dockerfile: model from the M4 script, libtorch pre-populated or pulled in via `pytorch-native-cpu` + `pytorch-jni` matching DJL 0.36.0, `-Dai.djl.offline=true`, and an AOT-cache training run.
- Switch `eval-retrieval` to run this image.

Done when:
- Proof that nothing downloads at runtime: on `docker network create --internal radar-offline`, with only Postgres beside it, the service boots and a curl container on that network gets 5 hits from `/search`.
- The offline boot test runs under `docker run --memory=2g` with the Dockerfile's `-XX:MaxRAMPercentage`. Heap and native memory are recorded separately (Native Memory Tracking on, `jcmd <pid> VM.native_memory`). A container with no memory limit would hide the heap OOM.
- The retrieval gate on the image equals the baseline.
- Cold-start time is recorded with and without the AOT cache.

### M10: Deploy and public-demo safety (deploy, ~2.5 days)
Changes:
- Everything in the Hosting and Public-demo safety rows.
- A separate `radar-demo` workspace with its own spend limit.
- Effort set to low on the public /ask path.

Done when:
- `curl -i -X POST $URL/sync` without the secret returns `401`; with it, `200 {"ingested": N}`.
- Sending /search from one IP past the bucket limit returns `429`.
- A forced upstream error returns a generic body with no upstream text.
- A manual run of the cron workflow is green.

### M11: P5 Blog/Loot ingest and "Ask the radar" (P5, ~2 days)
Changes:
- Blog (Title + Brief + Comment) and Loot (Repo + Asset + Intro + Why; `Asset` names the thing worth taking, confirm it in golden_v2), each with its own `source` value, page-id keys, and a full refresh per source in its own `@Transactional`.
- All four loot ledgers (claude, copilot, opencode, codex) come from a configured target-to-data-source map, and `freeze-notion` gains them and Blog. Decide whether `heat:` rows (trending repos with a ruling, written by the loot triage) are embedded: included, each is a second copy of a trending repo.
- Blog `week` falls back to `Archived` when `Published` is empty.
- `/sync` returns a total plus per-source counts, and reports a failing source (a new ledger returns 404 until the Notion integration is shared into it) without discarding the others. M2's and M10's checks move to the new shape.
- golden_v2 labels are keyed (source, url, week): a loot `Link` falls back to the repo URL, so (url, week) alone collides with trending.
- M7's citation title falls back from repo to the post title for blog rows.
- Port from PR #6 by hand: the `fetchLoot` / `fetchBlog` property mappings, `LootRow` / `BlogRow` (plus `pageId`, and `archived` for Blog), the shared metadata keys (Type → category, Published → week, title, status), and three of its `IngestMappingTest` cases.
- `static/index.html`.
- The github-radar-ui outbound link.
- `golden_v2` with Blog and Loot items.

Done when:
- `curl -s -X POST $URL/search -H 'content-type: application/json' -d '{"q":"...","source":"blog","topK":5}' | jq length` prints `5`.
- The golden_v2 retrieval gate is green.
- In github-radar-ui, the diff adds one `<a href>` and no `fetch(` call, so D6 holds.

## 4. Do-not-add list

| Item | Why | Durable / deferred | Flip-when |
|---|---|---|---|
| Other in-process embedding models: bge-m3, Qwen3-Embedding-0.6B, EmbeddingGemma-300m | bge-m3 uses CLS pooling, but the runtime always mean-pools; it is also 1024-d and 2.27 GB. Qwen3 uses last-token pooling and has no official ONNX. EmbeddingGemma is gated and has Dense layers that mean pooling skips. | Deferred | A measured shortfall of mE5-small that error analysis blames on the model, or P5 needing >512-token Blog Summaries embedded whole. Then use an Ollama sidecar. |
| multilingual-e5-base (768-d) | +1.5 nDCG@10 on MIRACL for double the size and a column change ([arXiv](https://arxiv.org/html/2402.05672)) | Deferred | The A/B shows mE5-small is the bottleneck. |
| jina-embeddings-v3 | CC-BY-NC-4.0 license | Durable | — |
| Quantized ONNX variants | CPU-specific files: an arm64 Mac and x86 CI would produce different embeddings | Durable | — |
| Ollama sidecar | An extra container locally and in CI, with no gain for 384-d in-process models | Deferred | An Ollama-only model wins the A/B, or DJL cannot load the XLM-R tokenizer (see Risks). |
| Third-party embedding APIs (Voyage / Jina / Gemini) | Puts a key and a vendor free tier on the CI gate; current free-tier limits are unverified | Deferred | The deployed memory budget cannot hold the in-process model. |
| HNSW / IVFFlat index | Exact search is trivial at 190 rows, and the approximate index filters after the scan ([pgvector](https://github.com/pgvector/pgvector/blob/master/README.md)) | Deferred | Over ~50k rows, or /search p95 over 100 ms. Then use HNSW with `iterative_scan`. |
| Postgres 17 / 18 | No feature needed; 16 is supported to 2028-11-09 ([versioning](https://www.postgresql.org/support/versioning/)) | Deferred | A needed extension or feature requires it, or EOL gets close. |
| CJK keyword extensions + RRF (pg_bigm, PGroonga, zhparser) | Custom images to fix what the multilingual embedding fixes | Deferred | Golden queries on exact repo names fail and ILIKE / pg_trgm on ASCII names does not fix them. |
| Reranker (cross-encoder or LLM) | With topK 5, the generator already reads every candidate | Deferred | Recall@20 is high but nDCG@5 / P@5 are low. |
| Chunking | Rows are at most 160 tokens (median 86) | Deferred | P5 must answer from Blog Summary detail that Title + Brief lack. |
| Anthropic prompt caching on /ask | The only stable prefix is the ~80-token system prompt, below the cache minimum (512 tokens on Opus 5 per Anthropic's prompt-caching documentation; Opus 5.5 is not listed there, but the whole range is 512-4096, far above ~80) | Deferred | A stable prefix above the minimum appears (multi-turn history, a long few-shot prompt). |
| `search_result` blocks through the raw SDK | Bypasses ChatClient and the observations P4 needs | Deferred | Spring AI exposes `search_result` blocks. |
| Prompt-level [n] citation markers on 1.0.0 | Not validated, and thrown away at the upgrade | Deferred | D1's "upgrade stuck > 2 days" fires. |
| ranx or any IR-metrics library | Five formulas you must be able to derive anyway; heavy dependencies; last release 2025-08-07 ([PyPI](https://pypi.org/pypi/ranx/json)) | Deferred | Several systems compared on more than ~300 queries with paired significance tests. |
| DeepEval ContextualPrecision / ContextualRecall | They need a reference answer per item and re-measure the labels with a noisy judge | Deferred | Golden items gain reference answers for another reason. |
| DeepEval Synthesizer goldens | Synthetic questions echo the row wording and hide the zh-TW/English gap | Deferred | P5 needs more than ~150 items across new question types. |
| Confident AI / `deepeval test run` as the results store | A second vendor next to Langfuse; per-item asserts are the wrong granularity for a noisy judge | Durable | Langfuse is dropped as the P4 backend. |
| LLM judge on every PR | $4.5-8.5 per run (estimate), and flaky | Deferred | A run costs under ~$0.50 and judge-vs-human agreement has been measured. |
| Scheduled (nightly or weekly) LLM runs | Against a frozen fixture they only measure judge noise; about $180/month nightly | Deferred | The service is deployed with real traffic, or the fixture is refreshed on a schedule. |
| Self-hosted Langfuse | v4 needs web, worker, Postgres, ClickHouse, Redis and S3; compose is sized at 4 cores / 16 GiB ([compose](https://langfuse.com/self-hosting/deployment/docker-compose)) | Deferred | Traces must not leave the machine, or more than 30 days of history is needed. Try Core at $29 first ([pricing](https://langfuse.com/pricing)). |
| Phoenix / OpenLIT | Phoenix does not map gen_ai.* ([#10622](https://github.com/Arize-ai/phoenix/issues/10622)); OpenLIT needs self-hosted ClickHouse | Deferred | Phoenix maps gen_ai.* and Hobby's limits start to bind. |
| opentelemetry-spring-boot-starter / OTel Java agent | Boot's own OTLP export covers the Micrometer observations; the starter has a documented JDBC conflict ([guide](https://langfuse.com/integrations/frameworks/spring-ai)) | Deferred | Spans are needed from libraries Micrometer does not observe. |
| respx / pytest-httpx | `httpx.MockTransport` is built in | Durable | — |
| ty type checker | Still Beta 0.0.x; Pydantic support pending ([PyPI](https://pypi.org/project/ty/)) | Deferred | A stable release with first-class Pydantic support. |
| Kubernetes / GKE / Helm | One stateless service with max-instances 1 | Deferred | A posting you apply to requires Kubernetes evidence. Prefer a README section first. |
| Terraform / IaC | Two resources | Deferred | Taiwan job-posting counts show Terraform widely required. Then a ~50-line module. |
| GraalVM native image | onnxruntime and DJL are JNI-heavy, with no proven reachability metadata | Deferred | Cold start is still over ~10 s after the AOT cache and CPU boost, and it visibly hurts the demo. |
| In-app daily dollar cap (a Postgres row) | The workspace monthly limit, Turnstile and a per-IP bucket on max-instances 1 already bound spend. The cap adds token x price bookkeeping that must track pricing | Deferred | The workspace monthly limit is hit before month end. |
| Redis / distributed rate limiting | With max-instances 1, an in-memory Bucket4j limit is exact | Durable | max-instances goes above 1. |
| Spring Security / OAuth / user accounts | Only /sync needs protection, and a shared-secret header covers it | Deferred | The demo needs per-user identity. |
| Dedicated vector DB (Pinecone, Qdrant, Weaviate, Milvus) | pgvector behind Spring AI's VectorStore covers this scale | Durable | A specific target posting names one. Add it side by side, not as a replacement. |
| Cloud Armor / external load balancer / API Gateway | The load balancer alone breaks the $0-10/month budget | Deferred | Abuse gets past Turnstile, or a paid budget exists. |
| Permanent min-instances 1 | ~$10/month (secondary source, unverified) | Deferred | Live-interview days only: set it to 1 for the day, then revert. |
| Other hosts | Fly.io: no free tier, ~$5.70-5.92/month for 1 GB ([pricing](https://fly.io/pricing-update/)). Render Free: 0.1 CPU / 512 MB, and Postgres expires after 30 days ([docs](https://render.com/docs/free)). Railway: $10/GB-month RAM ([plans](https://docs.railway.com/pricing/plans)). App Runner: closed to new customers since 2026-04-30 ([AWS](https://docs.aws.amazon.com/apprunner/latest/dg/apprunner-availability-change.html)). Oracle Always Free: halved, and reclaims idle instances ([InfoQ](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)). Supabase Free: pauses after 7 days ([docs](https://supabase.com/docs/guides/platform/free-project-pausing)). Cloud SQL: no free tier (unverified). | Deferred | Cloud Run's or Neon's free terms change. |
| Automated Claude PR review (`claude-code-review.yml`, removed after M0) | It ran on every PR (#4, #5, #6, #10) for about $0.1-0.8 and never posted a review: each run ended with one permission denial and "No buffered inline comments", so its green check reviewed nothing. `claude.yml` (@claude on demand) stays | Deferred | A fixed setup is shown to post findings on a test PR. |
| JaCoCo gate, PIT, Pact | Coverage numbers on 500 lines are vanity, and the API has no consumers | Deferred | The service grows well past four endpoints, or a second consumer appears. |
| Queue / async ingestion | ~200 rows once a week | Durable | Ingest exceeds the Cloud Run request timeout. |
| Multi-arch images | Cloud Run runs amd64 | Deferred | The deploy target becomes ARM. |
| An "Ask" panel inside github-radar-ui that calls radar-rag | Breaks D6 | Durable while D6 holds | The UI drops its pure-reader contract (also a D1 flip-when). |

## 5. Open decisions

| # | Decision | Recommendation | Alternatives | Needed by |
|---|---|---|---|---|
| 1 | Monthly spend limit for the dedicated `radar-eval` Anthropic workspace | **$40/month while M5-M7 run, then $15.** M5 needs 2 runs, M6 and M7 at least 1 each, at $4.5-8.5 per run (estimate, from [pricing](https://platform.claude.com/docs/en/about-claude/pricing)), plus dev calls. | $15 flat: fewer reruns, and the M5 noise band might wait a month. $60: more headroom. The demo workspace budget is decided at M10. | M1 (you create the key) |
| 2 | How the golden set gets written | **You write all 60 questions and confirm every label. Claude first lists candidate (url, week) rows for each question from the fixture, and you accept, reject or add.** About 2 h instead of 3-4 h, and the questions never copy row wording. The catch: a relevant row missing from the candidate list may go unlabelled. | Fully by hand: 3-4 h, the strongest line in an interview. Claude drafting the questions is rejected (the Synthesizer problem). | M2 (done: you wrote every zh-TW question from a plain-language scenario per repo and confirmed every label; the English twins are Claude's literal translations, because you read English only as technical terms) |
| 3 | When radar-rag goes public, and whether to keep the personal Gmail in the 11 existing commits | **Flip after M4 (the A/B table is the README headline). Keep the existing commit metadata, and set future commits to your GitHub noreply address.** Before the flip: gitleaks and the stripped fixture. Flip github-radar-ui and ai-assistant at the same time only after they pass the same scan; otherwise remove the README cross-links. | Rewrite the history. That needs a force-push to main, done by hand. Or stay private until M10. | after M4 (M5 no longer depends on it) |

Recommended outright (not put to you):
- The harness lives in `evals/` inside radar-rag. D6 covers the three existing repos, and a fourth repo would need cross-repo triggers for the gate.
- Set max-tokens to 16000. It is a ceiling; effort and the workspace limit control what is actually spent.
- Collapsing multiple weeks of one repo: measure both granularities through M4, then decide from the golden results. Any rule applies to /search and /ask alike, so D1's equivalence holds.
- Langfuse region: JP, the nearest to Taiwan, if Hobby is offered there; otherwise EU. Retention is 30 days, so a later switch is cheap.
- Deploy target: Cloud Run, unless a count of the Taiwan postings you already read shows Azure or AWS clearly ahead. The image is portable, so only the deploy job would change.

## 6. Risks and unknowns

- **Can 1.0.0 parse an Opus 5.5 response?** Untested, because no key exists yet. M1 settles it, and a failure fires a D1 flip-when.
- **Tokenizer loading:** that DJL can load the XLM-R-family `tokenizer.json` is unverified, and both multilingual arms use that tokenizer family. If it fails, there is no config-only fallback; the next step is an Ollama sidecar.
- **Deploy sizing:** mE5-small's fp32 model (~470 MB) likely needs 2 GiB on Cloud Run, which halves the free GiB-seconds. Images of ~1 GB exceed Artifact Registry's 0.5 GB free tier; the cost is small but unverified.
- **Cost estimates:** LLM-gate cost, Langfuse units per run (~900) and the $0.33 worst case per /ask are all estimates. Replace them with measured usage after M5 and M8.
- **Cold starts:** a Cloud Run JVM (3-10 s, secondary source) plus Neon resuming after 5 idle minutes. The AOT cache and CPU boost reduce it; on interview days, set min-instances to 1.
- **Unconfirmed guesses:** trace linking depends on `http.server.requests` staying on (inferred; M8 checks it). Whether delete + add stays atomic inside `@Transactional` is unverified (M3 checks it).
- **Langfuse on Boot 4.1 is off the documented road.** Langfuse's Spring AI guide targets Spring AI 1.0.x with `opentelemetry-spring-boot-starter`; this plan uses Boot 4.1's `spring-boot-starter-opentelemetry`. M8's done-when is the test, and D1's flip-when covers a failure.
- **Startup memory:** both multilingual models load as one ~470 MB on-heap array plus a native copy. M9's `--memory=2g` boot test settles the size.
- **D1's first flip-when:** nothing in this plan measures it; it is tracked outside this repo.

## 7. Earlier P3-P5 PRs

PRs #4 (P3), #5 (P4) and #6 (P5) were opened on 2026-07-10, before this plan, as a stack: #5 targets #4's
branch and #6 targets #5's. None can merge. #4's Java in-process harness conflicts with D1, D4 and D5,
and #5 and #6 sit on top of it. They are closed; their branches `feat/p3-eval-harness`,
`feat/p4-langfuse-tracing` and `feat/p5-blog-loot-ingest` stay as reference until M11 lands. Deleting a
base branch first would auto-close the PR stacked on it.

What each milestone takes from them is written into M1, M2, M3, M8 and M11 above. The rest is discarded:
the Java eval classes, `corpus.json` and `golden.json` (a synthetic corpus whose repos are almost all
absent from the real archive, with url-only labels), `application-eval.yml`, #4's `ci.yml`, the three
README sections, and #6's loot week-collapse and url-keyed ids.
