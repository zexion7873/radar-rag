"""Validated shapes of everything the harness reads from outside: service responses and fixtures."""

import unicodedata
from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

# The (url, week) pair a relevance label and a retrieved row are matched on.
type RowKey = tuple[str, str]


class HitMetadata(BaseModel):
    """Per-row metadata; extra keys (category, stars_per_week, ...) pass through."""

    model_config = ConfigDict(extra="allow", frozen=True)

    source: str
    repo: str | None = None
    url: str | None = None
    week: str | None = None


class SearchHit(BaseModel):
    model_config = ConfigDict(frozen=True)

    id: str
    text: str
    metadata: HitMetadata
    score: float

    @property
    def key(self) -> RowKey | None:
        if self.metadata.url is None or self.metadata.week is None:
            return None
        return (self.metadata.url, self.metadata.week)


class SyncResponse(BaseModel):
    ingested: int
    sources: dict[str, int]
    failed: dict[str, str]


class Source(BaseModel):
    """A row /ask retrieved and sent to the model."""

    model_config = ConfigDict(frozen=True)

    id: str
    source: str | None = None
    repo: str | None = None
    title: str | None = None
    url: str | None = None
    week: str | None = None
    score: float | None = None

    @property
    def key(self) -> RowKey | None:
        if self.url is None or self.week is None:
            return None
        return (self.url, self.week)


class Citation(Source):
    """A retrieved row the answer cites, with the passages cited from it."""

    model_config = ConfigDict(frozen=True, populate_by_name=True)

    cited_text: list[str] = Field(alias="citedText")


class AskUsage(BaseModel):
    """Output tokens include thinking."""

    model_config = ConfigDict(frozen=True, populate_by_name=True)

    model: str
    input_tokens: int = Field(alias="inputTokens")
    output_tokens: int = Field(alias="outputTokens")


class AskResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    answer: str
    citations: list[Citation]
    sources: list[Source]
    usage: AskUsage


@dataclass(frozen=True)
class SourceSpec:
    """How IngestService reads one Notion table: the columns it parses, its key and its text."""

    data_source_id: str
    properties: tuple[str, ...]
    url: str
    # The first date present is the row's week, cut to the day.
    dates: tuple[str, ...]
    text: tuple[str, ...]


SOURCES = {
    "trending": SourceSpec(
        data_source_id="f67aaa24-d5f2-415c-9358-c7d9d2f9713e",
        properties=(
            "Repo",
            "Week",
            "Stars/wk",
            "Language",
            "Category",
            "Link",
            "Description",
            "Comment",
        ),
        url="Link",
        dates=("Week",),
        text=("Repo", "Description", "Comment"),
    ),
    "blog": SourceSpec(
        data_source_id="d8e442b5-17c1-4e6f-a665-feb49d6e3099",
        properties=("Title", "URL", "Type", "Published", "Archived", "Brief", "Comment"),
        url="URL",
        dates=("Published", "Archived"),
        text=("Title", "Brief", "Comment"),
    ),
}


class FrozenPage(BaseModel):
    """One Notion page, stripped to its id and the properties the service's parser reads."""

    model_config = ConfigDict(frozen=True)

    id: str
    properties: dict[str, dict[str, Any]]

    def _prop(self, name: str) -> dict[str, Any]:
        return self.properties.get(name, {})

    def plain_text(self, name: str) -> str:
        prop = self._prop(name)
        runs = prop.get(prop.get("type", ""), [])
        return "".join(run.get("plain_text", "") for run in runs) if isinstance(runs, list) else ""

    def key(self, spec: SourceSpec) -> RowKey | None:
        url = self._prop(spec.url).get("url")
        starts = ((self._prop(d).get("date") or {}).get("start") for d in spec.dates)
        week = next((w for w in starts if isinstance(w, str)), None)
        if not isinstance(url, str) or week is None:
            return None
        return (url, week[:10])

    def embeddable(self, spec: SourceSpec) -> bool:
        """Mirrors IngestService, which skips a row whose text columns are all blank."""
        text = "".join(self.plain_text(p) for p in spec.text)
        return not all(_java_whitespace(c) for c in text)


_JAVA_NON_BREAKING = {"\u00a0", "\u2007", "\u202f"}


def _java_whitespace(c: str) -> bool:
    """Java's Character.isWhitespace; unlike str.strip, it keeps no-break spaces and U+0085."""
    if c in "\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f":
        return True
    return unicodedata.category(c) in ("Zs", "Zl", "Zp") and c not in _JAVA_NON_BREAKING


class NotionFixture(BaseModel):
    # trending.json predates the field; the default keeps it, and baselines stamped with its hash,
    # valid as is.
    source: str = "trending"
    data_source_id: str
    captured_at: str
    pages: list[FrozenPage]

    @property
    def spec(self) -> SourceSpec:
        return SOURCES[self.source]

    def keys(self) -> set[RowKey]:
        return {k for p in self.pages if (k := p.key(self.spec)) is not None}

    def embeddable_count(self) -> int:
        return sum(1 for p in self.pages if p.embeddable(self.spec))
