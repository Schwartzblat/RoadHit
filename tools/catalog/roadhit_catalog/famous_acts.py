"""Every song of the famous acts, 1960–1999 (source ``famous-acts``).

For each act of ``data/famous_acts.txt`` the act's albums on Deezer are matched,
by title, to albums whose original year is known without Deezer:

1. a Hebrew Wikipedia album page of the act (its ``אלבומי YYYY`` category and type),
2. the discography section of the act's Hebrew Wikipedia page (title + year),
3. the act's MusicBrainz release groups (first-release date; primary type
   Album/EP/Single; no secondary type Compilation, Live, Remix, Audiobook, …).

The first of these that knows the album decides its year and its kind. A
studio album, EP or single of 1960–1999 gives one candidate per track (when
the album has a Hebrew Wikipedia page with a track list, only the tracks on it): the
track's title without its bracketed notes, the act as the credit, the album's
year as ``chart_year`` and the Deezer track as ``deezer_hint`` (the deezer
stage takes that very track). Albums Deezer lists twice (a remaster) give the
tracks of the copy whose best track ranks highest. Tracks credited to another
artist, live/remix/instrumental takes, bonus tracks and intros are skipped.
Deezer's ``release_date`` is never a year.

Parsers are pure functions; :func:`collect` does the fetching through
:mod:`roadhit_catalog.http`.
"""

from __future__ import annotations

import re
from pathlib import Path

from . import http
from .candidates import FAMOUS_SOURCE, ROOT
from .deezer import MATCH_THRESHOLD, act_form, clean_title, hebrew_acts, lead_act, marker_in, title_score
from .hewiki_albums import CHILDREN, CHILDREN_TITLE_RE, FOREIGN, INSTRUMENTAL, is_hebrew_title
from .hewiki_songs import BATCH, merge_page_batch, templates
from .keys import normalize
from .wikitext import clean, strip_quotes

SOURCE = FAMOUS_SOURCE
ACTS_TXT = ROOT / "data" / "famous_acts.txt"
FIRST, LAST = 1960, 1999

DEEZER_ARTIST_SEARCH = "https://api.deezer.com/search/artist"
DEEZER_ARTIST_ALBUMS = "https://api.deezer.com/artist/{}/albums"
DEEZER_ALBUM = "https://api.deezer.com/album/{}"
MB_ARTIST = "https://musicbrainz.org/ws/2/artist"
MB_RELEASE_GROUPS = "https://musicbrainz.org/ws/2/release-group"
MB_PAGE = 100

ARTIST_HITS = 6  # Deezer artist-search hits looked at for duplicate profiles of the act

ALBUM, EP, SINGLE, COMPILATION, LIVE, OTHER = "album", "ep", "single", "compilation", "live", "other"
STUDIO = (ALBUM, EP, SINGLE)
PAGE, DISCOGRAPHY, MUSICBRAINZ = "album page", "discography", "musicbrainz"

# Deezer album verdicts.
TAKEN, NOT_STUDIO_TITLE, NO_YEAR, NOT_STUDIO, OUT_OF_RANGE, OTHER_COPY = (
    "taken", "title marks a compilation/live/re-recording", "no established year",
    "not a studio album", "year outside 1960–1999", "another copy taken",
)  # fmt: skip
ALBUM_REASONS = (TAKEN, NOT_STUDIO_TITLE, NO_YEAR, NOT_STUDIO, FOREIGN, CHILDREN, INSTRUMENTAL, OUT_OF_RANGE, OTHER_COPY)
_OTHER_KINDS = (FOREIGN, CHILDREN, INSTRUMENTAL)  # album-page kinds that are not Hebrew songs (round 2)
_DEEZER_GENRES = {"Kids": CHILDREN, "Jazz": INSTRUMENTAL}


def deezer_album_other(album: dict) -> str | None:
    """FOREIGN when most track titles are in Latin letters; CHILDREN or INSTRUMENTAL by Deezer's genre."""
    titles = [t.get("title") or "" for t in (album.get("tracks") or {}).get("data") or []]
    if titles and sum(not is_hebrew_title(t) for t in titles) * 2 > len(titles):
        return FOREIGN
    for genre in (album.get("genres") or {}).get("data") or []:
        if genre.get("name") in _DEEZER_GENRES:
            return _DEEZER_GENRES[genre["name"]]
    return None

