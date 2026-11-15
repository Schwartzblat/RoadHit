"""Stage 3: the year each song first came out.

The game asks for the original release year, so a chart year can be wrong by
years (a re-release, a late hit). For each matched song:

- ``first`` is the earliest ``first-release-date`` on MusicBrainz over the
  recordings of this title by this artist (score >= 90 *and* title and artist
  agreeing with the row); if MusicBrainz has nothing that counts, the earliest
  publication date (P577) of the song's Wikidata item, if one is clearly this
  song by this artist.
- ``chart_year - 2 <= first <= chart_year`` → ``year = first``; otherwise the
  chart year stands and the row is flagged for review.
- Hand-picked classics keep their CSV year, and rows taken from an album
  (sources ``hewiki-albums`` and ``famous-acts``, when such a row leads) keep
  their album's year (``chart_year``) without a lookup: the album's year is the
  evidence. ``data/year_overrides.csv`` is applied last.

Deezer's ``release_date`` is never used: it is the album's date, often a reissue.
"""

from __future__ import annotations

import csv
import re
from pathlib import Path
from typing import Protocol

from . import http, latin
from .candidates import ALBUM_SOURCE, CLASSICS_SOURCE, FAMOUS_SOURCE, FIRST_YEAR, ROOT
from .deezer import artist_score, candidate_artists, chart_titles, clean_title, is_hebrew, lead_act, title_score
from .keys import normalize

MUSICBRAINZ_URL = "https://musicbrainz.org/ws/2/recording"
WIKIDATA_API = "https://www.wikidata.org/w/api.php"
MUSICBRAINZ_LIMIT = "100"
WIKIDATA_LIMIT = "10"
OVERRIDES_CSV = ROOT / "data" / "year_overrides.csv"

SCORE_MIN = 90  # MusicBrainz's own relevance score
AGREEMENT = 85  # title_score / artist_score against the row
WINDOW = 2  # a first release up to this many years before the chart year is the year
MAX_NAMES = 4  # title and artist variants per query

MUSICBRAINZ, WIKIDATA, CHART, CLASSICS, OVERRIDE = "musicbrainz", "wikidata", "chart", "classics", "override"
ALBUM = "album"
YEAR_SOURCES = (MUSICBRAINZ, WIKIDATA, CHART, CLASSICS, ALBUM, OVERRIDE)

# Wikidata classes of an item that is a song (not an album, film, ...):
# song, single, musical work/composition, musical work, musical composition, audio track.
SONG_CLASSES = {"Q7366", "Q134556", "Q105543609", "Q2188189", "Q207628", "Q55850593"}

_LUCENE_SPECIAL_RE = re.compile(r'([+\-&|!(){}\[\]^"~*?:\\/])')


class YearError(RuntimeError):
    """A bad year override or a failed sanity check."""


class Lookups(Protocol):
    def recordings(self, query: str) -> dict: ...
    def wikidata_search(self, text: str, language: str) -> dict: ...
    def wikidata_entities(self, ids: list[str], props: str) -> dict: ...


class WebLookups:
    """The real thing, through the cached, rate-limited HTTP helper. Counts calls per service."""

    def __init__(self) -> None:
        self.calls = {MUSICBRAINZ: 0, WIKIDATA: 0}

    def recordings(self, query: str) -> dict:
        self.calls[MUSICBRAINZ] += 1
        return http.get_json(MUSICBRAINZ_URL, {"query": query, "fmt": "json", "limit": MUSICBRAINZ_LIMIT})

    def wikidata_search(self, text: str, language: str) -> dict:
        self.calls[WIKIDATA] += 1
        return http.get_json(
            WIKIDATA_API,
            {"action": "wbsearchentities", "search": text, "language": language, "type": "item",
             "limit": WIKIDATA_LIMIT, "format": "json"},
        )  # fmt: skip

    def wikidata_entities(self, ids: list[str], props: str) -> dict:
        self.calls[WIKIDATA] += 1
        return http.get_json(
            WIKIDATA_API,
            {"action": "wbgetentities", "ids": "|".join(ids), "props": props, "languages": "en|he", "format": "json"},
        )


# --- MusicBrainz -------------------------------------------------------------------


def lucene_escape(text: str) -> str:
    return _LUCENE_SPECIAL_RE.sub(r"\\\1", text)


def recording_query(titles: list[str], artists: list[str], until: int | None = None) -> str:
    """``(recording:"T1" OR …) AND (artist:"A1" OR …)``, optionally released by the end of ``until``."""
    query = "({}) AND ({})".format(
        " OR ".join(f'recording:"{lucene_escape(t)}"' for t in titles),
        " OR ".join(f'artist:"{lucene_escape(a)}"' for a in artists),
    )
    if until is not None:
        query += f" AND firstreleasedate:[* TO {until}-12-31]"
    return query


