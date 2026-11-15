"""English Wikipedia's Billboard lists.

* 1959 onward: "Billboard Year-End Hot 100 singles of YYYY" — top 25.
* 1946–1958: the earlier year-end articles, whose titles vary ("Billboard
  year-end top 30 singles of 1950", "… top 50 singles of 1956"); the title is
  found with the search API — top 25.
* For the chart years 1950–1990 (``WHOLE_YEARS``) the year-end list is taken
  whole: the top 25 as above, every later row marked ``extended``.
* 1940–1945: "List of Billboard number-one singles of YYYY" — every distinct
  number one, positioned by order of first appearance.

Parsers are pure functions of the rendered page HTML (``prop=text``).
"""

from __future__ import annotations

import re

from bs4 import BeautifulSoup, Tag

from . import http
from .keys import normalize
from .wikitext import strip_quotes

YEAR_END_SOURCE = "billboard-year-end"
NUMBER_ONES_SOURCE = "billboard-number-ones"
YEAR_END_LIMIT = 25
HOT100_FIRST_YEAR = 1959
YEAR_END_FIRST_YEAR = 1946
NUMBER_ONES_YEARS = range(1940, 1946)
WHOLE_YEARS = range(1950, 1991)  # year-end lists taken whole (stage il-3000 C)


def _text(cell: Tag) -> str:
    for sup in cell.select("sup"):
        sup.decompose()
    text = cell.get_text(" ", strip=True)
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"\s+([,.!?)])", r"\1", text)
    text = re.sub(r"\(\s+", "(", text)
    return text.strip()


def _title_text(cell: Tag) -> str:
    """A title cell's text, joined without separators so quotes hug the title."""
    for sup in cell.select("sup"):
        sup.decompose()
    return re.sub(r"\s+", " ", cell.get_text("")).strip()


def _title(text: str) -> str:
    """'"Song"' → 'Song'; '"A" / "B"' → 'A / B'; inner quotes stay balanced."""
    text = re.sub(r"\[\s*(?:\d+|[a-z]|note \d+)\s*\]", "", text).strip()
    text = re.sub(r'"\s*/\s*"', " / ", text)  # double A-sides
    if text.startswith('"'):
        end = text.rfind('"')
        if end > 0:
            rest = text[end + 1 :].strip()
            return (text[1:end].strip() + (" " + rest if rest else "")).strip()
    return strip_quotes(text)


def table_grid(table: Tag) -> list[list[Tag]]:
    """Rows of cells with rowspan/colspan expanded (a cell repeats where it spans)."""
    grid: list[list[Tag]] = []
    pending: dict[int, tuple[Tag, int]] = {}  # column → (cell, rows left)
    for tr in table.find_all("tr"):
        row: list[Tag] = []

        def fill_spanned():
            while len(row) in pending:
                col = len(row)
                cell, left = pending[col]
                row.append(cell)
                if left <= 1:
                    del pending[col]
                else:
                    pending[col] = (cell, left - 1)

        for cell in tr.find_all(["td", "th"], recursive=False):
            fill_spanned()
            colspan = int(re.sub(r"\D", "", str(cell.get("colspan", "1"))) or 1)
            rowspan = int(re.sub(r"\D", "", str(cell.get("rowspan", "1"))) or 1)
            for _ in range(colspan):
                if rowspan > 1:
                    pending[len(row)] = (cell, rowspan - 1)
                row.append(cell)
        fill_spanned()
        grid.append(row)
    return grid


def _find_column(header: list[str], *names: str) -> int | None:
    for i, text in enumerate(header):
        low = text.lower()
        if any(name in low for name in names):
            return i
    return None


def _chart_tables(html: str):
    soup = BeautifulSoup(html, "html.parser")
    for table in soup.select("table.wikitable"):
        grid = table_grid(table)
        if grid:
            yield grid


