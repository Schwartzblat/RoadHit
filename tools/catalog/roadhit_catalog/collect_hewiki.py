"""Hebrew Wikipedia's annual charts.

* "מצעד הפזמונים העברי השנתי" — the annual Hebrew song chart, one sub-article
  per Hebrew-calendar decade, one section per Hebrew year, usually two or more
  charts per year (Kol Israel / Reshet Gimel / Kan Gimel and Galei Tzahal /
  Galgalatz, plus, in recent years, many other stations' charts).
* "מצעד הפזמונים הבין-לאומי השנתי" — the annual international chart heard on
  Israeli radio, one sub-article per Gregorian decade.

Parsers are pure functions of the page wikitext; :func:`collect_il` and
:func:`collect_intl` do the fetching (through :mod:`roadhit_catalog.http`).
"""

from __future__ import annotations

import csv
import re
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path

from rapidfuzz import fuzz

from . import http
from .candidates import CHART_SOURCE
from .keys import normalize
from .wikitext import (
    clean,
    is_quoted,
    split_dash,
    split_quoted,
    strip_quotes,
    strip_trailing_parens,
)

IL_MAIN = "מצעד הפזמונים העברי השנתי"
INTL_MAIN = "מצעד הפזמונים הבין-לאומי השנתי"
IL_SOURCE = CHART_SOURCE
INTL_SOURCE = "hewiki-intl"
IL_LIMIT = 20
INTL_LIMIT = 25
MIN_CHART_SONGS = 10  # shorter lists are award categories / partial teasers


# --- Hebrew calendar -------------------------------------------------------------

_LETTER_VALUES = {
    "א": 1, "ב": 2, "ג": 3, "ד": 4, "ה": 5, "ו": 6, "ז": 7, "ח": 8, "ט": 9,
    "י": 10, "כ": 20, "ך": 20, "ל": 30, "מ": 40, "ם": 40, "נ": 50, "ן": 50,
    "ס": 60, "ע": 70, "פ": 80, "ף": 80, "צ": 90, "ץ": 90,
    "ק": 100, "ר": 200, "ש": 300, "ת": 400,
}  # fmt: skip


def hebrew_year_to_gregorian(text: str) -> int:
    """The Gregorian year in which a Hebrew year ends: תשכ"ג (5723) → 1963.

    A Hebrew year starts in September/October, so it ends in Gregorian year
    (Hebrew year − 3760).
    """
    text = text.strip()
    if text[:2] in ("ה'", "ה׳"):
        text = text[2:]
    letters = [ch for ch in text if ch in _LETTER_VALUES]
    if not letters:
        raise ValueError(f"not a Hebrew year: {text!r}")
    value = sum(_LETTER_VALUES[ch] for ch in letters)
    if value < 1000:
        value += 5000
    return value - 3760


# --- Discovering the decade sub-articles -------------------------------------------


def decade_pages(links: list[dict], main_title: str) -> list[str]:
    """Sub-article titles ("<main> (…)") linked from the main page, in order."""
    prefix = main_title + " ("
    seen: list[str] = []
    for link in links:
        title = link.get("title", "")
        if link.get("ns", 0) == 0 and link.get("exists", True) and title.startswith(prefix):
            if title not in seen:
                seen.append(title)
    return sorted(seen, key=_decade_sort_key)


def _decade_sort_key(title: str) -> int:
    inner = title[title.index("(") + 1 :]
    m = re.match(r"\s*(\d{4})", inner)
    if m:
        return int(m.group(1))
    first = re.split(r"[–\-\s]", inner.strip())[0]
    try:
        return hebrew_year_to_gregorian(first)
    except ValueError:
        return 10_000


# --- Page structure ------------------------------------------------------------------

