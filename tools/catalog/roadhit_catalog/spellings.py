"""Spelling files: the Hebrew-letter and Latin-letter names the app matches speech against.

Files live in ``data/spellings/``: every ``artists*.json`` (keyed by credit) and every
``titles*.json`` (keyed by song key) is merged. This module loads and validates them,
writes the work lists the data agents fill from, and builds the seed spellings.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from . import latin
from .candidates import ROOT

SPELLINGS_DIR = ROOT / "data" / "spellings"
MIZRAHI_FILE = ROOT / "data" / "mizrahi_artists.txt"
SEED_CATALOG = ROOT.parent.parent / "app" / "src" / "test" / "resources" / "seed-catalog.json"

_HEBREW_RE = re.compile(r"[א-ת]")
_HE_VALID_RE = re.compile(r"[א-ת0-9 '\"\-]+")
ARTIST_FIELDS = {"he", "en", "aliases"}
TITLE_FIELDS = {"he", "en", "aliases", "script"}
INTL_TITLE_FILES = 4
EXAMPLE_TITLES = 3


class SpellingError(Exception):
    pass


@dataclass
class Spellings:
    artists: dict[str, dict] = field(default_factory=dict)
    titles: dict[str, dict] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)


def normalize_he(text: str) -> str:
    """׳ → ', ״ → ", runs of spaces collapsed."""
    return re.sub(r"\s+", " ", text.replace("׳", "'").replace("״", '"')).strip()


def _validate(entry: object, allowed: set[str], is_title: bool, where: str) -> tuple[dict | None, list[str]]:
    """Returns the cleaned entry (or None) and the problems found, each prefixed with `where`."""
    if not isinstance(entry, dict):
        return None, [f"{where}: the entry must be an object with {sorted(allowed)}"]
    problems = []
    for name in sorted(set(entry) - allowed):
        problems.append(f"{where}: unknown field '{name}' (allowed: {sorted(allowed)})")
    required = allowed - {"aliases"}
    for name in sorted(required - set(entry)):
        problems.append(f"{where}: missing field '{name}'")
    he, en, aliases = entry.get("he"), entry.get("en"), entry.get("aliases", [])
    if "he" in entry:
        if not isinstance(he, str):
            problems.append(f"{where}: he must be a string")
            he = None
        else:
            he = normalize_he(he)
            if not _HE_VALID_RE.fullmatch(he):
                problems.append(
                    f"{where}: he {he!r} must contain only Hebrew letters, digits, spaces and ' \" - "
                    "(no niqqud, Latin letters, parentheses or other punctuation) and not be empty"
                )
    if "en" in entry:
        if not isinstance(en, str):
            problems.append(f"{where}: en must be a string")
            en = None
        else:
            en = en.strip()
            if not en:
                problems.append(f"{where}: en is empty")
            elif _HEBREW_RE.search(en):
                problems.append(f"{where}: en {en!r} contains Hebrew letters; use Latin letters")
    clean_aliases: list[str] = []
    if not isinstance(aliases, list) or not all(isinstance(a, str) for a in aliases):
        problems.append(f"{where}: aliases must be a list of strings")
    else:
        clean_aliases = [normalize_he(a) if _HEBREW_RE.search(a) else a.strip() for a in aliases]
        seen: set[str] = set()
        for alias in clean_aliases:
            if not alias:
                problems.append(f"{where}: alias is an empty string")
            elif alias in seen:
                problems.append(f"{where}: alias {alias!r} appears twice")
            elif alias in (he, en):
                problems.append(f"{where}: alias {alias!r} equals the entry's own he/en")
            seen.add(alias)
    script = entry.get("script")
    if is_title and "script" in entry and script not in ("HE", "EN"):
        problems.append(f"{where}: script must be \"HE\" or \"EN\", not {script!r}")
    if problems:
        return None, problems
    cleaned = {"he": he, "en": en, "aliases": clean_aliases}
    if is_title:
        cleaned["script"] = script
    return cleaned, []


def _load_group(directory: Path, pattern: str, allowed: set[str], is_title: bool) -> tuple[dict, list[str]]:
    merged: dict[str, dict] = {}
    origin: dict[str, str] = {}
    duplicated: set[str] = set()
    errors: list[str] = []
    for path in sorted(directory.glob(pattern)):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            errors.append(f"{path.name}: cannot read as JSON ({error})")
            continue
        if not isinstance(data, dict):
            errors.append(f"{path.name}: the file must be one JSON object keyed by "
                          f"{'song key' if is_title else 'credit'}")
            continue
        for key, entry in data.items():
            if key in origin:
                duplicated.add(key)
                errors.append(f"duplicate key '{key}' in {origin[key]} and {path.name}")
                continue
            origin[key] = path.name
            cleaned, problems = _validate(entry, allowed, is_title, f"{path.name}, key '{key}'")
            errors.extend(problems)
            if cleaned is not None:
                merged[key] = cleaned
    for key in duplicated:
        merged.pop(key, None)
    return merged, errors


