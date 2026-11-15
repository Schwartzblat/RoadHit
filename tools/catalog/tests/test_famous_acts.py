"""Source famous-acts: the albums of the famous acts, on trimmed real answers only.

``famous_acts_kaveret.json`` holds real Deezer and MusicBrainz answers for כוורת (and the Deezer
artist search for בנזין), keyed "<kind>|<id or name>".
"""

import pytest

from conftest import load_fixture
from roadhit_catalog import famous_acts as fa

ANSWERS = load_fixture("famous_acts_kaveret.json")
KAVERET_MBID = "6ac29708-9886-4cef-a81b-5d289f55645d"


def fake_get_json(calls):
    def get_json(url, params=None, fresh=False):
        calls.append((url, params))
        if url == fa.DEEZER_ARTIST_SEARCH:
            return ANSWERS[f"deezer_artist_search|{params['q']}"]
        if url.startswith("https://api.deezer.com/artist/"):
            return ANSWERS[f"deezer_artist_albums|{url.split('/')[-2]}"]
        if url.startswith("https://api.deezer.com/album/"):
            return ANSWERS[f"deezer_album|{url.split('/')[-1]}"]
        if url == fa.MB_ARTIST:
            return ANSWERS[f"mb_artist|{params['query'].split(chr(34))[1]}"]
        if url == fa.MB_RELEASE_GROUPS:
            assert params["offset"] == "0"
            return ANSWERS[f"mb_release_groups|{params['artist']}"]
        raise AssertionError(f"unexpected request {url} {params}")

    return get_json


@pytest.fixture
def calls(monkeypatch):
    out = []
    monkeypatch.setattr(fa.http, "get_json", fake_get_json(out))
    return out


def test_the_acts_file_keeps_its_order_and_reads_comments(tmp_path):
    path = tmp_path / "acts.txt"
    path.write_text("# famous\nכוורת\nגידי גוב  # a note\n\nכוורת\n", encoding="utf-8")
    assert fa.load_acts(path) == ["כוורת", "גידי גוב"]
    assert fa.load_acts()[:2] == ["כוורת", "גזוז"]


def test_deezer_artist_is_the_first_hit_and_its_duplicate_profiles():
    assert fa.deezer_artist_ids("כוורת", ANSWERS["deezer_artist_search|כוורת"]) == [10985362]
    # "Benzin" first, and a second profile spelled as the act
    assert fa.deezer_artist_ids("בנזין", ANSWERS["deezer_artist_search|בנזין"]) == [13680683, 57986442]
    assert fa.deezer_artist_ids("x", {"data": [{"id": 1, "name": "x", "nb_album": 0}]}) == []
    assert fa.deezer_artist_ids("x", {"data": []}) == []


def test_musicbrainz_artist_must_carry_the_name_or_an_alias():
    answer = ANSWERS["mb_artist|כוורת"]
    assert fa.mb_artist_id("כוורת", answer) == KAVERET_MBID
    assert fa.mb_artist_id("Kaveret", answer) == KAVERET_MBID  # an alias
    assert fa.mb_artist_id("גידי גוב", answer) is None


def test_musicbrainz_release_groups_give_year_and_kind():
    albums = {a["title"]: a for a in fa.mb_albums(ANSWERS[f"mb_release_groups|{KAVERET_MBID}"]["release-groups"])}
    assert (albums["סיפורי פוגי"]["year"], albums["סיפורי פוגי"]["kind"]) == (1973, fa.ALBUM)
    assert albums["כוורת בפארק"]["kind"] == fa.LIVE
    assert albums["כוורת בקופסא: אנתולוגיה / 1971–1976"]["kind"] == fa.COMPILATION
    assert albums["She Looked Me in the Eye / Morris and His Turtle"]["kind"] == fa.SINGLE
    assert not any(a["title"].startswith("1990-10-04") for a in albums.values())  # a broadcast without a date


@pytest.mark.parametrize("title, other", [
    ("סיפורי פוגי (Remastered)", False),
    ("כוורת - המופע האחרון (Live)", True),
    ("כוורת בקופסא - אנתולוגיה / 1971-1976", True),
    ("נגנו עכשיו - האוסף", True),
    ("רדיו חזק (Remixed)", True),
    ("תיסלם והסימפונית", False),  # judged by its year and kind instead
    ("שירים מחדש", True),
    ("The Best of the Best", True),
])
def test_album_titles_that_mark_something_else(title, other):
    assert fa.title_marks_other(title) is other


