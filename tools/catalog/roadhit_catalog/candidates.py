"""Merging chart rows from all sources into one candidate list."""

from __future__ import annotations

import csv
import re
from pathlib import Path

from rapidfuzz import fuzz

from .keys import normalize, song_key
from .latin import first_act

ROOT = Path(__file__).resolve().parent.parent
CLASSICS_CSV = ROOT / "data" / "classics_il.csv"
CLASSICS_SOURCE = "classics"
EXTRAS_CSV = ROOT / "data" / "extras.csv"
EXTRAS_SOURCE = "extras"
CHART_SOURCE = "hewiki-il"  # the Hebrew annual charts (collect_hewiki.IL_SOURCE)
ALBUM_SOURCE = "hewiki-albums"  # tracks of Hebrew Wikipedia album pages (hewiki_albums)
FAMOUS_SOURCE = "famous-acts"  # tracks of the famous acts' albums (famous_acts)
ORIGINS = ("IL", "INTL")

FIRST_YEAR = 1940
LAST_YEAR = 2026
MERGE_YEAR_WINDOW = 3  # chart years of two rows of one song may differ by this much
ARTIST_AGREEMENT = 80  # rapidfuzz token_set_ratio on normalised first-credited acts

FIELDS = (
    "key", "title", "artist", "credit", "chart_year", "origin", "source", "position",
    "sources", "title_latin", "artist_latin", "needs_latin", "extended",
)  # fmt: skip
# Fields only album rows have: the album the track is on, and (famous-acts) its Deezer track.
OPTIONAL_FIELDS = ("album", "deezer_hint")
# Among extended rows of one song, an album-page row loses to a chart or song-article row and a
# famous-acts row to all of them (lower ranks win; chart and article rows merge as they always did).
_SOURCE_RANK = {ALBUM_SOURCE: 1, FAMOUS_SOURCE: 2}

_HEBREW_RE = re.compile(r"[א-ת]")
_PARENS_RE = re.compile(r"\([^()]*\)|\[[^\[\]]*\]")


def load_classics(path: Path = CLASSICS_CSV) -> list[dict]:
    rows = []
    with Path(path).open(encoding="utf-8", newline="") as f:
        for line in csv.DictReader(f):
            rows.append(
                {
                    "title": line["title"].strip(),
                    "artist": line["artist"].strip(),
                    "chart_year": int(line["year"]),
                    "origin": "IL",
                    "source": CLASSICS_SOURCE,
                    "position": 0,
                }
            )
    return rows


class ExtrasError(ValueError):
    """A malformed row of data/extras.csv."""


def load_extras(path: Path = EXTRAS_CSV) -> list[dict]:
    """Hand-picked songs (origin,title,artist,year,note) as candidate rows of source "extras"."""
    rows = []
    with Path(path).open(encoding="utf-8", newline="") as f:
        for number, line in enumerate(csv.DictReader(f), 2):
            origin, title, artist, year = (
                (line.get(field) or "").strip() for field in ("origin", "title", "artist", "year")
            )
            problem = (
                f"origin {origin!r} is not IL or INTL" if origin not in ORIGINS
                else "empty title" if not title
                else "empty artist" if not artist
                else f"year {year!r} is not a number" if not year.isdigit()
                else None
            )  # fmt: skip
            if problem:
                raise ExtrasError(f"{Path(path).name} line {number}: {problem}")
            rows.append(
                {
                    "title": title,
                    "artist": artist,
                    "chart_year": int(year),
                    "origin": origin,
                    "source": EXTRAS_SOURCE,
                    "position": 0,
                }
            )
    return rows


class YearOutOfRange(ValueError):
    pass


def check_years(rows: list[dict]) -> None:
    """Fail loudly on any row outside the catalog's 1940–2026 range."""
    bad = [r for r in rows if not FIRST_YEAR <= r["chart_year"] <= LAST_YEAR]
    if bad:
        listed = "; ".join(f"{r['source']} {r['chart_year']} {r['title']} – {r['artist']}" for r in bad)
        raise YearOutOfRange(f"chart_year outside {FIRST_YEAR}–{LAST_YEAR}: {listed}")


