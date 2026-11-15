"""Tracks of Hebrew albums that have a Hebrew Wikipedia page (source ``hewiki-albums``).

The pages of ``קטגוריה:אלבומים בעברית`` and of its sub-categories (minus the
compilation, live, mixtape and similar ones) give candidates when

* they are in a year category (``קטגוריה:אלבומי YYYY`` or, for a debut,
  ``קטגוריה:אלבומי בכורה מ-YYYY``) with 1960 ≤ YYYY ≤ 1999 — that year is the
  tracks' ``chart_year``;
* they are an original studio album or EP: not a compilation, live, remix,
  karaoke, video, tribute, covers, festival, soundtrack or multi-artist album (judged by
  the page's categories, the infobox's type and, for covers and tribute albums, the lead text);
* they are Hebrew songs: not a foreign-language album (categories, the infobox's ``שפה``), a
  children's or holiday album (categories, title, the infobox's ``סוגה``) or an instrumental one;
* the infobox names the performing act (``מאת``), one act or a duo/band.

Each track of the page's track list is one candidate: the ``{{רשימת השמעה}}``
template (``שםN``), or numbered ``#`` lines, or the rows of a wikitable whose first
cell is the track number, or (failing those) ``*`` lines, inside a section whose heading names
the songs or tracks. Titles are cleaned of durations, writer credits, bracketed
notes and footnotes. Bonus tracks of a re-issue, instrumental pieces, intros and
tracks sung by another performer are skipped.

Parsers are pure functions of the page data; :func:`collect` does the fetching.
"""

from __future__ import annotations

import re
from pathlib import Path

from .candidates import ALBUM_SOURCE
from .hewiki_songs import category_members, fetch_pages, template_params, templates
from .keys import normalize
from .wikitext import clean, strip_quotes

SOURCE = ALBUM_SOURCE
ROOT_CATEGORY = "קטגוריה:אלבומים בעברית"
KEEP_TXT = Path(__file__).resolve().parent.parent / "data" / "album_keep.txt"
FIRST, LAST = 1960, 1999

# Sub-categories that hold no studio albums of new recordings ("אלבומי אוסף בעברית",
# "אלבומי הופעה של משינה", "מיקסטייפים בעברית", …).
SKIP_CATEGORY_RE = re.compile(r"^קטגוריה:(?:אלבומי אוסף|אלבומי הופעה|מיקסטייפים)")

# "אלבומי 1985", a debut "אלבומי בכורה מ-1981", an EP "מיני-אלבומים מ-1990" / "מיני-אלבומי בכורה מ-1990".
_YEAR_CATEGORY_RE = re.compile(r"^קטגוריה:(?:אלבומי (?:בכורה מ-)?|מיני-אלבומים מ-|מיני-אלבומי בכורה מ-|מיני אלבומים מ-)(\d{4})$")
_DISAMBIGUATION_RE = re.compile(r"\s*\([^()]*\)\s*$")

