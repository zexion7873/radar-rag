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
  Outside the prod profile it also answers only a `Host` of `localhost`, `127.0.0.1` or `[::1]` (`403`
  otherwise), so a DNS-rebinding page cannot reach it under its own name.
- **`/sync` refuses browsers.** A request carrying `Origin` gets `403` in every profile: a browser sends
  one with every cross-site POST, form and no-cors fetch alike, and curl and the Weekly sync workflow
  send none.
- **Reads Notion.** `POST /sync` pulls the Trending Archive with one internal integration token and
  replaces those rows in pgvector. The token stays on the server. A row's url is kept only when it is
  `http(s)`. Notion failures return a generic
  body with Notion's status code; Notion's text is only logged.
- **Calls Claude.** `POST /ask` sends the retrieved rows to Anthropic with the server's
  `ANTHROPIC_API_KEY`. Anthropic failures return a generic body; the upstream text is only logged.
- **Prod profile (`SPRING_PROFILES_ACTIVE=prod`).** `/sync` needs `Authorization: Bearer` with
  `SYNC_SECRET`, compared in constant time; boot fails without it, and an empty one refuses every call.
  `/ask` and `/search` are rate-limited per client, keyed on the right-most `X-Forwarded-For` entry
  (an IPv6 client by its /64), and per IPv6 /48; `/ask` also has one 100-a-day limit for every client
  together, spent only by a question that passed Turnstile. `/ask` needs a Cloudflare Turnstile token, checked with siteverify
  before any retrieval or model call; a missing or rejected token, or one issued on a host other than
  github-radar-ui's, is `403`, and an unreachable
  siteverify `503`, never a pass. Error bodies carry no message, logs are `INFO`, Swagger UI and
  `/v3/api-docs` are off, and `POSTGRES_PASSWORD` has no fallback.
- **No page here.** `/` redirects (301) to github-radar-ui's `/ask`, which renders the answers; this
  service serves no HTML, only JSON.
- **Cross-origin `/ask` from one site.** `POST /ask` allows CORS from github-radar-ui's production
  origin only (`radar.ask.allowed-origins`; `http://localhost:3000` outside prod), with the
  `Content-Type` and `X-Turnstile-Token` headers. Every other path and origin keeps the browser's
  same-origin default. Preflights are not rate-limited, so each question costs one request from the
  limit. The Turnstile token and the rate limit apply as to any other caller.
- **Validates filters.** `/search` rejects filter values containing quotes or backslashes before
  building the vector store's filter expression, which Spring AI also escapes into its SQL.
- **Bounds each request.** `/search` and `/ask` stop reading a body at 16 KiB, refuse a question over
  1,000 characters, and `/search` a
  `topK` over 50, so one request can neither read an unbounded number of rows nor send an unbounded
  question to the model.
- **Downloads nothing at runtime.** The image carries the model and every native library, pinned and
  checked at build time.
- **Automation.** No workflow runs a model on issue or pull-request text; the paid LLM eval runs only on
  the owner's label or dispatch. Any job on `main` can write the Actions cache, so Deploy builds with no
  layer cache and the LLM eval restores only the sha256-checked model from it.
- **Deploys without a stored cloud key.** The Deploy workflow authenticates to Google Cloud through
  Workload Identity Federation, which trusts only the Deploy workflow file in this repository (by its
  numeric id) on `refs/heads/main`. The service runs as its own account, which can read its secrets in
  Secret Manager and nothing else.

### In scope

- Getting `/search` filters past validation into the filter expression.
- Anything that sends the Notion token, the Anthropic key or the Langfuse keys to a response or to a log
  a caller can read.
- Getting `/ask` to return Anthropic's upstream error text, or `/sync` Notion's.
- Under the prod profile: getting `/ask` to reach the model without a token Turnstile accepted.
- Under the prod profile: calling `/sync` without the secret, or getting past the rate limit from one
  client.
- Getting a browser on an origin outside `radar.ask.allowed-origins` to read a response from `/ask`, or
  from any other path cross-origin.
- Outside the prod profile: getting a web page to reach the loopback service under a name other than a
  loopback one, or to trigger `/sync`.
- Making the LLM eval run for someone other than the owner.
- Getting a workflow run from another repository, a fork, a non-`main` ref or any workflow other than
  Deploy to authenticate to the project's Google Cloud.

### Not yet, and already known

A report of these is a duplicate:

- Without the prod profile, `/sync` has no authentication and Swagger UI is on. That run binds
  loopback by default, answers only a loopback `Host`, and `/sync` refuses any request with `Origin`.
- Turnstile stops scripted `/ask` calls, not a person asking by hand from many addresses. Each new
  IPv4 address or IPv6 /48 gets fresh buckets; the 100-a-day `/ask` limit across all clients and the
  dedicated Anthropic workspace's monthly spend limit cap the cost, and a hundred questions that pass
  Turnstile in a day refuse `/ask` to everyone until the limit refills.
- The rate limit lives in one instance's memory: it resets on restart and is not shared between
  instances (the deployment runs at most one).

### Out of scope

- Answer content. The model answers from rows written upstream by separate routines; a wrong or odd
  answer is a quality issue, not a vulnerability.
- Anything that needs an attacker who already controls the Notion workspace, the Anthropic or Langfuse
  account, or the repository.
