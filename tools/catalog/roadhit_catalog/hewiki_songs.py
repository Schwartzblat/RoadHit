"""Hebrew songs that have a Hebrew Wikipedia article (source ``hewiki-songs``).

The pages of ``קטגוריה:שירים בעברית`` and of its sub-categories that hold
recorded songs are candidates when

* they are in a year category (``קטגוריה:שירי YYYY`` or
  ``קטגוריה:סינגלים מ-YYYY``; several → the earliest), 1940 ≤ YYYY ≤ 2026 — that
  year is the row's ``chart_year``;
* their infobox (or, failing that, their first sentence) names a performer —
  the act the song is known by, not its lyricist or composer;

and the title is the page title without a bracketed disambiguation.

Parsers are pure functions of the page data; :func:`collect` does the fetching
(through :mod:`roadhit_catalog.http`), batched 50 titles per request.
"""

from __future__ import annotations

import re

from . import http
from .candidates import FIRST_YEAR, LAST_YEAR
from .wikitext import clean, strip_quotes

SOURCE = "hewiki-songs"
ROOT_CATEGORY = "קטגוריה:שירים בעברית"
BATCH = 50  # titles per action=query request (the API's limit for page content)


# --- Fetching ----------------------------------------------------------------------------


def category_members(category: str, kind: str) -> list[str]:
    """Titles of the pages (``kind`` "page") or sub-categories ("subcat") of ``category``."""
    out: list[str] = []
    cont: dict = {}
    while True:
        data = http.get_json(
            http.HE_API,
            {
                "action": "query",
                "list": "categorymembers",
                "cmtitle": category,
                "cmtype": kind,
                "cmlimit": "500",
                "format": "json",
                "formatversion": "2",
                **cont,
            },
        )
        out += [m["title"] for m in data.get("query", {}).get("categorymembers", [])]
        if "continue" not in data:
            return out
        cont = {"cmcontinue": data["continue"]["cmcontinue"]}


def fetch_pages(titles: list[str]) -> dict[str, dict]:
    """{title: {"categories": [...], "wikitext": str}} for every title, 50 per request."""
    out: dict[str, dict] = {}
    for start in range(0, len(titles), BATCH):
        batch = titles[start : start + BATCH]
        cont: dict = {}
        while True:
            data = http.get_json(
                http.HE_API,
                {
                    "action": "query",
                    "prop": "revisions|categories",
                    "rvprop": "content",
                    "rvslots": "main",
                    "cllimit": "max",
                    "clshow": "!hidden",
                    "titles": "|".join(batch),
                    "format": "json",
                    "formatversion": "2",
                    **cont,
                },
            )
            merge_page_batch(out, data)
            if "continue" not in data:
                break
            cont = data["continue"]
    return out


def merge_page_batch(out: dict[str, dict], data: dict) -> None:
    """Fold one ``action=query`` answer (or continuation) into ``out``."""
    for page in data.get("query", {}).get("pages", []):
        if page.get("missing"):
            continue
        entry = out.setdefault(page["title"], {"categories": [], "wikitext": ""})
        for category in page.get("categories", []):
            if category["title"] not in entry["categories"]:
                entry["categories"].append(category["title"])
        revisions = page.get("revisions") or []
        if revisions:
            entry["wikitext"] = revisions[0].get("slots", {}).get("main", {}).get("content", "")


# --- Parsing -----------------------------------------------------------------------------

_YEAR_CATEGORY_RE = re.compile(r"^קטגוריה:(?:שירי |סינגלים מ-)(\d{4})$")
_DISAMBIGUATION_RE = re.compile(r"\s*\([^()]*\)\s*$")


def category_year(categories: list[str]) -> int | None:
    """The earliest year of the page's ``שירי YYYY`` / ``סינגלים מ-YYYY`` categories."""
    years = [int(m.group(1)) for c in categories if (m := _YEAR_CATEGORY_RE.match(c))]
    return min(years) if years else None


def song_title(page_title: str) -> str:
    """'פראג (שיר)' → 'פראג'; 'אם הייתי (שיר של ברי סחרוף)' → 'אם הייתי'."""
    return _DISAMBIGUATION_RE.sub("", page_title).strip() or page_title


def templates(wikitext: str) -> list[tuple[str, str]]:
    """Top-level templates of the page as (name, body), in page order (nesting respected)."""
    out = []
    depth, start = 0, None
    i = 0
    while i < len(wikitext) - 1:
        pair = wikitext[i : i + 2]
        if pair == "{{":
            if depth == 0:
                start = i + 2
            depth += 1
            i += 2
            continue
        if pair == "}}" and depth:
            depth -= 1
            if depth == 0 and start is not None:
                body = wikitext[start:i]
                out.append((body.split("|", 1)[0].strip(), body))
            i += 2
            continue
        i += 1
    return out