# Category words that mark an album as something other than a studio album of new recordings.
_KIND_CATEGORY_WORDS = (
    ("compilation", re.compile(r"אלבומי אוסף|אוספי|אלבומי להיטים")),
    ("live", re.compile(r"אלבומי הופעה|אלבומי הופעות|הופעות חיות")),
    ("remix", re.compile(r"רמיקס|רימיקס")),
    ("karaoke", re.compile(r"קריוקי")),
    ("video", re.compile(r"אלבומי וידאו|DVD|וידאו")),
    ("tribute", re.compile(r"אלבומי מחווה|מחווה")),
    ("covers", re.compile(r"גרסאות כיסוי|גרסות כיסוי|אלבומי קאברים")),  # "אלבומי גרסאות כיסוי"
    ("soundtrack", re.compile(r"פסקול|פסקולים|מחזות זמר|אלבומי מחזמר")),
    ("festival", re.compile(r"פסטיבל|אירוויזיון")),
    ("multi-artist", re.compile(r"מרובי משתתפים|אמנים שונים|אלבומים שיתופיים")),
)
# The infobox's type (``סוג``) words for the same.
_KIND_TYPE_WORDS = (
    ("compilation", re.compile(r"אוסף|להיטים|המיטב")),
    ("live", re.compile(r"הופעה|חי(?![א-ת])|לייב")),
    ("remix", re.compile(r"רמיקס|רימיקס")),
    ("karaoke", re.compile(r"קריוקי|פלייבק")),
    ("video", re.compile(r"וידאו|DVD")),
    ("tribute", re.compile(r"מחווה")),
    ("covers", re.compile(r"כיסוי|קאברים")),  # "סוג = גרסאות כיסוי"
    ("soundtrack", re.compile(r"פסקול|מחזמר")),
    ("festival", re.compile(r"פסטיבל")),
)
# A sentence of the lead about the album itself ("X הוא אלבום כפול של גרסאות כיסוי", "האלבום מהווה
# מחווה לאלבומם של …"); a sentence about a later tribute or one cover song does not count.
_LEAD_KIND_WORDS = (
    ("covers", re.compile(r"אלבום(?: [^ .,]+)? של (?:גרסאות|גרסות) כיסוי|אלבום (?:גרסאות|גרסות) כיסוי")),
    ("tribute", re.compile(r"אלבום (?:ה)?מחווה|(?:מהווה|היווה|הוא|היה) (?:מעין )?מחווה ל")),
)
_KIND_TITLE_WORDS = (
    ("compilation", re.compile(r"להיטים|הלהיטים|המיטב|(?<![א-ת])אוסף|האוסף")),
    ("live", re.compile(r"בהופעה|הופעה חיה|(?<![א-ת])live(?![a-z])", re.I)),
)
INFOBOX_NAMES = ("אלבום",)
PERFORMER_PARAMS = ("מאת", "אמן", "מבצע", "ביצוע")
TYPE_PARAMS = ("סוג", "סוג אלבום")
_MANY_ACTS_RE = re.compile(r"אמנים שונים|משתתפים שונים|מבצעים שונים|זמרים שונים|שונים")

CANDIDATE = "candidate"
REDIRECT, NO_YEAR, YEAR_OUT_OF_RANGE, NOT_STUDIO, NO_PERFORMER, NO_TRACKS = (
    "redirect", "no year category", "year outside 1960–1999", "not a studio album", "no performer",
    "no track list parsed",
)  # fmt: skip
# Round 2: albums that give no rows because the game is played on Hebrew songs.
FOREIGN, CHILDREN, INSTRUMENTAL = "foreign-language album", "children's or holiday album", "instrumental or jazz album"
REASONS = (CANDIDATE, REDIRECT, NOT_STUDIO, FOREIGN, CHILDREN, INSTRUMENTAL, NO_YEAR, YEAR_OUT_OF_RANGE, NO_PERFORMER,
           NO_TRACKS)  # fmt: skip
_HEBREW_LETTER_RE = re.compile(r"[א-ת]")
# "אלבומים ביידיש", "מיני-אלבומים באנגלית": a language other than Hebrew.
_LANGUAGE_CATEGORY_RE = re.compile(
    r"^קטגוריה:(?:מיני-)?אלבומים ב(?:אנגלית|יידיש|ערבית|צרפתית|ספרדית|לאדינו|גרמנית|איטלקית|פורטוגזית|יוונית|"
    r"רוסית|אמהרית|טורקית|פרסית)$"
)
_CHILDREN_CATEGORY_RE = re.compile(r"לילדים|שירי ילדים|קלטות ילדים")
CHILDREN_TITLE_RE = re.compile(r"(?<!לא )לילדים|שירי ילדים|שירי חגים|לחגים|הגדה|פסטיגל|שירים לפסח|שירי חנוכה")
_INSTRUMENTAL_GENRE_RE = re.compile(r"אינסטרומנטל|מוזיקה כלית")
_JAZZ_RE = re.compile(r"ג['׳]אז|מוזיקה קלאסית")
_SUNG_GENRE_RE = re.compile(r"פופ|רוק|פולק|זמר|ווקאל|קאנטרי|היפ הופ|עולם|לטינית|יהודית|מזרחית")
_PLAYS_TITLE_RE = re.compile(r"(?<![א-ת])מנג(?:ן|נים|נת)(?![א-ת])")