_HEADING_RE = re.compile(r"^(=+)\s*(.*?)\s*=+\s*$")
_IL_YEAR_RE = re.compile(r"^(?:ה['׳])?(ת[א-ת\"'״׳]+)\s*[-–]\s*(\d{4})$")
_INTL_YEAR_RE = re.compile(r"^(\d{4})$")
_ITEM_RE = re.compile(
    r"^\s*(?:\}\}\s*)?(?:\{\{[^{}|]*טורים\s*\|\s*)?(?:\|?\s*תוכן\s*=\s*)?([*#]+)\s*(.*)$"
)
_NUMBER_RE = re.compile(r"^(\d{1,3})\s*(?:\.\s*[א-ת]\s*\.|[א-ת]?\s*\.)\s*")  # "7.", "7א.", "25.א."
_NEUTRAL_RE = re.compile(
    r"^\s*(\{\|.*|\|-.*|\|\s*valign.*|\|\s*width.*|\{\{[^{}|]*טורים\s*\|?\s*"
    r"|\|\s*רוחב\s*=.*|\|\s*עמודות\s*=.*|\|?\s*תוכן\s*=\s*)?\s*$"
)
_CLOSE_RE = re.compile(r"^\s*(\}\}|\|\})\s*$")


@dataclass(frozen=True)
class Entry:
    """One parsed chart line."""

    position: int
    title: str
    artist: str  # the act (role text such as "סולן: …" removed)
    credit: str  # the full credit as the chart gives it
    title_link: str | None = None  # Hebrew Wikipedia article the title links to
    artist_link: str | None = None  # article of the first-credited act, if linked


@dataclass
class Chart:
    """One ranked list on a page, with the text that introduces it."""

    year_label: str
    label: list[str] = field(default_factory=list)
    raw: list[tuple[int, str]] = field(default_factory=list)  # (position, item wikitext)

    @property
    def entries(self) -> list[Entry]:
        """Every parseable line.

        Lines without quotes (and lines quoted on both sides) follow the order
        the chart's other quoted lines use (one page section writes
        'artist – "title"', most write '"title" – artist').
        """
        texts = [(position, content, clean(content)) for position, content in self.raw]
        sides = [quoted_side(text) for _, _, text in texts]
        title_side = "right" if sides.count("right") > sides.count("left") else "left"
        out = []
        for position, content, text in texts:
            parsed = parse_item(text, title_side)
            if parsed:
                title, credit = parsed
                artist = split_role(credit)
                title_link, artist_link = find_links(content, title, artist)
                out.append(Entry(position, title, artist, credit, title_link, artist_link))
        return out


_ROLE_RE = re.compile(r"(?:\s*,\s*|\s+)(?:ה)?סול(?:ן|נית|נים|ניות)\s*:.*$")


def split_role(credit: str) -> str:
    """'להקת הנח"ל, סולן: מוטי פליישר' → 'להקת הנח"ל' (the act before the role text)."""
    act = _ROLE_RE.sub("", credit).strip(" ,;:")
    return act or credit


_RAW_LINK_RE = re.compile(
    r"\[\[(?!(?:קובץ|File|Image|תמונה|קטגוריה|Category):)([^\[\]|#]*)(?:#[^\[\]|]*)?(?:\|([^\[\]]*))?\]\]"
)


def find_links(content: str, title: str, artist: str) -> tuple[str | None, str | None]:
    """The articles linked from a line's title and from its first-credited act."""
    title_key, artist_key = normalize(title), normalize(artist)
    title_link = artist_link = None
    for m in _RAW_LINK_RE.finditer(content):
        target = m.group(1).strip()
        shown = normalize(strip_quotes(clean(m.group(2) if m.group(2) is not None else target)))
        if not target or not shown:
            continue
        if title_link is None and shown == title_key:
            title_link = target
        elif artist_link is None and artist_key.startswith(shown) and (
            len(artist_key) == len(shown) or artist_key[len(shown)] == " "
        ):
            artist_link = target
    return title_link, artist_link


_TRAILING_NUMBER_RE = re.compile(r"\s\.(\d{1,3})\s*$")  # RTL typo: "artist – title .16"


