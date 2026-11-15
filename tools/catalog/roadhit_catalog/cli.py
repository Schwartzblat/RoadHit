"""roadhit-catalog command line."""

from __future__ import annotations

import argparse
import datetime
import json
import sys
from collections import Counter
from pathlib import Path

import httpx

from . import (
    assemble, collect_billboard, collect_hewiki, collect_uk, deezer, duplicates, famous_acts, hewiki_albums, hewiki_songs, http, latin,
    spellings, sweep, years,
)  # fmt: skip
from .candidates import (
    CLASSICS_SOURCE,
    EXTRAS_SOURCE,
    LAST_YEAR,
    ROOT,
    ExtrasError,
    YearOutOfRange,
    check_years,
    find_gaps,
    load_classics,
    load_extras,
    merge_candidates,
)

WORK_DIR = ROOT / "work"
CANDIDATES_JSON = WORK_DIR / "candidates.json"
MATCHED_JSON = WORK_DIR / "matched.json"
DROPPED_JSON = WORK_DIR / "dropped.json"
DATED_JSON = WORK_DIR / "dated.json"
SWEEP_JSON = WORK_DIR / "sweep.json"
HEWIKI_SONGS_JSON = WORK_DIR / "hewiki_songs.json"
HEWIKI_ALBUMS_JSON = WORK_DIR / "hewiki_albums.json"
FAMOUS_ACTS_JSON = WORK_DIR / "famous_acts.json"

NUMBER_ONES_MINIMUM = 5  # fewer distinct number ones than this in a year is suspicious
UK_MINIMUM = 20  # the UK year-end tables hold 20 (1972) or 50 singles


def _last_years(today: datetime.date) -> tuple[int, int]:
    """(last Hebrew-chart year, last finished Gregorian chart year)."""
    # The Hebrew chart is broadcast on the eve of Rosh Hashana (Sept/Oct).
    last_hebrew = today.year if today.month >= 10 else today.year - 1
    return last_hebrew, today.year - 1