def is_hebrew_title(title: str) -> bool:
    return bool(_HEBREW_LETTER_RE.search(title))


def not_hebrew_songs(categories: list[str], box: dict[str, str] | None, title: str, track_titles: list[str]) -> str | None:
    """FOREIGN, CHILDREN or INSTRUMENTAL for an album the game should not take songs from, else None.

    Foreign: a language category other than Hebrew without the Hebrew one (an album in both is a
    Hebrew album with a song or two in another language), an infobox language (שפה) that does not
    name Hebrew, or most track titles in Latin letters.
    Children's or holiday: a children's category or title (לילדים, שירי חגים, הגדה, פסטיגל), or a
    children's genre (סוגה = שירי ילדים).
    Instrumental: an instrumental genre (סוגה), a "… מנגן/מנגנים …" title, or jazz/classical (genre or
    category) with no sung genre named beside it.
    """
    hebrew = any(c in ("קטגוריה:אלבומים בעברית", "קטגוריה:מיני-אלבומים בעברית") for c in categories)
    if not hebrew and any(_LANGUAGE_CATEGORY_RE.match(c) for c in categories):
        return FOREIGN
    language = clean((box or {}).get("שפה", ""))
    if language and "עברית" not in language:  # the infobox names the album's language(s), none of them Hebrew
        return FOREIGN
    if track_titles and sum(not is_hebrew_title(t) for t in track_titles) * 2 > len(track_titles):
        return FOREIGN
    genre = clean((box or {}).get("סוגה", ""))
    if any(_CHILDREN_CATEGORY_RE.search(c) for c in categories) or CHILDREN_TITLE_RE.search(title) or "ילדים" in genre:
        return CHILDREN
    if _INSTRUMENTAL_GENRE_RE.search(genre) or _PLAYS_TITLE_RE.search(title):
        return INSTRUMENTAL
    # Jazz (or classical) only when no sung genre is named with it: "רוק, ג'אז" is a sung album.
    jazz = _JAZZ_RE.search(genre) or any(_JAZZ_RE.search(c) for c in categories)
    if jazz and not _SUNG_GENRE_RE.search(genre):
        return INSTRUMENTAL
    return None
# Track-list forms met.
TEMPLATE, NUMBERED, BULLETED, TABLE = "template", "numbered list", "bulleted list", "table"


def category_year(categories: list[str]) -> int | None:
    years = [int(m.group(1)) for c in categories if (m := _YEAR_CATEGORY_RE.match(c))]
    return min(years) if years else None


def album_title(page_title: str) -> str:
    """'שלום חנוך (אלבום)' → 'שלום חנוך'."""
    return _DISAMBIGUATION_RE.sub("", page_title).strip() or page_title


def infobox(wikitext: str) -> dict[str, str] | None:
    for name, body in templates(wikitext):
        if name.replace("_", " ").strip() in INFOBOX_NAMES:
            return template_params(body)
    return None


def album_kind(categories: list[str], box: dict[str, str] | None, title: str = "") -> str | None:
    """None for a studio album or EP, else what it is ('compilation', 'live', ...): by the page's
    categories, the infobox's type, or a title that says so ("… בלהיטים הגדולים", "… בהופעה")."""
    for kind, pattern in _KIND_TITLE_WORDS:
        if pattern.search(title):
            return kind
    for kind, pattern in _KIND_CATEGORY_WORDS:
        if any(pattern.search(c) for c in categories):
            return kind
    for param in TYPE_PARAMS:
        value = clean((box or {}).get(param, ""))
        for kind, pattern in _KIND_TYPE_WORDS:
            if value and pattern.search(value):
                return kind
    return None


