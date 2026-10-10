# Working on radar-rag

A Java 25 / Spring Boot 4.1 / Spring AI 2.0 service over a Notion archive, plus a typed Python eval
harness in `evals/` that tests it as a black box over HTTP. `README.md` covers what it does;
`docs/stack-plan.md` holds the decisions and the milestones, each with a status line. This file covers
what will waste your time if you assume it.

What earns a place here: the command, what it covers, and the trap. Rationale belongs in the code
comment, the commit body or the stack plan.

## Commands and what they cover

- `scripts/fetch-models.sh` — the embedding model into `models/`, pinned and sha256-checked. The
  service does not start without it.
- `mvn -B verify` — unit tests plus the `*IT` integration tests on Testcontainers pgvector (needs
  Docker). Homebrew's `mvn` runs on whatever `JAVA_HOME` names; it must be a JDK 25.
- `cd evals && uv run ruff check && uv run ruff format --check && uv run mypy && uv run pytest` —
  exactly the CI `evals` job. `pytest -m service` needs a running service and is CI's.
- `docker compose --profile app up --build` — the shipped image beside Postgres, 2 GiB, no swap.
- CI `eval-retrieval` builds and runs the image and gates on hit@5 flips against
  `evals/results/baseline.json`. CI `eval-llm` is paid (~$3.3) and runs only on the owner's `eval:llm`
  label or dispatch: never trigger it without the owner's yes.

## Traps

- **Vectors must stay bit-identical.** The retrieval gate fails on one flipped hit. `OnnxEmbeddingModel`
  mirrors ATen's summation order and Spring AI's passage text (`getFormattedContent(NONE)`); change
  either and near-ties reorder. A deliberate model change re-baselines from the CI artifact, never from
  a dev machine.
- **Memory tests need swap off.** Docker's `--memory` lends as much swap again; use `--memory-swap`
  equal to `--memory`, as CI and compose do. Cloud Run has no swap.
- **The AOT cache is flag-sensitive.** The training run in the Dockerfile must use the entrypoint's
  `JAVA_FLAGS`; a different `--enable-native-access` drops the cache silently, and cached adapters or
  stubs from another CPU crash with SIGILL, which is why both are off.
- **Spring AI spans carry content.** The vector-store span records the query text and
  `ChatContentObservationFilter` puts the prompt and answer on the chat span; `PRIVACY.md` says so and
  must change with them.
- **The `prod` profile is invisible to the gates.** Only the deployment activates it: low effort, rate
  limits, a required `SYNC_SECRET`. Turning it on in CI throttles the harness's 62 queries from one
  address and moves the LLM gate off the effort it was measured at.
- **Langfuse orgs created on or after 2026-09-16 have no legacy read API.** Read traces through
  `GET /api/public/v2/observations` with explicit `fields`.
- **Docs move with the code.** A milestone's status line in `docs/stack-plan.md`, the README, and
  `SECURITY.md` / `PRIVACY.md` when exposure or data flow changes.