# Words in a Deezer album title that mark something other than a studio album of new recordings.
_ALBUM_WORDS_RE = re.compile(
    r"אוסף|המיטב|מיטב|להיטי|הלהיטים|אנתולוגיה|הופעה|בהופעה|לייב|מחדש|חדש וישן|קריוקי|פלייבק|רמיקס|"
    r"מחווה|שרים את|"
    r"\b(?:best|greatest|hits|collection|anthology|live|remix(?:es|ed)?|karaoke|tribute|essential|gold)\b",
    re.I,
)
# Words in a track title that mark a bonus track, an intro or a skit.
_TRACK_WORDS_RE = re.compile(
    r"בונוס|אינטרו|אאוטרו|סקיט|(?<![א-ת])פינו?ת(?![א-ת])|\b(?:bonus|intro|outro|interlude|skit|reprise)\b|"
    r"\(\s*נעימה\s*\)|מגה מיקס|(?<![א-ת])מחרוזת(?![א-ת])",  # an instrumental "(נעימה)" (not the song נעימה נעימה), medleys
    re.I,
)
# Deezer's right-to-left garbling of "X (Y)": "(X (Y".
_GARBLED_RE = re.compile(r"^\((?P<head>[^()]+?) \((?P<note>[^()]+)$")


def repair_brackets(title: str) -> str:
    """'(גחליליות (נעימה' → 'גחליליות (נעימה)'."""
    m = _GARBLED_RE.match(title.strip())
    return f"{m.group('head')} ({m.group('note')})" if m else title
_MB_BAD_SECONDARY = {
    "Compilation", "Live", "Remix", "Audiobook", "Spokenword", "Interview", "DJ-mix",
    "Mixtape/Street", "Audio drama", "Demo",
}  # fmt: skip
_MB_PRIMARY = {"Album": ALBUM, "EP": EP, "Single": SINGLE}


def load_aliases(path: Path = ACTS_TXT) -> dict[str, list[str]]:
    """{act: [other spellings]} from "name | alias | …" lines (tried when the name finds no Deezer or
    MusicBrainz artist)."""
    out: dict[str, list[str]] = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        names = [n.strip() for n in line.split("#", 1)[0].split("|")]
        if len(names) > 1 and names[0]:
            out.setdefault(names[0], []).extend(n for n in names[1:] if n)
    return out


def load_acts(path: Path = ACTS_TXT) -> list[str]:
    """The acts in file order (one per line, '#' starts a comment; aliases after '|' are left out)."""
    out = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        act = line.split("#", 1)[0].split("|", 1)[0].strip()  # "name | alias": the catalog's name
        if act and act not in out:
            out.append(act)
    return out


# --- Deezer ------------------------------------------------------------------------------


def deezer_artist_ids(act: str, answer: dict) -> list[int]:
    """Deezer artists of the act: the first hit of the search for its Hebrew name (as the deezer
    stage's artist confirmation trusts it), and further hits of the same name (duplicate profiles),
    each with at least one album."""
    hits = [h for h in (answer.get("data") or [])[:ARTIST_HITS]]
    if not hits or not hits[0].get("nb_album"):
        return []
    names = {normalize(hits[0].get("name") or ""), normalize(act)}
    out = [hits[0]["id"]]
    for hit in hits[1:]:
        if hit.get("nb_album") and normalize(hit.get("name") or "") in names and hit["id"] not in out:
            out.append(hit["id"])
    return out


def artist_albums(artist_id: int) -> list[dict]:
    out: list[dict] = []
    params = {"limit": "100"}
    while True:
        answer = http.get_json(DEEZER_ARTIST_ALBUMS.format(artist_id), params)
        out += answer.get("data") or []
        if not answer.get("next") or not answer.get("data"):
            return out
        params = {"limit": "100", "index": str(len(out))}