def _without_templates(wikitext: str) -> str:
    out, depth, i = [], 0, 0
    while i < len(wikitext):
        if wikitext.startswith("{{", i):
            depth, i = depth + 1, i + 2
        elif wikitext.startswith("}}", i) and depth:
            depth, i = depth - 1, i + 2
        else:
            if depth == 0:
                out.append(wikitext[i])
            i += 1
    return "".join(out)


def lead_kind(wikitext: str, title: str) -> str | None:
    """'covers' or 'tribute' when the lead text (before the first heading) says the album is one:
    a sentence that starts with the album's title or with "האלבום" / "אלבום זה" and calls it a
    covers album or a tribute."""
    lead = clean(re.split(r"^=", _without_templates(wikitext), maxsplit=1, flags=re.M)[0])
    starts = tuple(s for s in (strip_quotes(title), "האלבום", "אלבום זה") if s)
    for sentence in re.split(r"(?<=[.!?])\s+", lead):
        if not sentence.lstrip("\"“”„״' ").startswith(starts):
            continue
        for kind, pattern in _LEAD_KIND_WORDS:
            if pattern.search(sentence):
                return kind
    return None


def performer(box: dict[str, str] | None) -> str | None:
    """The act the infobox names (``מאת``): one act, a duo or a band; None for many acts."""
    box = box or {}
    # Failing מאת, the chronology template inside the infobox names the act ({{כרונולוגיה|מאת=…}}).
    chronology = [template_params(body) for _, body in templates(box.get("כרונולוגיה", ""))]
    values = [box.get(param, "") for param in PERFORMER_PARAMS] + [c.get("מאת", "") for c in chronology]
    for raw in values:
        if not raw.strip():
            continue
        text = strip_quotes(clean(re.sub(r"<br\s*/?>", " ו", raw)).strip(" ,.:"))
        if not text or not re.search(r"[א-תA-Za-z]", text) or _MANY_ACTS_RE.search(text):
            return None
        if text.count(",") >= 2:  # a list of acts
            return None
        return text
    return None


# --- Track lists ------------------------------------------------------------------------------

_SECTION_RE = re.compile(r"רשימת (?:ה)?(?:שירים|רצועות|השירים|הרצועות)|שירי האלבום|רצועות|תוכן האלבום|השירים באלבום|צד א|צד ב")
_HEADING_RE = re.compile(r"^(=+)\s*(.*?)\s*\1\s*$")
_PART_RE = re.compile(r"צד|דיסק|תקליטור|תקליט|קלטת|\bCD\b|חלק|\bLP\b", re.I)
_BONUS_RE = re.compile(r"בונוס|bonus|הוצאה מחודשת|במהדורת (?:ה)?(?:תקליטור|דיסק|CD)|בגרסת (?:ה)?(?:תקליטור|דיסק|CD)|הוצאת (?:ה)?תקליטור", re.I)
_INSTRUMENTAL_RE = re.compile(r"קטע כלי|אינסטרומנטל|instrumental|נגינה בלבד", re.I)
_INTRO_RE = re.compile(r"^(?:פתיח|פתיחה|מבוא|אינטרו|intro|outro|אאוטרו|סיום|הקדמה|מעבר|interlude|סקיט|skit)(?:\s*\d*)?$", re.I)
_OTHER_SINGER_RE = re.compile(
    r"(?<![א-ת])(?:(?:שירה|שרה|שר|שרים|סולו|סולן|סולנית|מבצע|מבצעת|ביצוע|ביצועים)\s*:|בביצוע(?:ו|ה|ם|ן)?\s)\s*([^,;)]+)"
)
_DURATION_RE = re.compile(r"\(?\s*\d{1,2}:\d{2}\s*\)?")
_REF_RE = re.compile(r"<ref[^>/]*/>|<ref[^>]*>.*?</ref>|\{\{הערה\|.*?\}\}", re.S | re.I)
_QUOTED_RE = re.compile(r"^[\"“”„״']+([^\"“”„״]+)[\"“”„״']+")


