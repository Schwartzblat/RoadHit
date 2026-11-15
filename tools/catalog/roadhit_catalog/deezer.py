"""Stage 2: find each candidate's track on Deezer and apply the popularity floor.

The matching rules are pure functions of parsed JSON; :class:`Matcher` gets
its answers from an ``api`` object (:class:`DeezerApi` in real runs, canned
fixtures in tests), so the rules are testable without the network.

Precision comes first: a wrong match (another song, a cover, a live take) is
worse than a dropped song, so a result is accepted only when

- its title matches the chart title (``token_set_ratio`` >= 85 on normalised
  text, like script against like script, with version suffixes such as
  "(Remastered 2009)" or " - Single Version" removed, every word of each
  title matched in the other, so "Believe Me" is not "Believe");
- its artist matches the candidate's: directly in the same script, or, for a
  Hebrew credit and a Latin Deezer name, through the English Wikipedia name of
  the Hebrew article, or failing that when Deezer's own artist search for the
  Hebrew name returns that very artist first;
- one-word artist names agree exactly, and a longer Deezer name only as
  "Credit & ..." / "... & Credit" ("Celtic Pink Floyd" is not Pink Floyd);
- neither its title nor its album marks another recording (live, cover,
  karaoke, remix, re-make, unknown "... Version") and its artist name marks
  no tribute act, unless the chart title says the same;
- it has a preview clip.
"""

from __future__ import annotations

import csv
import re
import tomllib
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from rapidfuzz import fuzz

from . import http
from .candidates import ALBUM_SOURCE, CHART_SOURCE, CLASSICS_SOURCE, EXTRAS_SOURCE, FAMOUS_SOURCE, ROOT
from .hewiki_songs import SOURCE as SONG_ARTICLE_SOURCE
from .keys import normalize
from .latin import strip_disambiguation

SEARCH_URL = "https://api.deezer.com/search"
ARTIST_SEARCH_URL = "https://api.deezer.com/search/artist"
ALBUM_URL = "https://api.deezer.com/album/{}"
TRACK_URL = "https://api.deezer.com/track/{}"
SEARCH_LIMIT = "25"

MATCH_THRESHOLD = 85  # token_set_ratio for titles and artists
SECOND_LOOK = 92  # matches scoring below this on title or artist are listed for review
# token_set_ratio says 100 when one title's words are a subset of the other's
# ("Love" vs "Love Me Do"); the word-order-free full ratio must also agree.
TITLE_SORT_MIN = 75
WORD_MIN = 80  # fuzz.ratio for two spellings of one word (4+ letters) in multi-word titles
ALBUMS_TO_TRY = 5
# The album-artist preference may pick a track only within this share of the
# best accepted track's rank: a budget reissue filed under the artist's own name
# must not beat a far more played original on a soundtrack or compilation.
ALBUM_PREFERENCE_BAND = 0.5
# A rejected same-artist "… Mix"/"… Edit" that outranks every accepted copy by
# this factor is the hit version: the candidate becomes no_match instead of
# taking a low-ranked copy (Here Comes the Hotstepper (Heartical Mix)).
BLOCKING_MIX_FACTOR = 3
POOLED_QUERIES = 3  # the advanced query and the two plain ones are searched together
# Deezer gives exactly this rank to tracks it has no play data for (seen on
# obscure 1940s–60s reissues and karaoke uploads alike): it says nothing about fame.
PLACEHOLDER_RANK = 100000

FLOORS_TOML = ROOT / "data" / "floors.toml"
KEEP_TXT = ROOT / "data" / "keep.txt"
OVERRIDES_CSV = ROOT / "data" / "track_overrides.csv"
EXCLUDE_TXT = ROOT / "data" / "exclude.txt"
FAMOUS_TXT = ROOT / "data" / "famous_acts.txt"
ERAS = ("since_2000", "from_1970", "before_1970")
# Optional IL floors (stage B) for the chart rows and the album rows of 1960–1999.
KIND_FLOORS = ("chart_from_1970", "chart_before_1970", "album_from_1970", "album_before_1970")
KIND_FIRST_YEAR, KIND_LAST_YEAR = 1960, 1999

MATCHED, NO_MATCH, NO_PREVIEW = "matched", "no_match", "no_preview"
BELOW_FLOOR, DUPLICATE_TRACK, EXCLUDED = "below_floor", "duplicate_track", "excluded"
REASONS = (EXCLUDED, NO_MATCH, NO_PREVIEW, DUPLICATE_TRACK, BELOW_FLOOR)

_HEBREW_RE = re.compile(r"[א-ת]")
_BRACKETS_RE = re.compile(r"\s*[(\[][^()\[\]]*[)\]]")
_DASH_SUFFIX_RE = re.compile(r"\s+[-–—]\s+.*$")

