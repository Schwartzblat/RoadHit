# roadhit-catalog

Builds the app's song catalog (`app/src/main/assets/catalog.json`) offline. Run every command from
this folder with `uv run roadhit-catalog <stage>`; the tests run with `uv run pytest -q`.
Network answers are cached in `.cache/`, so a re-run fetches nothing new; add `--offline` to
`collect`, `deezer` or `years` to fail on a cache miss instead of fetching. `work/` holds each
stage's output and is not committed; `data/` holds the hand-written inputs and is.

`work/` and `.cache/` exist only on the machine that built the catalog (both are git-ignored).
Keep them: every recipe below starts from `work/dated.json` and re-runs stages with `--offline`.
Without them (a fresh clone, a cleaned folder) the stages fetch everything again — about 6,000
MusicBrainz requests at one per second, with answers that may have changed since. For a one-song
fix without them, use [the hand-edit fallback](#hand-edit-fallback).

## Stages

1. `collect` — reads the Hebrew Wikipedia charts (Israeli and international), the Billboard
   charts, the Hebrew Wikipedia song articles (source `hewiki-songs`), the tracks of Hebrew
   Wikipedia album pages of 1960–1999 (source `hewiki-albums`), the tracks of the studio albums
   of the acts in `data/famous_acts.txt` whose year Hebrew Wikipedia or MusicBrainz knows (source
   `famous-acts`, 1960–1999; an album page whose categories, infobox or lead text say it is a
   covers or tribute album, or whose infobox language (`שפה`) is not Hebrew or genre (`סוגה`) is
   children's songs, gives no rows — unless its page title is listed in `data/album_keep.txt`),
   `data/classics_il.csv` and `data/extras.csv`, and writes the
   candidates, each with a `key`, to `work/candidates.json`. The Hebrew annual charts are taken
   whole; rows past the top 20 of a year's main charts and the song-article, album-page and
   famous-acts rows are marked `extended`. For the international songs of 1950–1990 the Billboard
   year-end lists (chart years 1950–1990) and Hebrew Wikipedia's international chart (1969–1990)
   are taken whole too, rows past each year's top 25 marked `extended`, and the UK's year-end
   best-selling singles from English Wikipedia's "YYYY in British music" (source `uk-year-end`,
   top 40, 1972–1990: earlier pages have no year-end ranking) are all `extended`; and an extended row never changes the row of the
   original sources it merges with (among extended rows: chart, then song article, then album
   page, then famous acts). Why each song article, album page and famous act's Deezer album did
   or did not give candidates is written to `work/hewiki_songs.json`, `work/hewiki_albums.json`
   and `work/famous_acts.json`.
2. `deezer` — finds each candidate's Deezer track with a preview, drops `data/exclude.txt` keys,
   duplicates and songs below the popularity floor (`data/floors.toml`, `data/keep.txt`), and
   writes `work/matched.json` and `work/dropped.json`. A famous-acts row takes the album track
   `collect` found; an album-page row prefers a track on its own album. The 1960–1999 chart,
   album-page and famous-acts rows of a famous act skip the floor.
3. `years` — finds each song's original release year (MusicBrainz, Wikidata,
   `data/year_overrides.csv`), flags doubtful ones and writes `work/dated.json`. Rows led by an
   album source keep their album's year (`year_source` "album") without lookups.
4. `spellings` — checks the spelling files in `data/spellings/` (see its README) and writes what
   is still missing to `work/spellings/*.todo.json`.
5. `assemble` — joins `work/dated.json` with the spellings into the app's `catalog.json` and
   writes the numbers to `work/report.md`. It stops, listing every problem, if a spelling is
   missing or two songs clash.

## Look for duplicates and covers

`duplicates` reads `work/dated.json` and writes `work/duplicates.md`: the Israeli rows whose titles
are the same after normalisation, differ by one edit (titles of 5 letters or more) or differ by a
bracketed part, grouped, each row with its key, credit, year, Deezer artist and sources; then the
international rows grouped the same way (by their Latin titles, each side of an "A / B" double
A-side counting as a title too). Read it
after a run that added songs: a row that covers or re-records a song whose original performer has
a row, or the same song twice, goes into `data/exclude.txt` (see [Remove a song](#remove-a-song));
different songs that share a name stay.

## Check the previews

`sweep` asks Deezer afresh (never from `.cache/`) for every song in the app's catalog and checks
that it still has a preview; it also downloads 30 random previews (`--seed N` repeats a sample)
and checks they are MP3 clips of a sensible size. It writes `work/sweep.json` and prints a
`data/exclude.txt` line for each song that failed: add those lines, then run `deezer --offline`,
`years --offline` and `assemble`. If more than 2.5% fail it prints a note to look into it instead
of the exclude lines. A sampled preview that cannot be downloaded is listed as that song's problem.

## Add a song

1. Add a row to `data/extras.csv`: `origin,title,artist,year,note` (`IL` or `INTL`; the title
   and artist as the song is known; the year it came out).
2. Run `collect`, `deezer`, `years`. Extras skip the popularity floor; check `work/dropped.json`
   if the song does not reach `work/dated.json` (no Deezer match, or no preview).
3. Run `spellings`: the song's title (and the artist, if the credit is new) appear in
   `work/spellings/*.todo.json`. Add the title spelling to a `titles-*.json` and, if needed, the
   artist spelling to an `artists-*.json`, then run `spellings` again until nothing is missing.
4. Run `assemble` and rebuild the app.

## Remove a song

Find the song's `key` in `work/dated.json` and add a line to `data/exclude.txt`:
`<key>  # <title> – <artist>: <why>` (the reason is required). Run `deezer` and `years`
(`--offline` is enough), then `assemble`. Its spelling entries may stay.

## Fix a year or a recording

- Wrong year: add `key,year,note` to `data/year_overrides.csv`, then run `years --offline` and
  `assemble`.
- Wrong recording (a cover, a live take or a re-recording plays): find the right track on Deezer
  and add `key,deezer_id,note` to `data/track_overrides.csv`, then run `deezer`, `years` and
  `assemble` (the new track is looked up once, so leave out `--offline` for `deezer` and `years`).

## Hand-edit fallback

When `work/` and `.cache/` are not there, a one-song fix can be made in the asset itself:
`app/src/main/assets/catalog.json` has one song per line (`{"id": …, "title": …, "year": …}`).

- Remove a song: delete its line (if it was the last song, also remove the comma now ending the
  line before it).
- Fix a year: change that line's `"year"` value.

Then run the app tests (`./gradlew :app:testDebugUnitTest` from the repository root; `CatalogTest`
checks the asset) and rebuild the app. Record the same fix in `data/` as well (`data/exclude.txt`
or `data/year_overrides.csv`, as above), so the next full rebuild keeps it.
