"""Stage 2: Deezer matching and the popularity floor, on canned responses only.

Fixtures (real Deezer / Hebrew Wikipedia answers, trimmed to the fields used):
- ``deezer_search.json``: {plain search query: search response}
- ``deezer_artist_search.json``: {Hebrew artist name: /search/artist response}
- ``deezer_albums.json``: {album id: /album response}
- ``hewiki_langlinks_il_artists.json``: langlinks for four Israeli artists
"""

import copy
import json

import pytest

from conftest import load_fixture
from roadhit_catalog import deezer, http, latin
from roadhit_catalog.keys import song_key

SEARCHES = load_fixture("deezer_search.json")
ARTIST_SEARCHES = load_fixture("deezer_artist_search.json")
ALBUMS = load_fixture("deezer_albums.json")
TRACKS = load_fixture("deezer_tracks.json")
LATIN_NAMES = latin.parse_langlinks(load_fixture("hewiki_langlinks_il_artists.json"))

EMPTY = {"data": [], "total": 0}


class FakeDeezer:
    """Answers from the fixtures; any query not saved there finds nothing."""

    def __init__(self, searches=None):
        self.searches = SEARCHES if searches is None else searches
        self.queries = []

    def search(self, query):
        self.queries.append(query)
        return self.searches.get(query, EMPTY)

    def artist_search(self, name):
        return ARTIST_SEARCHES.get(name, EMPTY)

    def album(self, album_id):
        return ALBUMS[str(album_id)]

    def track(self, track_id):
        return TRACKS[str(track_id)]


def candidate(title, artist, year=1970, origin="INTL", source="billboard-year-end", position=1, **extra):
    hebrew = origin == "IL" or not latin.is_latin(artist)
    row = {
        "key": song_key(title, artist),
        "title": title,
        "artist": artist,
        "credit": artist,
        "chart_year": year,
        "origin": origin,
        "source": source,
        "position": position,
        "sources": [source],
        "title_latin": None if origin == "IL" else (title if latin.is_latin(title) else None),
        "artist_latin": None if hebrew else artist,
        "needs_latin": False,
    }
    row.update(extra)
    return row


def match(row, api=None):
    return deezer.Matcher(api or FakeDeezer(), LATIN_NAMES).match(row)


def result(query, track_id):
    return next(r for r in SEARCHES[query]["data"] if r["id"] == track_id)


# --- Brief step 1 ----------------------------------------------------------------


def test_picks_the_highest_ranked_acceptable_version():
    # Remastered 2009 (rank 886736) wins; the 2019 Mix and the Take 9 are other recordings, the medley
    # "Here Comes The Sun / The Inner Light", "Sun King", the children's version,
    # the "as made famous by" and the tribute band's live take are not acceptable.
    m = match(candidate("Here Comes the Sun", "The Beatles", 1969))
    assert m["status"] == "matched"
    assert m["track"]["id"] == 116348464
    assert m["track"]["rank"] == 886736
    assert m["album"]["genre_id"] == 152


def test_rejects_hound_dog_live():
    row = candidate("Hound Dog", "Elvis Presley", 1956)
    matcher = deezer.Matcher(FakeDeezer(), LATIN_NAMES)
    verdict = matcher.evaluate(row, result("Elvis Presley Hound Dog", 69405204))  # "Hound Dog (Live)"
    assert not verdict.ok
    assert "live" in verdict.reason
    m = matcher.match(row)
    assert m["status"] == "matched"
    assert "live" not in m["track"]["title"].lower()
    assert "concert" not in m["track"]["album"]["title"].lower()  # EPiC: Elvis Presley in Concert


def test_live_is_allowed_when_the_chart_title_says_live():
    row = candidate("Hound Dog (Live)", "Elvis Presley", 1956)
    verdict = deezer.Matcher(FakeDeezer(), LATIN_NAMES).evaluate(row, result("Elvis Presley Hound Dog", 69405204))
    assert verdict.ok


def test_rejects_a_result_whose_artist_differs():
    # The saved results hold Cohen, Rufus Wainwright, SafetySuit and Katherine Jenkins,
    # but no Jeff Buckley studio take.
    api = FakeDeezer({"Jeff Buckley Hallelujah": SEARCHES["Leonard Cohen Hallelujah"]})
    assert match(candidate("Hallelujah", "Jeff Buckley", 1994), api)["status"] == "no_match"
    assert match(candidate("Hallelujah", "Leonard Cohen", 1984))["track"]["id"] == 15218949


FLOORS = {
    "INTL": {"since_2000": 300000, "from_1970": 200000, "before_1970": 100000},
    "IL": {"since_2000": 100000, "from_1970": 30000, "before_1970": 10000},
}


def matched_row(year, origin, popularity, source="hewiki-il", **extra):
    row = candidate(f"Song {year} {origin} {popularity}", "Someone", year, origin, source)
    row.update(deezer_id=year * 1000 + popularity % 1000, rank=popularity, popularity=popularity, **extra)
    return row


def test_below_floor_by_era_and_origin():
    rows = [
        matched_row(2010, "INTL", 299999),  # below 300000
        matched_row(2010, "INTL", 300000),
        matched_row(1985, "INTL", 199999),  # below 200000
        matched_row(1969, "INTL", 150000),  # before 1970: floor 100000
        matched_row(2000, "IL", 99999),  # 2000 is in the 2000+ era
        matched_row(1999, "IL", 99999),  # 1970–1999: floor 30000
        matched_row(1970, "IL", 29999),  # below 30000
        matched_row(1969, "IL", 10000),
    ]
    kept, dropped = deezer.apply_floor(rows, FLOORS, keep=set())
    assert [r["chart_year"] for r in dropped] == [2010, 1985, 2000, 1970]
    assert all(r["reason"] == "below_floor" for r in dropped)
    assert [r["chart_year"] for r in kept] == [2010, 1969, 1999, 1969]
    assert deezer.floor_for(FLOORS, "IL", 1969) == 10000
    assert deezer.floor_for(FLOORS, "INTL", 2000) == 300000


def test_empty_preview_is_no_preview():
    searches = copy.deepcopy(SEARCHES)
    for r in searches["מאור כהן הכי יפה בעולם"]["data"]:
        r["preview"] = ""
    row = candidate("הכי יפה בעולם", "מאור כהן", 1999, "IL", "hewiki-il")
    assert match(row, FakeDeezer(searches))["status"] == "no_preview"
    assert match(row)["status"] == "matched"


# --- Decision 1: Deezer's quota error -------------------------------------------


