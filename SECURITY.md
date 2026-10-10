# Security Policy

## Reporting a vulnerability

**Do not open a public issue.** Use
[GitHub's private vulnerability reporting](https://github.com/zexion7873/radar-rag/security/advisories/new).

This is a one-person project. You will get a first response within a few days, not within hours. The
fix ships as a commit to `main`.

## Supported versions

Only `main`. There are no releases, tags or maintenance branches. The public demo is `main`, deployed
to Cloud Run by the Deploy workflow, and runs the prod profile described below.

## What the service does

Worth knowing before you decide whether something is in scope.

- **Binds loopback by default.** `server.address` is `127.0.0.1` unless `SERVER_ADDRESS` says
  otherwise; only the container image sets `0.0.0.0`, and compose publishes it on `127.0.0.1` alone.
- **Reads Notion.** `POST /sync` pulls the Trending Archive with one internal integration token and
  replaces those rows in pgvector. The token stays on the server. Notion failures return a generic
  body with Notion's status code; Notion's text is only logged.
- **Calls Claude.** `POST /ask` sends the retrieved rows to Anthropic with the server's
  `ANTHROPIC_API_KEY`. Anthropic failures return a generic body; the upstream text is only logged.
- **Prod profile (`SPRING_PROFILES_ACTIVE=prod`).** `/sync` needs `Authorization: Bearer` with
  `SYNC_SECRET`, compared in constant time; boot fails without it, and an empty one refuses every call.
  `/ask` and `/search` are rate-limited per client, keyed on the right-most `X-Forwarded-For` entry
  (an IPv6 client by its /64). `/ask` needs a Cloudflare Turnstile token, checked with siteverify
  before any retrieval or model call; a missing or rejected token is `403`, and an unreachable
  siteverify `503`, never a pass. Error bodies carry no message, logs are `INFO`, Swagger UI and
  `/v3/api-docs` are off, and `POSTGRES_PASSWORD` has no fallback.
- **The page.** `/` serves static HTML whose Content-Security-Policy allows scripts and frames from
  this origin and Cloudflare Turnstile only. Model output is inserted as text nodes, never as HTML,
  and only `http(s)` citation URLs become links.
- **Validates filters.** `/search` rejects filter values that could break out of the vector store's
  filter expression before building it.
- **Downloads nothing at runtime.** The image carries the model and every native library, pinned and
  checked at build time.
- **Automation.** The repository's `@claude` workflow runs only for comments, reviews and issues written
  by the repository owner; the paid LLM eval runs only on the owner's label or dispatch.
- **Deploys without a stored cloud key.** The Deploy workflow authenticates to Google Cloud through
  Workload Identity Federation, which trusts only this repository (by its numeric id) on
  `refs/heads/main`. The service runs as its own account, which can read its secrets in Secret Manager
  and nothing else.

### In scope

- Getting `/search` filters past validation into the filter expression.
- Anything that sends the Notion token, the Anthropic key or the Langfuse keys to a response or to a log
  a caller can read.
- Getting `/ask` to return Anthropic's upstream error text, or `/sync` Notion's.
- Under the prod profile: getting `/ask` to reach the model without a token Turnstile accepted, or
  getting the page to run script from an answer.
- Under the prod profile: calling `/sync` without the secret, or getting past the rate limit from one
  client.
- Making the `@claude` workflow or the LLM eval run for someone other than the owner.
- Getting a workflow run from another repository, a fork or a non-`main` ref to authenticate to the
  project's Google Cloud.

### Not yet, and already known

A report of these is a duplicate:

- Without the prod profile, `/sync` has no authentication and Swagger UI is on. That run binds
  loopback by default.
- Turnstile stops scripted `/ask` calls, not a person asking by hand from many addresses. Each new
  address gets fresh rate-limit buckets; the dedicated Anthropic workspace's monthly spend limit is
  what caps the cost.
- The rate limit lives in one instance's memory: it resets on restart and is not shared between
  instances (the deployment runs at most one).

### Out of scope

- Answer content. The model answers from rows written upstream by separate routines; a wrong or odd
  answer is a quality issue, not a vulnerability.
- Anything that needs an attacker who already controls the Notion workspace, the Anthropic or Langfuse
  account, or the repository.