def title_marks_other(title: str) -> bool:
    """A Deezer album title that names a compilation, a live album, a remix or a re-recording."""
    return bool(_ALBUM_WORDS_RE.search(title)) or bool(marker_in(title, [], notes="album", extended=True))


def track_rows(act: str, artist_ids: list[int], album: dict, established: dict) -> list[dict]:
    """One candidate per acceptable track of a Deezer album answer."""
    rows = []
    for track in (album.get("tracks") or {}).get("data") or []:
        title = repair_brackets(track.get("title") or "")
        if (track.get("artist") or {}).get("id") not in artist_ids:
            continue  # credited to another performer
        version = f"{title} {track.get('title_version') or ''}"
        if _TRACK_WORDS_RE.search(version) or marker_in(title, [], notes="track", extended=True):
            continue
        name = clean_title(title)
        if not normalize(name) or not is_hebrew_title(name):  # a Latin-titled track gives no row (round 2)
            continue
        listed = established.get("tracks")
        if listed and title_score(listed, [name]) < MATCH_THRESHOLD:
            continue  # not on the album page's track list: a bonus track of a re-issue
        rows.append({
            "title": name,
            "artist": act,
            "credit": act,
            "chart_year": established["year"],
            "origin": "IL",
            "source": SOURCE,
            "position": 0,
            "extended": True,
            "album": established["title"],
            "deezer_hint": track["id"],
        })  # fmt: skip
    return rows


# --- MusicBrainz -----------------------------------------------------------------------------


def mb_artist_id(act: str, answer: dict) -> str | None:
    """The MusicBrainz artist whose name (or an alias) is the act's, scored 100."""
    for artist in answer.get("artists") or []:
        if artist.get("score", 0) < 100:
            continue
        names = [artist.get("name") or ""] + [a.get("name") or "" for a in artist.get("aliases") or []]
        if normalize(act) in map(normalize, names):
            return artist["id"]
    return None


def mb_release_groups(mbid: str) -> list[dict]:
    out: list[dict] = []
    while True:
        answer = http.get_json(
            MB_RELEASE_GROUPS, {"artist": mbid, "fmt": "json", "limit": str(MB_PAGE), "offset": str(len(out))}
        )
        groups = answer.get("release-groups") or []
        out += groups
        if not groups or len(out) >= answer.get("release-group-count", 0):
            return out


def mb_albums(groups: list[dict]) -> list[dict]:
    """[{"title", "year", "kind", "source"}] for release groups that have a first-release year."""
    out = []
    for g in groups:
        date = g.get("first-release-date") or ""
        if not date[:4].isdigit():
            continue
        secondary = set(g.get("secondary-types") or [])
        kind = _MB_PRIMARY.get(g.get("primary-type") or "", OTHER)
        if secondary & {"Compilation"}:
            kind = COMPILATION
        elif secondary & {"Live"}:
            kind = LIVE
        elif secondary & _MB_BAD_SECONDARY:
            kind = OTHER
        out.append({"title": g.get("title") or "", "year": int(date[:4]), "kind": kind, "source": MUSICBRAINZ})
    return out


# --- Hebrew Wikipedia: the act's discography -----------------------------------------------------

_HEADING_RE = re.compile(r"^(=+)\s*(.*?)\s*\1\s*$")
_DISCOGRAPHY_RE = re.compile(r"דיסקוגרפיה|אלבומים|אלבומי אולפן")
_YEAR_RE = re.compile(r"(?<!\d)(19[4-9]\d|20[0-2]\d)(?!\d)")
_LINK_RE = re.compile(r"\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]")
_ITALIC_RE = re.compile(r"''([^']+?)''")
_QUOTED_RE = re.compile(r"[\"“”״]([^\"“”״]+)[\"“”״]")
_DISAMBIGUATION_RE = re.compile(r"\s*\([^()]*\)\s*$")


