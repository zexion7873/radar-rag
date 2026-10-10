"""The golden set: hand-written questions with (url, week, grade) labels, one per line."""

import re
from collections import Counter
from pathlib import Path
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

from radar_evals.models import RowKey

type Script = Literal["cjk-only", "mixed", "latin"]

_CJK = re.compile(r"[㐀-䶿一-鿿豈-﫿]")
_LATIN = re.compile(r"[A-Za-z]")


def script_of(text: str) -> Script:
    """cjk-only questions are the ones an English-only embedding cannot anchor on a loanword."""
    has_cjk = bool(_CJK.search(text))
    has_latin = bool(_LATIN.search(text))
    if has_cjk and not has_latin:
        return "cjk-only"
    return "mixed" if has_cjk else "latin"


class Label(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    url: str
    week: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    grade: Literal[1, 2]

    @property
    def key(self) -> RowKey:
        return (self.url, self.week)


class GoldenItem(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    id: str
    q: str = Field(min_length=1)
    lang: Literal["zh-TW", "en"]
    kind: Literal["answerable", "no-answer", "adversarial"]
    pair: str | None = None
    labels: tuple[Label, ...] = ()

    @property
    def script(self) -> Script:
        return script_of(self.q)

    @property
    def gains(self) -> dict[RowKey, int]:
        return {label.key: label.grade for label in self.labels}

    @model_validator(mode="after")
    def _labels_match_kind(self) -> Self:
        if self.kind == "answerable" and not self.labels:
            raise ValueError(f"{self.id}: an answerable item needs at least one label")
        if self.kind == "no-answer" and self.labels:
            raise ValueError(f"{self.id}: a no-answer item cannot carry labels")
        if len({label.key for label in self.labels}) != len(self.labels):
            raise ValueError(f"{self.id}: a (url, week) is labelled twice")
        return self


def validate_set(items: list[GoldenItem]) -> None:
    """Cross-item rules: unique ids; zh-TW/en pairs point at each other and share labels."""
    dupes = [i for i, n in Counter(item.id for item in items).items() if n > 1]
    if dupes:
        raise ValueError(f"duplicate golden ids: {dupes}")
    by_id = {item.id: item for item in items}
    for item in items:
        if item.pair is None:
            continue
        twin = by_id.get(item.pair)
        if twin is None or twin.pair != item.id:
            raise ValueError(f"{item.id}: pair {item.pair!r} does not point back")
        if twin.lang == item.lang:
            raise ValueError(f"{item.id}: a pair must cross languages")
        if twin.gains != item.gains:
            raise ValueError(f"{item.id}: pair {twin.id} has different labels")


def load(path: Path) -> list[GoldenItem]:
    items = [
        GoldenItem.model_validate_json(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    validate_set(items)
    return items


def rotten_labels(items: list[GoldenItem], present: set[RowKey]) -> list[tuple[str, RowKey]]:
    """Labels on a (url, week) no fixture holds: no retrieval can ever hit them."""
    return [
        (item.id, label.key) for item in items for label in item.labels if label.key not in present
    ]
