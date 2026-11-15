"""Latin-script title and artist for international rows.

Hebrew Wikipedia's international chart writes most artists (and some titles)
in Hebrew. When a chart cell links to an article, that article's English
Wikipedia title (via the MediaWiki ``langlinks`` API) gives the Latin form, so
the row can be matched with its Billboard twin and searched for on Deezer.
"""

from __future__ import annotations

import re

from . import http

_HEBREW_RE = re.compile(r"[א-ת]")
_LATIN_RE = re.compile(r"[A-Za-z]")
_DISAMBIGUATION_RE = re.compile(r"\s*\([^()]*\)\s*$")
_FIRST_ACT_RE = re.compile(
    r"\s+(?:featuring|feat\.?|ft\.?|with|and|&|vs\.?|duet with)\s+|\s*,\s*|\s*/\s*", re.I
)


def is_latin(text: str | None) -> bool:
    return bool(text) and bool(_LATIN_RE.search(text)) and not _HEBREW_RE.search(text)


def strip_disambiguation(title: str) -> str:
    """'Rihanna (singer)' → 'Rihanna'; 'Popcorn (Hot Butter song)' → 'Popcorn'."""
    return _DISAMBIGUATION_RE.sub("", title).strip()


def first_act(credit: str) -> str:
    """The first-credited act of a Latin credit ('X featuring Y' → 'X')."""
    return _FIRST_ACT_RE.split(credit.strip(), maxsplit=1)[0].strip() or credit.strip()


def parse_langlinks(response: dict) -> dict[str, str | None]:
    """A ``prop=langlinks&lllang=en`` response → {requested Hebrew title: English title}."""
    query = response.get("query", {})
    english: dict[str, str | None] = {}
    for page in query.get("pages", []):
        links = page.get("langlinks", [])
        english[page["title"]] = links[0].get("title") if links else None
    # Map the titles as requested (before normalisation / redirects) to their page.
    aliases: dict[str, str] = {}
    for step in ("normalized", "redirects"):
        for item in query.get(step, []):
            aliases[item["from"]] = item["to"]
    for source, target in list(aliases.items()):
        seen = set()
        while target in aliases and target not in seen:
            seen.add(target)
            target = aliases[target]
        english.setdefault(source, english.get(target))
    return english


def fetch_langlinks(titles: list[str]) -> dict[str, str | None]:
    """English titles for Hebrew Wikipedia articles, 50 per (cached) request."""
    unique = sorted({t for t in titles if t})
    english: dict[str, str | None] = {}
    for i in range(0, len(unique), 50):
        response = http.get_json(
            http.HE_API,
            {
                "action": "query",
                "prop": "langlinks",
                "lllang": "en",
                "lllimit": "max",
                "redirects": "1",
                "titles": "|".join(unique[i : i + 50]),
                "format": "json",
                "formatversion": "2",
            },
        )
        english.update(parse_langlinks(response))
    return english


def _from_link(target: str | None, english: dict[str, str | None]) -> str | None:
    if not target:
        return None
    resolved = english.get(target)
    if resolved:
        return strip_disambiguation(resolved)
    if is_latin(target):  # e.g. [[Shocking Blue|שוקינג בלו]]: the article title is Latin
        return strip_disambiguation(target)
    return None


def add_latin(rows: list[dict], english: dict[str, str | None]) -> list[dict]:
    """Give every row ``title_latin``, ``artist_latin`` and ``needs_latin``.

    IL rows get None/None/False. INTL rows use their own text when it is Latin,
    otherwise the English title of the linked article; a row whose title or
    first-credited act stays unresolved gets ``needs_latin: True``.
    """
    out = []
    for row in rows:
        row = dict(row)
        if row["origin"] != "INTL":
            row.update(title_latin=None, artist_latin=None, needs_latin=False)
        else:
            title_latin = row["title"] if is_latin(row["title"]) else _from_link(row.get("title_link"), english)
            if is_latin(row["artist"]):
                artist_latin = row["artist"]
            else:
                artist_latin = _from_link(row.get("artist_link"), english)
            row.update(
                title_latin=title_latin,
                artist_latin=artist_latin,
                needs_latin=title_latin is None or artist_latin is None,
            )
        out.append(row)
    return out