class FakeResponse:
    def __init__(self, body, status=200):
        self.status_code = status
        self.headers = {}
        self._body = body

    def json(self):
        return self._body

    def raise_for_status(self):
        pass


class FakeClient:
    def __init__(self, bodies):
        self.bodies = list(bodies)
        self.calls = 0

    def get(self, url, params=None):
        self.calls += 1
        return FakeResponse(self.bodies.pop(0))


QUOTA = {"error": {"type": "Exception", "message": "Quota limit exceeded", "code": 4}}


def test_quota_error_body_is_retried_and_not_cached(tmp_path, monkeypatch):
    monkeypatch.setattr(http, "CACHE_DIR", tmp_path)
    monkeypatch.setattr(http.time, "sleep", lambda s: None)
    good = {"data": [], "total": 0}
    client = FakeClient([QUOTA, QUOTA, good])
    monkeypatch.setattr(http, "_get_client", lambda: client)
    params = {"q": "x", "limit": "25"}
    assert http.get_json(deezer.SEARCH_URL, params) == good
    assert client.calls == 3
    cached = http._cache_path(http.cache_key(deezer.SEARCH_URL, params))
    assert json.loads(cached.read_text(encoding="utf-8")) == good


def test_persistent_quota_error_raises_and_caches_nothing(tmp_path, monkeypatch):
    monkeypatch.setattr(http, "CACHE_DIR", tmp_path)
    monkeypatch.setattr(http.time, "sleep", lambda s: None)
    monkeypatch.setattr(http, "_get_client", lambda: FakeClient([QUOTA] * http.MAX_ATTEMPTS))
    with pytest.raises(http.ApiError):
        http.get_json(deezer.SEARCH_URL, {"q": "y"})
    assert not list(tmp_path.rglob("*.json"))


def test_deezer_host_is_throttled_to_eight_requests_per_second():
    assert http.min_interval("api.deezer.com") == pytest.approx(1 / 8)
    assert http.min_interval("he.wikipedia.org") == http.WIKIPEDIA_MIN_INTERVAL


# --- Decision 3: scripts, suffixes, compilations --------------------------------


def test_hebrew_artist_matches_a_latin_named_deezer_artist_via_the_resolved_name():
    # שושנה דמארי → "Shoshana Damari" (langlinks); Deezer's artist is "Shoshana Damari".
    m = match(candidate("כלניות", "שושנה דמארי", 1945, "IL", "classics", 0))
    assert m["status"] == "matched"
    assert m["track"]["artist"]["name"] == "Shoshana Damari"
    assert m["artist_via"] == "resolved"
    # The higher-ranked "השירים היפים" track wins; the "Queen Shoshana" soundtrack
    # (a Various Artists album) loses.
    assert m["track"]["id"] == 621320812


def test_hebrew_artist_matches_when_the_artist_search_confirms_identity():
    # מאור כהן has no English article; /search/artist?q=מאור כהן → Maor Cohen (3795221) first.
    m = match(candidate("הכי יפה בעולם", "מאור כהן", 1999, "IL", "hewiki-il"))
    assert m["status"] == "matched"
    assert m["artist_via"] == "confirmed"
    # כוורת resolves to "Beehive" (the insect), which is not "Kaveret"; the artist search confirms.
    m = match(candidate("שיר המכולת", "כוורת", 1973, "IL", "hewiki-il"))
    assert m["status"] == "matched"
    assert m["track"]["id"] == 736805642  # not the Live or Remastered takes
    assert m["artist_via"] == "confirmed"


def test_unresolvable_artist_without_confirmation_is_no_match():
    # רביב כנר: no English article and /search/artist finds nothing; the title-only
    # search finds Deezer's "Raviv Kaner" (Latin) and another singer's cover (לישי).
    api = FakeDeezer()
    row = candidate("רוצה שלום", "רביב כנר", 2021, "IL", "hewiki-il")
    assert match(row, api)["status"] == "no_match"
    assert "רוצה שלום" in api.queries  # the title-only search was tried


def test_remastered_and_single_version_suffixes_do_not_block_a_match():
    matcher = deezer.Matcher(FakeDeezer(), LATIN_NAMES)
    beatles = candidate("Here Comes the Sun", "The Beatles", 1969)
    v = matcher.evaluate(beatles, result("The Beatles Here Comes the Sun", 116348464))  # (Remastered 2009)
    assert v.ok and v.title_score == 100
    wolfsheim = candidate("Kein Zurück", "Wolfsheim", 2003)
    v = matcher.evaluate(wolfsheim, result("Single Version", 62548806))  # "Kein Zurück - Single Version (- Single Version)"
    assert v.ok and v.title_score == 100
    assert deezer.clean_title("Paint It Black (Remastered 2011)") == "Paint It Black"
    assert deezer.clean_title("Mrs. Robinson - Single Version") == "Mrs. Robinson"
    assert deezer.clean_title("Uptown Funk (feat. Bruno Mars)") == "Uptown Funk"


def test_album_preference_does_not_override_a_far_higher_rank():
    # "Hound Dog" on the Forrest Gump soundtrack (rank 561783, album artist:
    # Original Motion Picture Soundtrack) is the original 1956 recording; the
    # Elvis-owned "Gonna Rock It Up" copy ranks 79109, under half of it, so the
    # album preference may not pick it.
    m = match(candidate("Hound Dog", "Elvis Presley", 1956))
    assert m["track"]["id"] == 17479889
    assert m["popularity"] == 561783


def test_shop_around_takes_the_original_not_a_rank_2226_budget_copy():
    # The Gold album is filed under "Smokey Robinson & The Miracles" while the
    # track artist is "The Miracles"; "Treasured Collection (1950'S Golden
    # Voices)" is filed under The Miracles but ranks 2226.
    m = match(candidate("Shop Around", "The Miracles", 1961))
    assert m["track"]["id"] == 593726482
    assert m["track"]["album"]["title"] == "Gold"


def test_a_title_that_is_only_part_of_the_deezer_title_is_not_a_match():
    matcher = deezer.Matcher(FakeDeezer(), LATIN_NAMES)
    beatles = candidate("Here Comes the Sun", "The Beatles", 1969)
    assert not matcher.evaluate(beatles, result("The Beatles Here Comes the Sun", 126848601)).ok  # "/ The Inner Light"


# --- Decision 4 and 5: deduplication and exemptions ------------------------------