def with_defaults(row: dict) -> dict:
    """Fill the optional row fields: credit, and the Latin fields of Billboard/IL rows."""
    row = dict(row)
    row.setdefault("credit", row["artist"])
    row.setdefault("extended", False)
    if "needs_latin" not in row:
        if row["origin"] == "INTL":  # Billboard: the text is already Latin
            row.update(title_latin=row["title"], artist_latin=row["artist"], needs_latin=False)
        else:
            row.update(title_latin=None, artist_latin=None, needs_latin=False)
    return row


def _title_forms(title: str) -> set[str]:
    return {normalize(title), normalize(_PARENS_RE.sub(" ", title))} - {""}


def _mergeable(c: dict) -> bool:
    return c["origin"] == "INTL" and not c["needs_latin"]


def _agree(a: dict, b: dict) -> bool:
    """Two INTL candidates are one song: titles match and first-credited acts agree."""
    if not (_mergeable(a) and _mergeable(b)):
        return False
    if abs(a["chart_year"] - b["chart_year"]) > MERGE_YEAR_WINDOW:
        return False
    if not _title_forms(a["title_latin"]) & _title_forms(b["title_latin"]):
        return False
    act_a = normalize(first_act(a["artist_latin"]))
    act_b = normalize(first_act(b["artist_latin"]))
    return bool(act_a and act_b) and fuzz.token_set_ratio(act_a, act_b) >= ARTIST_AGREEMENT


def _own_sources(c: dict) -> set[str]:
    """The sources of a candidate's own rows, without those only ``extended`` rows brought."""
    return set(c["sources"]) - set(c.get("tail_sources", ()))


def _only_extras(c: dict) -> bool:
    return _own_sources(c) == {EXTRAS_SOURCE}


def _rank(c: dict) -> int:
    return _SOURCE_RANK.get(c["source"], 0) if c["extended"] else 0


def _absorb(into: dict, other: dict) -> None:
    """Fold ``other`` into ``into``: earliest year (then best position) wins, sources unite.

    A hand-picked (extras) row never wins over a chart row: the chart row's fields stay.
    An ``extended`` row (the tail of a chart, a Hebrew Wikipedia song article, a
    UK year-end row) never wins over a row of the original sources: that row's
    fields stay, and the sources it adds (``tail_sources`` while merging) never
    make that row lose a later merge it would have won without them.
    Among extended rows an album-page row loses to a chart or article row, and a
    famous-acts row to all of them; an album-page row keeps a famous-acts row's
    Deezer track (``deezer_hint``).
    """
    sources = sorted(set(into["sources"]) | set(other["sources"]))
    own = _own_sources(into) | _own_sources(other)
    tail = (set(into.get("tail_sources", ())) | set(other.get("tail_sources", ()))) - own
    if into["extended"] != other["extended"]:
        same_origin = into["origin"] == other["origin"]
        tail_row = dict(into if into["extended"] else other)
        if into["extended"]:
            into.clear()
            into.update(other)
        if same_origin:  # a foreign song on an Israeli chart's tail adds nothing to its row
            # What the extended row brought stays out of the existing row's own sources, so it never
            # changes which existing row leads a later merge (a hand-picked row, ``_only_extras``).
            kept_own = _own_sources(into)
            into["tail_sources"] = sorted(set(into.get("tail_sources", ())) | (set(tail_row["sources"]) - kept_own))
            into["sources"] = sources
        return
    _merge_rows(into, other, sources)
    if tail:
        into["tail_sources"] = sorted(tail)
    else:
        into.pop("tail_sources", None)