def test_collect_act_takes_one_copy_of_each_studio_album_with_a_year(calls):
    rows, report = fa.collect_act("כוורת", [], None)
    verdicts = {a["title"]: a["verdict"] for a in report["albums"]}
    assert verdicts["כוורת - המופע האחרון (Live)"] == fa.NOT_STUDIO_TITLE
    assert verdicts["כוורת בקופסא - אנתולוגיה / 1971-1976"] == fa.NOT_STUDIO_TITLE
    assert verdicts["אופרת פוגי - סיפור הארון"] == fa.NO_YEAR  # 2024: MusicBrainz does not know it
    assert verdicts["Poogy"] == fa.NO_YEAR
    # Both copies of each album agree with MusicBrainz; the copy whose best track ranks highest is taken.
    assert verdicts["סיפורי פוגי"] == fa.TAKEN and verdicts["סיפורי פוגי (Remastered)"] == fa.OTHER_COPY
    assert verdicts["צפוף באוזן (Remastered)"] == fa.TAKEN and verdicts["צפוף באוזן"] == fa.OTHER_COPY
    assert verdicts["פוגי בפיתה"] == fa.TAKEN
    years = {a["title"]: a.get("year") for a in report["albums"]}
    assert years["סיפורי פוגי"] == 1973 and years["פוגי בפיתה"] == 1974 and years["צפוף באוזן (Remastered)"] == 1975
    assert report["deezer_artists"] == [10985362] and report["deezer_albums"] == 11
    assert report["tracks"] == len(rows)
    by_title = {r["title"]: r for r in rows}
    assert by_title["שיר המכולת"] == {
        "title": "שיר המכולת", "artist": "כוורת", "credit": "כוורת", "chart_year": 1973, "origin": "IL",
        "source": "famous-acts", "position": 0, "extended": True, "album": "סיפורי פוגי", "deezer_hint": 736805642,
    }
    assert by_title["גוליית"]["chart_year"] == 1975  # the Remastered copy, its title without "(Remastered)"
    assert "מתימטיקה" not in by_title and "שעור פסנתר" not in by_title  # "(פינות פוגי)" skits
    assert "היו לילות" in by_title  # a CD bonus track: nothing on Deezer says so


def test_an_album_page_track_list_leaves_out_the_bonus_tracks_of_the_reissue(calls):
    page = {"title": "סיפורי פוגי", "year": 1973, "kind": fa.ALBUM, "source": fa.PAGE,
            "tracks": ["סיפורי פוגי", "שיר המכולת", "ביום ובלילה", "שירות עצמי", "ילד מזדקן", "למרות הכל",
                       "פה קבור הכלב", "סיפור הארון", "לא ידענו מה לעשות", "יוסי מה נשמע", "המגפיים של ברוך",
                       "נחמד", "יו יה"]}
    rows, report = fa.collect_act("כוורת", [page], None)
    poogy = [r["title"] for r in rows if r["album"] == "סיפורי פוגי"]
    assert len(poogy) == 13 and "היו לילות" not in poogy and "פוגי 71" not in " ".join(poogy)
    entry = next(a for a in report["albums"] if a["title"] == "סיפורי פוגי")
    assert entry["year_from"] == fa.PAGE


def test_an_album_page_year_wins_over_musicbrainz():
    known = [{"title": "פוגי בפיתה", "year": 1974, "kind": fa.ALBUM, "source": fa.MUSICBRAINZ},
             {"title": "פוגי בפיתה", "year": 1975, "kind": fa.ALBUM, "source": fa.DISCOGRAPHY},
             {"title": "פוגי בפיתה", "year": 1976, "kind": fa.ALBUM, "source": fa.PAGE}]
    assert fa.established_for("פוגי בפיתה (Remastered)", known)["year"] == 1976
    assert fa.established_for("פוגי בפיתה", known[:2])["year"] == 1975
    assert fa.established_for("צפוף באוזן", known) is None


def test_albums_outside_1960_1999_or_not_studio_give_nothing():
    known = [{"title": "אם היינו", "year": 2015, "kind": fa.ALBUM, "source": fa.MUSICBRAINZ},
             {"title": "כוורת בפארק", "year": 1998, "kind": fa.LIVE, "source": fa.MUSICBRAINZ}]
    judged = fa.judge_albums([{"id": 1, "title": "אם היינו"}, {"id": 2, "title": "כוורת בפארק"}], known)
    assert [v for _, v, _ in judged] == [fa.OUT_OF_RANGE, fa.NOT_STUDIO]


