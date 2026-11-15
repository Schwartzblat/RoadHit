"""Assembles the app's catalog.json from work/dated.json and the spelling files."""

from __future__ import annotations

import json
import re
import unicodedata
from collections import Counter
from pathlib import Path

from .candidates import ROOT
from .spellings import SPELLINGS_DIR, MIZRAHI_FILE, Spellings, credit_of, load_mizrahi, load_spellings

APP_CATALOG = ROOT.parent.parent / "app" / "src" / "main" / "assets" / "catalog.json"
MIN_YEAR, MAX_YEAR = 1940, 2026
GENRES = {132: "POP", 152: "ROCK", 85: "ROCK", 464: "ROCK", 116: "HIPHOP", 113: "DANCE", 106: "DANCE",
          165: "RNB", 169: "RNB"}  # fmt: skip
SIZE_TARGETS = "about 2,000 INTL, 1,200 IL, 100 IL before 1963"
ALL_GENRES = ["POP", "ROCK", "HIPHOP", "DANCE", "RNB", "MIZRAHI", "OTHER"]


class AssembleError(Exception):
    pass


def slug(text: str) -> str:
    """Lower-case ASCII; `&` → and; apostrophes vanish; other runs of non-alphanumerics → one `-`."""
    folded = "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.category(c).startswith("M"))
    folded = folded.lower().replace("&", " and ")
    folded = re.sub(r"['’‘`´]", "", folded)
    return re.sub(r"[^a-z0-9]+", "-", folded).strip("-")


def genre_of(genre_id: int, artist_he: str, mizrahi: set[str]) -> str:
    if artist_he in mizrahi:
        return "MIZRAHI"
    return GENRES.get(genre_id, "OTHER")


def _quoted(items) -> str:
    return ", ".join(repr(i) for i in items)


def build_songs(rows: list[dict], spellings: Spellings, mizrahi: set[str] | None = None) -> list[dict]:
    """Song dicts in the app's schema, sorted by id. Raises AssembleError listing every problem."""
    mizrahi = mizrahi or set()
    problems: list[str] = []
    missing_credits = sorted({credit_of(r) for r in rows if credit_of(r) not in spellings.artists})
    missing_keys = sorted(r["key"] for r in rows if r["key"] not in spellings.titles)
    if missing_credits:
        problems.append(f"{len(missing_credits)} credits have no artist spelling: {_quoted(missing_credits)}")
    if missing_keys:
        problems.append(f"{len(missing_keys)} song keys have no title spelling: {_quoted(missing_keys)}")
    for r in rows:
        label = f"{r['key']} ({r['title']} / {r['artist']})"
        if not MIN_YEAR <= r["year"] <= MAX_YEAR:
            problems.append(f"year {r['year']} outside {MIN_YEAR}-{MAX_YEAR}: {label}")
        if not r["deezer_id"] or r["deezer_id"] <= 0:
            problems.append(f"deezer id {r['deezer_id']} must be positive: {label}")
    for deezer_id, count in Counter(r["deezer_id"] for r in rows if r["deezer_id"] and r["deezer_id"] > 0).items():
        if count > 1:
            keys = [r["key"] for r in rows if r["deezer_id"] == deezer_id]
            problems.append(f"deezer id {deezer_id} is used by {count} songs: {_quoted(keys)}")
    if problems:
        raise AssembleError("\n".join(problems))

    songs = []
    for r in rows:
        a, t = spellings.artists[credit_of(r)], spellings.titles[r["key"]]
        songs.append({
            "id": f"{slug(a['en'])}-{slug(t['en'])}",
            "title": {"he": t["he"], "en": t["en"]},
            "artist": {"he": a["he"], "en": a["en"]},
            "titleAliases": list(t["aliases"]),
            "artistAliases": list(a["aliases"]),
            "script": t["script"],
            "year": r["year"],
            "origin": r["origin"],
            "genre": genre_of(r["genre_id"], a["he"], mizrahi),
            "deezerId": r["deezer_id"],
            "rank": r["popularity"],
        })  # fmt: skip
    for song, r in zip(songs, rows):
        if not slug(song["title"]["en"]) or not slug(song["artist"]["en"]):
            problems.append(f"{r['key']}: the en spelling has no letters or digits, so no id can be made "
                            f"({song['artist']['en']!r} / {song['title']['en']!r})")
    counts = Counter(s["id"] for s in songs)
    for song in songs:
        if counts[song["id"]] > 1:
            song["id"] = f"{song['id']}-{song['year']}"
    counts = Counter(s["id"] for s in songs)
    for song_id, count in counts.items():
        if count > 1:
            problems.append(f"id {song_id!r} is still used by {count} songs after adding the year")
    if problems:
        raise AssembleError("\n".join(problems))
    return sorted(songs, key=lambda s: s["id"])


