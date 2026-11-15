"""Stage 3: original release years, on canned responses only.

Fixtures (real MusicBrainz / Wikidata answers, trimmed to the fields used):
- ``musicbrainz_recordings.json``: {recording search query: response}
- ``wikidata.json``: {"search|<language>|<text>" or "entities|<ids>": response}
"""

import json

import pytest

from conftest import load_fixture
from roadhit_catalog import http, latin, years
from roadhit_catalog.keys import song_key

RECORDINGS = load_fixture("musicbrainz_recordings.json")
WIKIDATA = load_fixture("wikidata.json")

NOTHING = {"count": 0, "recordings": []}


class FakeLookups:
    """Answers from the fixtures; anything not saved there finds nothing. Records every call."""

    def __init__(self, recordings=None, wikidata=None):
        self.recordings_by_query = RECORDINGS if recordings is None else recordings
        self.wikidata = WIKIDATA if wikidata is None else wikidata
        self.calls = []

    def recordings(self, query):
        self.calls.append(("musicbrainz", query))
        return self.recordings_by_query.get(query, NOTHING)

    def wikidata_search(self, text, language):
        self.calls.append(("wikidata", text))
        return self.wikidata.get(f"search|{language}|{text}", {"search": []})

    def wikidata_entities(self, ids, props):
        self.calls.append(("wikidata", ids))
        return self.wikidata.get(f"entities|{'|'.join(ids)}", {"entities": {}})


def row(title, artist, chart_year, origin="INTL", sources=("billboard-year-end",), **extra):
    out = {
        "key": song_key(title, artist),
        "title": title,
        "artist": artist,
        "credit": artist,
        "chart_year": chart_year,
        "origin": origin,
        "source": sources[0],
        "sources": list(sources),
        "position": 1,
        "title_latin": title if latin.is_latin(title) else None,
        "artist_latin": artist if latin.is_latin(artist) else None,
        "needs_latin": False,
        "deezer_title": title,
        "deezer_artist": artist,
    }
    out.update(extra)
    return out


def date(r, lookups=None):
    return years.date_row(r, lookups or FakeLookups())


# --- Brief step 1 ----------------------------------------------------------------


def test_first_release_a_year_before_the_chart_year_is_the_year():
    out = date(row("Hound Dog", "Elvis Presley", 1957))
    assert (out["year"], out["year_source"], out["flagged"], out["first"]) == (1956, "musicbrainz", False, 1956)


def test_1984_release_charting_in_1985():
    out = date(row("Like a Virgin", "Madonna", 1985))
    assert (out["year"], out["year_source"], out["flagged"]) == (1984, "musicbrainz", False)


def test_first_release_far_before_the_chart_year_keeps_the_chart_year_flagged():
    out = date(row("Big Girls Don't Cry", "The Four Seasons", 1985))
    assert out["first"] == 1962
    assert (out["year"], out["year_source"], out["flagged"]) == (1985, "chart", True)


def test_no_result_keeps_the_chart_year_flagged():
    out = date(row("Zzyzx Unheard Tune", "Nobody Band", 1985))
    assert (out["year"], out["year_source"], out["flagged"], out["first"]) == (1985, "chart", True, None)


def test_a_classics_row_keeps_its_own_year_and_makes_no_lookups():
    lookups = FakeLookups()
    out = date(row("כלניות", "שושנה דמארי", 1945, origin="IL", sources=("classics",),
                   deezer_artist="Shoshana Damari"), lookups)  # fmt: skip
    assert (out["year"], out["year_source"], out["flagged"], out["first"]) == (1945, "classics", False, None)
    assert lookups.calls == []


# --- the rule's edges -------------------------------------------------------------


@pytest.mark.parametrize(
    "first, expected",
    [(1983, (1983, False)), (1985, (1985, False)), (1982, (1985, True)), (1986, (1985, True)), (None, (1985, True))],
)
def test_window_is_two_years_before_the_chart_year(first, expected):
    assert years.decide(1985, first) == expected


# --- MusicBrainz: queries and agreement ------------------------------------------