def test_two_candidates_matching_one_track_become_one():
    hebrew = candidate("Hound Dog", "אלביס פרסלי", 1957, "INTL", "hewiki-intl", 3, needs_latin=True)
    billboard = candidate("Hound Dog", "Elvis Presley", 1958, "INTL", "billboard-year-end", 2)
    rows = []
    for row in (hebrew, billboard):
        rows.append({**row, "deezer_id": 783066172, "rank": 79109, "popularity": 561783})
    kept, dropped = deezer.dedupe_by_track(rows)
    assert len(kept) == 1 and len(dropped) == 1
    (song,) = kept
    assert song["artist"] == "Elvis Presley"  # the row with Latin fields
    assert song["chart_year"] == 1957
    assert song["sources"] == ["billboard-year-end", "hewiki-intl"]
    assert song["position"] == 2
    assert dropped[0]["key"] == hebrew["key"] and dropped[0]["reason"] == "duplicate_track"


def test_an_existing_row_wins_over_extended_rows_of_its_track():
    existing = candidate("שיר", "זמר", 1991, "IL", "hewiki-il", 5, extended=False)
    tail = candidate("שיר", "זמר הזמר", 1990, "IL", "hewiki-il", 1, extended=True)
    article = candidate("שיר (שיר)", "זמר", 1988, "IL", "hewiki-songs", 0, extended=True)
    rows = [{**r, "deezer_id": 42, "rank": 5000, "popularity": p}
            for r, p in ((tail, 9000), (article, 3000), (existing, 5000))]
    kept, dropped = deezer.dedupe_by_track(rows)
    (song,) = kept
    assert song["key"] == existing["key"]
    assert (song["chart_year"], song["position"], song["source"]) == (1991, 5, "hewiki-il")
    assert song["sources"] == ["hewiki-il", "hewiki-songs"]
    assert song["popularity"] == 9000
    assert song["extended"] is False
    assert {d["key"] for d in dropped} == {tail["key"], article["key"]}


def test_an_extended_israeli_row_on_an_international_track_leaves_that_row_as_it_was():
    intl = candidate("Flowers", "Miley Cyrus", 2023, "INTL", "billboard-year-end", 1)
    tail = candidate("Flowers", "מיילי סיירוס", 2023, "IL", "hewiki-il", 12, extended=True)
    rows = [{**intl, "deezer_id": 7, "rank": 900000, "popularity": 900000},
            {**tail, "deezer_id": 7, "rank": 950000, "popularity": 950000}]
    (song,), (gone,) = deezer.dedupe_by_track(rows)
    assert song == {**rows[0], "merged_keys": []}
    assert gone["key"] == tail["key"] and gone["reason"] == "duplicate_track"


def test_among_extended_rows_a_chart_row_wins_over_a_song_article_row():
    article = candidate("הנערים הספנים", "מרים פריש, אהלה הלוי, עפרה חזה", 1950, "IL", "hewiki-songs", 0, extended=True)
    chart = candidate("הנערים הספנים", "עפרה חזה", 1986, "IL", "hewiki-il", 27, extended=True)
    rows = [{**r, "deezer_id": 42, "rank": 5000, "popularity": p} for r, p in ((article, 9000), (chart, 5000))]
    (song,), (gone,) = deezer.dedupe_by_track(rows)
    assert (song["key"], song["credit"], song["chart_year"], song["source"]) == (chart["key"], "עפרה חזה", 1986, "hewiki-il")
    assert song["sources"] == ["hewiki-il", "hewiki-songs"] and song["popularity"] == 9000
    assert gone["key"] == article["key"]


def test_extended_rows_of_one_track_dedupe_as_before():
    a = candidate("שיר", "זמר", 1991, "IL", "hewiki-il", 25, extended=True)
    b = candidate("שיר", "זמר הזמר", 1990, "IL", "hewiki-il", 30, extended=True)  # same kind: earliest wins
    rows = [{**r, "deezer_id": 42, "rank": 5000, "popularity": 5000} for r in (a, b)]
    (song,), _ = deezer.dedupe_by_track(rows)
    assert song["key"] == b["key"] and song["chart_year"] == 1990


def test_classics_row_below_the_floor_is_kept():
    row = matched_row(1945, "IL", 500, source="classics")
    kept, dropped = deezer.apply_floor([row], FLOORS, keep=set())
    assert kept == [row] and dropped == []


def test_keep_txt_key_below_the_floor_is_kept(tmp_path):
    row = matched_row(2015, "INTL", 1000, source="billboard-year-end")
    other = matched_row(2016, "INTL", 1000, source="billboard-year-end")
    keep_file = tmp_path / "keep.txt"
    keep_file.write_text(f"# songs to keep whatever their rank\n\n{row['key']}  # a comment\n", encoding="utf-8")
    keep = deezer.load_keep(keep_file)
    assert keep == {row["key"]}
    kept, dropped = deezer.apply_floor([row, other], FLOORS, keep)
    assert kept == [row]
    assert [d["key"] for d in dropped] == [other["key"]]


def test_real_floors_and_keep_files_load():
    floors = deezer.load_floors(deezer.FLOORS_TOML)
    for origin in ("IL", "INTL"):
        assert {"since_2000", "from_1970", "before_1970"} <= set(floors[origin])
        assert set(floors[origin]) <= {"since_2000", "from_1970", "before_1970", *deezer.KIND_FLOORS}
        assert all(isinstance(v, int) and v >= 0 for v in floors[origin].values())
    assert isinstance(deezer.load_keep(deezer.KEEP_TXT), set)


# --- Rules tightened after the by-eye audit (real results that slipped through) ----


def test_a_title_with_a_different_or_extra_word_is_not_a_match():
    matcher = deezer.Matcher(FakeDeezer(), LATIN_NAMES)
    madonna = candidate("American Life", "מדונה", 2003, artist_latin="Madonna", title_latin="American Life")
    assert not matcher.evaluate(madonna, result("Madonna American Life", 3667561)).ok  # "American Pie"
    # Stage 1 resolved שר to "Minister"; Deezer offers Ministry's "Believe Me".
    cher = candidate("Believe", "שר", 1998, artist_latin="Minister", title_latin="Believe")
    assert not matcher.evaluate(cher, result("Minister Believe", 1393203852)).ok
    assert deezer.words_agree("independent women part i", "independent women pt 1")
    assert deezer.words_agree("הקנאה", "קנאה")
    assert not deezer.words_agree("hero", "heroe")


def test_a_one_word_artist_name_must_be_spelled_the_same():
    matcher = deezer.Matcher(FakeDeezer(), LATIN_NAMES)
    oasis = candidate("Don't Look Back In Anger", "אואזיס", 1996, artist_latin="Oasis",
                      title_latin="Don't Look Back In Anger")
    assert not matcher.evaluate(oasis, result("Oasis Don't Look Back In Anger", 1635275372)).ok  # "Ohasis"
    assert deezer.artist_score(["N Sync"], "*NSYNC") == 100
    assert deezer.artist_score(["Prince and the Revolution"], "Prince") == 100