def clean_track(text: str) -> str:
    """'"ילד מזדקן" (מילים: ..., לחן: ...) – 3:45' → 'ילד מזדקן'."""
    text = _REF_RE.sub("", text)
    text = re.sub(r"^[#*:;\s]+", "", text)
    text = re.sub(r"^\d+\s*[.)]\s*", "", text)  # "1. title" in a bulleted or table list
    plain = clean(text)
    quoted = _QUOTED_RE.match(plain)
    if quoted:
        plain = quoted.group(1)
    else:
        head = re.split(r"\s+[-–—]\s+|\s*\(|\s*\[", plain, maxsplit=1)[0]
        # A title that starts with a bracket ("(That's Not) Carol") keeps its words.
        plain = head if normalize(head) else re.split(r"\s+[-–—]\s+", plain, maxsplit=1)[0]
    plain = _DURATION_RE.sub(" ", plain)
    unbracketed = re.sub(r"\([^()]*\)|\[[^\[\]]*\]", " ", plain)
    plain = unbracketed if normalize(unbracketed) else plain.replace("(", " ").replace(")", " ")
    plain = re.sub(r"[{}\[\]|*]", " ", plain)  # leftovers of a broken template or link, footnote stars
    return strip_quotes(re.sub(r"\s+", " ", plain).strip(" -–—,.:;"))


def _skip_track(title: str, note: str, act: str) -> bool:
    """A bonus track, an instrumental, an intro, or a track sung by someone other than the act."""
    if not title or not normalize(title):
        return True
    if _BONUS_RE.search(note) or _INSTRUMENTAL_RE.search(note) or _INSTRUMENTAL_RE.search(title):
        return True
    if _INTRO_RE.match(title):
        return True
    if ":" in title or ";" in title or title.startswith("מחרוזת"):  # a medley, or a note line ("בתקליט: שירים 1–5")
        return True
    if re.search(r"[^\W\d_]{2,}\W*\s*/\s*\W*[^\W\d_]{2,}", title):  # two songs in one track ("ליבי ליבי/סחרחורת"); not "7/8"
        return True
    singer = _OTHER_SINGER_RE.search(clean(note))
    if singer:
        name = normalize(singer.group(1))
        return bool(name) and normalize(act) not in name and name not in normalize(act)
    return False


def template_tracks(wikitext: str) -> list[tuple[str, str]] | None:
    """(title, note) per ``שםN`` of every track-list template, in order; None if there is none."""
    found = None
    for name, body in templates(wikitext):
        if name.strip() not in ("רשימת השמעה", "רשימת שירים", "Track listing", "רשימת רצועות"):
            continue
        params = template_params(body)
        if _BONUS_RE.search(clean(params.get("כותרת", "") + " " + params.get("headline", ""))):
            continue  # a bonus-tracks list of a re-issue
        found = found or []
        numbers = sorted(int(m.group(1)) for k in params if (m := re.fullmatch(r"(?:שם|title)(\d+)", k)))
        for n in numbers:
            title = params.get(f"שם{n}") or params.get(f"title{n}") or ""
            note = " ".join(params.get(f"{p}{n}", "") for p in ("הערה", "הערות", "note", "extra", "ביצוע", "שירה"))
            if f"מילים{n}" in params and not clean(params[f"מילים{n}"]) and clean(params.get(f"לחן{n}", "")):
                note += " קטע כלי"  # a composer and an empty lyricist: an instrumental piece
            found.append((title, note))
    return found