def catalog_text(songs: list[dict]) -> str:
    """{ "version": 1, "songs": [...] } with one song per line, sorted by id, UTF-8 unescaped."""
    ordered = sorted(songs, key=lambda s: s["id"])
    body = ",\n".join("    " + json.dumps(s, ensure_ascii=False) for s in ordered)
    return '{\n  "version": 1,\n  "songs": [\n' + body + "\n  ]\n}\n"


def build_report(songs: list[dict], dated: list[dict], dropped: list[dict]) -> str:
    lines = ["# Catalog report", ""]
    lines.append(f"Songs: {len(songs)} ({sum(s['origin'] == 'IL' for s in songs)} IL, "
                 f"{sum(s['origin'] == 'INTL' for s in songs)} INTL)")
    lines += ["", "## Songs per decade, origin and genre", ""]
    lines.append("| decade | origin | " + " | ".join(ALL_GENRES) + " | total |")
    lines.append("|---|---|" + "---|" * (len(ALL_GENRES) + 1))
    counts = Counter((s["year"] // 10 * 10, s["origin"], s["genre"]) for s in songs)
    totals = Counter()
    for decade, origin in sorted({(d, o) for d, o, _ in counts}, key=lambda k: (k[0], k[1])):
        cells = [counts[(decade, origin, g)] for g in ALL_GENRES]
        totals.update({(origin, g): c for g, c in zip(ALL_GENRES, cells)})
        lines.append(f"| {decade}s | {origin} | " + " | ".join(map(str, cells)) + f" | {sum(cells)} |")
    for origin in ("IL", "INTL"):
        cells = [totals[(origin, g)] for g in ALL_GENRES]
        lines.append(f"| total | {origin} | " + " | ".join(map(str, cells)) + f" | {sum(cells)} |")
    lines += ["", "## Dropped candidates by reason", ""]
    reasons = Counter(d["reason"] for d in dropped)
    lines += [f"- {reason}: {count}" for reason, count in sorted(reasons.items())] or ["- none"]
    lines.append(f"- total dropped: {len(dropped)}")
    lines += ["", "## Flagged years", ""]
    for origin in ("IL", "INTL"):
        flagged = sum(1 for r in dated if r["origin"] == origin and r.get("flagged"))
        lines.append(f"- {origin}: {flagged} of {sum(1 for r in dated if r['origin'] == origin)} songs have a flagged year")
    il = [s for s in songs if s["origin"] == "IL"]
    lines += ["", f"## Size targets ({SIZE_TARGETS})", "", "| | target | actual |", "|---|---|---|",
              f"| INTL songs | about 2,000 | {sum(s['origin'] == 'INTL' for s in songs)} |",
              f"| IL songs | about 1,200 | {len(il)} |",
              f"| IL songs before 1963 | about 100 | {sum(s['year'] < 1963 for s in il)} |", ""]  # fmt: skip
    return "\n".join(lines)


def read_work_json(path: Path, stage: str) -> list[dict]:
    if not path.exists():
        raise AssembleError(f"{path} does not exist; run `uv run roadhit-catalog {stage}` first")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise AssembleError(f"{path} is not valid JSON ({error})") from error


def run_assemble(
    out: Path = APP_CATALOG,
    work_dir: Path = ROOT / "work",
    spellings_dir: Path = SPELLINGS_DIR,
    mizrahi_path: Path = MIZRAHI_FILE,
) -> int:
    """Writes `out` and work/report.md; returns the number of songs."""
    dated = read_work_json(work_dir / "dated.json", "years")
    dropped = read_work_json(work_dir / "dropped.json", "deezer")
    loaded = load_spellings(spellings_dir)
    if loaded.errors:
        raise AssembleError("spelling files have errors:\n" + "\n".join(f"  {e}" for e in loaded.errors))
    songs = build_songs(dated, loaded, load_mizrahi(mizrahi_path))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(catalog_text(songs), encoding="utf-8")
    (work_dir / "report.md").write_text(build_report(songs, dated, dropped), encoding="utf-8")
    return len(songs)