# Words marking a different recording. Latin ones are matched as whole words in
# the normalised text, Hebrew ones anywhere (Hebrew prefixes attach: בהופעה חיה).
_LATIN_MARKERS = (
    "live", "live at", "en vivo", "ao vivo", "en directo", "unplugged", "concert",
    "cover", "covers", "karaoke", "remix", "remixed", "tribute", "instrumental",
    "made famous by", "in the style of", "originally performed by", "as popularized by",
    "re recorded", "rerecorded", "re recording", "taylors version",
    "medley", "acoustic", "demo", "outtake", "rehearsal", "sped up", "slowed", "nightcore",
    "lullaby", "piano version", "orchestral version", "8 bit",
    "symphony orchestra", "philharmonic", "en espanol", "spanish version", "version espanol",
    "new recording", "newly recorded", "re record", "rerecording", "soundalike", "sound alike",
    "alternate", "alternative version", "alternative single", "98", "2 0", "3 0",
    "backing track", "extended", "long version", "club", "dub", "sessions", "rmx",
    "out take", "reprise", "new version", "pianoforte", "latin version",
    "12 version", "12 mix", "12 inch", "mixed by", "alternative", "commentary", "itunes", "ringtone",
    "reunited",  # Cliff Richard & The Shadows' 2009 re-recordings album
    "a cappella", "acapella", "dj", "interlude", "vip",
)  # fmt: skip
# Markers judged in track titles only: albums "Them vs. You vs. Me" and
# "Halo - Single & Remixes" hold the original recordings too.
_TRACK_ONLY_MARKERS = ("vs", "remixes", "long", "christmas")  # albums "The Long Road", "It Won't Be Soon Before Long"
_HEBREW_TRACK_ONLY_MARKERS = ("הופעה", "מוסיקה", "מוזיקה")  # albums "מוזיקה ושקט"
# Words that mark a tribute or cover act in a Deezer artist name ("The Beatles Revival Band").
_ARTIST_MARKERS = (
    "revival", "tribute", "cover", "covers", "karaoke", "made famous by", "in the style of",
    "originally performed by", "soundalike", "sound alike", "piano", "lullaby",
)  # fmt: skip
_HEBREW_MARKERS = (
    "הופעה חיה", "הופעות", "בהופעה", "לייב", "רמיקס", "קריוקי", "גרסת כיסוי", "מחרוזת",
    "אקוסטי", "הקלטה", "הקלטות", "רימיקס",
)  # fmt: skip
# Markers added with the extended candidates (stage il-3000). They judge only ``extended``
# candidates: two existing rows play a track that they would reject (a tribute album's
# אדם צובר זיכרונות, Chuck Berry's School Days from "After School Session").
_EXTENDED_HEBREW_MARKERS = (
    "אינסטרומנטלי",  # also אינסטרומנטלית
    "מחווה", "חברים שרים",  # tribute albums ("חברים שרים קורין", "אלבום מחווה ל…")
    "ההופעה",  # live albums ("… - ההופעה"); plain "הופעה" stays track-only
    "חזרות", "סשן",  # rehearsal and session recordings
)  # fmt: skip
_EXTENDED_LATIN_MARKERS = ("session",)
# Takes, sketches, demos and a-cappella versions (fix round), whole Hebrew words in a track title of an
# extended candidate ("(טייק 2)", "(סקיצה)", "(א-קפלה)"); not in album titles (היי פייב's album "טייק 1+2").
_EXTENDED_TAKE_RE = re.compile(r"(?<![א-ת])(?:טייק|טייקים|סקיצה|סקיצות|דמו|א קפלה|אקפלה|אקאפלה)(?![א-ת])")
_TAKE_RE = re.compile(r"\btake \d+\b|\b(?:19|20)\d\d version\b|\bversion (?:19|20)\d\d\b")
# Version notes that do not change the recording ("Remastered 2009", "Single Version", "feat. X").
_CLEAN_VERSION_RE = re.compile(
    r"remaster|single|album version|mono|stereo|radio edit|radio version|original|\bedit\b|"
    r"feat|\bft\b|\bwith\b|\bfrom\b|bonus|deluxe|explicit|clean|duet|duett|main version|\b7\b|anniversary|expanded|soundtrack",
    re.I,
)

_ACT_SEPARATORS_RE = re.compile(
    r"\s*,\s*|\s+[-–]\s+|\s*\([^()]*\)?\s*|\s*&\s*|\s+(?:עם|ביחד עם|בהשתתפות|בשיתוף|בליווי|מארח|מארחת|מארחים|"
    r"feat\.?|ft\.?|featuring)(?:\s+את)?\s+"
)
# A joining ו before a later word; not "וו", which starts foreign names (בוב וולש).
_VAV_SPLIT_RE = re.compile(r"\s+ו(?=[א-ת]{2})(?!ו)")


def is_hebrew(text: str | None) -> bool:
    return bool(text) and bool(_HEBREW_RE.search(text))


def clean_title(title: str) -> str:
    """Drop bracketed parts and a spaced-dash suffix: 'X (Remastered 2009)', 'X - Single Version'."""
    text, previous = title, None
    while text != previous:
        previous, text = text, _BRACKETS_RE.sub("", text)
    text = _DASH_SUFFIX_RE.sub("", text).strip()
    return text or title.strip()


def _forms(texts) -> list[str]:
    out = []
    for text in texts:
        if not text:
            continue
        for form in (text, clean_title(text)):
            n = normalize(form)
            if n and n not in out:
                out.append(n)
    return out


_WORD_ALIASES = {"pt": "part", "i": "1", "ii": "2", "iii": "3", "n": "and"}
_HEBREW_PREFIXES = "הושבלמכ"


_STOPWORDS = {"the", "a", "and", "song"}


def _hebrew_skeleton(word: str) -> str:
    """Spelling without inner ו/י (ktiv male vs haser): הכול = הכל, שמיים = שמים, עליי = עלי."""
    return word[:1] + re.sub("[וי]", "", word[1:]) if is_hebrew(word) else word


def _words(text: str) -> list[str]:
    words = [_hebrew_skeleton(_WORD_ALIASES.get(w, w)) for w in text.split()]
    return [w for w in words if w not in _STOPWORDS] or words


def _same_word(a: str, b: str, fuzzy: bool) -> bool:
    """Equal; or one Hebrew prefix letter apart (הקנאה = קנאה); or, if allowed, close spellings."""
    if a == b:
        return True
    short, long_ = sorted((a, b), key=len)
    if is_hebrew(long_) and len(long_) == len(short) + 1 and long_[0] in _HEBREW_PREFIXES and long_[1:] == short:
        return True
    return fuzzy and len(short) >= 4 and fuzz.ratio(a, b) >= WORD_MIN


def words_agree(a: str, b: str) -> bool:
    """Every word of each title has a counterpart in the other.

    token_set_ratio alone accepts "Believe" for "Believe Me" and "American Pie"
    for "American Life"; this check does not. One-word titles must agree exactly
    (no fuzzy spelling: "Hero" is not "Héroe").
    """
    wa, wb = _words(a), _words(b)
    if not wa or not wb:
        return False
    if "".join(wa) == "".join(wb):
        return True
    if sorted(wa) == sorted(wb):
        return wa == wb  # same words in another order is another title ("5 to 9" / "9 to 5")
    fuzzy = len(wa) > 1 and len(wb) > 1
    return all(any(_same_word(x, y, fuzzy) for y in wb) for x in wa) and all(
        any(_same_word(y, x, fuzzy) for x in wa) for y in wb
    )


def title_score(candidate_titles: list[str], deezer_titles: list[str]) -> int:
    """Best title agreement between candidate and Deezer titles of the same script (0..100)."""
    best = 0
    for c in _forms(candidate_titles):
        for d in _forms(deezer_titles):
            if is_hebrew(c) != is_hebrew(d) or not words_agree(c, d):
                continue
            score = fuzz.token_set_ratio(c, d)
            order = fuzz.token_sort_ratio(c, d)
            if order < TITLE_SORT_MIN:
                score = min(score, order)
            best = max(best, round(score))
    return best


def _strip_vav(text: str) -> str:
    """'שלמה ארצי ועמיר לב' → 'שלמה ארצי עמיר לב' (a joining ו before a later word)."""
    words = text.split()
    return " ".join(words[:1] + [w[1:] if w.startswith("ו") and len(w) > 2 else w for w in words[1:]])