def _merge_rows(into: dict, other: dict, sources: list[str]) -> None:
    """Fold ``other`` into ``into`` when both are extended or both are not (see :func:`_absorb`)."""
    if _rank(into) != _rank(other):  # an album row and a better-ranked extended row
        hint = into.get("deezer_hint") or other.get("deezer_hint")
        if _rank(into) > _rank(other):
            into.clear()
            into.update(other)
        if into["source"] == ALBUM_SOURCE and hint:  # the act's own album track, found by famous_acts
            into["deezer_hint"] = hint
        into["sources"] = sources
        return
    if _only_extras(into) != _only_extras(other):  # a hand-picked row and a chart row
        if _only_extras(into):
            into.clear()
            into.update(other)
        into["sources"] = sources
        return
    into["sources"] = sources
    if (other["chart_year"], other["position"]) < (into["chart_year"], into["position"]):
        for field in ("chart_year", "position", "source", *OPTIONAL_FIELDS):
            if field in other or field in into:
                into[field] = other.get(field)
    # Show a Latin-script credit for an international song when one is available.
    if (
        into["origin"] == "INTL"
        and _HEBREW_RE.search(into["artist"] + into["title"])
        and not _HEBREW_RE.search(other["artist"] + other["title"])
    ):
        for field in ("title", "artist", "credit", "key"):
            into[field] = other[field]
    for field in ("title_latin", "artist_latin"):
        into[field] = into[field] or other[field]


def merge_candidates(rows: list[dict]) -> list[dict]:
    """Deduplicate rows into candidates, sorted by origin, chart year, position.

    1. Same key (normalised title + artist text) from any source → one candidate.
    2. International candidates whose Latin titles match and whose first-credited
       acts agree (token_set_ratio >= 80), with chart years at most 3 apart → one
       candidate. Rows without a resolved Latin title/artist (``needs_latin``)
       take part only in step 1.
    The candidate keeps the earliest chart year and records every source. A
    hand-picked (extras) row that merges with a chart row only adds "extras" to
    the chart row's sources.
    """
    by_key: dict[str, dict] = {}
    for row in map(with_defaults, rows):
        key = song_key(row["title"], row["artist"])
        candidate = {**row, "key": key, "sources": [row["source"]]}
        if key in by_key:
            _absorb(by_key[key], candidate)
        else:
            by_key[key] = candidate

    merged: list[dict] = []
    members: list[list[dict]] = []  # rows already folded into merged[i]
    by_title: dict[str, list[int]] = {}  # Latin title form → indexes into merged
    for candidate in sorted(by_key.values(), key=lambda c: (c["chart_year"], c["position"], c["key"])):
        forms = _title_forms(candidate["title_latin"]) if _mergeable(candidate) else set()
        groups = sorted({i for form in forms for i in by_title.get(form, [])})
        target = next((i for i in groups if any(_agree(candidate, m) for m in members[i])), None)
        if target is None:
            target = len(merged)
            merged.append(dict(candidate))
            members.append([candidate])
        else:
            _absorb(merged[target], candidate)
            members[target].append(candidate)
        for form in forms:
            if target not in by_title.setdefault(form, []):
                by_title[form].append(target)

    out = [{**{f: c[f] for f in FIELDS}, **{f: c[f] for f in OPTIONAL_FIELDS if c.get(f) is not None}} for c in merged]
    out.sort(key=lambda c: (c["origin"], c["chart_year"], c["position"], c["title"]))
    return out


def find_gaps(rows: list[dict], expected: dict[str, tuple[range, int]]) -> list[tuple[str, int, int, int]]:
    """(source, year, found, expected) for every year with fewer rows than expected."""
    counts: dict[tuple[str, int], int] = {}
    for row in rows:
        counts[(row["source"], row["chart_year"])] = counts.get((row["source"], row["chart_year"]), 0) + 1
    gaps = []
    for source, (years, minimum) in expected.items():
        for year in years:
            found = counts.get((source, year), 0)
            if found < minimum:
                gaps.append((source, year, found, minimum))
    return gaps