def template_params(body: str) -> dict[str, str]:
    """Named parameters of a template body (pipes inside links and nested templates ignored)."""
    parts, depth_t, depth_l, current = [], 0, 0, []
    i = 0
    while i < len(body):
        pair = body[i : i + 2]
        if pair in ("{{", "}}", "[[", "]]"):
            depth_t += {"{{": 1, "}}": -1}.get(pair, 0)
            depth_l += {"[[": 1, "]]": -1}.get(pair, 0)
            current.append(pair)
            i += 2
            continue
        ch = body[i]
        if ch == "|" and depth_t == 0 and depth_l == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
        i += 1
    parts.append("".join(current))
    out = {}
    for part in parts[1:]:
        name, eq, value = part.partition("=")
        if eq:
            out[name.strip()] = value.strip()
    return out


# Infobox templates of a song page and the parameters that name its performer.
INFOBOX_NAMES = ("סינגל", "שיר")
PERFORMER_PARAMS = ("מאת", "מבצע", "ביצוע", "אמן", "מבצעים")
_LIST_SPLIT_RE = re.compile(r"<br\s*/?>|(?:^|\n)\s*[*#]+|•|;|/")
_NOTES_RE = re.compile(r"<small>.*?</small>|\([^()]*\)", re.S)


def infobox_performer(wikitext: str) -> str | None:
    """The performer named by the page's song infobox (the first one listed), or None."""
    for name, body in templates(wikitext):
        if name.replace("_", " ") not in INFOBOX_NAMES:
            continue
        params = template_params(body)
        for param in PERFORMER_PARAMS:
            value = _NOTES_RE.sub("", params.get(param, ""))  # year notes go before the list is split
            for piece in _LIST_SPLIT_RE.split(value):
                text = strip_quotes(clean(piece).strip(" ,.:"))
                if text and re.search(r"[א-תA-Za-z]", text):
                    return text
        return None
    return None


# --- One page, and the whole source ---------------------------------------------------------

CANDIDATE = "candidate"
REDIRECT, NO_YEAR, YEAR_OUT_OF_RANGE, NO_PERFORMER = "redirect", "no year category", "year outside range", "no performer"
REASONS = (CANDIDATE, REDIRECT, NO_YEAR, YEAR_OUT_OF_RANGE, NO_PERFORMER)

# Sub-categories that hold poems, prayers and religious texts rather than recorded songs.
SKIP_CATEGORIES = (
    "קטגוריה:פואמות בעברית",
    "קטגוריה:שירים מתורת נחמן מברסלב",
    "קטגוריה:שירים על תורה",
)


def parse_page(title: str, page: dict) -> tuple[dict | None, str]:
    """(candidate row or None, reason) for one song page."""
    wikitext = page.get("wikitext") or ""
    if re.match(r"\s*#(?:הפניה|redirect)", wikitext, re.I):
        return None, REDIRECT
    year = category_year(page.get("categories") or [])
    if year is None:
        return None, NO_YEAR
    if not FIRST_YEAR <= year <= LAST_YEAR:
        return None, YEAR_OUT_OF_RANGE
    artist = page_performer(wikitext)
    if not artist:
        return None, NO_PERFORMER
    row = {
        "title": song_title(title),
        "artist": artist,
        "credit": artist,
        "chart_year": year,
        "origin": "IL",
        "source": SOURCE,
        "position": 0,
        "title_link": title,
        "artist_link": None,
        "extended": True,
    }
    return row, CANDIDATE


def page_performer(wikitext: str) -> str | None:
    return infobox_performer(wikitext) or sentence_performer(wikitext)


def song_pages(root: str = ROOT_CATEGORY, skip: tuple[str, ...] = SKIP_CATEGORIES) -> tuple[list[str], list[str]]:
    """(page titles, categories walked) under ``root`` and its sub-categories, minus ``skip``."""
    pages: set[str] = set()
    walked: list[str] = []
    todo = [root]
    while todo:
        category = todo.pop(0)
        if category in walked or category in skip:
            continue
        walked.append(category)
        pages.update(category_members(category, "page"))
        todo += category_members(category, "subcat")
    return sorted(pages), walked


def collect() -> tuple[list[dict], dict]:
    """Candidate rows, and a report: {"categories": [...], "pages": n, "reasons": {reason: [titles]}}."""
    titles, walked = song_pages()
    pages = fetch_pages(titles)
    rows: list[dict] = []
    reasons: dict[str, list[str]] = {r: [] for r in REASONS}
    for title in titles:
        row, reason = parse_page(title, pages.get(title, {}))
        reasons[reason].append(title)
        if row:
            rows.append(row)
    return rows, {"categories": walked, "pages": len(titles), "reasons": reasons}


# --- The first sentence ----------------------------------------------------------------------