def test_tracks_by_another_artist_and_marked_takes_are_skipped():
    album = {"tracks": {"data": [
        {"id": 1, "title": "שיר", "rank": 5, "artist": {"id": 9}},
        {"id": 2, "title": "שיר של אחר", "rank": 5, "artist": {"id": 8}},  # another performer
        {"id": 3, "title": "שיר (Live)", "rank": 5, "artist": {"id": 9}},
        {"id": 4, "title": "שיר (רמיקס)", "rank": 5, "artist": {"id": 9}},
        {"id": 5, "title": "אינטרו", "rank": 5, "artist": {"id": 9}},
        {"id": 6, "title": "שיר נוסף", "title_version": "(Bonus Track)", "rank": 5, "artist": {"id": 9}},
        {"id": 7, "title": "שיר אחרון (Instrumental)", "rank": 5, "artist": {"id": 9}},
    ]}}
    rows = fa.track_rows("להקה", [9], album, {"title": "אלבום", "year": 1980})
    assert [r["deezer_hint"] for r in rows] == [1]


# --- The discography section of the act's page ----------------------------------------------------

DISCOGRAPHY = """'''להקה''' היא להקה.
== היסטוריה ==
* 1965 – [[משהו אחר]]
== דיסקוגרפיה ==
=== אלבומי אולפן ===
* [[סיפורי פוגי]] (1973)
* 1974 – [[פוגי בפיתה]]
# ''צפוף באוזן'' (1975)
* "[[שיר (אלבום)|שיר]]" - [[1976 במוזיקה|1976]]
=== אלבומי הופעה ===
* [[כוורת בפארק]] (1998)
=== אוספים ===
* [[כוורת בקופסא]] (2013)
* [[להקת כוורת]] (2008, אוסף)
=== אלבומים בהשתתפות ===
{| class="wikitable"
! שנה !! שם
|-
| 1980 || [[אורח]]
|}
== ראו גם ==
* [[משהו]] 1999
"""


def test_discography_lines_give_title_year_and_the_kind_of_their_heading():
    got = {(a["title"], a["year"]): a["kind"] for a in fa.discography(DISCOGRAPHY)}
    assert got == {
        ("סיפורי פוגי", 1973): fa.ALBUM, ("פוגי בפיתה", 1974): fa.ALBUM, ("צפוף באוזן", 1975): fa.ALBUM,
        ("שיר", 1976): fa.ALBUM, ("כוורת בפארק", 1998): fa.LIVE, ("כוורת בקופסא", 2013): fa.COMPILATION,
        ("להקת כוורת", 2008): fa.COMPILATION, ("אורח", 1980): fa.OTHER,
    }
    assert all(a["source"] == fa.DISCOGRAPHY for a in fa.discography(DISCOGRAPHY))


def test_a_page_without_a_discography_gives_nothing():
    assert fa.discography("'''להקה''' היא להקה.\n== היסטוריה ==\n* [[אלבום]] (1973)\n") == []


@pytest.mark.parametrize("text, kind", [
    ("אלבומי אולפן", fa.ALBUM), ("אלבומי אוסף", fa.COMPILATION), ("אלבומי הופעה", fa.LIVE),
    ("סינגלים", fa.SINGLE), ("אלבומי ילדים", fa.OTHER), ("דיסקוגרפיה", fa.ALBUM),
])
def test_section_kind(text, kind):
    assert fa.section_kind(text) == kind


def test_an_act_page_must_be_about_music_and_not_a_disambiguation():
    band = {"wikitext": "x", "categories": ["קטגוריה:להקות רוק ישראליות"]}
    month = {"wikitext": "x", "categories": ["קטגוריה:חודשי הלוח העברי"]}
    choice = {"wikitext": "{{פירושונים|...}}", "categories": ["קטגוריה:פירושונים"]}
    assert fa.is_music_act(band) and not fa.is_music_act(month)
    assert fa.is_disambiguation(choice) and not fa.is_disambiguation(band)