def load_spellings(directory: Path = SPELLINGS_DIR) -> Spellings:
    """Merge every artists*.json and titles*.json under `directory`; invalid entries are
    left out of the result and described in `errors`."""
    if not directory.is_dir():
        return Spellings()
    artists, artist_errors = _load_group(directory, "artists*.json", ARTIST_FIELDS, False)
    titles, title_errors = _load_group(directory, "titles*.json", TITLE_FIELDS, True)
    return Spellings(artists, titles, artist_errors + title_errors)


def load_mizrahi(path: Path = MIZRAHI_FILE) -> set[str]:
    """Hebrew artist names (the `he` spelling), one per line; `#` starts a comment."""
    if not path.exists():
        return set()
    names = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        name = normalize_he(line.split("#", 1)[0])
        if name:
            names.add(name)
    return names


# ---- work lists ----

def credit_of(row: dict) -> str:
    """The key of a row's artist spelling."""
    return (row.get("artist_latin") or row["artist"]) if row["origin"] == "INTL" else row["artist"]


def title_of(row: dict) -> str:
    """Hebrew for IL rows; Latin for INTL rows."""
    return (row.get("title_latin") or row["title"]) if row["origin"] == "INTL" else row["title"]


def _most_common(values: list[str]) -> list[str]:
    return [v for v, _ in sorted(Counter(values).items(), key=lambda item: (-item[1], item[0]))]


def _artist_todo(rows: list[dict], origin: str, done: dict) -> list[dict]:
    by_credit: dict[str, list[dict]] = {}
    for r in rows:
        if r["origin"] == origin and credit_of(r) not in done:
            by_credit.setdefault(credit_of(r), []).append(r)
    entries = []
    for n, credit in enumerate(sorted(by_credit), 1):
        group = by_credit[credit]
        if origin == "IL":
            seen = _most_common([r["deezer_artist"] for r in group if latin.is_latin(r.get("deezer_artist"))])
            seen_field = {"en_seen": seen}
        else:
            seen = _most_common([r["artist"] for r in group if _HEBREW_RE.search(r["artist"])])
            seen_field = {"he_seen": seen}
        popular = sorted(group, key=lambda r: (-(r.get("popularity") or 0), title_of(r)))
        titles = list(dict.fromkeys(title_of(r) for r in popular))[:EXAMPLE_TITLES]
        entries.append({"n": n, "credit": credit, **seen_field, "songs": len(group), "titles": titles})
    return entries


def _title_entry(r: dict, n: int) -> dict:
    return {
        "n": n, "key": r["key"], "title": title_of(r), "artist": credit_of(r), "year": r["year"],
        "deezer_title": r.get("deezer_title") or "", "deezer_artist": r.get("deezer_artist") or "",
    }  # fmt: skip