def test_spanish_versions_and_tribute_acts_are_other_recordings():
    matcher = deezer.Matcher(FakeDeezer(), LATIN_NAMES)
    mariah = candidate("Hero", "Mariah Carey", 1994)
    assert not matcher.evaluate(mariah, result("Mariah Carey Hero", 1064722712)).ok  # "Héroe", Mariah En Español
    assert deezer.artist_marker("The Beatles Revival Band", ["The Beatles"]) == "revival"
    assert deezer.artist_marker("Creedence Clearwater Revival", ["Creedence Clearwater Revival"]) is None
    assert deezer.marker_in("Revival", ["River"]) is None  # Eminem's album "Revival" is not a revival act
    assert deezer.marker_in("Three Dog Night with the London Symphony Orchestra", ["Mama Told Me"]) == "symphony orchestra"


def test_a_longer_deezer_name_is_not_the_credited_act():
    assert deezer.artist_score(["Pink Floyd"], "Celtic Pink Floyd") < deezer.MATCH_THRESHOLD
    assert deezer.artist_score(["The Police"], "The Police Project") < deezer.MATCH_THRESHOLD
    assert deezer.artist_score(["Minister"], "Ministers De La Funk") < deezer.MATCH_THRESHOLD
    assert deezer.artist_score(["David Rose"], "David Rose & His Orchestra") == 100
    assert deezer.artist_score(["Janet"], "Janet Jackson") < deezer.MATCH_THRESHOLD  # one word: no extension
    assert deezer.artist_score(["The Four Seasons"], "Frankie Valli & The Four Seasons") == 100
    assert deezer.artist_score(["Cat Stevens"], "Yusuf / Cat Stevens") == 100
    assert deezer.artist_score(["Eliad Nachum"], "Eliad") == 100
    assert deezer.artist_score(["Chizbatron"], "HaChizbatron") >= deezer.MATCH_THRESHOLD


def test_hebrew_spelling_variants_stopwords_and_double_a_sides():
    assert deezer.words_agree("הכול סגור", "הכל סגור")
    assert deezer.words_agree("שמיים", "שמים")
    assert deezer.words_agree("the purple people eater", "purple people eater")
    assert deezer.words_agree("lovers friends", "lovers and friends")
    row = candidate("Bird Dog / Devoted to You", "The Everly Brothers", 1958)
    assert "Devoted to You" in deezer.chart_titles(row)


def test_remixes_and_backing_tracks_are_other_recordings():
    for title in ("Love Shine a Light (Xenomania Club Mix)", "Every Breath You Take (Backing Track)",
                  "Here Comes The Sun (2019 Mix)", "Everybody Wants To Rule The World (Extended Version)"):
        assert deezer.marker_in(title, ["x"]), title
    assert deezer.marker_in("Where Did Our Love Go (Single Version / Mono)", ["x"]) is None
    assert deezer.marker_in("Proud Mary (Mono Single Mix)", ["x"]) is None
    assert deezer.marker_in("Let a Boy Cry (Motiv-8 Radio Mix)", ["Let A Boy Cry"]) == "mix"
    assert deezer.marker_in("Tipsy (Radio Mix)", ["Tipsy"]) is None
    assert deezer.marker_in("It Wasn't Me (Hot Shot 2020)", ["It Wasn't Me"]) == "dated version"
    assert deezer.marker_in("נולדתי לשלום (גרסת 2003)", ["נולדתי לשלום"]) == "dated version"
    assert deezer.marker_in("Sweet Dreams (Are Made of This) (2005 Remaster)", ["Sweet Dreams"]) is None
    assert deezer.marker_in("Every Breath You Take (Out-Take)", ["Every Breath You Take"]) == "out take"
    assert deezer.marker_in("Russians (Guitar/Cello Version)", ["Russians"]) == "other version"
    assert deezer.marker_in("Fields Of Gold - The Best Of Sting 1984 - 1994", ["Russians"], notes="album") is None
    assert deezer.marker_in("Cheerleader (Felix Jaehn Remix) (Radio Edit)", ["Cheerleader (Felix Jaehn Remix)"]) is None


def test_deezers_placeholder_rank_is_not_popularity():
    assert deezer.popularity([{"rank": 100000}, {"rank": 5000}]) == 5000
    assert deezer.popularity([{"rank": 100000}]) == 0
    assert deezer.popularity([{"rank": 100001}, {"rank": 100000}]) == 100001


def test_a_featured_act_in_the_credit_is_not_the_artist():
    # Billboard 2003: "Drift Away" by Uncle Kracker featuring Dobie Gray is Uncle
    # Kracker's recording, not Dobie Gray's 1973 original.
    # Billboard rows keep the whole credit in ``artist``.
    row = candidate("Drift Away", "Uncle Kracker featuring Dobie Gray", 2003)
    matcher = deezer.Matcher(FakeDeezer(), LATIN_NAMES)
    assert not matcher.evaluate(row, result("Drift Away", 620021372)).ok  # Dobie Gray
    assert matcher.evaluate(row, result("Drift Away", 661836)).ok  # Uncle Kracker
    hebrew = candidate("גשם כבד", "אביב גפן והתעויוט בהשתתפות אריק איינשטיין", 1996, "IL", "hewiki-il")
    assert deezer.candidate_artists(hebrew) == ["אביב גפן והתעויוט"]
    assert deezer.candidate_artists(candidate("X", "Simon & Garfunkel")) == ["Simon & Garfunkel"]


def test_a_different_guest_marks_another_version():
    row = candidate("Into You", "Fabolous featuring Tamia", 2003)
    assert deezer.featured_clash(row, "Into You (feat. Ashanti)")
    assert not deezer.featured_clash(row, "Into You (feat. Tamia)")
    assert not deezer.featured_clash(row, "Into You")
    assert not deezer.featured_clash(candidate("Mood", "24kGoldn featuring Iann Dior"), "Mood (feat. iann dior)")


def test_a_remix_on_the_chart_needs_a_remix_on_deezer():
    row = candidate("Hound Dog (Remix)", "Elvis Presley", 1956)
    matcher = deezer.Matcher(FakeDeezer(), LATIN_NAMES)
    verdict = matcher.evaluate(row, result("Elvis Presley Hound Dog", 783066172))
    assert not verdict.ok and verdict.reason == "chart says remix"
    live_and_let_die = candidate("Live and Let Die", "Wings", 1973)
    assert matcher.evaluate(live_and_let_die, {**result("Elvis Presley Hound Dog", 783066172),
                                               "title": "Live and Let Die", "title_short": "Live and Let Die",
                                               "artist": {"id": 1, "name": "Wings"}}).ok