# Extra words in a Deezer name that make it another act ("The Police Project").
_OTHER_ACT_WORDS = {
    "project", "band", "tribute", "revival", "revived", "experience", "singers", "players", "ensemble",
    "allstars", "kids", "cover", "covers", "symphony", "philharmonic", "quartet", "trio",
}  # fmt: skip


def _longer_name_ok(c: str, deezer_raw: str) -> bool:
    """Deezer's name contains the credit and more: the same act only as 'Credit …' or '… & Credit'.

    'Janet' → 'Janet Jackson', 'David Rose' → 'David Rose & His Orchestra',
    'The Four Seasons' → 'Frankie Valli & The Four Seasons', 'Cat Stevens' →
    'Yusuf / Cat Stevens'; but not 'Celtic Pink Floyd' or 'The Police Project'.
    """
    joined = normalize(re.sub(r"[&/+]", " and ", deezer_raw))
    if joined.startswith(c + " ") and len(c.split()) > 1:  # not "ABBA" → "Abba na Viola"
        extra = set(joined[len(c):].split())
        return not extra & _OTHER_ACT_WORDS
    return joined.endswith(" and " + c) or joined.endswith(" and the " + c)


def _artist_pair_score(c: str, d: str, deezer_raw: str = "") -> int:
    """token_set_ratio, but stricter where it is blind.

    A one-word name never matches by fuzzy spelling ("Oasis" ≠ "Ohasis"), and a
    Deezer name that only contains the credit must pass :func:`_longer_name_ok`.
    """
    tc, td = set(c.split()) - {"the"}, set(d.split()) - {"the"}
    cc, dc = c.replace(" ", ""), d.replace(" ", "")
    if cc == dc:
        return 100
    if tc and td and tc < td:
        return 100 if _longer_name_ok(c, deezer_raw or d) else round(min(fuzz.ratio(c, d), MATCH_THRESHOLD - 1))
    if len(tc) <= 1 or len(td) <= 1:
        if tc and td and td <= tc:
            return 100
        # One-word names: no fuzzy spelling, only a longer transliteration of the
        # same one word ("Chizbatron" / "HaChizbatron").
        if len(tc) == 1 and len(td) == 1:
            short, long_ = sorted((cc, dc), key=len)
            return 90 if len(short) >= 5 and short in long_ else 0
        return 0
    return round(fuzz.token_set_ratio(c, d))


def artist_score(candidate_artists: list[str], deezer_artist: str) -> int:
    """Best agreement between the candidate's credits and a Deezer artist name of the same script."""
    deezer_artist = deezer_artist.replace("$", "s")  # "Ke$ha" is Kesha
    deezer_artist = re.sub(r"(?<=\w)!(?=\w)", "i", deezer_artist)  # "P!nk" is Pink
    d = normalize(deezer_artist)
    best = 0
    for text in candidate_artists:
        if not text or is_hebrew(text) != is_hebrew(deezer_artist):
            continue
        for c in {normalize(text), normalize(_strip_vav(text))} - {""}:
            best = max(best, _artist_pair_score(c, d, deezer_artist))
    return best


def hebrew_acts(credit: str) -> list[str]:
    """The credit and the acts in it, for name lookups.

    'אביב גפן והתעויוט בהשתתפות אריק איינשטיין' → the whole credit, then
    'אביב גפן והתעויוט', 'אריק איינשטיין', 'אביב גפן', 'התעויוט'. A joining ו
    could be part of a name, so every contiguous run of ו-joined pieces is kept
    ('חלב ודבש וגלי עטרי' → 'חלב ודבש', 'גלי עטרי', ...).
    """
    acts = [credit.strip()]
    parts = [p.strip() for p in _ACT_SEPARATORS_RE.split(credit) if p and p.strip()]
    acts += parts
    for part in parts:
        pieces = [p.strip() for p in _VAV_SPLIT_RE.split(part) if p.strip()]
        for i in range(len(pieces)):
            for j in range(i + 1, len(pieces) + 1):
                acts.append(" ו".join(pieces[i:j]))
    out = []
    for act in acts:
        if act and act not in out:
            out.append(act)
    return out


_PLAIN_NOTE_RE = re.compile(
    r"(?:(?:19|20)\d\d )?(?:digital )?(?:remaster(?:ed)? )?(?:(?:19|20)\d\d )?"
    r"(?:(?:mono|stereo|single|original|album|radio|7|video|promo|short|us|uk)\s+){0,3}(?:version\s+)?(?:mix|edit)(?: (?:19|20)\d\d)?"
)
_FEAT_NOTE_RE = re.compile(r"(?:feat|featuring|ft|with)\b")
_YEAR_RE = re.compile(r"\b(?:19|20)\d\d\b")
_YEAR_OK_RE = re.compile(r"remaster|original|single|mono|stereo")
_HEBREW_YEAR_VERSION_RE = re.compile(r"גרסת \d{4}|גרסה \d{4}")


def _odd_note(note: str) -> str | None:
    """A version note naming a remix ('Motiv-8 Radio Mix') or a dated re-make ('Hot Shot 2020')."""
    n = normalize(note)
    if not n:
        return None
    if re.search(r"\b(?:mix|edit)\b", n) and not _PLAIN_NOTE_RE.fullmatch(n) and not _FEAT_NOTE_RE.match(n):
        return "mix"  # "Motiv-8 Radio Mix", "Moto Blanco Edit"
    if _YEAR_RE.search(n) and not _YEAR_OK_RE.search(n):
        return "dated version"
    if re.search(r"\b(?:version|edition)\b", n) and not _CLEAN_VERSION_RE.search(n):
        return "other version"  # "Guitar/Cello Version", "Latin Version", "Seal's Version"
    if _HEBREW_YEAR_VERSION_RE.search(n):
        return "dated version"
    if re.search(r"גרסת|גרסה", n) and not re.search(r"סינגל|אלבום|מקורית|אירוויזיון", n):
        return "other version"  # "(גרסת שלמה והאנדלוסים)": another recording
    return None


def _notes(text: str) -> list[str]:
    notes = [m.group(0).strip(" ()[]") for m in _BRACKETS_RE.finditer(text)]
    dash = _DASH_SUFFIX_RE.search(_BRACKETS_RE.sub("", text))
    if dash:
        notes.append(dash.group(0).strip(" -–—"))
    # "Edit; 2006 Remaster", "Single Version / Mono": judge each part.
    return [part.strip() for note in notes for part in re.split(r"[;/,]| - ", note) if part.strip()]