_LINK = r"\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]"
# Words (often linked: ה[[זמר]]ת ה[[ישראלי]]ת) that describe the performer before the name.
_PERFORMER_TYPE = r"(?:ה?\[\[(?:זמר|זמרת|ראפר|להקה|צמד|זמר-יוצר|זמר יוצר|מוזיקאי|להקה צבאית)[^\[\]]*\]\]|הזמר|הזמרת|הראפר|להקת|הלהקה|הצמד)"
_TRIGGER = (
    r"(?:(?<![א-ת])(?:ו|ש|וש)?ביצע(?:ה|ו)?(?: לראשונה| במקור)?"
    r"|(?<![א-ת])(?:ו|ש|וש)?בוצע(?:ה)? (?:לראשונה |במקור )?(?:על ידי|על-ידי|בידי)"
    r"|(?<![א-ת])(?:ו|ש|וש)?מבוצע(?:ת)? (?:על ידי|על-ידי|בידי)"
    r"|(?<![א-ת])ו?ב(?:ביצוע|ביצועו|ביצועה|ביצועם|ביצוען)(?: של)?(?: הראשון| המקורי)?(?: של)?"
    r"|(?<![א-ת])(?:ו|ש|וש)?הוקלט(?:ה)? (?:לראשונה )?(?:על ידי|על-ידי|בידי)"
    r"|(?<![א-ת])(?:ו|ש|וש)?הקליט(?:ה|ו)?(?: לראשונה)?"
    r"|(?<![א-ת])\[\[סינגל\]\](?: ה?[א-ת]+){0,2} (?:של|מאת)"
    r"|(?<![א-ת])סינגל(?: ה?[א-ת]+){0,2} (?:של|מאת)"
    rf"|(?<![א-ת])(?:\[\[[^\[\]]*\]\]|שיר|פזמון|סינגל) של(?= {_PERFORMER_TYPE})"
    r"|(?<![א-ת])מ(?:אלבומו|אלבומה|אלבומם)(?: ה?[א-ת]+){0,3}(?: של)?"
    r")"
)
_TRIGGER_RE = re.compile(_TRIGGER)
_LINK_RE = re.compile(_LINK)
# Generic words a lead sentence links before the name (ה[[זמר]] ה[[ישראלים|ישראלי]] …).
_GENERIC = re.compile(
    r"^(?:ה?(?:זמר|זמרת|זמרים|זמר-יוצר|זמר יוצר|יוצר|מוזיקאי|מוזיקאית|ראפר|ראפרית|להקה|להקת רוק|צמד|שחקן|שחקנית|"
    r"מלחין|פזמונאי|משורר|משוררת|תקליטן|חזן|פייטן|ישראלים|ישראלי|ישראלית|יהודים|אמריקאי|אמריקאים|"
    r"מוזיקה ישראלית|זמר עברי|מוזיקה מזרחית|זמר מזרחי|מוזיקה חסידית|חסידות|להקה צבאית|להקות צבאיות|"
    r"רוק ישראלי|פופ|רוק|היפ הופ|ראפ|סינגל|שיר|פזמון|אלבום|אלבום בכורה)(?: [א-ת]+)?)$"
)
_NAME_WINDOW = 160  # characters after the trigger word in which the name must appear


def lead_sentence(wikitext: str) -> str:
    """The article's first sentence, as wikitext (links kept)."""
    text = re.sub(r"<!--.*?-->|<ref[^>/]*/>|<ref[^>]*>.*?</ref>", "", wikitext, flags=re.S | re.I)
    # Drop top-level templates (infoboxes, quote boxes) so the lead paragraph is the first prose line.
    for _, body in templates(text):
        text = text.replace("{{" + body + "}}", "", 1)
    for line in text.split("\n"):
        line = line.strip()
        if not line or line.startswith(("[[קובץ:", "[[File:", "[[תמונה:", "|", "{", "}", "=", "<", "__", "*", "#", ":")):
            continue
        if "'''" not in line:
            continue
        depth, out = 0, []
        for i, ch in enumerate(line):
            depth += line.startswith("[[", i) - line.startswith("]]", i)
            out.append(ch)
            if ch == "." and depth <= 0 and (i + 1 == len(line) or line[i + 1] == " "):
                break
        return "".join(out)
    return ""


def sentence_performer(wikitext: str) -> str | None:
    """The performer the lead sentence names ('… וביצע [[שלמה ארצי]]'), or None.

    After a performing word (ביצע, בביצוע, הוקלט על ידי, סינגל של, …) the first
    linked name counts; generic links (ה[[זמר]] ה[[ישראלים|ישראלי]]) are skipped.
    """
    sentence = lead_sentence(wikitext)
    for trigger in _TRIGGER_RE.finditer(sentence):
        window = sentence[trigger.end() : trigger.end() + _NAME_WINDOW]
        for link in _LINK_RE.finditer(window):
            before = window[: link.start()]
            if re.search(r"[,.;]|(?<![א-ת])(?:שכתב|שהלחין|הלחין|כתב|מילים|לחן|עבור|באלבום)", before):
                break
            target, shown = link.group(1).strip(), (link.group(2) or link.group(1)).strip()
            prefixed = re.search(r"(?:^|\s)ו?ה$", before)
            if prefixed or _GENERIC.match(target) or _GENERIC.match(shown):
                continue
            name = strip_quotes(clean(shown))
            return name or None
    return None
