"""Validated shapes of everything the harness reads from outside: service responses and fixtures."""

import unicodedata
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


class Citation(BaseModel):
    model_config = ConfigDict(frozen=True)

    id: str
    repo: str | None = None
    url: str | None = None
    week: str | None = None
    score: float | None = None


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
    usage: AskUsage


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

    @property
    def key(self) -> RowKey | None:
        url = self._prop("Link").get("url")
        date = self._prop("Week").get("date") or {}
        week = date.get("start")
        if not isinstance(url, str) or not isinstance(week, str):
            return None
        return (url, week)

    @property
    def embeddable(self) -> bool:
        """Mirrors TrendingIngestService, which skips a row with no repo, description or comment."""
        text = "".join(self.plain_text(p) for p in ("Repo", "Description", "Comment"))
        return not all(_java_whitespace(c) for c in text)


_JAVA_NON_BREAKING = {"\u00a0", "\u2007", "\u202f"}


def _java_whitespace(c: str) -> bool:
    """Java's Character.isWhitespace; unlike str.strip, it keeps no-break spaces and U+0085."""
    if c in "\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f":
        return True
    return unicodedata.category(c) in ("Zs", "Zl", "Zp") and c not in _JAVA_NON_BREAKING


class NotionFixture(BaseModel):
    data_source_id: str
    captured_at: str
    pages: list[FrozenPage]

    def keys(self) -> set[RowKey]:
        return {k for p in self.pages if (k := p.key) is not None}