def marker_in(
    text: str, chart_titles: list[str], latin_markers=_LATIN_MARKERS, notes: str = "track", extended: bool = False
) -> str | None:
    """The first different-recording marker found in ``text`` and not in the chart title.

    ``notes="track"`` also judges the bracketed/dash version notes of a track
    title (remix names, dated re-makes, unknown "… Version"s); for an album
    title (``notes="album"``) only a remix note counts ("Best Of 1984 - 1994" is fine).
    """
    n = f" {normalize(text)} "
    charts = [f" {normalize(t)} " for t in chart_titles if t]
    recording_markers = latin_markers is _LATIN_MARKERS  # (not the artist-name list)
    if recording_markers and notes == "track":
        latin_markers = latin_markers + _TRACK_ONLY_MARKERS
    if recording_markers and extended:
        latin_markers = latin_markers + _EXTENDED_LATIN_MARKERS
    for marker in latin_markers:
        word = f" {marker} "
        if word in n and not any(word in c for c in charts):
            return marker
    if _TAKE_RE.search(n) and not any(_TAKE_RE.search(c) for c in charts):
        return "take"
    if recording_markers:
        chart_notes = " ".join(charts)
        for note in _notes(text):
            odd = _odd_note(note)
            if notes == "album" and odd != "mix":
                continue
            if odd and normalize(note) not in chart_notes:
                return odd
    hebrew_markers = _HEBREW_MARKERS + (_HEBREW_TRACK_ONLY_MARKERS if recording_markers and notes == "track" else ())
    if recording_markers and extended:
        hebrew_markers += _EXTENDED_HEBREW_MARKERS
    for marker in hebrew_markers:
        if marker in n and not any(marker in c for c in charts):
            return marker
    if recording_markers and extended and notes == "track":
        take = _EXTENDED_TAKE_RE.search(n)
        if take and not any(_EXTENDED_TAKE_RE.search(c) for c in charts):
            return take.group(0)
    return None


def artist_marker(deezer_artist: str, candidate_artists: list[str]) -> str | None:
    """A tribute/cover-act word in the Deezer artist name that the credit does not have."""
    return marker_in(deezer_artist, candidate_artists, _ARTIST_MARKERS)


def version_note(result: dict) -> str:
    """The version text Deezer attaches to a title ('(Remastered 2009)', '- Single Version')."""
    title = result.get("title") or ""
    notes = [m.group(0) for m in _BRACKETS_RE.finditer(title)]
    dash = _DASH_SUFFIX_RE.search(_BRACKETS_RE.sub("", title))
    if dash:
        notes.append(dash.group(0))
    if result.get("title_version"):
        notes.append(result["title_version"])
    return " ".join(notes)


def is_clean_version(result: dict, candidate: dict | None = None) -> bool:
    """No note that suggests another version of the recording.

    Clean notes: remaster / single version / mono and the like; a note that is
    part of the chart title itself ("All Night Long (All Night)"); a guest the
    chart credit names, or an orchestra or choir credited with "with". A guest
    whom a Latin chart credit does not name ("love nwantiti (feat. Jah Khalib)"
    for "CKay", "People (with Stevie Wonder)" for "Barbra Streisand") marks a
    later version. Not clean only means "preferred less", never rejected.
    """
    chart = " ".join(normalize(t) for t in chart_titles(candidate)) if candidate else ""
    credit_texts: list[str] = []
    if candidate:
        credit_texts = [t for t in (candidate.get("artist"), candidate.get("credit"), candidate.get("artist_latin")) if t]
    credit = " ".join(normalize(t) for t in credit_texts)
    hebrew_credit = bool(credit_texts) and is_hebrew(credit_texts[0])
    notes = [n for n in re.split(r"[()\[\]/;]|\s[-–—]\s", version_note(result)) if n.strip()]
    for note in notes:
        n = normalize(note)
        if not n or (chart and n in chart):
            continue
        guest = _FEAT_NOTE_RE.match(n)
        if guest:
            name = n[guest.end():].strip()
            if candidate is None or hebrew_credit:
                continue
            if re.search(r"\b(?:orchestra|singers|chorus|choir|band|quartet|trio)\b", name):
                continue
            if fuzz.partial_ratio(name, credit) < 80:
                return False
            continue
        # Anything else is a subtitle ("Only the Lonely (Know the Way I Feel)",
        # "(I Got Spurs That) Jingle, Jangle, Jingle"): version-like notes were
        # already rejected by ``marker_in``.
    return True


def chart_titles(candidate: dict) -> list[str]:
    """The chart title(s), and each side of a double A-side ('Bird Dog / Devoted to You')."""
    out = []
    for title in (candidate.get("title"), candidate.get("title_latin")):
        if not title:
            continue
        for t in [title, *(re.split(r"\s*/\s*", title) if "/" in title else [])]:
            if t.strip() and t.strip() not in out:
                out.append(t.strip())
    return out


_FEATURED_RE = re.compile(
    r"\s+(?:featuring|feat\.?|ft\.?|with|duet with|vs\.?|בהשתתפות|בשיתוף|בליווי|עם|ביחד עם|מארח|מארחת|מארחים)\s+.*$",
    re.I,
)


_DEEZER_FEAT_RE = re.compile(r"[(\[]\s*(?:feat\.?|featuring|ft\.?|with)\s+([^)\]]+)[)\]]", re.I)


def featured_clash(candidate: dict, deezer_title: str) -> bool:
    """The chart credit names a guest and Deezer's title names only other guests.

    'Into You' by "Fabolous featuring Tamia" is not "Into You (feat. Ashanti)".
    """
    guests = [m.group(1) for m in _DEEZER_FEAT_RE.finditer(deezer_title or "")]
    if not guests:
        return False
    for text in (candidate.get("artist"), candidate.get("credit"), candidate.get("artist_latin")):
        if not text or is_hebrew(text):
            continue
        m = _FEATURED_RE.search(text)
        if m:
            credited = normalize(re.sub(r"^\s*\S+\s+", "", m.group(0)))  # drop the "featuring" word
            if not any(fuzz.token_set_ratio(credited, normalize(g)) >= 60 for g in guests):
                return True
    return False


def guest_missing(candidate: dict, deezer_title: str) -> bool:
    """The chart credit names a guest whom Deezer's title does not mention."""
    for text in (candidate.get("artist"), candidate.get("credit"), candidate.get("artist_latin")):
        if not text or is_hebrew(text):
            continue
        m = _FEATURED_RE.search(text)
        if m:
            credited = normalize(re.sub(r"^\s*\S+\s+", "", m.group(0)))
            if fuzz.partial_ratio(credited, normalize(deezer_title)) < 80:
                return True
    return False