def work_lists(rows: list[dict], spellings: Spellings) -> dict[str, list[dict]]:
    """File name → entries, for every credit and every song key that has no spelling yet."""
    lists = {
        "artists-il.todo.json": _artist_todo(rows, "IL", spellings.artists),
        "artists-intl.todo.json": _artist_todo(rows, "INTL", spellings.artists),
    }
    todo = [r for r in rows if r["key"] not in spellings.titles]
    il = sorted((r for r in todo if r["origin"] == "IL"), key=lambda r: (title_of(r), r["key"]))
    intl = sorted((r for r in todo if r["origin"] == "INTL"), key=lambda r: (title_of(r), r["key"]))
    lists["titles-il.todo.json"] = [_title_entry(r, n) for n, r in enumerate(il, 1)]
    for part in range(INTL_TITLE_FILES):
        chunk = intl[part * len(intl) // INTL_TITLE_FILES : (part + 1) * len(intl) // INTL_TITLE_FILES]
        lists[f"titles-intl-{part + 1}.todo.json"] = [_title_entry(r, n) for n, r in enumerate(chunk, 1)]
    return lists


def write_work_lists(lists: dict[str, list[dict]], directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for name, entries in lists.items():
        (directory / name).write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def summary(rows: list[dict], spellings: Spellings) -> list[str]:
    lines = []
    for origin in ("IL", "INTL"):
        group = [r for r in rows if r["origin"] == origin]
        credits = {credit_of(r) for r in group}
        lines.append(
            f"{origin}: {len(group)} rows, {len(credits)} distinct credits, "
            f"{sum(1 for c in credits if c in spellings.artists)} credits with a spelling, "
            f"{sum(1 for r in group if r['key'] in spellings.titles)} titles with a spelling"
        )
    if spellings.errors:
        lines.append(f"{len(spellings.errors)} loader errors:")
        lines.extend(f"  {e}" for e in spellings.errors)
    return lines


# ---- seed spellings ----

# Seed id → (row title, row credit) as `credit_of`/`title_of` give them for the row in dated.json.
SEED_ROWS = {
    "shoshana-damari-kalaniyot": ("כלניות", "שושנה דמארי"),
    "shuli-natan-yerushalayim-shel-zahav": ("ירושלים של זהב", "שולי נתן"),
    "arik-einstein-ani-veata": ("אני ואתה", "אריק איינשטיין"),
    "kaveret-yo-ya": ("יו יה", "כוורת"),
    "shmulik-kraus-shuv": ("שוב", "שמוליק קראוס"),
    "zohar-argov-haperach-begani": ("הפרח בגני", "זוהר ארגוב"),
    "mashina-rakevet-layla-lekahir": ("רכבת לילה", "משינה"),
    "dana-international-diva": ("דיווה", "דנה אינטרנשיונל"),
    "hadag-nahash-shirat-hasticker": ("שירת הסטיקר", "הדג נחש ואהובה עוזרי"),
    "omer-adam-tel-aviv": ("תל אביב", "עומר אדם"),
    "static-and-ben-el-tavori-tudo-bom": ("טודו בום", "סטטיק ובן-אל תבורי"),
    "netta-toy": ("Toy", "נטע ברזילי"),
    "elvis-presley-hound-dog": ("Hound Dog", "Elvis Presley"),
    "the-beatles-help": ("Help!", "The Beatles"),
    "abba-dancing-queen": ("Dancing Queen", "ABBA"),
    "nena-99-luftballons": ("99 Luftballons", "Nena"),
    "u2-with-or-without-you": ("With or Without You", "U2"),
    "nirvana-smells-like-teen-spirit": ("Smells Like Teen Spirit", "Nirvana"),
    "spice-girls-stop": ("Stop", "Spice Girls"),
    "eminem-lose-yourself": ("Lose Yourself", "Eminem"),
    "beyonce-crazy-in-love": ("Crazy in Love", "Beyoncé featuring Jay-Z"),
    "ed-sheeran-shape-of-you": ("Shape of You", "Ed Sheeran"),
    "ariana-grande-7-rings": ("7 Rings", "Ariana Grande"),
    "alex-warren-ordinary": ("Ordinary", "Alex Warren"),
}  # fmt: skip

# Seed id → (corrected artist he, the seed's old he, kept as an alias).
SEED_HE_CORRECTIONS = {"nena-99-luftballons": ("ננה", "נינה")}

# Row credit → spellings of the other credited acts, added as aliases of the seed artist. For these
# multi-act credits the whole credit string is not kept as an alias (seed aliases that contain one of
# the other acts' names, like "Beyoncé feat. Jay-Z", are dropped too). The other 22 seed credits
# each name a single act.
SEED_OTHER_ACTS = {
    "Beyoncé featuring Jay-Z": ["Jay-Z", "ג'יי זי"],
    "הדג נחש ואהובה עוזרי": ["אהובה עוזרי", "Ahuva Ozeri"],
}


def build_seed_spellings(
    seed: dict,
    rows: list[dict],
    row_names: dict[str, tuple[str, str]] = SEED_ROWS,
    he_corrections: dict[str, tuple[str, str]] = SEED_HE_CORRECTIONS,
    other_acts: dict[str, list[str]] = SEED_OTHER_ACTS,
) -> tuple[dict[str, dict], dict[str, dict]]:
    """(artists by credit, titles by key) for the seed songs, copied from the seed catalog."""
    by_name = {(title_of(r), credit_of(r)): r for r in rows}
    artists: dict[str, dict] = {}
    titles: dict[str, dict] = {}
    missing = []
    for song in seed["songs"]:
        row = by_name.get(row_names.get(song["id"], ("", "")))
        if row is None:
            missing.append(f"{song['id']} (looked for {row_names.get(song['id'])})")
            continue
        credit = credit_of(row)
        he = song["artist"]["he"]
        aliases = list(song.get("artistAliases", []))
        if song["id"] in he_corrections:
            he, old = he_corrections[song["id"]]
            aliases.append(old)
        if credit in other_acts:
            aliases = [a for a in aliases if not any(act in a for act in other_acts[credit])]
            aliases.extend(other_acts[credit])
        elif credit not in aliases:
            aliases.append(credit)
        aliases = [a for a in dict.fromkeys(aliases) if a not in (he, song["artist"]["en"])]
        artists[credit] = {"he": he, "en": song["artist"]["en"], "aliases": aliases}
        titles[row["key"]] = {
            "he": song["title"]["he"], "en": song["title"]["en"],
            "aliases": list(song.get("titleAliases", [])), "script": song["script"],
        }  # fmt: skip
    if missing:
        raise SpellingError("seed songs with no row in dated.json: " + "; ".join(missing))
    return artists, titles


def write_seed_files(rows: list[dict], directory: Path = SPELLINGS_DIR, seed_path: Path = SEED_CATALOG) -> None:
    seed = json.loads(seed_path.read_text(encoding="utf-8"))
    artists, titles = build_seed_spellings(seed, rows)
    directory.mkdir(parents=True, exist_ok=True)
    for name, data in (("artists-seed.json", artists), ("titles-seed.json", titles)):
        (directory / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