def parse_charts(wikitext: str, year_re: re.Pattern) -> list[Chart]:
    """All ranked lists inside year sections, in page order."""
    charts: list[Chart] = []
    year: str | None = None
    year_level = 0
    label: list[str] = []
    current: Chart | None = None

    def close():
        nonlocal current
        if current is not None:
            charts.append(current)
            current = None

    for line in wikitext.split("\n"):
        heading = _HEADING_RE.match(line)
        if heading:
            close()
            label = []
            level, text = len(heading.group(1)), clean(heading.group(2))
            m = year_re.match(text)
            if m:
                year, year_level = text, level
            elif year is not None and level > year_level and "עשור" not in text:
                pass  # a sub-heading inside a year section
            else:
                year = None
            continue
        if year is None:
            continue
        item = _ITEM_RE.match(line)
        if item and item.group(2).strip():
            if current is None:
                current = Chart(year_label=year, label=label)
                label = []
            content = item.group(2)
            number = _NUMBER_RE.match(content) or _TRAILING_NUMBER_RE.search(content)
            if number:
                position = int(number.group(1))
                content = content[: number.start()] + content[number.end() :]
            else:
                position = len(current.raw) + 1
            current.raw.append((position, content))
            continue
        if _CLOSE_RE.match(line):
            close()
            continue
        if _NEUTRAL_RE.match(line):
            continue
        close()
        label.append(line)
    close()
    return charts


def quoted_side(text: str) -> str | None:
    """Which side of "x – y" holds the quoted title: 'left', 'right', 'both' or None."""
    quoted = split_quoted(text) if is_quoted(text) else None
    if quoted:
        rest = quoted[1].lstrip(" –—-")
        other = split_quoted(rest) if is_quoted(rest) else None
        if other and not other[1]:
            return "both"  # '"artist" – "title"': decided by the chart's order
        return "left"
    parts = split_dash(text)
    if parts and split_quoted(parts[1]) and not is_quoted(parts[0]):
        return "right"
    if parts and is_quoted(parts[1]) and not split_quoted(parts[0]) and not split_quoted(parts[1]):
        return "right"  # dangling quotes ('"Bomfunk Mc's – "Freestyler'): the opened side is the title
    return None


def parse_item(text: str, title_side: str = "left") -> tuple[str, str] | None:
    """One cleaned chart line (no bullet/number) → (title, artist).

    The quoted side is the title; an unquoted line puts its title on
    ``title_side`` of the dash.
    """
    if not text:
        return None
    title = artist = None
    side = quoted_side(text)
    if side == "both":
        side = title_side
    if side == "left" and split_quoted(text):
        title, rest = split_quoted(text)
        rest = re.sub(r"^\s*(\([^()]*\))?\s*[–—-]?\s*", "", rest)  # "(cover version) –"
        artist = rest
    else:
        parts = split_dash(text)
        if not parts:
            return None
        left, right = parts
        if side == "right":
            title = split_quoted(right)[0] if split_quoted(right) else strip_quotes(right)
            artist = strip_quotes(left)
        elif title_side == "right":
            title, artist = right, left
        else:
            title, artist = left, right
    artist = artist or ""
    if artist.startswith("("):  # typo'd credits: '"title"- (artist ((lyricist/(composer)'
        artist = artist[1:].split("(")[0]
    artist = strip_quotes(strip_trailing_parens(artist).strip(" ,;:–-"))
    title = strip_quotes(title or "").strip(" ,;:")
    if not title or not artist:
        return None
    return title, artist


# --- Choosing charts and taking the top of each year -------------------------------------

_MAIN_STATIONS = [
    r"קול ישראל",
    r"רשת ג['׳]",
    r"רשת גימל",
    r"כאן ג['׳]",
    r"כאן גימל",
    r"גלי צה[\"״]ל",
    r"גל[\"״]צ",
    r"גלגלצ",
]
_OTHER_CHARTS = [
    r"מאקו", r"היטליסט", r"מורשת", r"מדיה פור", r"וואלה", r"ynet", r"רדיו תל אביב",
    r"רדיו דרום", r"גלי ישראל", r"ישראל היום", r"קול רגע", r"קול גת", r"רמת השרון",
    r"סרוגים", r"ישראל בידור", r"ישראלה", r"קריוקי", r"אייס", r"אקו", r"eco",
    r"טיקטוק", r"מושמע", r"השמעות", r"השמעה", r"סטרימינג", r"עשור", r"ערוץ 24",
]  # fmt: skip
_STATION_RE = re.compile(
    "|".join(f"(?P<main{i}>{p})" for i, p in enumerate(_MAIN_STATIONS))
    + "|"
    + "|".join(f"(?P<other{i}>{p})" for i, p in enumerate(_OTHER_CHARTS))
)