def _track_sections(wikitext: str) -> list[list[str]]:
    """The lines of each section whose heading names the track list (bonus sections left out)."""
    sections: list[list[str]] = []
    level = None
    current: list[str] | None = None
    for raw in wikitext.split("\n"):
        line = raw.strip()
        heading = _HEADING_RE.match(line)
        if heading:
            depth, name = len(heading.group(1)), clean(heading.group(2))
            if level is not None and depth > level:  # a sub-heading: side A/B or a disc; not bonus tracks or credits
                wanted = (_SECTION_RE.search(name) or _PART_RE.search(name)) and not _BONUS_RE.search(name)
                current = [] if wanted else None
                if current is not None:
                    sections.append(current)
                continue
            level, current = None, None
            if _SECTION_RE.search(name) and not _BONUS_RE.search(name):
                level, current = depth, []
                sections.append(current)
            continue
        if current is None:
            continue
        # A bold or plain line about bonus tracks ends the album's own list.
        if not line.startswith(("#", "*", "|", "!")) and _BONUS_RE.search(line):
            current = None
            continue
        current.append(line)
    return sections


def list_tracks(wikitext: str) -> tuple[list[tuple[str, str]], str | None]:
    """(title text, note) per track of the track-list sections: numbered lines, else table rows, else bulleted lines."""
    sections = _track_sections(wikitext)
    lines = [line for s in sections for line in s]
    numbered = [line for line in lines if re.match(r"#(?![#*:])", line)]
    if numbered:
        return [(line, line) for line in numbered], NUMBERED
    rows = table_rows(lines)
    if rows:
        return rows, TABLE
    bulleted = [line for line in lines if re.match(r"\*(?![*:])", line)]
    if bulleted:
        return [(line, line) for line in bulleted], BULLETED
    return [], None


def table_rows(lines: list[str]) -> list[tuple[str, str]]:
    """(title cell, whole row) per wikitable row whose first cell is a track number."""
    out = []
    cells: list[str] = []

    def end() -> None:
        if len(cells) >= 2 and re.fullmatch(r"\s*\d+\s*\.?\s*", clean(cells[0]) or "x"):
            out.append((cells[1], " ".join(cells)))
        cells.clear()

    for line in lines:
        if line.startswith(("|-", "|}", "{|")):
            end()
        elif line.startswith("!"):
            cells.clear()
        elif line.startswith("|"):
            body = re.sub(r"^\|\s*(?:[a-z\-]+=\"[^\"]*\"\s*\|)?", "", line)
            cells.extend(c.strip() for c in body.split("||"))
    end()
    return out


def tracks(wikitext: str, act: str) -> tuple[list[str], str | None, int]:
    """(clean titles, the form met, tracks skipped) for one album page."""
    found = template_tracks(wikitext)
    form = TEMPLATE if found else None
    if not found:
        found, form = list_tracks(wikitext)
    titles, skipped = [], 0
    for raw, note in found:
        title = clean_track(raw)
        if note == raw:  # a list line or table row: the note is the line without its title
            note = clean(raw).replace(title, " ", 1)
        if _skip_track(title, note, act):
            skipped += 1
            continue
        if title not in titles:
            titles.append(title)
    return titles, form, skipped


# --- One page, and the whole source ----------------------------------------------------------


def load_keep(path: Path | None = None) -> frozenset[str]:
    """Album page titles from ``data/album_keep.txt``: pages taken as studio albums although a rule
    (covers, tribute, …) says otherwise. One page title per line; '#' starts a comment."""
    path = KEEP_TXT if path is None else path
    if not path.exists():
        return frozenset()
    lines = (line.split("  #")[0].strip() for line in path.read_text(encoding="utf-8").splitlines())
    return frozenset(line for line in lines if line and not line.startswith("#"))


