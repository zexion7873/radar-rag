import pytest

from radar_evals.models import FrozenPage


def _page(repo: str) -> FrozenPage:
    return FrozenPage(
        id="p", properties={"Repo": {"type": "title", "title": [{"plain_text": repo}]}}
    )


# Java's String.strip().isBlank() uses Character.isWhitespace: no-break spaces and U+0085 are
# not whitespace there, so TrendingIngestService embeds a row holding only them.
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
    assert _page(repo).embeddable is embeddable