def test_named_edits_word_order_and_one_word_extensions():
    assert deezer.marker_in("Set Fire to the Rain (Moto Blanco Edit)", ["Set Fire to the Rain"]) == "mix"
    assert deezer.marker_in("Glad You Came (Alex Gaudino Radio Edit)", ["Glad You Came"]) == "mix"
    assert deezer.marker_in("Desert Rose (Radio Edit)", ["Desert Rose"]) is None
    assert deezer.marker_in("Turn Back Time (Edit)", ["Turn Back Time"]) is None
    assert deezer.marker_in('Let It Go (From "Frozen"/Soundtrack Version)', ["Let It Go"]) is None
    assert not deezer.words_agree("9 to 5", "5 to 9")
    assert deezer.artist_score(["ABBA"], "Abba na Viola") < deezer.MATCH_THRESHOLD
    assert "ולש" not in deezer.hebrew_acts("בוב וולש")
    despacito = candidate("Despacito (Remix)", "Luis Fonsi and Daddy Yankee featuring Justin Bieber", 2017)
    assert deezer.guest_missing(despacito, "Despacito (Major Lazer & MOSKA Remix)")
    assert not deezer.guest_missing(despacito, "Despacito - Remix (feat. Justin Bieber)")


# --- Fix round 1 -------------------------------------------------------------------


def test_plain_queries_are_pooled_before_choosing():
    # "Eminem Cleaning Out My Closet" offers only a mixtape copy (rank 233683);
    # "Cleaning Out My Closet Eminem" has The Eminem Show track (748879).
    row = candidate("Cleaning Out My Closet", "אמינם", 2002, title_latin="Cleaning Out My Closet",
                    artist_latin="Eminem")
    m = match(row)
    assert m["track"]["id"] == 916414
    assert m["album"]["title"] == "The Eminem Show"
    assert m["popularity"] == 748879


def test_a_far_higher_ranked_named_mix_blocks_a_low_ranked_copy():
    # The charting single is "Here Comes the Hotstepper (Heartical Mix)" (754978);
    # the accepted plain-titled copies sit on riddim / dance compilations (197863).
    m = match(candidate("Here Comes the Hotstepper", "Ini Kamoze", 1995))
    assert m["status"] == "no_match"
    assert m["blocked_by"] == {"id": 13211888, "title": "Here Comes the Hotstepper (Heartical Mix)", "rank": 754978}
    rows = deezer.run([candidate("Here Comes the Hotstepper", "Ini Kamoze", 1995)],
                      deezer.Matcher(FakeDeezer(), LATIN_NAMES), FLOORS, set(), overrides={})
    (dropped,) = rows[2]
    assert dropped["reason"] == "no_match" and dropped["blocked_by"]["id"] == 13211888


def test_override_uses_the_hand_picked_track(tmp_path):
    toy = candidate("Toy", "נטע ברזילי", 2018, "IL", "hewiki-il")
    csv_file = tmp_path / "track_overrides.csv"
    csv_file.write_text(f"key,deezer_id,note\n{toy['key']},3782953802,Eurovision 2018 original\n", encoding="utf-8")
    overrides = deezer.load_overrides(csv_file)
    assert overrides == {toy["key"]: 3782953802}
    _, kept, dropped = deezer.run([toy], deezer.Matcher(FakeDeezer(), LATIN_NAMES), FLOORS, set(), overrides)
    (row,) = kept
    assert row["deezer_id"] == 3782953802 and row["artist_via"] == "override"
    assert row["popularity"] == 556275 and row["genre_id"] == 132
    assert dropped == []


def test_override_with_no_preview_or_no_track_is_an_error():
    toy = candidate("Toy", "נטע ברזילי", 2018, "IL", "hewiki-il")
    api = FakeDeezer()
    api.track = lambda track_id: {**TRACKS["3782953802"], "preview": ""}
    with pytest.raises(deezer.OverrideError, match=toy["key"]):
        deezer.run([toy], deezer.Matcher(api, LATIN_NAMES), FLOORS, set(), {toy["key"]: 3782953802})
    with pytest.raises(deezer.OverrideError, match=toy["key"]):
        deezer.run([toy], deezer.Matcher(FakeDeezer(), LATIN_NAMES), FLOORS, set(), {toy["key"]: 1})


def test_an_overridden_track_still_meets_the_floor():
    toy = candidate("Toy", "נטע ברזילי", 2018, "IL", "hewiki-il")
    high = {**FLOORS, "IL": {**FLOORS["IL"], "since_2000": 600000}}
    _, kept, dropped = deezer.run([toy], deezer.Matcher(FakeDeezer(), LATIN_NAMES), high, set(),
                                  {toy["key"]: 3782953802})
    assert kept == [] and dropped[0]["reason"] == "below_floor"


def test_real_overrides_file_loads():
    assert all(isinstance(v, int) for v in deezer.load_overrides(deezer.OVERRIDES_CSV).values())


def test_a_note_that_is_part_of_the_chart_title_is_not_a_version():
    # "All Night Long (All Night)" is the song's own title; the plain "All Night
    # Long" on Tuskegee (2012 duets album) must not win as the "clean" one.
    m = match(candidate("All Night Long (All Night)", "Lionel Richie", 1984))
    assert m["track"]["id"] == 920062
    # A guest the chart credit does not name marks a remix version: the plain
    # "love nwantiti (ah ah ah)" wins over "(feat. Jah Khalib)".
    m = match(candidate("Love Nwantiti", "CKay", 2021))
    assert m["track"]["id"] == 752155092


def test_subtitles_are_clean_but_hebrew_version_notes_are_not():
    row = candidate("Only the Lonely", "Roy Orbison", 1960)
    assert deezer.is_clean_version({"title": "Only the Lonely (Know the Way I Feel)"}, row)
    assert not deezer.is_clean_version({"title": "People (with Stevie Wonder)"}, candidate("People", "Barbra Streisand"))
    assert deezer.is_clean_version({"title": "It's Magic (with Percy Faith & His Orchestra)"}, candidate("It's Magic", "Doris Day"))
    assert deezer.marker_in("תרקוד (גרסת שלמה והאנדלוסים)", ["תרקוד"]) == "other version"
    assert deezer.marker_in("חיים משל עצמי (גרסת הופעה)", ["חיים משל עצמי"])
    assert deezer.marker_in("What Goes Around.../...Comes Around (Interlude)", ["What Goes Around... Comes Around"])