def parse_page(title: str, page: dict, keep: frozenset[str] = frozenset()) -> dict:
    """{"reason", "rows", "album": {...}, "form", "skipped"} for one album page.

    A page whose title is in ``keep`` is not judged by the album-kind rules.

    ``album`` (artist, title, year, kind, tracks) is filled for every page that is not a
    redirect, also for albums out of range or not studio: :mod:`famous_acts` uses them.
    """
    wikitext = page.get("wikitext") or ""
    out = {"reason": None, "rows": [], "album": None, "form": None, "skipped": 0, "latin": 0}
    if re.match(r"\s*#(?:הפניה|redirect)", wikitext, re.I):
        out["reason"] = REDIRECT
        return out
    categories = page.get("categories") or []
    year = category_year(categories)
    box = infobox(wikitext)
    kind = None if title in keep else album_kind(categories, box, album_title(title)) or lead_kind(wikitext, album_title(title))
    artist = performer(box)
    titles, form, skipped = tracks(wikitext, artist or "") if box else ([], None, 0)
    other = None if kind else not_hebrew_songs(categories, box, album_title(title), titles)
    out["album"] = {"title": album_title(title), "artist": artist, "year": year, "kind": kind or other or "album",
                    "tracks": titles}
    if kind:
        out["reason"] = NOT_STUDIO
    elif other:
        out["reason"] = other
    elif year is None:
        out["reason"] = NO_YEAR
    elif not FIRST <= year <= LAST:
        out["reason"] = YEAR_OUT_OF_RANGE
    elif not artist:
        out["reason"] = NO_PERFORMER
    if out["reason"]:
        return out
    out.update(form=form, skipped=skipped)
    if not titles:
        out["reason"] = NO_TRACKS
        return out
    out["reason"] = CANDIDATE
    hebrew = [t for t in titles if is_hebrew_title(t)]  # a Latin-titled track of a Hebrew album gives no row
    out["latin"] = len(titles) - len(hebrew)
    out["rows"] = [
        {
            "title": t, "artist": artist, "credit": artist, "chart_year": year, "origin": "IL", "source": SOURCE,
            "position": 0, "title_link": None, "artist_link": None, "extended": True, "album": album_title(title),
        }
        for t in hebrew
    ]  # fmt: skip
    return out


def album_pages(root: str = ROOT_CATEGORY, skip: re.Pattern = SKIP_CATEGORY_RE) -> tuple[list[str], list[str]]:
    """(page titles, categories walked) under ``root`` and its sub-categories, minus those ``skip`` matches."""
    pages: set[str] = set()
    walked: list[str] = []
    todo = [root]
    while todo:
        category = todo.pop(0)
        if category in walked or skip.match(category):
            continue
        walked.append(category)
        pages.update(category_members(category, "page"))
        todo += category_members(category, "subcat")
    return sorted(pages), walked


def collect() -> tuple[list[dict], dict, list[dict]]:
    """Candidate rows; a report {"categories", "pages", "reasons": {reason: [titles]}, "forms", "skipped"};
    and the albums every page describes (for :mod:`famous_acts`)."""
    titles, walked = album_pages()
    pages = fetch_pages(titles)
    rows: list[dict] = []
    reasons: dict[str, list[str]] = {r: [] for r in REASONS}
    forms: dict[str, int] = {}
    albums: list[dict] = []
    skipped = latin = 0
    keep = load_keep()
    for title in titles:
        parsed = parse_page(title, pages.get(title, {}), keep)
        reasons[parsed["reason"]].append(title)
        if parsed["album"]:
            albums.append(parsed["album"])
        if parsed["form"]:
            forms[parsed["form"]] = forms.get(parsed["form"], 0) + 1
        skipped += parsed["skipped"]
        latin += parsed["latin"]
        rows += parsed["rows"]
    report = {"categories": walked, "pages": len(titles), "reasons": reasons, "forms": forms, "skipped_tracks": skipped,
              "latin_tracks": latin}
    return rows, report, albums