def test_act_pages_try_the_name_then_the_disambiguated_titles(monkeypatch):
    answers = {
        "כוורת|גידי גוב": {"query": {
            "normalized": [], "redirects": [{"from": "גידי גוב", "to": "גידי גוב (זמר)"}],
            "pages": [{"title": "כוורת", "categories": [{"title": "קטגוריה:פירושונים"}],
                       "revisions": [{"slots": {"main": {"content": "{{פירושונים}}"}}}]},
                      {"title": "גידי גוב (זמר)", "categories": [{"title": "קטגוריה:זמרים ישראלים"}],
                       "revisions": [{"slots": {"main": {"content": "'''גידי גוב'''"}}}]}]}},
    }
    variants = {"query": {"pages": [
        {"title": "כוורת (להקה)", "categories": [{"title": "קטגוריה:להקות רוק ישראליות"}],
         "revisions": [{"slots": {"main": {"content": "'''כוורת'''"}}}]},
        {"title": "כוורת (זמר)", "missing": True}]}}
    seen = []

    def get_json(url, params=None, fresh=False):
        seen.append(params["titles"])
        assert params["redirects"] == "1"
        return answers.get(params["titles"], variants)

    monkeypatch.setattr(fa.http, "get_json", get_json)
    got = fa.fetch_act_pages(["כוורת", "גידי גוב"])
    assert {act: page["title"] for act, page in got.items()} == {"כוורת": "כוורת (להקה)", "גידי גוב": "גידי גוב (זמר)"}
    assert seen[0] == "כוורת|גידי גוב" and seen[1].split("|") == fa.act_page_titles("כוורת")[1:]


def test_album_pages_of_the_act_include_its_duets_and_shared_albums():
    pages = [
        {"title": "מאחורי הצלילים", "artist": "מתי כספי ושלמה גרוניך", "year": 1973, "kind": "live", "tracks": []},
        {"title": "סיפורי פוגי", "artist": "כוורת", "year": 1973, "kind": "album", "tracks": ["שיר המכולת"]},
        {"title": "אחר", "artist": "מתי כספי בהשתתפות כוורת", "year": 1980, "kind": "album", "tracks": []},
        {"title": "בלי שנה", "artist": "מתי כספי", "year": None, "kind": "album", "tracks": []},
    ]
    assert [p["title"] for p in fa.act_album_pages("מתי כספי", pages)] == ["מאחורי הצלילים", "אחר"]
    assert [p["title"] for p in fa.act_album_pages("שלמה גרוניך", pages)] == ["מאחורי הצלילים"]
    assert [p["title"] for p in fa.act_album_pages("כוורת", pages)] == ["סיפורי פוגי"]  # not as a guest
    assert fa.act_album_pages("כוורת", pages)[0] == {"title": "סיפורי פוגי", "year": 1973, "kind": "album",
                                                    "source": fa.PAGE, "tracks": ["שיר המכולת"]}


# --- Round 2: foreign-language, children's and instrumental albums, Latin-titled tracks ---------

def deezer_album(titles, genres=()):
    return {"genres": {"data": [{"name": g} for g in genres]},
            "tracks": {"data": [{"id": i, "title": t, "rank": 5, "artist": {"id": 9}} for i, t in enumerate(titles)]}}


@pytest.mark.parametrize("album, verdict", [
    (deezer_album(["שיר", "עוד שיר"], ["Pop"]), None),
    (deezer_album(["Song", "Another Song", "שיר"]), fa.FOREIGN),  # mostly Latin titles
    (deezer_album(["שיר", "עוד שיר"], ["Kids"]), fa.CHILDREN),
    (deezer_album(["שיר", "עוד שיר"], ["Jazz"]), fa.INSTRUMENTAL),
])
def test_deezer_album_that_is_not_hebrew_songs(album, verdict):
    assert fa.deezer_album_other(album) == verdict


@pytest.mark.parametrize("kind, verdict", [
    ("foreign-language album", fa.FOREIGN), ("children's or holiday album", fa.CHILDREN),
    ("instrumental or jazz album", fa.INSTRUMENTAL), ("live", fa.NOT_STUDIO),
])
def test_an_album_page_that_is_not_hebrew_songs_gives_its_reason(kind, verdict):
    known = [{"title": "אלבום", "year": 1980, "kind": kind, "source": fa.PAGE}]
    assert [v for _, v, _ in fa.judge_albums([{"id": 1, "title": "אלבום"}], known)] == [verdict]