def section_kind(text: str) -> str:
    """The kind of album a discography heading or line names (studio unless it says otherwise)."""
    if re.search(r"אוסף|אוספים|המיטב", text):
        return COMPILATION
    if re.search(r"הופעה|הופעות|לייב|הקלטה חיה|\blive\b", text, re.I):
        return LIVE
    if re.search(r"סינגל|סינגלים|תקליטונים", text):
        return SINGLE
    if re.search(r"ילדים|מחווה|כיסוי|קאברים|פסקול|שיתופי|השתתפות|אורח|(?<![א-ת])ה?אחר(?:ים|ות)?(?![א-ת])|וידאו|DVD|תוכניות", text):
        return OTHER
    return ALBUM


def _line_title(line: str) -> str | None:
    """The album title a discography line names: its first link's text, else ''italic'' or quoted text."""
    for m in _LINK_RE.finditer(line):
        target = m.group(1).strip()
        if re.match(r"(?:קובץ|File|תמונה|קטגוריה):", target):
            continue
        shown = (m.group(2) or _DISAMBIGUATION_RE.sub("", target)).strip()
        if _YEAR_RE.fullmatch(shown):  # a linked year ([[1973 במוזיקה|1973]])
            continue
        return strip_quotes(clean(shown)) or None
    for pattern in (_ITALIC_RE, _QUOTED_RE):
        m = pattern.search(line)
        if m:
            return strip_quotes(clean(m.group(1))) or None
    return None


def discography(wikitext: str) -> list[dict]:
    """[{"title", "year", "kind", "source"}] from the act page's discography section.

    The section starts at a heading that says דיסקוגרפיה / אלבומים and ends at the next
    heading of its level or higher that does not; sub-headings give the kind (אלבומי
    אוסף, הופעות, סינגלים …). Every list line, and every table row, with a year and a
    title (a link, ''italics'' or quotes) is one album.
    """
    text = re.sub(r"<!--.*?-->|<ref[^>/]*/>|<ref[^>]*>.*?</ref>", "", wikitext, flags=re.S | re.I)
    out: list[dict] = []
    level: int | None = None
    kind = ALBUM
    row: list[str] = []  # the cells of the table row being read

    def end_row() -> None:
        if row:
            out.extend(_album_lines([" ".join(row)], kind))
            row.clear()

    for raw in text.split("\n"):
        line = raw.strip()
        heading = _HEADING_RE.match(line)
        if heading:
            end_row()
            depth, name = len(heading.group(1)), heading.group(2)
            if level is not None and depth <= level and not _DISCOGRAPHY_RE.search(name):
                level = None
            elif level is None and _DISCOGRAPHY_RE.search(name):
                level, kind = depth, section_kind(name)
            elif level is not None:
                kind = section_kind(name)
            continue
        if level is None:
            continue
        if line.startswith(("|-", "|}", "{|")):
            end_row()
        elif line.startswith(("|", "!")):
            row.append(line)
        elif line.startswith(("*", "#")):
            end_row()
            out.extend(_album_lines([line], kind))
    end_row()
    seen, unique = set(), []
    for album in out:
        key = (normalize(album["title"]), album["year"])
        if key not in seen:
            seen.add(key)
            unique.append(album)
    return unique


def _album_lines(lines: list[str], kind: str) -> list[dict]:
    out = []
    for line in lines:
        plain = clean(line)
        year = _YEAR_RE.search(plain)
        title = _line_title(line)
        if not year or not title or _YEAR_RE.fullmatch(title):
            continue
        own = section_kind(plain.replace(title, ""))  # "(אוסף)", "הופעה חיה" on the line itself
        out.append({"title": title, "year": int(year.group(1)), "kind": own if own != ALBUM else kind,
                    "source": DISCOGRAPHY})
    return out


def act_page_titles(act: str) -> list[str]:
    """Pages that may be the act's article: its name, then the usual disambiguated forms."""
    return [act] + [f"{act} ({d})" for d in ("להקה", "להקה ישראלית", "זמר", "זמרת", "מוזיקאי", "צמד", "הרכב")]


_MUSIC_CATEGORY_RE = re.compile(
    r"להקות|להקה|זמרי|זמרות|זמרים|זמר|מוזיקאי|יוצרי|פזמונאי|מלחיני|צמדים|הרכבים|גיטריסט|פסנתרנ|מתופפ|ראפר|"
    r"מבצעי|חברי להקת|זוכי פרס אקו״ם|אמני"
)