def _add(out: list[str], text: str | None) -> None:
    if text and text.strip() and normalize(text) and normalize(text) not in map(normalize, out):
        out.append(text.strip())


def query_names(row: dict) -> tuple[list[str], list[str]]:
    """Title and artist variants to search for.

    Israeli rows: the Hebrew title with the Hebrew artist and with Deezer's
    (usually Latin) artist name. International rows: Latin forms only, the lead
    act of a credit and its first-credited act.
    """
    israeli = row["origin"] == "IL"

    def wanted(text: str | None) -> bool:
        return bool(text) and (israeli or not is_hebrew(text))

    titles: list[str] = []
    for title in chart_titles(row):
        if wanted(title):
            _add(titles, title)
            _add(titles, clean_title(title))
    if wanted(row.get("deezer_title")):  # Deezer's title without its "(feat. …)" / "(Remastered)" notes
        _add(titles, clean_title(row["deezer_title"]))
    artists: list[str] = []
    for artist in (row.get("artist"), row.get("artist_latin"), row.get("deezer_artist")):
        if wanted(artist):
            lead = lead_act(artist)
            _add(artists, lead)
            if latin.is_latin(lead):
                _add(artists, latin.first_act(lead))
    return titles[:MAX_NAMES], artists[:MAX_NAMES]


def _row_titles(row: dict) -> list[str]:
    return [t for t in [*chart_titles(row), row.get("deezer_title")] if t]


def _row_artists(row: dict) -> list[str]:
    out = candidate_artists(row)
    if row.get("deezer_artist") and row["deezer_artist"] not in out:
        out.append(row["deezer_artist"])
    return out


def _credit_names(recording: dict) -> list[str]:
    credit = recording.get("artist-credit") or []
    names = ["".join(c.get("name", "") + c.get("joinphrase", "") for c in credit).strip()]
    for c in credit:
        names += [c.get("name", ""), (c.get("artist") or {}).get("name", "")]
    return [n for n in names if n]


def counting_years(row: dict, response: dict) -> list[int]:
    """First-release years of the recordings in ``response`` that are this song by this artist."""
    titles, artists = _row_titles(row), _row_artists(row)
    out = []
    for recording in response.get("recordings", []):
        date = recording.get("first-release-date") or ""
        if recording.get("score", 0) < SCORE_MIN or not date[:4].isdigit():
            continue
        if title_score(titles, [recording.get("title", "")]) < AGREEMENT:
            continue
        if max((artist_score(artists, name) for name in _credit_names(recording)), default=0) < AGREEMENT:
            continue
        out.append(int(date[:4]))
    return out


def musicbrainz_first(row: dict, lookups: Lookups) -> int | None:
    """Earliest release year; searches up to the chart year first, then without a date limit."""
    titles, artists = query_names(row)
    if not titles or not artists:
        return None
    for until in (row["chart_year"], None):
        found = counting_years(row, lookups.recordings(recording_query(titles, artists, until)))
        if found:
            return min(found)
    return None


# --- Wikidata ------------------------------------------------------------------------


def _claim_values(entity: dict, prop: str) -> list[dict]:
    out = []
    for claim in entity.get("claims", {}).get(prop, []):
        value = claim.get("mainsnak", {}).get("datavalue", {}).get("value")
        if isinstance(value, dict):
            out.append(value)
    return out


def _publication_years(entity: dict) -> list[int]:
    out = []
    for value in _claim_values(entity, "P577"):
        time = value.get("time", "")
        if value.get("precision", 0) >= 9 and time[:1] == "+" and time[1:5].isdigit():
            out.append(int(time[1:5]))
    return out


def _texts(entity: dict, field: str) -> list[str]:
    return [v["value"] for v in entity.get(field, {}).values() if v.get("value")]


def _names_artist(description: str, artists: list[str]) -> bool:
    text = f" {normalize(description)} "
    return any(f" {normalize(a)} " in text for a in artists if normalize(a))