def test_lucene_special_characters_are_escaped():
    assert years.lucene_escape('(I Can\'t Get No) "Satisfaction"') == '\\(I Can\'t Get No\\) \\"Satisfaction\\"'
    assert years.lucene_escape("AC/DC: Back-in-Black!") == "AC\\/DC\\: Back\\-in\\-Black\\!"
    query = years.recording_query(["Say \"Hi\" (Remix)"], ["Me & You"], until=2000)
    assert query == ('(recording:"Say \\"Hi\\" \\(Remix\\)") AND (artist:"Me \\& You") '
                     "AND firstreleasedate:[* TO 2000-12-31]")  # fmt: skip


def test_israeli_rows_search_the_hebrew_title_with_the_hebrew_and_the_deezer_artist():
    r = row("כלניות", "שושנה דמארי", 1945, origin="IL", deezer_artist="Shoshana Damari")
    titles, artists = years.query_names(r)
    assert titles == ["כלניות"]
    assert artists == ["שושנה דמארי", "Shoshana Damari"]


def test_lead_act_only_for_a_featuring_credit():
    r = row("Look Alive", "BlocBoy JB featuring Drake", 2018, deezer_title="Look Alive (feat. Drake)",
            deezer_artist="BlocBoy JB")  # fmt: skip
    titles, artists = years.query_names(r)
    assert titles == ["Look Alive"]
    assert artists == ["BlocBoy JB"]


def test_same_title_by_another_artist_does_not_count():
    response = RECORDINGS['(recording:"Hallelujah") AND (artist:"Leonard Cohen") AND firstreleasedate:[* TO 1994-12-31]']
    assert years.counting_years(row("Hallelujah", "Jeff Buckley", 1994), response) == []
    assert min(years.counting_years(row("Hallelujah", "Leonard Cohen", 1994), response)) == 1984


def test_first_is_the_minimum_over_the_counting_results():
    response = RECORDINGS['(recording:"כלניות") AND (artist:"שושנה דמארי" OR artist:"Shoshana Damari")']
    r = row("כלניות", "שושנה דמארי", 1945, origin="IL", deezer_artist="Shoshana Damari")
    found = years.counting_years(r, response)
    assert len(found) == 8  # the ninth (with the Haifa orchestra) scores 80
    assert min(found) == 1988


def test_nothing_up_to_the_chart_year_searches_again_without_the_date_limit():
    # Kalaniot has no release up to 1945 on MusicBrainz: the unlimited search finds 1988 (case "after").
    lookups = FakeLookups()
    r = row("כלניות", "שושנה דמארי", 1945, origin="IL", deezer_artist="Shoshana Damari")
    out = date(r, lookups)
    queries = [q for kind, q in lookups.calls if kind == "musicbrainz"]
    assert queries == [
        '(recording:"כלניות") AND (artist:"שושנה דמארי" OR artist:"Shoshana Damari") '
        "AND firstreleasedate:[* TO 1945-12-31]",
        '(recording:"כלניות") AND (artist:"שושנה דמארי" OR artist:"Shoshana Damari")',
    ]
    assert (out["first"], out["year"], out["year_source"], out["flagged"]) == (1988, 1945, "chart", True)
    assert not [c for c in lookups.calls if c[0] == "wikidata"]


# --- Wikidata fallback ----------------------------------------------------------


def test_wikidata_is_asked_only_when_musicbrainz_gives_nothing():
    lookups = FakeLookups()
    date(row("Hound Dog", "Elvis Presley", 1957), lookups)
    assert [kind for kind, _ in lookups.calls] == ["musicbrainz"]

    out = date(row("Hey Jude", "The Beatles", 1968), lookups)
    assert (out["first"], out["year"], out["year_source"], out["flagged"]) == (1968, 1968, "wikidata", False)


def test_wikidata_song_item_naming_other_performers_does_not_count():
    # The song item lists Wilson Pickett among three performers, but its description
    # names only the Beatles; Pickett's own track item has no date. No evidence for him.
    out = date(row("Hey Jude", "Wilson Pickett", 1969))
    assert (out["first"], out["year_source"], out["flagged"]) == (None, "chart", True)


# --- overrides and the whole stage ------------------------------------------------