def _first_station(text: str) -> bool | None:
    m = _STATION_RE.search(text)
    if not m:
        return None
    return m.lastgroup.startswith("main")


def is_main_chart(label: str) -> bool:
    """Is the chart introduced by ``label`` Reshet Gimel's or Galgalatz's?

    The chart's own title line (bold ''' … ''' or ; … ) decides when it names
    a station; otherwise the first station named anywhere in the introduction
    does. Charts whose introduction names no station at all are the single
    chart of an early year and are kept.
    """
    lines = [l for l in label.split("\n") if l.strip()]
    titles = [l for l in lines if re.match(r"^\s*(\}\}\s*)?('''|;)", l)]
    for title_line in reversed(titles[-1:]):
        verdict = _first_station(clean(title_line))
        if verdict is not None:
            return verdict
    verdict = _first_station(clean(" ".join(lines)))
    return True if verdict is None else verdict


ARTIST_AGREEMENT = 80  # rapidfuzz token_set_ratio on normalised credits


def _credit_tokens(artist: str) -> str:
    """Normalised credit with the Hebrew conjunction ו dropped from each word,
    so 'נתן גושן וישי ריבו' and 'ישי ריבו ונתן גושן' compare equal."""
    return " ".join(t[1:] if len(t) > 2 and t[0] == "ו" else t for t in normalize(artist).split())


def same_song(title_a: str, artist_a: str, title_b: str, artist_b: str) -> bool:
    """Same normalised title and agreeing artists (credit order may differ)."""
    if normalize(title_a) != normalize(title_b):
        return False
    return fuzz.token_set_ratio(_credit_tokens(artist_a), _credit_tokens(artist_b)) >= ARTIST_AGREEMENT


def union_top(charts: list[list[Entry]], limit: int | None, already: Sequence[Entry] = ()) -> list[Entry]:
    """Distinct songs (title + artist) from several charts of one year, best positions first.

    ``limit`` None takes them all; songs in ``already`` are left out.
    """
    ranked = sorted(
        ((entry.position, chart_index, order), entry)
        for chart_index, chart in enumerate(charts)
        for order, entry in enumerate(chart)
    )
    top: list[Entry] = []
    for _, entry in ranked:
        if any(same_song(entry.title, entry.artist, kept.title, kept.artist) for kept in [*already, *top]):
            continue
        top.append(entry)
        if len(top) == limit:
            break
    return top


def _rows(year: int, entries: list[Entry], origin_of, source: str) -> list[dict]:
    return [
        {
            "title": e.title,
            "artist": e.artist,
            "credit": e.credit,
            "chart_year": year,
            "origin": origin_of(e, year),
            "source": source,
            "position": e.position,
            "title_link": e.title_link,
            "artist_link": e.artist_link,
        }
        for e in entries
    ]


def _by_year(charts: list[Chart]) -> dict[str, list[Chart]]:
    grouped: dict[str, list[Chart]] = {}
    for chart in charts:
        grouped.setdefault(chart.year_label, []).append(chart)
    return grouped


def parse_il_page(wikitext: str, core: int = IL_LIMIT) -> list[dict]:
    """A Hebrew-chart decade page → rows: every song of every chart of a Hebrew year.

    Only charts of at least ``MIN_CHART_SONGS`` songs count. First the union of
    the year's main charts (Reshet Gimel / Galgalatz), best positions first, then
    the songs of the other stations' charts that are not already there. The first
    ``core`` rows of a year (the top of the main charts) are the catalog's
    original chart rows; the rest are marked ``extended``.
    """
    rows: list[dict] = []
    for year_label, charts in _by_year(parse_charts(wikitext, _IL_YEAR_RE)).items():
        hebrew_year = _IL_YEAR_RE.match(year_label).group(1)
        year = hebrew_year_to_gregorian(hebrew_year)
        full = [c for c in charts if len(c.entries) >= MIN_CHART_SONGS]
        main = union_top([c.entries for c in full if is_main_chart("\n".join(c.label))], None)
        other = union_top([c.entries for c in full if not is_main_chart("\n".join(c.label))], None, main)
        year_rows = _rows(year, main + other, lambda _e, _y: "IL", IL_SOURCE)
        for i, row in enumerate(year_rows):
            row["extended"] = i >= core
        rows += year_rows
    return rows