def test_a_dollar_sign_in_a_name_is_an_s():
    assert deezer.artist_score(["Kesha"], "Ke$ha") == 100
    assert deezer.artist_score(["Pink"], "P!nk") == 100


def test_the_placeholder_rank_does_not_win_the_choice():
    # Rank 100000 is Deezer's no-data value: "Patti Page on Stage" must not beat
    # the copy with a real rank (65247); the "Re-Recording"s are rejected outright.
    m = match(candidate("Tennessee Waltz", "Patti Page", 1950))
    assert m["track"]["id"] == 10821367
    assert m["popularity"] == 65247


def test_known_re_recording_and_holiday_notes():
    assert deezer.marker_in("Blowin' in the Wind (Christmas)", ["Blowin' in the Wind"])
    assert deezer.marker_in("Reunited", ["I'm The Lonely One"], notes="album")


def test_a_copy_marked_original_wins_within_the_rank_band():
    # "Chubby Checker Classics" (440153) vs "25 Original Greatest Hits- Cameo
    # Parkway" (309086, the original label): within half the top rank, the copy
    # that says "Original" wins.
    m = match(candidate("The Twist", "Chubby Checker", 1960))
    assert m["track"]["id"] == 2800144
    assert m["popularity"] == 440153
    assert not deezer.says_original({"title": "Walk Like a Man", "album": {"title": "Jersey Boys Original Broadway Cast Recording"}})
    assert not deezer.says_original({"title": "High Noon - Original Broadcast", "album": {"title": "Remembered"}})
    assert not deezer.says_original({"title": "Dragostea din tei (Original Italian version)", "album": {"title": "x"}})
    assert deezer.says_original({"title": "Rag Mop - Original", "album": {"title": "x"}})


@pytest.mark.parametrize("text, notes", [
    ("אהבה כזו (אינסטרומנטלי)", "track"),
    ("ואם תבואי אליי (גרסה אינסטרומנטלית)", "album"),
    ("חברים שרים קורין", "album"),
    ("הדרכים הידועות - אלבום מחווה לחתונה לבנה", "album"),
    ("עבודה עברית - מחווה ל-50 שנות יצירה מקורית", "album"),
    ("חור בשמיים הכחולים - ההופעה", "album"),
    ("רמי קלינשטיין קרן פלס, ההופעה, היכל התרבות - קיסריה 2016", "album"),
    ("אהובי (מתוך חזרות למופע NEXT)", "track"),
    ("גן יבנה (אנלוג סשן)", "track"),
    ("ליהי טולדנו - אנלוג סשן - EP", "album"),
    ("Song (Studio Session)", "track"),
])
def test_instrumental_tribute_live_album_rehearsal_and_session_mark_another_recording(text, notes):
    assert deezer.marker_in(text, ["שיר"], notes=notes, extended=True), text


@pytest.mark.parametrize("text", [
    "סינרומן (אינטריגה בארבעה פרקים) (טייק 1)",
    "שיר לכל המשפחה (טייק 2)",
    "שפריץ חרוזים ללואיז (סקיצה)",
    "עוד יום שקט (א-קפלה) (Remastered)",
    "שיר (אקפלה)",
    "שיר (דמו)",
    "Song (A Cappella)",
    "Song (Demo)",
    "Song (Take 3)",
])
def test_takes_sketches_demos_and_a_cappella_mark_another_recording_of_a_new_row(text):
    assert deezer.marker_in(text, ["שיר"], extended=True), text


def test_the_take_words_leave_songs_albums_and_existing_rows_alone():
    assert deezer.marker_in("שיר (טייק 2)", ["שיר"]) is None  # an existing row is judged as before
    assert deezer.marker_in("טייק 1+2", ["x"], notes="album", extended=True) is None  # היי פייב's album "טייק 1+2"
    assert deezer.marker_in("דמויות", ["x"], extended=True) is None  # not the word דמו
    assert deezer.marker_in("אדמונית", ["x"], extended=True) is None


def test_a_new_row_takes_the_plain_recording_over_an_a_cappella_version():
    row = candidate("עוד יום שקט", "אתי אנקרי", 1998, "IL", "hewiki-albums", 0, extended=True, album="רואה לך בעיניים")
    m = match(row, FakeDeezer())
    assert m["track"]["id"] == 1397759382  # "(Remastered)", not "(א-קפלה) (Remastered)" ranked 37390
    assert m["popularity"] == 10152  # the a-cappella version's rank no longer counts


def test_a_new_row_with_only_a_take_on_deezer_finds_no_match():
    searches = copy.deepcopy(SEARCHES)
    for q in ("מאיר אריאל שיר לכל המשפחה", "שיר לכל המשפחה מאיר אריאל"):  # only "(טייק 2)" left
        searches[q]["data"] = [r for r in searches[q]["data"] if "טייק" in r["title"]]
    row = candidate("שיר לכל המשפחה", "מאיר אריאל", 1978, "IL", "hewiki-albums", 0, extended=True, album="ירוקות")
    assert match(row, FakeDeezer(searches))["status"] == deezer.NO_MATCH


def test_the_new_markers_do_not_fire_when_the_chart_title_has_them_or_on_a_plain_album():
    assert deezer.marker_in("מחווה", ["מחווה"], extended=True) is None
    assert deezer.marker_in("הופעה חד פעמית", ["x"], notes="album", extended=True) is None  # "הופעה" alone stays track-only
    assert deezer.marker_in("השירים היפים", ["x"], notes="album", extended=True) is None


def test_the_new_markers_judge_only_extended_candidates_so_existing_matches_stay():
    # The existing row אדם צובר זיכרונות – אברהם טל plays a track of a tribute album, and
    # School Days – Chuck Berry one of the album "After School Session": both stay as they were.
    assert deezer.marker_in("עבודה עברית - מחווה ל-60 שנות יצירה מקורית", ["x"], notes="album") is None
    assert deezer.marker_in("After School Session", ["School Days"], notes="album") is None
    matcher = deezer.Matcher(FakeDeezer(), LATIN_NAMES)
    album = {"id": 1, "title": "ואם תבואי אליי (גרסה אינסטרומנטלית)"}
    track = {"id": 7, "title": "בראשית (אינסטרומנטלי)", "title_short": "בראשית", "rank": 5000,
             "preview": "x", "artist": {"id": 3, "name": "עידן רייכל"}, "album": album}
    old = candidate("בראשית", "עידן רייכל", 2002, "IL", "hewiki-il", 5)
    new = candidate("בראשית", "עידן רייכל", 2002, "IL", "hewiki-songs", 0, extended=True)
    assert matcher.evaluate(old, track).ok
    assert matcher.evaluate(new, track).reason.startswith("marked")


