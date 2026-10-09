# Security Policy

## Reporting a vulnerability

**Do not open a public issue.** Use
[GitHub's private vulnerability reporting](https://github.com/zexion7873/radar-rag/security/advisories/new).

This is a one-person project. You will get a first response within a few days, not within hours. The
fix ships as a commit to `main`.

## Supported versions

Only `main`. There are no releases, tags or maintenance branches, and no public deployment yet: the
service runs locally and in CI. The public demo, with the protections listed under "Not yet" below, is
milestone M10 in [docs/stack-plan.md](docs/stack-plan.md).

## What the service does

Worth knowing before you decide whether something is in scope.

- **Binds loopback by default.** `server.address` is `127.0.0.1` unless `SERVER_ADDRESS` says
  otherwise; only the container image sets `0.0.0.0`, and compose publishes it on `127.0.0.1` alone.
- **Reads Notion.** `POST /sync` pulls the Trending Archive with one internal integration token and
  replaces those rows in pgvector. The token stays on the server.
- **Calls Claude.** `POST /ask` sends the retrieved rows to Anthropic with the server's
  `ANTHROPIC_API_KEY`. Anthropic failures return a generic body; the upstream text is only logged.
- **Validates filters.** `/search` rejects filter values that could break out of the vector store's
  filter expression before building it.
- **Downloads nothing at runtime.** The image carries the model and every native library, pinned and
  checked at build time.
- **Automation.** The repository's `@claude` workflow runs only for comments, reviews and issues written
  by the repository owner; the paid LLM eval runs only on the owner's label or dispatch.

### In scope

- Getting `/search` filters past validation into the filter expression.
- Anything that sends the Notion token, the Anthropic key or the Langfuse keys to a response or to a log
  a caller can read.
- Getting `/ask` to return Anthropic's upstream error text.
- Making the `@claude` workflow or the LLM eval run for someone other than the owner.

### Not yet, and already known

These are open until M10 deploys the service, so a report of them is a duplicate:

- `/sync` has no authentication; anyone who can reach the port can trigger a sync.
- There is no rate limit or spend cap in the service; `/ask` spends the operator's Anthropic credit.
- Notion errors from `/sync` are returned with Notion's response body, and Swagger UI is on.

### Out of scope

- Answer content. The model answers from rows written upstream by separate routines; a wrong or odd
  answer is a quality issue, not a vulnerability.
- Anything that needs an attacker who already controls the Notion workspace, the Anthropic or Langfuse
  account, or the repository.