def is_music_act(page: dict) -> bool:
    """The page's categories say it is about a singer, a musician or a band (not a month or a tree)."""
    return any(_MUSIC_CATEGORY_RE.search(c) for c in page.get("categories") or [])


def is_disambiguation(page: dict) -> bool:
    text = page.get("wikitext") or ""
    return "{{פירושונים" in text or "קטגוריה:פירושונים" in (page.get("categories") or [])


def _query_pages(titles: list[str]) -> tuple[dict[str, dict], dict[str, str]]:
    """Pages (redirects followed) of ``titles``, 50 per request, and the {from: to} redirect map."""
    pages: dict[str, dict] = {}
    redirects: dict[str, str] = {}
    for start in range(0, len(titles), BATCH):
        batch = titles[start : start + BATCH]
        cont: dict = {}
        while True:
            data = http.get_json(
                http.HE_API,
                {
                    "action": "query", "prop": "revisions|categories", "rvprop": "content", "rvslots": "main",
                    "cllimit": "max", "redirects": "1", "titles": "|".join(batch), "format": "json",
                    "formatversion": "2", **cont,
                },
            )  # fmt: skip
            for r in data.get("query", {}).get("normalized", []) + data.get("query", {}).get("redirects", []):
                redirects[r["from"]] = r["to"]
            merge_page_batch(pages, data)
            if "continue" not in data:
                break
            cont = data["continue"]
    return pages, redirects


def fetch_act_pages(acts: list[str]) -> dict[str, dict]:
    """{act: page}: the act's article (redirects followed); for a disambiguation page or no
    page, the usual disambiguated titles (``X (להקה)``, ``X (זמר)`` …) are tried next."""
    out: dict[str, dict] = {}
    todo = list(acts)
    for attempt in (lambda act: act_page_titles(act)[:1], lambda act: act_page_titles(act)[1:]):
        titles = [t for act in todo for t in attempt(act)]
        if not titles:
            break
        pages, redirects = _query_pages(titles)
        for act in todo:
            for title in attempt(act):
                target = redirects.get(redirects.get(title, title), redirects.get(title, title))
                page = pages.get(target)
                if page and page.get("wikitext") and not is_disambiguation(page) and is_music_act(page):
                    out[act] = {**page, "title": target}
                    break
        todo = [act for act in todo if act not in out]
    return out


# --- Putting it together -------------------------------------------------------------------


def established_for(title: str, known: list[dict]) -> dict | None:
    """The best-ranked known album (album page, then discography, then MusicBrainz) of this title."""
    rank = {PAGE: 0, DISCOGRAPHY: 1, MUSICBRAINZ: 2}
    agreeing = [k for k in known if k["title"] and title_score([k["title"]], [title]) >= MATCH_THRESHOLD]
    if not agreeing:
        return None
    return min(agreeing, key=lambda k: (rank[k["source"]], k["year"]))


def judge_albums(deezer_albums: list[dict], known: list[dict]) -> list[tuple[dict, str, dict | None]]:
    """(Deezer album, verdict, established album) for each Deezer album of the act."""
    out = []
    for album in deezer_albums:
        title = album.get("title") or ""
        if title_marks_other(title):
            out.append((album, NOT_STUDIO_TITLE, None))
            continue
        established = established_for(title, known)
        if established is None:
            out.append((album, NO_YEAR, None))
        elif established["kind"] in _OTHER_KINDS:
            out.append((album, established["kind"], established))
        elif CHILDREN_TITLE_RE.search(title) or CHILDREN_TITLE_RE.search(established["title"]):
            out.append((album, CHILDREN, established))
        elif established["kind"] not in STUDIO:
            out.append((album, NOT_STUDIO, established))
        elif not FIRST <= established["year"] <= LAST:
            out.append((album, OUT_OF_RANGE, established))
        else:
            out.append((album, TAKEN, established))
    return out


def best_rank(album: dict) -> int:
    return max((t.get("rank") or 0 for t in (album.get("tracks") or {}).get("data") or []), default=0)