@pytest.mark.parametrize("title", ["עפרה חזה לילדים", "שירי חגים", "הגדה של פסח", "פסטיגל 85"])
def test_children_and_holiday_titles(title):
    known = [{"title": title, "year": 1980, "kind": fa.ALBUM, "source": fa.MUSICBRAINZ}]
    assert [v for _, v, _ in fa.judge_albums([{"id": 1, "title": title}], known)] == [fa.CHILDREN]


def test_latin_titled_tracks_give_no_rows():
    rows = fa.track_rows("להקה", [9], deezer_album(["שיר", "My Song", "עוד שיר"]), {"title": "אלבום", "year": 1980})
    assert [r["title"] for r in rows] == ["שיר", "עוד שיר"]


def test_collect_act_drops_a_kids_album(calls, monkeypatch):
    real = fa.http.get_json

    def get_json(url, params=None, fresh=False):
        answer = real(url, params, fresh)
        if url.endswith("/108231462"):  # סיפורי פוגי, as if Deezer filed it under Kids
            answer = {**answer, "genres": {"data": [{"name": "Kids"}]}}
        return answer

    monkeypatch.setattr(fa.http, "get_json", get_json)
    rows, report = fa.collect_act("כוורת", [], None)
    verdicts = {a["title"]: a["verdict"] for a in report["albums"]}
    assert verdicts["סיפורי פוגי"] == fa.CHILDREN and verdicts["סיפורי פוגי (Remastered)"] == fa.TAKEN


# --- Fix round ------------------------------------------------------------------------------------
# ``famous_acts_more.json``: real answers, trimmed to the fields the code reads. אבטיפוס (Deezer finds
# nothing for the Hebrew name, its profile is "Avtipus"), Eyal Golan's 160 Deezer albums (two pages)
# and Yehudit Ravitz's 104 MusicBrainz release groups (two pages), keyed "<kind>|<id or name>[|<offset>]".

MORE = load_fixture("famous_acts_more.json")
AVTIPUS_MBID = "f624cefb-6f0b-43b5-a206-47b44c8599a2"
RAVITZ_MBID = "10385cb9-e45f-4c18-b03e-190c94f5f305"


def fake_more(calls):
    kaveret = fake_get_json([])

    def get_json(url, params=None, fresh=False):
        calls.append((url, dict(params or {})))
        if url.startswith("https://api.deezer.com/artist/"):
            key = f"deezer_artist_albums|{url.split('/')[-2]}"
            return MORE.get(f"{key}|{(params or {}).get('index', '0')}") or MORE.get(key) or kaveret(url, params)
        if url == fa.MB_RELEASE_GROUPS:
            return MORE.get(f"mb_release_groups|{params['artist']}|{params['offset']}") or kaveret(url, params)
        if url == fa.DEEZER_ARTIST_SEARCH and f"deezer_artist_search|{params['q']}" in MORE:
            return MORE[f"deezer_artist_search|{params['q']}"]
        if url == fa.MB_ARTIST and f"mb_artist|{params['query'].split(chr(34))[1]}" in MORE:
            return MORE[f"mb_artist|{params['query'].split(chr(34))[1]}"]
        if url.startswith("https://api.deezer.com/album/") and f"deezer_album|{url.split('/')[-1]}" in MORE:
            return MORE[f"deezer_album|{url.split('/')[-1]}"]
        return kaveret(url, params)

    return get_json


@pytest.fixture
def more(monkeypatch):
    out = []
    monkeypatch.setattr(fa.http, "get_json", fake_more(out))
    return out


def test_artist_albums_follows_deezers_next_page(more):
    albums = fa.artist_albums(11951)
    assert len(albums) == 160 and len({a["id"] for a in albums}) == 160
    assert [p.get("index") for _, p in more] == [None, "100"]


def test_musicbrainz_release_groups_follow_the_offset(more):
    groups = fa.mb_release_groups(RAVITZ_MBID)
    assert len(groups) == 104
    assert [p["offset"] for _, p in more] == ["0", "100"]


def test_the_acts_file_gives_the_aliases_of_each_act(tmp_path):
    path = tmp_path / "acts.txt"
    path.write_text("אבטיפוס | Avtipus  # Deezer's name\nכוורת\n", encoding="utf-8")
    assert fa.load_acts(path) == ["אבטיפוס", "כוורת"]
    assert fa.load_aliases(path) == {"אבטיפוס": ["Avtipus"]}


