import pytest

from radar_evals.models import SOURCES, FrozenPage


def _page(repo: str) -> FrozenPage:
    return FrozenPage(
        id="p", properties={"Repo": {"type": "title", "title": [{"plain_text": repo}]}}
    )


# Java's String.strip().isBlank() uses Character.isWhitespace: no-break spaces and U+0085 are
# not whitespace there, so IngestService embeds a row holding only them.
@pytest.mark.parametrize(
    ("repo", "embeddable"),
    [
        ("a/one", True),
        ("", False),
        (" \t\n", False),
        ("\u3000", False),
        ("\u00a0", True),
        ("\u202f", True),
        ("\u0085", True),
    ],
)
def test_embeddable_matches_java_blankness(repo: str, embeddable: bool) -> None:
    assert _page(repo).embeddable(SOURCES["trending"]) is embeddable


def _blog(**props: dict[str, object]) -> FrozenPage:
    return FrozenPage(
        id="b", properties={"URL": {"type": "url", "url": "https://x.test/p"}} | props
    )


def test_a_blog_row_is_keyed_by_published_cut_to_the_day() -> None:
    page = _blog(Published={"type": "date", "date": {"start": "2026-09-30T08:00:00.000+08:00"}})
    assert page.key(SOURCES["blog"]) == ("https://x.test/p", "2026-09-30")


def test_a_blog_row_without_published_falls_back_to_archived() -> None:
    page = _blog(
        Published={"type": "date", "date": None},
        Archived={"type": "date", "date": {"start": "2026-10-02"}},
    )
    assert page.key(SOURCES["blog"]) == ("https://x.test/p", "2026-10-02")


def test_a_blog_row_embeds_title_brief_and_comment_only() -> None:
    summary = _blog(Summary={"type": "rich_text", "rich_text": [{"plain_text": "long text"}]})
    titled = _blog(Title={"type": "title", "title": [{"plain_text": "A post"}]})
    assert not summary.embeddable(SOURCES["blog"])
    assert titled.embeddable(SOURCES["blog"])
