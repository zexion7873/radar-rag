import pytest

from radar_evals import metrics

# docs/stack-plan.md M2's worked example: relevant rows at ranks 2 and 4, three relevant in all.
RANKED = ["x1", "a", "x2", "b", "x3"]
GAINS = {"a": 1, "b": 1, "c": 1}


def test_the_plan_worked_example() -> None:
    assert metrics.hit_at_k(RANKED, GAINS, 5) == 1.0
    assert metrics.precision_at_k(RANKED, GAINS, 5) == pytest.approx(0.4)
    assert metrics.recall_at_k(RANKED, GAINS, 5) == pytest.approx(0.667, abs=5e-4)
    assert metrics.reciprocal_rank(RANKED, GAINS, 5) == pytest.approx(0.5)
    assert metrics.ndcg_at_k(RANKED, GAINS, 5) == pytest.approx(0.498, abs=5e-4)


def test_graded_gains_favour_the_better_row_first() -> None:
    # DCG = 1/log2(2) + 2/log2(3) = 2.2619; IDCG = 2/log2(2) + 1/log2(3) = 2.6309
    assert metrics.ndcg_at_k(["b", "a", "x"], {"a": 2, "b": 1}, 5) == pytest.approx(
        0.8597, abs=5e-4
    )


def test_a_miss_scores_zero_everywhere() -> None:
    ranked, gains = ["x1", "x2"], {"a": 1}
    assert metrics.hit_at_k(ranked, gains, 5) == 0.0
    assert metrics.precision_at_k(ranked, gains, 5) == 0.0
    assert metrics.recall_at_k(ranked, gains, 5) == 0.0
    assert metrics.reciprocal_rank(ranked, gains, 5) == 0.0
    assert metrics.ndcg_at_k(ranked, gains, 5) == 0.0


def test_rows_below_the_cutoff_do_not_count() -> None:
    assert metrics.hit_at_k(["x1", "x2", "a"], {"a": 1}, 2) == 0.0


def test_a_row_returned_twice_counts_once() -> None:
    ranked, gains = ["a", "a", "x1", "x2", "x3"], {"a": 1}
    assert metrics.recall_at_k(ranked, gains, 5) == 1.0
    assert metrics.precision_at_k(ranked, gains, 5) == pytest.approx(0.2)
    assert metrics.ndcg_at_k(ranked, gains, 5) == pytest.approx(1.0)


def test_url_level_collapses_weeks_and_keeps_the_best_grade() -> None:
    ranked = [("u", "2026-09-14"), ("u", "2026-09-21"), ("v", "2026-09-14")]
    gains = {("u", "2026-09-14"): 1, ("v", "2026-08-31"): 2}
    urls, url_gains = metrics.by_url(ranked, gains)
    assert urls == ["u", "u", "v"]
    assert url_gains == {"u": 1, "v": 2}
    assert metrics.recall_at_k(ranked, gains, 5) == 0.5
    assert metrics.recall_at_k(urls, url_gains, 5) == 1.0


def test_a_repeated_url_still_holds_its_rank() -> None:
    # B is third in the list; the second A is a non-relevant repeat, so url-level MRR is 1/3.
    ranked = [("A", "2026-09-14"), ("A", "2026-09-21"), ("B", "2026-09-21")]
    urls, url_gains = metrics.by_url(ranked, {("B", "2026-09-21"): 1})
    assert metrics.reciprocal_rank(urls, url_gains, 5) == pytest.approx(1 / 3)


def test_recall_and_ndcg_are_undefined_without_relevant_rows() -> None:
    with pytest.raises(ValueError):
        metrics.recall_at_k(["a"], {}, 5)
    with pytest.raises(ValueError):
        metrics.ndcg_at_k(["a"], {}, 5)