def collect_act(
    act: str, known_pages: list[dict], act_page: dict | None, aliases: list[str] | tuple = ()
) -> tuple[list[dict], dict]:
    """Rows and a report for one act (its aliases are searched when its name finds no artist)."""
    report = {"act": act, "deezer_artists": [], "deezer_albums": 0, "established": 0, "albums": [], "tracks": 0}
    ids: list[int] = []
    for name in (act, *aliases):
        ids = deezer_artist_ids(name, http.get_json(DEEZER_ARTIST_SEARCH, {"q": name}))
        if ids:
            break
    report["deezer_artists"] = ids
    if not ids:
        return [], report
    deezer_albums: list[dict] = []
    for artist_id in ids:
        deezer_albums += [a for a in artist_albums(artist_id) if a["id"] not in {d["id"] for d in deezer_albums}]
    report["deezer_albums"] = len(deezer_albums)
    known = list(known_pages)
    if act_page:
        known += discography(act_page.get("wikitext") or "")
    mbid = None
    for name in (act, *aliases):
        mbid = mb_artist_id(name, http.get_json(MB_ARTIST, {"query": f'artist:"{name}"', "fmt": "json", "limit": "5"}))
        if mbid:
            break
    if mbid:
        known += mb_albums(mb_release_groups(mbid))
    judged = judge_albums(deezer_albums, known)
    # One Deezer copy per established album: the one whose best track ranks highest.
    chosen: dict[tuple, tuple[dict, dict]] = {}
    details: dict[int, dict] = {}
    for album, verdict, established in judged:
        if verdict != TAKEN:
            continue
        details[album["id"]] = http.get_json(DEEZER_ALBUM.format(album["id"]))
        if deezer_album_other(details[album["id"]]):
            continue
        key = (normalize(established["title"]), established["year"])
        if key not in chosen or best_rank(details[album["id"]]) > best_rank(details[chosen[key][0]["id"]]):
            chosen[key] = (album, established)
    taken_ids = {album["id"] for album, _ in chosen.values()}
    rows: list[dict] = []
    for album, verdict, established in judged:
        if verdict == TAKEN and album["id"] not in taken_ids:
            verdict = deezer_album_other(details[album["id"]]) or OTHER_COPY
        entry = {"deezer_album": album["id"], "title": album.get("title"), "verdict": verdict}
        if established:
            entry.update(year=established["year"], year_from=established["source"], known_as=established["title"])
        if verdict == TAKEN:
            album_rows = track_rows(act, ids, details[album["id"]], established)
            entry["tracks"] = len(album_rows)
            rows += album_rows
        report["albums"].append(entry)
    report["established"] = sum(1 for e in report["albums"] if "year" in e)
    report["tracks"] = len(rows)
    return rows, report


def act_album_pages(act: str, album_pages: list[dict]) -> list[dict]:
    """The known albums (source PAGE) of the album pages with a year whose credit has the act as one
    of its acts ("מתי כספי ושלמה גרוניך" counts for both; a guest after בהשתתפות does not)."""
    form = act_form(act)
    return [
        {"title": p["title"], "year": p["year"], "kind": p["kind"], "source": PAGE, "tracks": p.get("tracks") or []}
        for p in album_pages
        if p.get("year") and p.get("artist") and form in {act_form(a) for a in hebrew_acts(lead_act(p["artist"]))}
    ]


def collect(album_pages: list[dict], path: Path = ACTS_TXT) -> tuple[list[dict], list[dict]]:
    """Rows of every famous act, and one report per act.

    ``album_pages``: the Hebrew Wikipedia album pages already read by :mod:`hewiki_albums`, as
    [{"artist", "title", "year", "kind"}] — the act's own pages are the best evidence of a year.
    """
    acts = load_acts(path)
    aliases = load_aliases(path)
    act_pages = fetch_act_pages(acts)
    rows: list[dict] = []
    reports: list[dict] = []
    for act in acts:
        act_rows, report = collect_act(act, act_album_pages(act, album_pages), act_pages.get(act), aliases.get(act, []))
        report["act_page"] = (act_pages.get(act) or {}).get("title")
        rows += act_rows
        reports.append(report)
    return rows, reports