# --- Stage B: floors of their own for chart and album rows of 1960–1999, famous acts --------------

STAGE_B_FLOORS = {**FLOORS, "IL": {**FLOORS["IL"], "chart_from_1970": 5000, "chart_before_1970": 4000,
                                   "album_from_1970": 20000, "album_before_1970": 8000}}


@pytest.mark.parametrize("year, source, sources, floor", [
    (1985, "hewiki-il", ["hewiki-il"], 5000),  # a chart row of 1970–1999
    (1985, "hewiki-songs", ["hewiki-il", "hewiki-songs"], 5000),  # it charted: still a chart row
    (1965, "hewiki-il", ["hewiki-il"], 4000),
    (1985, "hewiki-albums", ["hewiki-albums"], 20000),
    (1962, "hewiki-albums", ["hewiki-albums"], 8000),
    (1985, "hewiki-songs", ["hewiki-albums", "hewiki-songs"], 30000),  # an article row: the era floor
    (1985, "hewiki-songs", ["hewiki-songs"], 30000),
    (2003, "hewiki-il", ["hewiki-il"], 100000),  # 2000+: unchanged
    (1959, "hewiki-songs", ["hewiki-songs"], 10000),
])
def test_row_floor_for_chart_and_album_rows(year, source, sources, floor):
    row = {"origin": "IL", "chart_year": year, "source": source, "sources": sources}
    assert deezer.row_floor(STAGE_B_FLOORS, row) == floor


def test_without_the_stage_b_keys_every_row_takes_its_era_floor():
    row = {"origin": "IL", "chart_year": 1985, "source": "hewiki-albums", "sources": ["hewiki-albums"]}
    assert deezer.row_floor(FLOORS, row) == 30000
    intl = {"origin": "INTL", "chart_year": 1985, "source": "hewiki-il", "sources": ["hewiki-il"]}
    assert deezer.row_floor(STAGE_B_FLOORS, intl) == 200000


def test_load_floors_reads_the_optional_keys(tmp_path):
    path = tmp_path / "floors.toml"
    path.write_text(
        "[INTL]\nsince_2000 = 3\nfrom_1970 = 2\nbefore_1970 = 1\n"
        "[IL]\nsince_2000 = 30\nfrom_1970 = 20\nbefore_1970 = 10\nchart_from_1970 = 15\nalbum_before_1970 = 0\n",
        encoding="utf-8",
    )
    floors = deezer.load_floors(path)
    assert floors["IL"] == {"since_2000": 30, "from_1970": 20, "before_1970": 10,
                            "chart_from_1970": 15, "album_before_1970": 0}
    assert floors["INTL"] == {"since_2000": 3, "from_1970": 2, "before_1970": 1}
    path.write_text(path.read_text(encoding="utf-8") + "album_after_2000 = 5\n", encoding="utf-8")
    with pytest.raises(ValueError, match="album_after_2000"):
        deezer.load_floors(path)


def test_apply_floor_uses_the_row_floor():
    chart = matched_row(1985, "IL", 6000)
    album = matched_row(1985, "IL", 19000, source="hewiki-albums")
    kept, dropped = deezer.apply_floor([chart, album], STAGE_B_FLOORS, keep=set())
    assert kept == [chart] and dropped[0]["key"] == album["key"] and dropped[0]["floor"] == 20000


def test_load_famous_acts(tmp_path):
    path = tmp_path / "famous_acts.txt"
    path.write_text("# acts\nכוורת\n\nגידי גוב  # a comment\n", encoding="utf-8")
    assert deezer.load_famous(path) == {"כוורת", "גידי גוב"}


def test_real_famous_acts_file_loads():
    famous = deezer.load_famous(deezer.FAMOUS_TXT)
    # 46 bands + 2 performers + 3 aliases (fix round: 2 bands out, 3 moved to the comments, 6 in)
    assert {"כוורת", "גידי גוב", "דני סנדרסון", 'שב"ק ס', "אף אוזן גרון", "Avtipus"} <= famous and len(famous) == 51
    assert "שלמה ארצי" not in famous and "דויד ברוזה" not in famous  # round 2: listed in a comment only
    assert not {"ברוש", "סטלה מאריס", "הדודאים", "הפרברים", "הגבעטרון"} & famous


@pytest.mark.parametrize("artist, famous", [
    ("כוורת", True),
    ("גידי גוב", True),
    ("גידי גוב ויהודית רביץ", True),  # a duet of famous acts
    ("שלמה ארצי ועמיר לב", True),  # one act of a duet
    ("עמיר לב בהשתתפות שלמה ארצי", False),  # a guest is not the act
    ("גידי", False),
    ("להקת כוורת", False),
])
def test_is_famous(artist, famous):
    acts = {"כוורת", "גידי גוב", "יהודית רביץ", "שלמה ארצי"}
    assert deezer.is_famous({"artist": artist}, acts) is famous


@pytest.mark.parametrize("year, source, popularity, kept", [  # 3000: below every floor, above the last resort
    (1985, "famous-acts", 3000, True),
    (1985, "hewiki-albums", 3000, True),
    (1985, "hewiki-il", 3000, True),
    (1965, "hewiki-il", 3000, True),
    (1985, "hewiki-songs", 3000, False),  # a song-article row keeps its floor
    (2005, "hewiki-il", 3000, False),  # 2000+ keeps its floor
])
def test_rows_by_a_famous_act_of_1960_1999_skip_the_floor(year, source, popularity, kept):
    row = {**matched_row(year, "IL", popularity, source=source), "artist": "כוורת"}
    got, _ = deezer.apply_floor([row], STAGE_B_FLOORS, keep=set(), famous={"כוורת"})
    assert bool(got) is kept
    other = {**row, "artist": "להקה אחרת"}
    assert deezer.apply_floor([other], STAGE_B_FLOORS, keep=set(), famous={"כוורת"})[0] == []


@pytest.mark.parametrize("sources, winner", [
    (("hewiki-albums", "hewiki-songs"), "hewiki-songs"),  # an article row wins over an album row
    (("famous-acts", "hewiki-albums"), "hewiki-albums"),  # an album row wins over a famous-acts row
    (("famous-acts", "hewiki-il"), "hewiki-il"),
    (("famous-acts", "hewiki-albums", "hewiki-songs", "hewiki-il"), "hewiki-il"),
])
def test_among_new_rows_chart_then_article_then_album_then_famous_acts(sources, winner):
    rows = [{**candidate("שיר", f"זמר {i}", 1970 + i, "IL", s, 0, extended=True),
             "deezer_id": 42, "rank": 5000, "popularity": 5000} for i, s in enumerate(sources)]
    (song,), dropped = deezer.dedupe_by_track(rows)
    assert song["source"] == winner and len(dropped) == len(sources) - 1
    assert song["sources"] == sorted(sources)