def test_overrides_are_applied_last(tmp_path):
    path = tmp_path / "year_overrides.csv"
    big_girls = row("Big Girls Don't Cry", "The Four Seasons", 1985)
    hound = row("Hound Dog", "Elvis Presley", 1957)
    path.write_text(f"key,year,note\n{big_girls['key']},1962,the 1962 single\n", encoding="utf-8")
    out = years.run([big_girls, hound], FakeLookups(), years.load_overrides(path), last_year=2026)
    assert (out[0]["year"], out[0]["year_source"], out[0]["flagged"], out[0]["first"]) == (1962, "override", False, 1962)
    assert (out[1]["year"], out[1]["year_source"]) == (1956, "musicbrainz")


def test_an_override_for_an_unknown_key_fails_naming_the_key(tmp_path):
    path = tmp_path / "year_overrides.csv"
    path.write_text("key,year,note\nnot-a-key,1970,\n", encoding="utf-8")
    with pytest.raises(years.YearError, match="not-a-key"):
        years.run([row("Hound Dog", "Elvis Presley", 1957)], FakeLookups(), years.load_overrides(path), last_year=2026)


def test_sanity_checks_catch_a_year_out_of_range():
    r = row("Hound Dog", "Elvis Presley", 1957)
    with pytest.raises(years.YearError, match="1999"):
        years.run([r], FakeLookups(), {r["key"]: 1999}, last_year=1990)


# --- HTTP: MusicBrainz politeness --------------------------------------------------


class FakeResponse:
    def __init__(self, status, body):
        self.status_code = status
        self.headers = {}
        self._body = body

    def json(self):
        return self._body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise AssertionError(f"HTTP {self.status_code}")


class FakeClient:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = 0

    def get(self, url, params=None):
        self.calls += 1
        return self.responses.pop(0)


def test_musicbrainz_503_is_retried_and_not_cached(tmp_path, monkeypatch):
    monkeypatch.setattr(http, "CACHE_DIR", tmp_path)
    monkeypatch.setattr(http.time, "sleep", lambda s: None)
    rate_limited = FakeResponse(503, {"error": "Your requests are exceeding the allowable rate limit."})
    client = FakeClient([rate_limited, FakeResponse(200, NOTHING)])
    monkeypatch.setattr(http, "_get_client", lambda: client)
    params = {"query": "x", "fmt": "json", "limit": "100"}
    assert http.get_json(years.MUSICBRAINZ_URL, params) == NOTHING
    assert client.calls == 2
    cached = [p for p in tmp_path.rglob("*.json")]
    assert len(cached) == 1 and json.loads(cached[0].read_text(encoding="utf-8")) == NOTHING
    assert http.min_interval("musicbrainz.org") >= 1.0
    assert http.min_interval("www.wikidata.org") >= 0.5


def test_an_error_body_is_never_cached(tmp_path, monkeypatch):
    monkeypatch.setattr(http, "CACHE_DIR", tmp_path)
    client = FakeClient([FakeResponse(200, {"error": {"code": "no-such-entity", "info": "x"}})])
    monkeypatch.setattr(http, "_get_client", lambda: client)
    with pytest.raises(http.ApiError):
        http.get_json(years.WIKIDATA_API, {"action": "wbgetentities", "ids": "Q0"})
    assert not list(tmp_path.rglob("*.json"))


# --- Stage B: album-derived rows take their album's year ---------------------------------------


@pytest.mark.parametrize("source", ["hewiki-albums", "famous-acts"])
def test_an_album_row_takes_the_album_year_and_makes_no_lookups(source):
    lookups = FakeLookups()
    out = date(row("Hound Dog", "Elvis Presley", 1957, origin="IL", sources=(source,)), lookups)
    assert (out["year"], out["year_source"], out["flagged"], out["first"]) == (1957, "album", False, None)
    assert lookups.calls == []


def test_an_article_row_that_absorbed_an_album_row_is_still_looked_up():
    r = row("Hound Dog", "Elvis Presley", 1957, sources=("billboard-year-end", "hewiki-albums"))
    assert date(r)["year_source"] == "musicbrainz"
