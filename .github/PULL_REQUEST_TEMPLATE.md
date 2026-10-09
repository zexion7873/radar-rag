## What and why

<!-- What changed, and what it fixes or adds. WHY rather than WHAT: the diff
     already says what. If it fixes an issue, link it. -->

## How you know it works

<!-- Delete the lines that do not apply. -->

- [ ] `mvn -B verify` passes, and `uv run pytest` in `evals/` if Python changed.
- [ ] I hit the changed endpoint myself (`curl -i`). The request and what came
      back:
- [ ] I changed a test, and **showed the old code fails it**: which case, and
      what it printed:
- [ ] The change touches embeddings or retrieval, and I expect the CI retrieval
      gate to keep every golden top 5 (or say why the baseline should move).
- [ ] The change touches `/ask`'s prompt, model or citations, and needs the
      paid LLM eval (the owner adds the `eval:llm` label).

## Docs this makes stale

<!-- Which docs does this change invalidate? "None" is a fine answer; say it
     out loud rather than leaving this blank. CONTRIBUTING.md lists the ones
     that usually go stale. -->