def test_run_passes_the_famous_acts_to_the_floor():
    row = candidate("Toy", "נטע ברזילי", 1985, "IL", "famous-acts", extended=True)
    _, kept, _ = deezer.run([row], deezer.Matcher(FakeDeezer(), LATIN_NAMES), {**FLOORS, "IL": {**FLOORS["IL"], "from_1970": 10**9}},
                            set(), {row["key"]: 3782953802}, famous={"נטע ברזילי"})
    assert [r["key"] for r in kept] == [row["key"]]


def _grocery_searches(album_title="כוורת - אוסף", rank=90000):
    """The real שיר המכולת answer, with the Remastered copy moved to another album and ranked first."""
    searches = copy.deepcopy(SEARCHES)
    for r in searches["כוורת שיר המכולת"]["data"]:
        if r["id"] == 1789769887:
            r["album"]["title"], r["rank"] = album_title, rank
    return searches


def test_an_album_row_prefers_a_track_on_its_own_album():
    api = FakeDeezer(_grocery_searches())
    plain = candidate("שיר המכולת", "כוורת", 1973, "IL", "hewiki-il")
    assert match(plain, api)["track"]["id"] == 1789769887  # the highest rank
    album_row = candidate("שיר המכולת", "כוורת", 1973, "IL", "hewiki-albums", 0, extended=True, album="סיפורי פוגי")
    m = match(album_row, api)
    assert m["track"]["id"] == 736805642 and m["album"]["title"] == "סיפורי פוגי"
    assert m["popularity"] == 90000  # the song's fame still counts every acceptable copy


@pytest.mark.parametrize("album, deezer_album, same", [
    ("סיפורי פוגי", "סיפורי פוגי (Remastered)", True),
    ("אהוד בנאי והפליטים", "אהוד בנאי והפליטים - מהדורה מחודשת", True),
    ("גזוז", "גזוז - 20 הלהיטים", False),
    ("שלמה ארצי", "שלמה ארצי - אוסף השנים הראשונות", False),
])
def test_same_album_is_not_fooled_by_a_compilation_named_after_the_album(album, deezer_album, same):
    assert deezer.same_album({"album": album}, {"album": {"title": deezer_album}}) is same


def test_an_album_row_whose_album_deezer_lacks_takes_the_usual_choice():
    api = FakeDeezer(_grocery_searches())
    row = candidate("שיר המכולת", "כוורת", 1973, "IL", "hewiki-albums", 0, extended=True, album="אלבום אחר")
    assert match(row, api)["track"]["id"] == 1789769887


def test_a_famous_acts_row_takes_its_own_track_without_searching():
    api = FakeDeezer()
    row = candidate("Toy", "נטע ברזילי", 1985, "IL", "famous-acts", 0, extended=True, deezer_hint=3782953802)
    _, kept, _ = deezer.run([row], deezer.Matcher(api, LATIN_NAMES), FLOORS, set(), famous={"נטע ברזילי"})
    (song,) = kept
    assert song["deezer_id"] == 3782953802 and song["popularity"] == 556275 and song["artist_via"] == "album"
    assert api.queries == []


def test_a_famous_acts_track_without_a_preview_is_no_preview():
    api = FakeDeezer()
    api.track = lambda track_id: {**TRACKS["3782953802"], "preview": ""}
    row = candidate("Toy", "נטע ברזילי", 1985, "IL", "famous-acts", 0, extended=True, deezer_hint=3782953802)
    _, kept, dropped = deezer.run([row], deezer.Matcher(api, LATIN_NAMES), FLOORS, set())
    assert kept == [] and dropped[0]["reason"] == "no_preview"


@pytest.mark.parametrize("artist", ["איפה הילד?", "שבק ס", "אתניX", "אף אוזן גרון", "מופע הארנבות של דר קספר"])
def test_famous_acts_match_spelling_variants_and_listed_aliases(artist):
    famous = deezer.load_famous(deezer.FAMOUS_TXT)
    assert deezer.is_famous({"artist": artist}, famous)


def test_spelling_blind_forms_match_vav_and_yod_spellings():
    assert deezer.is_famous({"artist": "בעז שרעבי"}, {"בועז שרעבי"})


def test_an_alias_line_names_one_act(tmp_path):
    path = tmp_path / "famous_acts.txt"
    path.write_text("אתניקס | אתניX  # Hebrew Wikipedia spells it with an X\nכוורת\n", encoding="utf-8")
    assert deezer.load_famous(path) == {"אתניקס", "אתניX", "כוורת"}


# --- Round 2: a floor of last resort for the exempt rows ---------------------------------------------


@pytest.mark.parametrize("source, popularity, album, album_title, kept", [
    ("famous-acts", 0, "אלבום", "אלבום", True),  # the album's own track (found on Deezer's copy of it)
    ("hewiki-albums", 900, "סיפורי פוגי", "סיפורי פוגי (Remastered)", True),  # its own album, remastered
    ("hewiki-albums", 900, "סיפורי פוגי", "כוורת בקופסא", False),  # matched only on some other release
    ("hewiki-albums", 1000, "סיפורי פוגי", "כוורת בקופסא", True),
    # fix round: a compilation that carries the album's name is not the album
    ("hewiki-albums", 900, "גזוז", "גזוז - 20 הלהיטים", False),
    ("hewiki-albums", 900, "שלמה ארצי", "שלמה ארצי - אוסף השנים הראשונות", False),
    ("hewiki-albums", 900, "מתי כספי", "מתי כספי - המיטב", False),
    ("hewiki-albums", 900, "פלסטלינה", "פלסטלינה - מהדורת ויניל 2016", True),  # a reissue of the album
    ("hewiki-albums", 900, "גבר הולך לאיבוד", "גבר הולך לאיבוד - הוצאה מחודשת (אוסף מחומש)", True),
    ("hewiki-il", 999, None, "כוורת בקופסא", False),  # a chart row: no album to check, so popularity decides
    ("hewiki-il", 1000, None, "כוורת בקופסא", True),
])
def test_an_exempt_row_under_1000_must_be_the_albums_own_track(source, popularity, album, album_title, kept):
    row = {**matched_row(1985, "IL", popularity, source=source), "artist": "כוורת", "album_title": album_title,
           **({"album": album} if album else {})}
    got, _ = deezer.apply_floor([row], STAGE_B_FLOORS, keep=set(), famous={"כוורת"})
    assert bool(got) is kept