def lead_act(credit: str) -> str:
    """The credit without its featured acts: 'X featuring Y' → 'X', 'X בהשתתפות Y' → 'X'.

    Joint acts stay whole ('Simon & Garfunkel', 'שלמה ארצי ועמיר לב').
    """
    return _FEATURED_RE.sub("", credit).strip() or credit.strip()


def candidate_artists(candidate: dict) -> list[str]:
    """The act to compare: ``artist`` and its Latin form, not the full credit.

    The credit can name a featured act ("Uncle Kracker featuring Dobie Gray"),
    who would otherwise match their own, different recording.
    """
    out = []
    for text in (candidate.get("artist"), candidate.get("artist_latin")):
        text = lead_act(text) if text else text
        if text and text not in out:
            out.append(text)
    return out


@dataclass
class Verdict:
    ok: bool
    reason: str = ""
    title_score: int = 0
    artist_score: int = 0
    artist_via: str = ""


class Api(Protocol):
    def search(self, query: str) -> dict: ...
    def artist_search(self, name: str) -> dict: ...
    def album(self, album_id: int) -> dict: ...
    def track(self, track_id: int) -> dict: ...


class DeezerApi:
    """The real thing, through the cached, rate-limited HTTP helper."""

    def search(self, query: str) -> dict:
        return http.get_json(SEARCH_URL, {"q": query, "limit": SEARCH_LIMIT})

    def artist_search(self, name: str) -> dict:
        return http.get_json(ARTIST_SEARCH_URL, {"q": name})

    def album(self, album_id: int) -> dict:
        return http.get_json(ALBUM_URL.format(album_id))

    def track(self, track_id: int) -> dict:
        return http.get_json(TRACK_URL.format(track_id))


def real_rank(result: dict) -> int:
    """The track's rank, with Deezer's no-data placeholder counted as 0."""
    rank = result.get("rank") or 0
    return 0 if rank == PLACEHOLDER_RANK else rank


_ORIGINAL_RE = re.compile(
    r"\b(?:\d+ )?original (?:\d+ )?(?:hits?|recordings?|greatest hits|artists?|single|singles|big hits|mono)\b"
)


def says_original(result: dict) -> bool:
    """Title or album claims the original recording: "25 Original Greatest Hits", "Rag Mop - Original",
    "(Original Single Mono Version)". Not "Original Broadway Cast", "Original Broadcast" or
    "Original Italian Version", which are other recordings."""
    title = result.get("title") or ""
    album = (result.get("album") or {}).get("title") or ""
    if any(normalize(note) == "original" for note in _notes(title)):
        return True
    return bool(_ORIGINAL_RE.search(normalize(f"{title} {album}")))


def prefer_marked_original(ordered: list[dict]) -> list[dict]:
    """Within the album-preference band of the top rank, move copies marked "Original" first."""
    if not ordered or says_original(ordered[0]):
        return ordered
    band = ALBUM_PREFERENCE_BAND * real_rank(ordered[0])
    marked = [r for r in ordered if says_original(r) and real_rank(r) >= band]
    return marked + [r for r in ordered if r not in marked]


def popularity(results: list[dict]) -> int:
    """The song's fame: the highest real rank among its acceptable versions (0 if none)."""
    ranks = [r.get("rank") or 0 for r in results]
    return max((r for r in ranks if r != PLACEHOLDER_RANK), default=0)