MIXED_CHART_YEARS = range(1962, 1969)
IL_FROM_MIXED_CSV = Path(__file__).resolve().parent.parent / "data" / "il_from_mixed_charts.csv"


def load_il_from_mixed(path: Path = IL_FROM_MIXED_CSV) -> set[tuple[str, str, int]]:
    """Hand-reviewed Israeli songs on the 1962–1968 mixed charts: (title, artist, year) keys."""
    with Path(path).open(encoding="utf-8", newline="") as f:
        return {
            (normalize(r["title"]), normalize(r["artist"]), int(r["chart_year"]))
            for r in csv.DictReader(f)
        }


WHOLE_INTL_YEARS = range(1969, 1991)  # international charts taken whole (stage il-3000 C)


def parse_intl_page(
    wikitext: str,
    limit: int = INTL_LIMIT,
    il_from_mixed: set[tuple[str, str, int]] = frozenset(),
    whole_years: range = WHOLE_INTL_YEARS,
) -> list[dict]:
    """An international-chart decade page → rows, top ``limit`` per year.

    Every row is INTL, except a song on the 1962–1968 mixed Hebrew/foreign
    charts that is listed in ``il_from_mixed`` (sung in Hebrew by Israelis).
    In ``whole_years`` every song of every chart of at least ``MIN_CHART_SONGS``
    songs is taken: first today's top ``limit`` (the union of the year's charts),
    then the main charts' other songs, then the other stations' songs not
    already there; rows past the top ``limit`` are marked ``extended``.
    """

    def origin(entry: Entry, year: int) -> str:
        key = (normalize(entry.title), normalize(entry.artist), year)
        return "IL" if year in MIXED_CHART_YEARS and key in il_from_mixed else "INTL"

    rows: list[dict] = []
    for year_label, charts in _by_year(parse_charts(wikitext, _INTL_YEAR_RE)).items():
        year = int(year_label)
        full = [c for c in charts if len(c.entries) >= MIN_CHART_SONGS]
        top = union_top([c.entries for c in full], limit)
        rest: list[Entry] = []
        if year in whole_years:
            main = [c.entries for c in full if is_main_chart("\n".join(c.label))]
            other = [c.entries for c in full if not is_main_chart("\n".join(c.label))]
            rest = union_top(main, None, top)
            rest += union_top(other, None, top + rest)
        year_rows = _rows(year, top + rest, origin, INTL_SOURCE)
        for i, row in enumerate(year_rows):
            row["extended"] = i >= len(top)
        rows += year_rows
    return rows


# --- Fetching ----------------------------------------------------------------------------


def _collect(main_title: str, parser) -> tuple[list[dict], list[str]]:
    links = http.parse_page(http.HE_API, main_title, prop="links")["links"]
    pages = decade_pages(links, main_title)
    rows: list[dict] = []
    resolved: list[str] = []
    for title in pages:
        page = http.parse_page(http.HE_API, title, prop="wikitext")
        if page["title"] in resolved:  # two links redirecting to one page
            continue
        resolved.append(page["title"])
        rows += parser(page["wikitext"])
    return rows, resolved


def collect_il() -> tuple[list[dict], list[str]]:
    return _collect(IL_MAIN, parse_il_page)


def collect_intl() -> tuple[list[dict], list[str]]:
    il_from_mixed = load_il_from_mixed()
    return _collect(INTL_MAIN, lambda w: parse_intl_page(w, il_from_mixed=il_from_mixed))