def wikidata_first(row: dict, lookups: Lookups) -> int | None:
    """Earliest P577 year of a Wikidata song item that is clearly this song by this artist.

    The item must be a song (not an album or film), carry the title, and either
    name the artist in its description or have the artist as its only performer.
    """
    israeli = row["origin"] == "IL"
    title = row["title"] if israeli else (row.get("title_latin") or (row["title"] if latin.is_latin(row["title"]) else None))
    if not title:
        return None
    titles, artists = _row_titles(row), _row_artists(row)
    hits = lookups.wikidata_search(title, "he" if israeli else "en").get("search", [])
    ids = []
    for hit in hits:
        names = [hit.get("label", ""), (hit.get("match") or {}).get("text", "")]
        if hit.get("id") and hit["id"] not in ids and title_score(titles, [n for n in names if n]) >= AGREEMENT:
            ids.append(hit["id"])
    if not ids:
        return None
    entities = lookups.wikidata_entities(ids, "claims|labels|descriptions").get("entities", {})
    found: list[int] = []
    sole_performer: dict[str, list[int]] = {}
    for item_id in ids:
        entity = entities.get(item_id) or {}
        dates = _publication_years(entity)
        if not dates or not {v.get("id") for v in _claim_values(entity, "P31")} & SONG_CLASSES:
            continue
        if any(_names_artist(d, artists) for d in _texts(entity, "descriptions")):
            found += dates
            continue
        performers = [v.get("id") for v in _claim_values(entity, "P175") if v.get("id")]
        if len(performers) == 1:
            sole_performer.setdefault(performers[0], []).extend(dates)
    if sole_performer:
        people = lookups.wikidata_entities(sorted(sole_performer), "labels").get("entities", {})
        for person_id, dates in sole_performer.items():
            labels = _texts(people.get(person_id) or {}, "labels")
            if max((artist_score(artists, label) for label in labels), default=0) >= AGREEMENT:
                found += dates
    return min(found) if found else None


# --- the rule and the stage -------------------------------------------------------------


def decide(chart_year: int, first: int | None) -> tuple[int, bool]:
    """(year, flagged): the first release if it lies in the window before the chart year."""
    if first is not None and chart_year - WINDOW <= first <= chart_year:
        return first, False
    return chart_year, True


def is_classic(row: dict) -> bool:
    return CLASSICS_SOURCE in row.get("sources", [row.get("source")])


def is_album_row(row: dict) -> bool:
    """A row led by an album source: its chart year is its album's year."""
    return row.get("source") in (ALBUM_SOURCE, FAMOUS_SOURCE)


def date_row(row: dict, lookups: Lookups) -> dict:
    if is_classic(row):
        return {**row, "year": row["chart_year"], "year_source": CLASSICS, "flagged": False, "first": None}
    if is_album_row(row):
        return {**row, "year": row["chart_year"], "year_source": ALBUM, "flagged": False, "first": None}
    source, first = MUSICBRAINZ, musicbrainz_first(row, lookups)
    if first is None:
        source, first = WIKIDATA, wikidata_first(row, lookups)
    year, flagged = decide(row["chart_year"], first)
    return {**row, "year": year, "year_source": CHART if flagged else source, "flagged": flagged, "first": first}


def load_overrides(path: Path = OVERRIDES_CSV) -> dict[str, int]:
    """{key: year} from year_overrides.csv (key,year,note)."""
    out: dict[str, int] = {}
    with Path(path).open(encoding="utf-8", newline="") as f:
        for line in csv.DictReader(f):
            key = (line.get("key") or "").strip()
            if not key or key.startswith("#"):
                continue
            if key in out:
                raise YearError(f"{key}: listed twice in {Path(path).name}")
            out[key] = int(line["year"])
    return out


def apply_overrides(rows: list[dict], overrides: dict[str, int]) -> list[dict]:
    keys = {r["key"] for r in rows}
    unknown = sorted(set(overrides) - keys)
    if unknown:
        raise YearError(f"year override for a key not in the input: {', '.join(unknown)}")
    return [
        {**r, "year": overrides[r["key"]], "year_source": OVERRIDE, "flagged": False} if r["key"] in overrides else r
        for r in rows
    ]


def check(rows_in: list[dict], rows_out: list[dict], last_year: int) -> None:
    keys_in = [r["key"] for r in rows_in]
    keys_out = [r["key"] for r in rows_out]
    if len(set(keys_out)) != len(keys_out) or sorted(keys_in) != sorted(keys_out):
        raise YearError("output keys differ from input keys (each must appear exactly once)")
    for r in rows_out:
        if not FIRST_YEAR <= r["year"] <= last_year:
            raise YearError(f"{r['key']}: year {r['year']} outside {FIRST_YEAR}..{last_year}")
        if r["year"] > r["chart_year"] and r["year_source"] != OVERRIDE:
            raise YearError(f"{r['key']}: year {r['year']} after chart year {r['chart_year']}")


def run(rows: list[dict], lookups: Lookups, overrides: dict[str, int], last_year: int, progress=None) -> list[dict]:
    """Date every row (input order kept), apply the overrides, check the result."""
    dated = []
    for i, row in enumerate(rows, 1):
        dated.append(date_row(row, lookups))
        if progress:
            progress(i, len(rows))
    dated = apply_overrides(dated, overrides)
    check(rows, dated, last_year)
    return dated