def parse_year_end(
    html: str, year: int, limit: int | None = YEAR_END_LIMIT, source: str = YEAR_END_SOURCE
) -> list[dict]:
    """A Billboard year-end page → its top ``limit`` rows (``None``: every row)."""
    for grid in _chart_tables(html):
        header = [_text(c) for c in grid[0]]
        pos_col = _find_column(header, "no", "№", "rank", "#", "pos")
        title_col = _find_column(header, "title", "song")
        artist_col = _find_column(header, "artist")
        if None in (pos_col, title_col, artist_col):
            continue
        rows: list[dict] = []
        for cells in grid[1:]:
            if len(cells) <= max(pos_col, title_col, artist_col):
                continue
            m = re.match(r"\s*(\d+)", _text(cells[pos_col]))
            if not m:
                continue
            title = _title(_title_text(cells[title_col]))
            artist = _text(cells[artist_col])
            if not title or not artist:
                continue
            rows.append(_row(title, artist, year, source, int(m.group(1))))
            if len(rows) == limit:
                break
        if rows:
            return rows
    return []


def year_end_rows(html: str, year: int) -> list[dict]:
    """A year-end page's rows: the whole list in ``WHOLE_YEARS``, else the top 25.

    The first ``YEAR_END_LIMIT`` rows are the catalog's original rows; the rest
    of a whole list are marked ``extended``.
    """
    rows = parse_year_end(html, year, limit=None if year in WHOLE_YEARS else YEAR_END_LIMIT)
    for i, row in enumerate(rows):
        row["extended"] = i >= YEAR_END_LIMIT
    return rows


def parse_number_ones(html: str, year: int) -> list[dict]:
    """A "number-one singles of YYYY" page → distinct number ones, first-appearance order.

    1940–1943 tables have Song and Artist columns; 1944–1945 tables have one
    column per chart with '"Song" Artist' in each cell — the first (best-selling
    retail records) chart is used.
    """
    for grid in _chart_tables(html):
        header = [_text(c) for c in grid[0]]
        song_col = _find_column(header, "song", "title")
        artist_col = _find_column(header, "artist")
        combined_col = None
        if song_col is None or artist_col is None:
            combined_col = _find_column(header, "retail", "best-selling", "best selling")
            if combined_col is None:
                continue
        rows: list[dict] = []
        seen: set[str] = set()
        for cells in grid[1:]:
            if combined_col is not None:
                if len(cells) <= combined_col:
                    continue
                text = _text(cells[combined_col])
                m = re.match(r'^\s*"\s*(.+?)\s*"\s*(.+)$', text)
                if not m:
                    continue
                title, artist = m.group(1), m.group(2)
            else:
                if len(cells) <= max(song_col, artist_col):
                    continue
                title = _title(_title_text(cells[song_col]))
                artist = _text(cells[artist_col])
            if not title or not artist or normalize(title) in seen:
                continue
            seen.add(normalize(title))
            rows.append(_row(title, artist, year, NUMBER_ONES_SOURCE, len(rows) + 1))
        if rows:
            return rows
    return []


def _row(title: str, artist: str, year: int, source: str, position: int) -> dict:
    return {
        "title": title,
        "artist": artist,
        "chart_year": year,
        "origin": "INTL",
        "source": source,
        "position": position,
    }


_YEAR_END_TITLE_RE = r"^Billboard year-end top(?: \d+)? singles of {year}$"


def pick_year_end_title(titles: list[str], year: int) -> str | None:
    """The pop year-end article among search results (not R&B, country, …)."""
    pattern = re.compile(_YEAR_END_TITLE_RE.format(year=year), re.I)
    for title in titles:
        if pattern.match(title):
            return title
    return None


# --- Fetching ----------------------------------------------------------------------------


def year_end_title(year: int) -> str | None:
    if year >= HOT100_FIRST_YEAR:
        return f"Billboard Year-End Hot 100 singles of {year}"
    titles = http.search_titles(http.EN_API, f"Billboard year-end singles {year}")
    return pick_year_end_title(titles, year)


def collect(last_year: int) -> tuple[list[dict], list[str]]:
    rows: list[dict] = []
    pages: list[str] = []
    for year in NUMBER_ONES_YEARS:
        title = f"List of Billboard number-one singles of {year}"
        page = http.parse_page(http.EN_API, title, prop="text")
        pages.append(page["title"])
        rows += parse_number_ones(page["text"], year)
    for year in range(YEAR_END_FIRST_YEAR, last_year + 1):
        title = year_end_title(year)
        if title is None:
            continue
        try:
            page = http.parse_page(http.EN_API, title, prop="text")
        except http.PageMissing:
            continue
        pages.append(page["title"])
        rows += year_end_rows(page["text"], year)
    return rows, pages