class Matcher:
    def __init__(self, api: Api, latin_names: dict[str, str | None]):
        self.api = api
        self.latin_names = latin_names

    # --- names -------------------------------------------------------------------

    def resolved_latin(self, hebrew_credit: str) -> list[str]:
        """English Wikipedia names for the credit and its acts (disambiguation stripped)."""
        out = []
        for act in hebrew_acts(hebrew_credit):
            name = self.latin_names.get(act)
            if name:
                name = strip_disambiguation(name)
                if name not in out:
                    out.append(name)
        return out

    def confirmed_by_artist_search(self, hebrew_credit: str, deezer_artist_id: int) -> bool:
        """Deezer's own artist search for the Hebrew name returns this artist first."""
        for act in hebrew_acts(hebrew_credit):
            found = self.api.artist_search(act).get("data") or []
            if found and found[0].get("id") == deezer_artist_id:
                return True
        return False

    # --- one result --------------------------------------------------------------

    def evaluate(self, candidate: dict, result: dict) -> Verdict:
        """Is this search result the candidate's recording? (Preview is checked separately.)"""
        titles = chart_titles(candidate)
        deezer_titles = [result.get("title") or "", result.get("title_short") or ""]
        t_score = title_score(titles, deezer_titles)
        if t_score < MATCH_THRESHOLD:
            return Verdict(False, f"title {t_score}", t_score)
        album_title = (result.get("album") or {}).get("title") or ""
        for text, notes in ((result.get("title") or "", "track"), (album_title, "album")):
            marker = marker_in(text, titles, notes=notes, extended=bool(candidate.get("extended")))
            if marker:
                return Verdict(False, f"marked {marker}", t_score)
        for title in titles:  # "Despacito (Remix)" on the chart is not the original "Despacito"
            marker = marker_in(" ".join(_notes(title)), [])  # only in its notes: not "Live and Let Die"
            if marker in ("remix", "live", "acoustic", "unplugged") and not (
                marker_in(result.get("title") or "", []) == marker or marker_in(album_title, [], notes="album") == marker
            ):
                return Verdict(False, f"chart says {marker}", t_score)
            if marker == "remix" and guest_missing(candidate, result.get("title") or ""):
                return Verdict(False, "chart remix guest missing", t_score)

        if featured_clash(candidate, result.get("title") or ""):
            return Verdict(False, "other featured act", t_score)
        return self.artist_verdict(candidate, result, t_score)

    def artist_verdict(self, candidate: dict, result: dict, t_score: int = 0) -> Verdict:
        """Is the result's artist the candidate's act? (directly, via English name, or confirmed)"""
        artist = result.get("artist") or {}
        name = artist.get("name") or ""
        artists = candidate_artists(candidate)
        marker = artist_marker(name, artists)
        if marker:  # "The Beatles Revival Band", "The Beatles - Covers On Piano"
            return Verdict(False, f"artist marked {marker}", t_score)
        a_score = artist_score(artists, name)
        if a_score >= MATCH_THRESHOLD:
            return Verdict(True, "", t_score, a_score, "direct")
        hebrew = [a for a in artists if is_hebrew(a)]
        if hebrew and not is_hebrew(name):
            resolved = [n for h in hebrew for n in self.resolved_latin(h)]
            r_score = artist_score(resolved, name)
            if r_score >= MATCH_THRESHOLD:
                return Verdict(True, "", t_score, r_score, "resolved")
            if any(self.confirmed_by_artist_search(h, artist.get("id")) for h in hebrew):
                return Verdict(True, "", t_score, max(a_score, r_score), "confirmed")
            return Verdict(False, f"artist {max(a_score, r_score)} unconfirmed", t_score, max(a_score, r_score))
        return Verdict(False, f"artist {a_score}", t_score, a_score)

    # --- one candidate -----------------------------------------------------------

    def queries(self, candidate: dict) -> list[str]:
        title = candidate["title"] if candidate["origin"] == "IL" else (candidate.get("title_latin") or candidate["title"])
        artist = candidate["artist"] if candidate["origin"] == "IL" else candidate.get("artist_latin")
        # English names of the whole credit or its lead act only: a fragment
        # ("בוב" of "בוב ומארסיה" → "Bob") makes a useless query.
        lead = lead_act(candidate["artist"])
        latin_names = [
            strip_disambiguation(n) for n in (self.latin_names.get(candidate["artist"]), self.latin_names.get(lead)) if n
        ] if is_hebrew(candidate["artist"]) else []
        if is_hebrew(candidate["artist"]):  # a duet's acts, when their names are full names
            latin_names += [n for n in self.resolved_latin(candidate["artist"]) if len(n.split()) > 1]
        latin_names = list(dict.fromkeys(latin_names))
        if not artist:  # an international row with a Hebrew-spelled artist
            artist = latin_names[0] if latin_names else candidate["artist"]
        out = [f'artist:"{artist}" track:"{title}"', f"{artist} {title}", f"{title} {artist}"]
        acts = hebrew_acts(artist) if is_hebrew(artist) else []
        if len(acts) > 1:  # a duet credit: also try the first-credited act alone
            out.append(f"{acts[1]} {title}")
        out += [f"{name} {title}" for name in latin_names]
        out.append(title)
        unique = []
        for q in out:
            if q not in unique:
                unique.append(q)
        return unique

    def _album_by_the_artist(self, ordered: list[dict]) -> tuple[dict, dict]:
        """Prefer a track on the track artist's own album over a compilation copy,
        but only among tracks ranked at least half as high as the top one."""
        top = ordered[0]
        if says_original(top):  # a copy marked "Original" is taken as it is
            return top, self.api.album(top["album"]["id"])
        floor = ALBUM_PREFERENCE_BAND * real_rank(top)
        first_album = None
        for result in ordered[:ALBUMS_TO_TRY]:
            if result is not top and real_rank(result) < floor:
                continue
            album = self.api.album(result["album"]["id"])
            if first_album is None:
                first_album = album
            album_artist = album.get("artist") or {}
            track_artist = result.get("artist") or {}
            if album_artist.get("id") == track_artist.get("id") or (
                normalize(album_artist.get("name") or "") == normalize(track_artist.get("name") or "")
            ):
                return result, album
        return top, first_album

    def _judge(self, candidate: dict, results: list[dict], state: dict) -> None:
        for result in results:
            if result["id"] in state["seen"]:
                continue
            state["seen"].add(result["id"])
            verdict = self.evaluate(candidate, result)
            if verdict.ok:
                if result.get("preview"):
                    state["accepted"].append((result, verdict))
                else:
                    state["no_preview"] = True
            elif verdict.reason == "marked mix" and self.artist_verdict(candidate, result).ok:
                state["blockers"].append(result)

    def match(self, candidate: dict) -> dict:
        """Search, pooling the first queries; fall back one query at a time; choose."""
        state = {"seen": set(), "accepted": [], "blockers": [], "no_preview": False}
        queries = self.queries(candidate)
        used = queries[:POOLED_QUERIES]
        for query in used:
            self._judge(candidate, self.api.search(query).get("data") or [], state)
        for query in queries[POOLED_QUERIES:]:
            if state["accepted"]:
                break
            used = [query]
            self._judge(candidate, self.api.search(query).get("data") or [], state)
        accepted = state["accepted"]
        if not accepted:
            return {"status": NO_PREVIEW if state["no_preview"] else NO_MATCH}
        best = max(real_rank(r) for r, _ in accepted)
        blocker = max(state["blockers"], key=lambda r: (real_rank(r), r["id"]), default=None)
        if blocker and real_rank(blocker) > BLOCKING_MIX_FACTOR * best:
            return {
                "status": NO_MATCH,
                "blocked_by": {"id": blocker["id"], "title": blocker.get("title"), "rank": blocker.get("rank")},
            }
        verdicts = {r["id"]: v for r, v in accepted}
        ordered = sorted(
            (r for r, _ in accepted),
            key=lambda r: (not is_clean_version(r, candidate), -real_rank(r), r["id"]),
        )
        ordered = prefer_marked_original(ordered)
        on_album = [r for r in ordered if same_album(candidate, r)]
        if on_album:  # an album row: the track of that very album, when Deezer has it
            track, album = on_album[0], self.api.album(on_album[0]["album"]["id"])
        else:
            track, album = self._album_by_the_artist(ordered)
        verdict = verdicts[track["id"]]
        return {
            "status": MATCHED,
            "track": track,
            "album": album,
            "popularity": popularity(ordered),
            "title_score": verdict.title_score,
            "artist_score": verdict.artist_score,
            "artist_via": verdict.artist_via,
            "query": " | ".join(used),
        }

    def match_override(self, candidate: dict, track_id: int) -> dict:
        """The hand-picked track of data/track_overrides.csv, instead of searching."""
        try:
            track = self.api.track(track_id)
        except (http.ApiError, KeyError) as error:
            raise OverrideError(f"{candidate['key']}: track {track_id} not found ({error})") from error
        if not track or "error" in track or not track.get("id"):
            raise OverrideError(f"{candidate['key']}: track {track_id} not found")
        if not track.get("preview"):
            raise OverrideError(f"{candidate['key']}: track {track_id} has no preview")
        return {
            "status": MATCHED,
            "track": track,
            "album": self.api.album(track["album"]["id"]),
            "popularity": track.get("rank") or 0,
            "title_score": None,
            "artist_score": None,
            "artist_via": "override",
            "query": "",
        }


    def match_hint(self, candidate: dict) -> dict:
        """A famous-acts row: the track collect took from the act's album (no search)."""
        track = self.api.track(candidate["deezer_hint"])
        if not track or "error" in track or not track.get("id"):
            return {"status": NO_MATCH}
        if not track.get("preview"):
            return {"status": NO_PREVIEW}
        return {
            "status": MATCHED,
            "track": track,
            "album": self.api.album(track["album"]["id"]),
            "popularity": real_rank(track),
            "title_score": None,
            "artist_score": None,
            "artist_via": "album",
            "query": "",
        }


