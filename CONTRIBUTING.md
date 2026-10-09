# Contributing

The README covers running the service; [docs/stack-plan.md](docs/stack-plan.md) holds the decisions and
the milestone order. The traps below are the ones that waste an afternoon if you meet them by surprise.

## Four things that will cost you an afternoon

**The service will not start without the model.** It loads the embedding model from `models/`, which
`scripts/fetch-models.sh` fills at a pinned Hugging Face revision with a sha256 check. Nothing is
downloaded at boot, by design; run the script once per clone.

**An embedding change has to keep the vectors bit-identical, or re-baseline.** The CI retrieval gate
compares every golden query's top 5 with `evals/results/baseline.json`, and a single flipped hit fails
it. `OnnxEmbeddingModel` sums in PyTorch's order for that reason: a plain running sum is an ulp or two
off, which is enough to reorder near-ties. A deliberate model change takes a new baseline from the CI
runner's artifact, never from a dev machine.

**Memory tests need swap off.** `docker run --memory=2g` lends another 2 GiB of swap by default, and
Cloud Run has none. Test sizes with `--memory-swap` equal to `--memory`, as CI and the compose `app`
service do, or a boot that does not fit will look fine.

**The LLM eval costs money and runs on request.** `eval-llm.yml` calls `/ask` on Opus 5.5 and a Sonnet
judge for all 62 golden items, about $3.3 a run. It runs only when the repository owner adds the
`eval:llm` label or dispatches it by hand.

## Working on it

```bash
scripts/fetch-models.sh                    # once per clone
mvn -B verify                              # unit and integration tests (Docker for Testcontainers)
cd evals && uv sync && uv run ruff check && uv run mypy && uv run pytest
docker compose --profile app up --build    # the shipped image, beside Postgres
```

The Java and Python test suites need no Notion token and no Anthropic key. Running the service against
real data needs `NOTION_TOKEN` and a Trending Archive with the same schema; `/ask` also needs
`ANTHROPIC_API_KEY`.

"Verified" means the tests pass **and** you hit the changed endpoint yourself (`curl -i`) and looked at
the response. A green test is one more kind of evidence, not a replacement for looking.

## Pull requests

Do not open a pull request you could not explain line by line if asked. That rule, and the two beside
it, are in the [Code of Conduct](CODE_OF_CONDUCT.md#send-work-you-understand).

Conventional Commits (`feat:` / `fix:` / `refactor:` / `docs:` / `chore:` / `test:` / `perf:`), in
English, saying WHY rather than WHAT. One logical change per commit.

If your change alters behaviour, interfaces, or project state, the docs it makes stale are part of the
diff: the README, `docs/stack-plan.md` if a milestone's status or a decision changed, and
`.env.example` if a variable was added or renamed.