def collect(offline: bool, today: datetime.date | None = None) -> int:
    http.set_offline(offline)
    last_hebrew, last_gregorian = _last_years(today or datetime.date.today())

    il_rows, il_pages = collect_hewiki.collect_il()
    intl_rows, intl_pages = collect_hewiki.collect_intl()
    bb_rows, bb_pages = collect_billboard.collect(last_gregorian)
    uk_rows, uk_pages = collect_uk.collect()
    song_rows, song_report = hewiki_songs.collect()
    album_rows, album_report, albums = hewiki_albums.collect()
    famous_rows, famous_report = famous_acts.collect(albums)
    classics = load_classics()
    extras = load_extras()
    intl_rows = resolve_latin(intl_rows)
    rows = il_rows + intl_rows + bb_rows + uk_rows + song_rows + album_rows + famous_rows + classics + extras
    check_years(rows)

    candidates = merge_candidates(rows)
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    CANDIDATES_JSON.write_text(json.dumps(candidates, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"Pages read: {len(il_pages)} Hebrew-chart, {len(intl_pages)} international-chart, "
          f"{len(bb_pages)} Billboard, {len(uk_pages)} UK year-end")
    extended = sum(1 for r in il_rows if r["extended"])
    intl_extended = sum(1 for r in intl_rows if r["extended"])
    bb_extended = sum(1 for r in bb_rows if r.get("extended"))
    print(f"Rows: {len(il_rows)} {collect_hewiki.IL_SOURCE} ({extended} of them past the top {collect_hewiki.IL_LIMIT}), "
          f"{len(intl_rows)} {collect_hewiki.INTL_SOURCE} ({intl_extended} past the top {collect_hewiki.INTL_LIMIT}), "
          f"{len(bb_rows)} billboard ({bb_extended} past the top {collect_billboard.YEAR_END_LIMIT}), "
          f"{len(uk_rows)} {collect_uk.SOURCE}, "
          f"{len(song_rows)} {hewiki_songs.SOURCE}, {len(album_rows)} {hewiki_albums.SOURCE}, "
          f"{len(famous_rows)} {famous_acts.SOURCE}, {len(classics)} {CLASSICS_SOURCE}, {len(extras)} {EXTRAS_SOURCE}")
    print_song_report(song_report)
    print_album_report(album_report)
    print_famous_report(famous_report)
    for path, report in ((HEWIKI_SONGS_JSON, song_report), (HEWIKI_ALBUMS_JSON, album_report),
                         (FAMOUS_ACTS_JSON, famous_report)):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with_extras = [c for c in candidates if EXTRAS_SOURCE in c["sources"]]
    merged = sum(1 for c in with_extras if len(c["sources"]) > 1)
    print(f"Extras: {len(with_extras)} candidates, {merged} of them merged into a chart candidate")
    print()
    print_counts(candidates)
    print()
    expected = {
        collect_hewiki.IL_SOURCE: (range(1963, last_hebrew + 1), collect_hewiki.IL_LIMIT),
        collect_hewiki.INTL_SOURCE: (range(1962, last_gregorian + 1), collect_hewiki.INTL_LIMIT),
        collect_billboard.YEAR_END_SOURCE: (
            range(collect_billboard.YEAR_END_FIRST_YEAR, last_gregorian + 1),
            collect_billboard.YEAR_END_LIMIT,
        ),
        collect_billboard.NUMBER_ONES_SOURCE: (collect_billboard.NUMBER_ONES_YEARS, NUMBER_ONES_MINIMUM),
        collect_uk.SOURCE: (range(1972, collect_uk.YEARS.stop), UK_MINIMUM),
    }
    gaps = find_gaps(rows, expected)
    print(f"Gaps ({len(gaps)} source-years with fewer rows than expected):")
    for source, year, found, minimum in gaps:
        print(f"  {source:<22} {year}  {found:>2}/{minimum}")
    print()
    print_latin_stats(intl_rows, candidates)
    print()
    print(f"Wrote {len(candidates)} candidates to {CANDIDATES_JSON.relative_to(ROOT)}")
    return 0


def print_song_report(report: dict) -> None:
    """How many song pages gave a candidate, and why the others did not (10 examples each)."""
    print(f"{hewiki_songs.SOURCE}: {report['pages']} pages in {len(report['categories'])} categories")
    for reason, titles in report["reasons"].items():
        examples = ", ".join(titles[:10])
        print(f"  {reason:<20} {len(titles):>5}  {examples}")


def print_album_report(report: dict) -> None:
    """How many album pages gave candidates, why the others did not, and the track-list forms met."""
    print(f"{hewiki_albums.SOURCE}: {report['pages']} pages in {len(report['categories'])} categories; "
          f"track lists: {report['forms']}; tracks skipped: {report['skipped_tracks']}; "
          f"Latin-titled tracks left out: {report['latin_tracks']}")
    for reason, titles in report["reasons"].items():
        print(f"  {reason:<26} {len(titles):>5}  {', '.join(titles[:10])}")


def print_famous_report(reports: list[dict]) -> None:
    """Per famous act: Deezer albums, albums with an established year, tracks taken."""
    print(f"{famous_acts.SOURCE}: {len(reports)} acts")
    print(f"  {'act':<24}{'deezer albums':>14}{'with year':>10}{'taken':>7}{'tracks':>8}")
    for r in reports:
        taken = sum(1 for a in r["albums"] if a["verdict"] == famous_acts.TAKEN)
        print(f"  {r['act']:<24}{r['deezer_albums']:>14}{r['established']:>10}{taken:>7}{r['tracks']:>8}")
    nothing = [r["act"] for r in reports if not r["tracks"]]
    print(f"  acts with no tracks ({len(nothing)}): {', '.join(nothing)}")


def resolve_latin(intl_rows: list[dict]) -> list[dict]:
    """Latin title/artist for Hebrew Wikipedia's international rows, via langlinks."""
    wanted = []
    for row in intl_rows:
        if row["origin"] == "INTL":
            if not latin.is_latin(row["title"]):
                wanted.append(row.get("title_link"))
            if not latin.is_latin(row["artist"]):
                wanted.append(row.get("artist_link"))
    return latin.add_latin(intl_rows, latin.fetch_langlinks([t for t in wanted if t]))


def print_latin_stats(intl_rows: list[dict], candidates: list[dict]) -> None:
    hebrew = [r for r in intl_rows if r["origin"] == "INTL" and not latin.is_latin(r["artist"])]
    resolved = [r for r in hebrew if r["artist_latin"]]
    needs = [r for r in intl_rows if r.get("needs_latin")]
    merged = sum(
        1 for c in candidates
        if collect_hewiki.INTL_SOURCE in c["sources"] and len(c["sources"]) > 1
    )  # fmt: skip
    print(f"Hebrew-wiki international rows with a Hebrew-script artist: {len(hebrew)}; "
          f"artist resolved to Latin: {len(resolved)}")
    print(f"Hebrew-wiki international rows left needs_latin (title or artist unresolved): {len(needs)}")
    print(f"Candidates combining hewiki-intl with another source: {merged}; "
          f"candidates with needs_latin: {sum(c['needs_latin'] for c in candidates)}")


def print_counts(candidates: list[dict]) -> None:
    counts = Counter((c["chart_year"] // 10 * 10, c["origin"]) for c in candidates)
    decades = sorted({d for d, _ in counts})
    print(f"{'decade':<8}{'IL':>6}{'INTL':>6}{'total':>7}")
    for decade in decades:
        il, intl = counts[(decade, "IL")], counts[(decade, "INTL")]
        print(f"{str(decade) + 's':<8}{il:>6}{intl:>6}{il + intl:>7}")
    il_total = sum(v for (_, o), v in counts.items() if o == "IL")
    intl_total = sum(v for (_, o), v in counts.items() if o == "INTL")
    print(f"{'total':<8}{il_total:>6}{intl_total:>6}{il_total + intl_total:>7}")


def _write_json(path: Path, rows: list[dict]) -> None:
    path.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def match_deezer(offline: bool) -> int:
    http.set_offline(offline)
    candidates = json.loads(CANDIDATES_JSON.read_text(encoding="utf-8"))
    latin_names = latin.fetch_langlinks(deezer.hebrew_names_to_resolve(candidates))
    matcher = deezer.Matcher(deezer.DeezerApi(), latin_names)
    floors = deezer.load_floors()
    keep = deezer.load_keep()
    overrides = deezer.load_overrides()
    exclude = deezer.load_exclude()
    famous = deezer.load_famous()

    def progress(i: int, total: int) -> None:
        if i % 250 == 0 or i == total:
            print(f"  matched {i}/{total}", file=sys.stderr)

    matched, kept, dropped = deezer.run(candidates, matcher, floors, keep, overrides, progress, exclude, famous)
    _write_json(MATCHED_JSON, kept)
    _write_json(DROPPED_JSON, dropped)
    print_deezer_counts(candidates, matched, kept, dropped)
    print()
    print(f"Wrote {len(kept)} songs to {MATCHED_JSON.relative_to(ROOT)} "
          f"and {len(dropped)} dropped candidates to {DROPPED_JSON.relative_to(ROOT)}")
    return 0


def print_deezer_counts(candidates: list[dict], matched: list[dict], kept: list[dict], dropped: list[dict]) -> None:
    """Counts per decade × origin: candidates in, matched, dropped by reason, kept."""
    def cell(row: dict) -> tuple[int, str]:
        return row["chart_year"] // 10 * 10, row["origin"]

    columns = ["in", "matched", *deezer.REASONS, "kept"]
    counts: dict[tuple[int, str], Counter] = {}
    for name, rows in (("in", candidates), ("matched", matched), ("kept", kept)):
        for row in rows:
            counts.setdefault(cell(row), Counter())[name] += 1
    for row in dropped:
        counts.setdefault(cell(row), Counter())[row["reason"]] += 1
    header = f"{'decade':<8}{'origin':<7}" + "".join(f"{c:>16}" for c in columns)
    print(header)
    totals = {origin: Counter() for origin in ("IL", "INTL")}
    for decade, origin in sorted(counts, key=lambda k: (k[1], k[0])):
        c = counts[(decade, origin)]
        totals[origin].update(c)
        print(f"{str(decade) + 's':<8}{origin:<7}" + "".join(f"{c[col]:>16}" for col in columns))
    for origin, c in totals.items():
        print(f"{'total':<8}{origin:<7}" + "".join(f"{c[col]:>16}" for col in columns))
    print("(decade of a duplicate or kept row is its own chart year; a kept row takes the earliest year of its duplicates)")


def date_songs(offline: bool) -> int:
    http.set_offline(offline)
    rows = json.loads(MATCHED_JSON.read_text(encoding="utf-8"))
    overrides = years.load_overrides()
    lookups = years.WebLookups()

    def progress(i: int, total: int) -> None:
        if i % 100 == 0 or i == total:
            print(f"  dated {i}/{total}", file=sys.stderr)

    dated = years.run(rows, lookups, overrides, LAST_YEAR, progress)
    _write_json(DATED_JSON, dated)
    print_year_counts(dated)
    print()
    searched = sum(1 for r in dated if r["year_source"] not in (years.CLASSICS, years.ALBUM))
    for service, calls in lookups.calls.items():
        print(f"{service} requests (cached or fetched): {calls} ({calls / max(searched, 1):.2f} per song looked up)")
    print()
    print(f"Wrote {len(dated)} songs to {DATED_JSON.relative_to(ROOT)}")
    return 0


def print_year_counts(dated: list[dict]) -> None:
    """Counts per decade (of the final year) × origin × year_source, and the flagged rows."""
    counts: dict[tuple[int, str], Counter] = {}
    for row in dated:
        c = counts.setdefault((row["year"] // 10 * 10, row["origin"]), Counter())
        c[row["year_source"]] += 1
        c["flagged"] += row["flagged"]
        c["total"] += 1
    columns = [*years.YEAR_SOURCES, "flagged", "total"]
    print(f"{'decade':<8}{'origin':<7}" + "".join(f"{c:>13}" for c in columns))
    totals = {origin: Counter() for origin in ("IL", "INTL")}
    for decade, origin in sorted(counts, key=lambda k: (k[1], k[0])):
        c = counts[(decade, origin)]
        totals[origin].update(c)
        print(f"{str(decade) + 's':<8}{origin:<7}" + "".join(f"{c[col]:>13}" for col in columns))
    for origin, c in totals.items():
        print(f"{'total':<8}{origin:<7}" + "".join(f"{c[col]:>13}" for col in columns))
    flagged = [r for r in dated if r["flagged"]]
    conflicts = sum(1 for r in flagged if r["first"] is not None)
    print(f"Flagged: {len(flagged)} ({conflicts} with a first release outside the window, "
          f"{len(flagged) - conflicts} with no evidence)")


def write_spellings() -> int:
    rows = assemble.read_work_json(WORK_DIR / "dated.json", "years")
    loaded = spellings.load_spellings()
    lists = spellings.work_lists(rows, loaded)
    target = WORK_DIR / "spellings"
    spellings.write_work_lists(lists, target)
    for line in spellings.summary(rows, loaded):
        print(line)
    print()
    for name, entries in lists.items():
        print(f"{len(entries):>6}  {target / name}")
    return 1 if loaded.errors else 0


def list_duplicates() -> int:
    rows = assemble.read_work_json(WORK_DIR / "dated.json", "years")
    israeli, international = duplicates.find_groups(rows, "IL"), duplicates.find_groups(rows, "INTL")
    target = WORK_DIR / "duplicates.md"
    target.write_text(
        duplicates.report(israeli, "IL") + "\n\n" + duplicates.report(international, "INTL") + "\n", encoding="utf-8"
    )
    print(f"{len(israeli)} groups, {sum(map(len, israeli))} Israeli rows; "
          f"{len(international)} groups, {sum(map(len, international))} international rows: {target}")
    return 0


def build_catalog(out: Path) -> int:
    count = assemble.run_assemble(out, work_dir=WORK_DIR)
    print(f"Wrote {count} songs to {out}")
    print(f"Wrote {WORK_DIR / 'report.md'}")
    return 0


def sweep_previews(catalog_path: Path, seed: int | None) -> int:
    songs = json.loads(catalog_path.read_text(encoding="utf-8"))["songs"]
    sample = sweep.pick_sample(songs, seed)

    def progress(i: int, total: int) -> None:
        if i % 250 == 0 or i == total:
            print(f"  checked {i}/{total}", file=sys.stderr)

    failures, downloads = sweep.run(songs, sweep.fetch_track, http.get_bytes, sample, progress)
    today = datetime.date.today().isoformat()
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    SWEEP_JSON.write_text(json.dumps({"date": today, "checked": len(songs), "seed": seed, "failures": failures,
                                      "downloads": downloads}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    bad_downloads = [d for d in downloads if d["problem"]]
    print(f"{len(songs)} songs checked, {len(failures)} without a preview "
          f"({len(failures) / max(len(songs), 1):.2%})")
    for failure in failures:
        print(f"  {failure['id']} (Deezer {failure['deezerId']}): {failure['reason']}")
    sizes = sorted(d["bytes"] for d in downloads)
    print(f"{len(downloads)} previews downloaded, {len(bad_downloads)} bad"
          + (f"; sizes {sizes[0]}–{sizes[-1]} bytes" if sizes else ""))
    for download in bad_downloads:
        print(f"  {download['id']}: {download['problem']}")
    if sweep.too_many(failures, len(songs)):
        # Above the bar the cause is not a few withdrawn tracks: no exclude lines to copy blindly.
        print()
        print(f"More than {sweep.MAX_FAILURE_SHARE:.1%} of the songs failed: look into it before excluding them.")
    elif failures:
        dated = json.loads(DATED_JSON.read_text(encoding="utf-8"))
        print()
        print("Lines for data/exclude.txt:")
        for line in sweep.exclude_lines(failures, dated, today):
            print(line)
    print(f"Wrote {SWEEP_JSON.relative_to(ROOT) if SWEEP_JSON.is_relative_to(ROOT) else SWEEP_JSON}")
    return 1 if failures or bad_downloads else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="roadhit-catalog")
    sub = parser.add_subparsers(dest="command", required=True)
    collect_cmd = sub.add_parser("collect", help="collect chart candidates into work/candidates.json")
    collect_cmd.add_argument(
        "--offline", action="store_true", help="fail on any cache miss instead of fetching"
    )
    deezer_cmd = sub.add_parser(
        "deezer", help="match candidates to Deezer and apply the popularity floor (work/matched.json)"
    )
    deezer_cmd.add_argument(
        "--offline", action="store_true", help="fail on any cache miss instead of fetching"
    )
    years_cmd = sub.add_parser("years", help="original release years (work/dated.json)")
    years_cmd.add_argument(
        "--offline", action="store_true", help="fail on any cache miss instead of fetching"
    )
    sub.add_parser("duplicates", help="list rows that may be one song twice or a cover (work/duplicates.md)")
    sub.add_parser("spellings", help="write the spelling work lists (work/spellings/) and check the spelling files")
    assemble_cmd = sub.add_parser("assemble", help="build the app's catalog.json from the dated songs and spellings")
    assemble_cmd.add_argument("--out", type=Path, default=assemble.APP_CATALOG, help="where to write the catalog")
    sweep_cmd = sub.add_parser("sweep", help="check that every catalog song still has a Deezer preview (fresh requests)")
    sweep_cmd.add_argument("--catalog", type=Path, default=assemble.APP_CATALOG, help="the catalog to check")
    sweep_cmd.add_argument("--seed", type=int, default=None, help="seed for the downloaded sample")
    args = parser.parse_args(argv)
    if args.command == "sweep":
        try:
            return sweep_previews(args.catalog, args.seed)
        except (http.ApiError, httpx.HTTPError) as error:
            print(f"error: {error}", file=sys.stderr)
            return 1
    if args.command in ("spellings", "assemble", "duplicates"):
        try:
            if args.command == "duplicates":
                return list_duplicates()
            return write_spellings() if args.command == "spellings" else build_catalog(args.out)
        except assemble.AssembleError as error:
            print(f"error: {error}", file=sys.stderr)
            return 1
    if args.command == "years":
        try:
            return date_songs(offline=args.offline)
        except (http.CacheMiss, years.YearError) as error:
            print(f"error: {error}", file=sys.stderr)
            return 1
    if args.command == "deezer":
        try:
            return match_deezer(offline=args.offline)
        except (http.CacheMiss, deezer.OverrideError, deezer.ExclusionError) as error:
            print(f"error: {error}", file=sys.stderr)
            return 1
    if args.command == "collect":
        try:
            return collect(offline=args.offline)
        except (http.CacheMiss, YearOutOfRange, ExtrasError) as error:
            print(f"error: {error}", file=sys.stderr)
            return 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