# A dash suffix that makes a Deezer album a compilation ("גזוז - 20 הלהיטים", "שלמה ארצי - אוסף השנים
# הראשונות"); a reissue note ("- מהדורה מחודשת") or a subtitle keeps the album.
_COMPILATION_SUFFIX_RE = re.compile(
    r"אוסף|המיטב|מיטב|להיטי|הלהיטים|אנתולוגיה|\b(?:best|greatest|hits|collection|anthology|essential)\b", re.I
)


def album_agrees(album: str | None, deezer_album: str | None) -> bool:
    """A Deezer album title is the album page's album: the titles agree, and a spaced-dash suffix the
    album's own title lacks does not name a compilation (title_score alone drops that suffix)."""
    if not album or not deezer_album:
        return False
    plain, previous = deezer_album, None
    while plain != previous:
        previous, plain = plain, _BRACKETS_RE.sub("", plain)
    suffix = _DASH_SUFFIX_RE.search(plain)
    if suffix and _COMPILATION_SUFFIX_RE.search(suffix.group(0)) and not _COMPILATION_SUFFIX_RE.search(album):
        return False
    return title_score([album], [deezer_album]) >= MATCH_THRESHOLD


def same_album(candidate: dict, result: dict) -> bool:
    """The result is on the album the candidate's track list comes from (album rows only)."""
    return album_agrees(candidate.get("album"), (result.get("album") or {}).get("title"))


class OverrideError(RuntimeError):
    """A track_overrides.csv entry whose track is missing or has no preview."""


def load_overrides(path: Path = OVERRIDES_CSV) -> dict[str, int]:
    """{candidate key: Deezer track id} from track_overrides.csv (key,deezer_id,note)."""
    out: dict[str, int] = {}
    with Path(path).open(encoding="utf-8", newline="") as f:
        for line in csv.DictReader(f):
            key = (line.get("key") or "").strip()
            if not key or key.startswith("#"):
                continue
            if key in out:
                raise OverrideError(f"{key}: listed twice in {Path(path).name}")
            out[key] = int(line["deezer_id"])
    return out


# --- the whole stage --------------------------------------------------------------


def matched_row(candidate: dict, m: dict) -> dict:
    track, album = m["track"], m["album"] or {}
    return {
        **candidate,
        "deezer_id": track["id"],
        "rank": track.get("rank") or 0,
        "popularity": m["popularity"],
        "genre_id": album.get("genre_id"),
        "deezer_title": track.get("title"),
        "deezer_artist": (track.get("artist") or {}).get("name"),
        "album_id": (track.get("album") or {}).get("id"),
        "album_title": (track.get("album") or {}).get("title"),
        "title_score": m["title_score"],
        "artist_score": m["artist_score"],
        "artist_via": m["artist_via"],
        "merged_keys": [],
    }


def dropped_row(row: dict, reason: str, **extra) -> dict:
    out = {k: row[k] for k in ("key", "title", "artist", "origin", "chart_year")}
    out["reason"] = reason
    out.update(extra)
    return out


def _has_latin(row: dict) -> bool:
    return bool(row.get("title_latin") and row.get("artist_latin")) and not row.get("needs_latin")


# Preference among extended rows of one track (lower leads); any other source counts as a chart row.
_NEW_ROW_RANK = {SONG_ARTICLE_SOURCE: 1, ALBUM_SOURCE: 2, FAMOUS_SOURCE: 3}


def dedupe_by_track(rows: list[dict]) -> tuple[list[dict], list[dict]]:
    """Candidates matched to one Deezer track are one song.

    The kept row is the one with Latin fields (else the earliest); it takes the
    earliest chart year (with that row's source), the best position, the union
    of sources and the highest popularity. When the group holds rows of the
    original sources, ``extended`` rows only add their sources and popularity;
    in a group of extended rows only, a chart row is preferred to a song-article row, that to an
    album-page row, and that to a famous-acts row.
    """
    groups: dict[int, list[dict]] = defaultdict(list)
    for row in rows:
        groups[row["deezer_id"]].append(row)
    kept, dropped = [], []
    for group in groups.values():
        # Existing rows lead; among extended rows a chart row, then a song-article row, then an
        # album-page row, then a famous-acts row.
        leading = [r for r in group if not r.get("extended")]
        if not leading:
            best = min(_NEW_ROW_RANK.get(r["source"], 0) for r in group)
            leading = [r for r in group if _NEW_ROW_RANK.get(r["source"], 0) == best]
        by_time = sorted(leading, key=lambda r: (r["chart_year"], r["position"], r["key"]))
        base = next((r for r in by_time if _has_latin(r)), by_time[0])
        song = dict(base)
        earliest = by_time[0]
        song["chart_year"] = earliest["chart_year"]
        song["source"] = earliest["source"]
        song["position"] = min(r["position"] for r in leading)
        # An extended row of another origin (a foreign hit on an Israeli chart's tail) adds nothing.
        joined = [r for r in group if not r.get("extended") or r["origin"] == base["origin"]]
        song["sources"] = sorted({s for r in joined for s in r["sources"]})
        song["popularity"] = max(r["popularity"] for r in joined)
        others = [r for r in group if r is not base]
        song["merged_keys"] = sorted({k for r in joined for k in [r["key"], *r.get("merged_keys", [])]} - {base["key"]})
        kept.append(song)
        dropped += [dropped_row(r, DUPLICATE_TRACK, duplicate_of=base["key"], deezer_id=r["deezer_id"]) for r in others]
    return kept, dropped


def load_floors(path: Path = FLOORS_TOML) -> dict[str, dict[str, int]]:
    """{origin: {era: floor}}; IL may add the KIND_FLOORS keys (any other key is an error)."""
    with Path(path).open("rb") as f:
        data = tomllib.load(f)
    out = {origin: {era: int(data[origin][era]) for era in ERAS} for origin in ("IL", "INTL")}
    for origin in ("IL", "INTL"):
        allowed = ERAS + (KIND_FLOORS if origin == "IL" else ())
        unknown = sorted(set(data[origin]) - set(allowed))
        if unknown:
            raise ValueError(f"{Path(path).name}: unknown floor keys in [{origin}]: {', '.join(unknown)}")
    out["IL"].update({k: int(data["IL"][k]) for k in KIND_FLOORS if k in data["IL"]})
    return out


def floor_for(floors: dict, origin: str, year: int) -> int:
    era = "since_2000" if year >= 2000 else "from_1970" if year >= 1970 else "before_1970"
    return floors[origin][era]


