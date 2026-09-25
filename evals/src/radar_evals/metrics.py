"""Ranked-retrieval metrics at a cutoff k, over any hashable row key.

`gains` maps a relevant key to its graded relevance (1 or 2); keys absent from it are not relevant.
A key retrieved twice counts once, at its first rank: after multi-source ingest the same row can
come back under two sources, and counting both would push recall above 1.
"""

import math
from collections.abc import Hashable, Mapping, Sequence


def _first_occurrences[K: Hashable](ranked: Sequence[K], k: int) -> list[tuple[int, K]]:
    """(1-based rank, key) for the first appearance of each key within the top k."""
    seen: set[K] = set()
    out: list[tuple[int, K]] = []
    for rank, key in enumerate(ranked[:k], start=1):
        if key not in seen:
            seen.add(key)
            out.append((rank, key))
    return out


def _relevant_hits[K: Hashable](ranked: Sequence[K], gains: Mapping[K, int], k: int) -> int:
    return sum(1 for _, key in _first_occurrences(ranked, k) if gains.get(key, 0) > 0)


def hit_at_k[K: Hashable](ranked: Sequence[K], gains: Mapping[K, int], k: int) -> float:
    return 1.0 if _relevant_hits(ranked, gains, k) > 0 else 0.0


def precision_at_k[K: Hashable](ranked: Sequence[K], gains: Mapping[K, int], k: int) -> float:
    return _relevant_hits(ranked, gains, k) / k


def recall_at_k[K: Hashable](ranked: Sequence[K], gains: Mapping[K, int], k: int) -> float:
    total = sum(1 for g in gains.values() if g > 0)
    if total == 0:
        raise ValueError("recall is undefined for an item with no relevant rows")
    return _relevant_hits(ranked, gains, k) / total


def reciprocal_rank[K: Hashable](ranked: Sequence[K], gains: Mapping[K, int], k: int) -> float:
    for rank, key in _first_occurrences(ranked, k):
        if gains.get(key, 0) > 0:
            return 1.0 / rank
    return 0.0


def ndcg_at_k[K: Hashable](ranked: Sequence[K], gains: Mapping[K, int], k: int) -> float:
    """Linear gains (the grade itself), discounted by log2(rank + 1)."""
    dcg = sum(
        gains.get(key, 0) / math.log2(rank + 1) for rank, key in _first_occurrences(ranked, k)
    )
    ideal = sorted((g for g in gains.values() if g > 0), reverse=True)[:k]
    idcg = sum(g / math.log2(rank + 1) for rank, g in enumerate(ideal, start=1))
    if idcg == 0:
        raise ValueError("nDCG is undefined for an item with no relevant rows")
    return dcg / idcg


def by_url(
    ranked: Sequence[tuple[str, str]], gains: Mapping[tuple[str, str], int]
) -> tuple[list[str], dict[str, int]]:
    """(url, week) keys as urls: a url counts at its first rank with its best grade;
    its later weeks stay in place as non-relevant repeats."""
    urls = [url for url, _ in ranked]
    url_gains: dict[str, int] = {}
    for (url, _), grade in gains.items():
        url_gains[url] = max(url_gains.get(url, 0), grade)
    return urls, url_gains