AVTIPUS_PAGE = {"title": "מה החזאי מבין", "artist": "אבטיפוס", "year": 1995, "kind": "album", "tracks": [
    "מועקה", "בוקר טוב עולם", "אשה אשה", "לילה אהבה", "איך זה שפתאום", "מה החזאי מבין", "הנה ההומור שלי",
    "כשאין לי דמיון", "לאן את הולכת", "תשאירי לי מקום לחבק אותך", "קצב הזמן", "אחרי ההתרגשות", "מועקה?"]}


def test_collect_reads_the_acts_file_and_reports_every_act(more, tmp_path, monkeypatch):
    path = tmp_path / "acts.txt"
    path.write_text("כוורת\nאבטיפוס | Avtipus\n", encoding="utf-8")
    monkeypatch.setattr(fa, "fetch_act_pages", lambda acts: {})
    rows, reports = fa.collect([AVTIPUS_PAGE], path)
    assert [r["act"] for r in reports] == ["כוורת", "אבטיפוס"]
    assert all(r["act_page"] is None for r in reports)
    kaveret, avtipus = reports
    assert kaveret["deezer_artists"] == [10985362] and kaveret["tracks"] == sum(r["artist"] == "כוורת" for r in rows)
    # The Hebrew name finds no Deezer artist; the alias does.
    assert avtipus["deezer_artists"] == [4332864]
    verdicts = {a["title"]: (a["verdict"], a.get("year"), a.get("year_from")) for a in avtipus["albums"]}
    assert verdicts["מה החזאי מבין"] == (fa.TAKEN, 1995, fa.PAGE)
    assert verdicts["איך עושים מים"] == (fa.TAKEN, 1997, fa.MUSICBRAINZ)
    assert verdicts["הודעה לנוסעים מחוץ לזמן"][0] == fa.OUT_OF_RANGE  # 2015 by MusicBrainz
    songs = {r["title"]: r for r in rows if r["artist"] == "אבטיפוס"}
    assert songs["בוקר טוב עולם"]["deezer_hint"] == 128117855 and songs["בוקר טוב עולם"]["chart_year"] == 1995
    assert songs["אוקיאנוס"]["chart_year"] == 1997
    assert not any(r["artist"] == "הפטריקים" for r in rows)


@pytest.mark.parametrize("deezer_title, title", [
    ("(מה אומרות עינייך (שני אורות בליל", "מה אומרות עינייך"),  # Deezer's right-to-left garbling
    ("שיר (Remastered)", "שיר"),
])
def test_garbled_brackets_are_repaired_before_the_title_is_cleaned(deezer_title, title):
    album = {"tracks": {"data": [{"id": 1, "title": deezer_title, "rank": 5, "artist": {"id": 9}}]}}
    assert [r["title"] for r in fa.track_rows("להקה", [9], album, {"title": "אלבום", "year": 1996})] == [title]


@pytest.mark.parametrize("deezer_title", [
    "(גחליליות (נעימה",  # an instrumental, garbled
    "אל תשכחי לזכור אותי (נעימה)",
    "מגה מיקס - בוא / נסיך שלי / ימים רבים / ניצוץ החיים / ערב סגריר / לאהוב",
    "מחרוזת שירי ארץ ישראל",
    "שיר לכל המשפחה (טייק 2)",
])
def test_instrumentals_medleys_and_takes_give_no_row(deezer_title):
    album = {"tracks": {"data": [{"id": 1, "title": deezer_title, "rank": 5, "artist": {"id": 9}}]}}
    assert fa.track_rows("להקה", [9], album, {"title": "אלבום", "year": 1996}) == []


def test_a_song_named_naima_stays():
    album = {"tracks": {"data": [{"id": 1, "title": "נעימה נעימה", "rank": 5, "artist": {"id": 9}}]}}
    assert [r["title"] for r in fa.track_rows("להקה", [9], album, {"title": "אלבום", "year": 1974})] == ["נעימה נעימה"]


@pytest.mark.parametrize("kind", ["covers", "tribute"])
def test_a_covers_or_tribute_album_page_is_not_a_studio_album(kind):
    known = [{"title": "שירים מלילה גוב", "year": 1995, "kind": kind, "source": fa.PAGE}]
    assert [v for _, v, _ in fa.judge_albums([{"id": 1, "title": "שירים מלילה גוב - חלק א"}], known)] == [fa.NOT_STUDIO]


def test_a_covers_heading_of_the_discography_is_not_studio():
    assert fa.section_kind("אלבומי גרסאות כיסוי") == fa.OTHER