def row_kind(row: dict) -> str | None:
    """"chart" for a row that charted (Hebrew chart among its sources), "album" for a row of
    album pages only, else None."""
    sources = set(row.get("sources") or [row.get("source")])
    if CHART_SOURCE in sources:
        return "chart"
    if sources == {ALBUM_SOURCE}:
        return "album"
    return None


def row_floor(floors: dict, row: dict) -> int:
    """The row's floor: its era's, or for an Israeli chart or album row of 1960–1999 the kind's own floor."""
    year = row["chart_year"]
    base = floor_for(floors, row["origin"], year)
    kind = row_kind(row)
    if row["origin"] != "IL" or kind is None or not KIND_FIRST_YEAR <= year <= KIND_LAST_YEAR:
        return base
    return floors["IL"].get(f"{kind}_{'from_1970' if year >= 1970 else 'before_1970'}", base)


def load_famous(path: Path = FAMOUS_TXT) -> set[str]:
    """Acts whose 1960–1999 chart, album and famous-acts rows skip the floor: one per line,
    '#' comments; "name | alias | …" lists other spellings of one act."""
    return {name.strip() for line in load_keep(path) for name in line.split("|") if name.strip()}


def act_form(name: str) -> str:
    """A spelling-blind form of an act's name: no punctuation, no inner ו/י (בעז = בועז, שבק ס = שב"ק ס')."""
    return " ".join(_hebrew_skeleton(w) for w in normalize(name).split())


def is_famous(row: dict, famous: set[str]) -> bool:
    """The row's act (not a guest) is a famous act, alone or as one act of a duet or band credit."""
    if not famous:
        return False
    forms = {act_form(f) for f in famous}
    return any(act_form(act) in forms for act in hebrew_acts(lead_act(row["artist"])))


def load_keep(path: Path = KEEP_TXT) -> set[str]:
    """Keys to keep whatever their popularity: one per line, '#' starts a comment."""
    keys = set()
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        key = line.split("#", 1)[0].strip()
        if key:
            keys.add(key)
    return keys


# Round 2: an exempt row under this popularity must be its album's own track (else it is more likely a
# wrong match on some other release than a song people know).
LAST_RESORT_FLOOR = 1000


def is_own_album_track(row: dict) -> bool:
    """The matched track is on Deezer's copy of the row's album: a famous-acts track, or an album
    row whose Deezer album title agrees with its album page."""
    if row.get("deezer_hint"):
        return True
    return album_agrees(row.get("album"), row.get("album_title"))


def is_exempt(row: dict, keep: set[str], famous: set[str] | frozenset = frozenset()) -> bool:
    """Hand-picked classics and extras, keep-listed songs and the 1960–1999 chart, album and
    famous-acts rows of a famous act only need a match with a preview (a famous act's row under
    LAST_RESORT_FLOOR only when it is its album's own track)."""
    sources = set(row.get("sources", [row.get("source")]))
    if {CLASSICS_SOURCE, EXTRAS_SOURCE} & sources:
        return True
    if (
        row["origin"] == "IL"
        and KIND_FIRST_YEAR <= row["chart_year"] <= KIND_LAST_YEAR
        and {CHART_SOURCE, ALBUM_SOURCE, FAMOUS_SOURCE} & sources
        and is_famous(row, famous)
        and (row["popularity"] >= LAST_RESORT_FLOOR or is_own_album_track(row))
    ):
        return True
    return bool(keep & {row["key"], *row.get("merged_keys", [])})


class ExclusionError(ValueError):
    """An exclude.txt line without a reason, or naming a key that is not a candidate."""


def load_exclude(path: Path = EXCLUDE_TXT) -> dict[str, str]:
    """{candidate key: reason} from exclude.txt: one key per line, then '#' and the reason."""
    out: dict[str, str] = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        key, _, reason = (part.strip() for part in line.partition("#"))
        if not key:
            continue
        if not reason:
            raise ExclusionError(f"{key}: no reason given in {Path(path).name}")
        out[key] = reason
    return out


def apply_floor(
    rows: list[dict], floors: dict, keep: set[str], famous: set[str] | frozenset = frozenset()
) -> tuple[list[dict], list[dict]]:
    kept, dropped = [], []
    for row in rows:
        floor = row_floor(floors, row)
        if row["popularity"] >= floor or is_exempt(row, keep, famous):
            kept.append(row)
        else:
            dropped.append(
                dropped_row(row, BELOW_FLOOR, popularity=row["popularity"], floor=floor,
                            deezer_id=row["deezer_id"], deezer_title=row.get("deezer_title"),
                            deezer_artist=row.get("deezer_artist"))
            )  # fmt: skip
    return kept, dropped


def sort_rows(rows: list[dict]) -> list[dict]:
    return sorted(rows, key=lambda r: (r["origin"], r["chart_year"], r.get("position", 0), r["title"], r["key"]))


def hebrew_names_to_resolve(candidates: list[dict]) -> list[str]:
    """Every Hebrew credit and act that may need an English Wikipedia name."""
    names = set()
    for c in candidates:
        for text in (c["artist"], c.get("credit")):
            if is_hebrew(text):
                names.update(hebrew_acts(text))
    return sorted(names)


def run(candidates: list[dict], matcher: Matcher, floors: dict, keep: set[str],
        overrides: dict[str, int] | None = None, progress=None, exclude: dict[str, str] | None = None,
        famous: set[str] | None = None):
    """Match every candidate; returns (all matches before dedup/floor, kept rows, dropped rows).

    Excluded keys (exclude.txt) are dropped before matching.
    """
    overrides, exclude = overrides or {}, exclude or {}
    unknown = sorted(set(exclude) - {c["key"] for c in candidates})
    if unknown:
        raise ExclusionError(f"excluded keys that are not candidates: {', '.join(unknown)}")
    matched, dropped = [], []
    for i, candidate in enumerate(candidates, 1):
        if candidate["key"] in exclude:
            m = {"status": EXCLUDED, "note": exclude[candidate["key"]]}
        elif candidate["key"] in overrides:
            m = matcher.match_override(candidate, overrides[candidate["key"]])
        elif candidate.get("deezer_hint"):
            m = matcher.match_hint(candidate)
        else:
            m = matcher.match(candidate)
        if m["status"] == MATCHED:
            matched.append(matched_row(candidate, m))
        else:
            dropped.append(dropped_row(candidate, m["status"], **{k: m[k] for k in ("blocked_by", "note") if k in m}))
        if progress:
            progress(i, len(candidates))
    unique, duplicates = dedupe_by_track(matched)
    kept, below = apply_floor(unique, floors, keep, famous or set())
    return matched, sort_rows(kept), sort_rows(dropped + duplicates + below)
