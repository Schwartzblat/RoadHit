"""The UK's year-end best-selling singles, from English Wikipedia's "YYYY in British music".

Those pages carry a year-end ranking ("Best-selling singles" under "Year-end
charts") from 1972 on: a table of 20 (1972) or 50 rows with No., Title, Artist
and Peak position columns. Before 1970 there was no recognised year-end list
(the pages say so), and 1970–1971 give plain numbered lists without a table,
one of them unreferenced: those years give no rows. The top ``LIMIT`` rows of
each table are taken, every one ``extended`` (a new source: an existing row of
the same song always wins).

The parser is a pure function of the rendered page HTML (``prop=text``).
"""

from __future__ import annotations

import re

from bs4 import BeautifulSoup

from . import collect_billboard, http

SOURCE = "uk-year-end"
LIMIT = 40
YEARS = range(1952, 1991)

_HEADING_RE = re.compile(r"best[- ]selling singles", re.I)
_MEDLEY_RE = re.compile(r'"\s*[–—-]\s*"')  # '"The Way We Were"–"Try to Remember"'


def _singles_table(html: str):
    """The first wikitable after the "Best-selling singles" heading, before the next heading."""
    soup = BeautifulSoup(html, "html.parser")
    for heading in soup.find_all(["h2", "h3", "h4"]):
        if not _HEADING_RE.search(heading.get_text(" ", strip=True)):
            continue
        for node in heading.find_all_next(["h2", "h3", "h4", "table"]):
            if node.name != "table":
                break
            if "wikitable" in (node.get("class") or []):
                return node
    return None


def parse_year_end(html: str, year: int, limit: int = LIMIT) -> list[dict]:
    """A "YYYY in British music" page → the top ``limit`` best-selling singles (``[]`` if none)."""
    table = _singles_table(html)
    if table is None:
        return []
    for text in table.find_all(string=_MEDLEY_RE):  # a medley of two songs reads like a double A-side
        text.replace_with(_MEDLEY_RE.sub('" / "', text))
    rows = collect_billboard.parse_year_end(str(table), year, limit=limit, source=SOURCE)
    for row in rows:
        row["extended"] = True
    return rows


def collect() -> tuple[list[dict], list[str]]:
    rows: list[dict] = []
    pages: list[str] = []
    for year in YEARS:
        try:
            page = http.parse_page(http.EN_API, f"{year} in British music", prop="text")
        except http.PageMissing:
            continue
        pages.append(page["title"])
        rows += parse_year_end(page["text"], year)
    return rows, pages
