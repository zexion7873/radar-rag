# Results

How each retrieval and answer-quality change measured, in the order it shipped. The
[README](../README.md) shows the current numbers; [stack-plan.md](stack-plan.md) holds each
milestone's rule and runs.

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
optimistic ([M18](stack-plan.md)).

**Repo questions lead with trending** (golden_v1, every source): most of its misses ask for a 套件,
and blog's most similar row led the turns and took 3 of the 5 slots, though only trending holds repos.
A question naming 套件, 函式庫, repo, package or library now takes trending first; 工具 / tools does
not, since golden_v1's topic questions say it too. Answerable hit@5 0.738 → 0.762, MRR 0.478 → 0.536,
no item lower; golden_v2 and golden_v3 unchanged. The word list was read off golden_v1, so the gain is
optimistic ([M19](stack-plan.md)).

**Named repos come first** (reported on the live `/ask`): "「mattpocock/skills」是做什麼的？" answered that
the radar lacks the repo, though it was on the chart. The embedding blurs a repo name inside a question
and ranked it 22nd among trending repos; a repo the question names as owner/name is now looked up by
name and placed first. No golden question names one, so all three gated result sets are unchanged item
for item ([M20](stack-plan.md)).

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
means. A run then cost about $3.3.

**After blog ingest, per-repo search and balanced sources**, with the prompt now asking for synthesis
rather than restatement (CI run 38072910360): Faithfulness 0.995, AnswerRelevancy 0.922, Attribution
0.890, Abstention 0.860, citation precision 0.385. Answers had restated their sources: 30.3% of answer
text was a run of 20+ characters copied from a retrieved row, now 20.5%, at about $4 a run.

**After M17–M20** (CI run 40373554229, 2026-10-26, with the eval harness on Langfuse SDK 5):
Faithfulness 0.998, AnswerRelevancy 0.922, Attribution 0.917, Abstention 0.870, citation
precision 0.369, 19.0% of answer text copied; the run cost $4.41.

## Limits of these numbers

- **Broad questions still miss some posts.** Keyword ranking lifted golden_v2's answerable hit@5 to
  0.750, but its weight and stop words were chosen on golden_v2, and a question still misses when its
  post never names the word it turns on (問卷 against a post about simulating people) or is described
  only in Chinese while the question is English.
- **Small golden sets.** golden_v1 has 62 items and golden_v2 33. The owner wrote the zh-TW
  questions (the English twins are literal translations) and confirmed every label from candidate
  lists. The retrieval gates catch any flipped hit, but the LLM metrics move by up to ~0.03 between
  identical runs, so only a drop beyond that reads as a regression.
