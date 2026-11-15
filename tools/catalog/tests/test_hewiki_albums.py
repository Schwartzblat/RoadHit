"""Source hewiki-albums: tracks of Hebrew Wikipedia album pages, on trimmed real answers only.

- ``hewiki_albums_pages.json``: one ``action=query`` answer with 21 real album pages, each trimmed
  to its infobox, its track-list sections and (for the last 6) the first lines of its lead text
  (verified to parse exactly like the full page).
- ``hewiki_albums_walk.json``: real ``categorymembers`` answers, trimmed, keyed
  "<category>|<page or subcat>|<cmcontinue>".
"""

import pytest

from conftest import load_fixture
from roadhit_catalog import hewiki_albums as ha
from roadhit_catalog import hewiki_songs as hs
from roadhit_catalog.candidates import merge_candidates


def pages():
    out = {}
    hs.merge_page_batch(out, load_fixture("hewiki_albums_pages.json"))
    return out


PAGES = pages()


def parsed(title):
    return ha.parse_page(title, PAGES[title])


# --- Verdicts per page -----------------------------------------------------------------------------


@pytest.mark.parametrize("title, reason", [
    ("סיפורי פוגי", ha.CANDIDATE),
    ("רדיו חזק", ha.CANDIDATE),  # a debut: year from "אלבומי בכורה מ-1981"
    ("בשנה הבאה (מיני-אלבום)", ha.CANDIDATE),  # an EP: year from "מיני-אלבומים מ-1970"
    ("שלום חנוך (אלבום)", ha.YEAR_OUT_OF_RANGE),  # 2009
    ("בני אל בלהיטים הגדולים 95", ha.NOT_STUDIO),  # the title says compilation
    ("מאחורי הצלילים", ha.NOT_STUDIO),  # a live album by its categories
    ("קווים (אלבום)", ha.NO_PERFORMER),  # four composers: a multi-artist album
    ("אלמנה שחורה (אלבום)", ha.NO_PERFORMER),  # no מאת, no chronology
    ("סיפור אחר - פסקול הסרט", ha.REDIRECT),
    # Fix round: what the infobox and the lead say
    ("חוה זינגט יידיש", ha.FOREIGN),  # שפה = [[יידיש]], though it sits in the Hebrew category
    ("אחלהזאורים", ha.CHILDREN),  # סוגה = [[שירי ילדים]]
    ("שירים מלילה גוב", ha.NOT_STUDIO),  # קטגוריה:אלבומי גרסאות כיסוי, סוג = גרסאות כיסוי
    ("בחזרה לשבלול", ha.NOT_STUDIO),  # the lead: "האלבום מהווה מחווה לאלבומם של ... שבלול"
    ("ארץ ישראל הישנה והטובה חלק ד': משירי סשה ארגוב", ha.CANDIDATE),  # "המבוסס כולו על גרסאות כיסוי": a studio album
    ("חתונה לבנה (אלבום)", ha.CANDIDATE),  # the lead names a later tribute album, not this one
])
def test_page_verdicts(title, reason):
    assert parsed(title)["reason"] == reason


def test_a_kept_album_page_is_taken_although_a_rule_says_otherwise():
    title = "שירים מלילה גוב"  # a covers album by its category and type
    kept = ha.parse_page(title, PAGES[title], keep=frozenset({title}))
    assert kept["reason"] == ha.CANDIDATE
    assert kept["rows"] and all(row["album"] == title for row in kept["rows"])
    assert parsed(title)["reason"] == ha.NOT_STUDIO  # without the keep list nothing changes


def test_the_keep_list_file_reads_page_titles_and_skips_comments(tmp_path):
    path = tmp_path / "album_keep.txt"
    path.write_text("# why\nצד ג' צד ד'  # the performer's own versions\n\nצד א' צד ב' (אלבום של מתי כספי)\n", encoding="utf-8")
    assert ha.load_keep(path) == frozenset({"צד ג' צד ד'", "צד א' צד ב' (אלבום של מתי כספי)"})
    assert ha.load_keep(tmp_path / "missing.txt") == frozenset()


def test_covers_and_tribute_albums_say_what_they_are():
    assert parsed("שירים מלילה גוב")["album"]["kind"] == "covers"
    assert parsed("בחזרה לשבלול")["album"]["kind"] == "tribute"


@pytest.mark.parametrize("box_extra, reason", [
    ("| שפה = [[עברית]]\n", ha.CANDIDATE),
    ("| שפה = עברית, אנגלית\n", ha.CANDIDATE),  # a Hebrew album with an English song
    ("| שפה = [[יידיש]]\n", ha.FOREIGN),
    ("| שפה = [[לאדינו]]\n", ha.FOREIGN),
    ("| סוגה = [[רוק ישראלי]], [[שירי ילדים]]\n", ha.CHILDREN),
    ("| סוגה = ילדים\n", ha.CHILDREN),
    ("| סוג = גרסאות כיסוי\n", ha.NOT_STUDIO),
])
def test_infobox_language_genre_and_type(box_extra, reason):
    assert ha.parse_page("אלבום", album_page([], HEBREW, box_extra))["reason"] == reason


@pytest.mark.parametrize("lead, kind", [
    ("'''אלבום''' הוא [[אלבום מוזיקה|אלבום]] משולש של [[גרסת כיסוי|גרסאות כיסוי]] של [[זמר]].", "covers"),
    ("'''אלבום''' הוא אלבומה השלישי של הלהקה. האלבום מהווה מחווה לאלבומם של אחרים.", "tribute"),
    ("'''אלבום''' הוא אלבום מחווה לזמר.", "tribute"),
    ("'''אלבום''' הוא האלבום השני של הזמר. בשנת 2008 יצא אלבום מחווה בשם \"אחר\".", None),
    ("'''אלבום''' הוא אלבום של הזמר, המבוסס כולו על גרסאות כיסוי לשירי המלחין.", None),
    ("'''אלבום''' הוא אלבום של הזמר. השיר \"לילה טוב\" הוא גרסת כיסוי לשיר של הביטלס.", None),
])
def test_lead_kind(lead, kind):
    wikitext = "{{אלבום\n| מאת = [[זמר]]\n}}\n" + lead + "\n== רשימת השירים ==\n# שיר\n"
    assert ha.lead_kind(wikitext, "אלבום") == kind


def test_a_candidate_album_gives_one_row_per_track():
    out = parsed("רדיו חזק")
    assert out["form"] == ha.TEMPLATE
    assert [r["title"] for r in out["rows"]] == [
        "רדיו חזק", "כלניות", "המנקה במלון", "תפסיק לכוון אלי", "חושב רחוק כרגע", "מעשנים ביחד", "על הבמה",
        "תנו לי רוק'נ'רול", "יש רגעים", "לראות אותה היום",
    ]  # the CD's English bonus tracks, listed after "רצועות בונוס", are not taken
    row = out["rows"][0]
    assert {k: row[k] for k in ("artist", "credit", "chart_year", "origin", "source", "extended", "album")} == {
        "artist": "תיסלם", "credit": "תיסלם", "chart_year": 1981, "origin": "IL", "source": "hewiki-albums",
        "extended": True, "album": "רדיו חזק",
    }


def test_an_instrumental_track_of_the_template_is_skipped():
    # "יוסי מה נשמע" has a composer and an empty lyricist (an instrumental guitar piece).
    out = parsed("סיפורי פוגי")
    titles = [r["title"] for r in out["rows"]]
    assert "יוסי מה נשמע" not in titles and out["skipped"] == 1
    assert titles[:3] == ["סיפורי פוגי", "שיר המכולת", "ביום ובלילה"] and len(titles) == 12


def test_the_performer_may_come_from_the_chronology_template():
    out = parsed("אין עוד יום")  # no מאת; {{כרונולוגיה|מאת=גידי גוב|…}}
    assert out["album"]["artist"] == "גידי גוב" and out["album"]["year"] == 1991
    assert out["reason"] == ha.CANDIDATE


@pytest.mark.parametrize("title, form, first, count", [
    ("קשורים", ha.NUMBERED, ["עוד שנייה", "תביני אותי", "3/4"], 11),  # "#עוד שנייה (3:16)"
    ("בזזז...", ha.NUMBERED, ["בזזז", "Kalifornia", "עיניים עצומות", "Carol"], 13),  # "מבוא" skipped
    ("התוכנית החדשה", ha.TABLE, ["סימן שאתה צעיר", "כחולה כחלום"], 13),
    ("כמו סינדרלה", ha.TABLE, ["כמו סינדרלה", "חמותי", "כשאני איתך אני כמו דג"], 12),  # not the musicians' list
    ("השטרודל האנושי", ha.BULLETED, ["רוק'נ'רול מצובאי", "Big Big Blue Wave"], 14),
])
def test_track_list_forms(title, form, first, count):
    out = parsed(title)
    titles = out["album"]["tracks"]  # every track; Latin-titled ones give no row (round 2)
    assert out["form"] == form and titles[: len(first)] == first and len(titles) == count


def test_albums_out_of_range_still_describe_themselves_for_famous_acts():
    album = parsed("שלום חנוך (אלבום)")["album"]
    assert album == {"title": "שלום חנוך", "artist": "שלום חנוך", "year": 2009, "kind": "album",
                     "tracks": album["tracks"]}
    assert album["tracks"][:2] == ["פתוחים לאהבה", "אלוהים"] and parsed("שלום חנוך (אלבום)")["rows"] == []
    assert parsed("מאחורי הצלילים")["album"]["kind"] == "live"


# --- The pieces ---------------------------------------------------------------------------------


@pytest.mark.parametrize("categories, year", [
    (["קטגוריה:אלבומי 1985"], 1985),
    (["קטגוריה:אלבומי בכורה מ-1981"], 1981),
    (["קטגוריה:מיני-אלבומים מ-1990"], 1990),
    (["קטגוריה:מיני-אלבומי בכורה מ-1994"], 1994),
    (["קטגוריה:אלבומי אוסף מ-1990"], None),  # a compilation's year category is not a studio year
    (["קטגוריה:אלבומי כוורת"], None),
])
def test_category_year(categories, year):
    assert ha.category_year(categories) == year


@pytest.mark.parametrize("raw, title", [
    ('# "ילד מזדקן" (מילים: אלון אולארצ\'יק, לחן: דני סנדרסון) – 3:11', "ילד מזדקן"),
    ("#עוד שנייה (3:16)", "עוד שנייה"),
    ("# [[ירח (שיר מאת נתן אלתרמן)|ירח]] ([[נתן אלתרמן]] / [[יונתן בר גיורא]])", "ירח"),
    # a leading bracket goes with the notes, as Deezer's clean_title drops it too
    ("#{{משמאל לימין|(That's Not) Carol}} (מילים: מאור כהן)", "Carol"),
    ('"ג\'אני ג\'אני"{{הערה|השיר הוקלט בירדן}}', "ג'אני ג'אני"),
    ("* Big Big Blue Wave{{כ}} (02:45)", "Big Big Blue Wave"),
    ("# שיר - [[מאיר אריאל]]", "שיר"),
])
def test_clean_track(raw, title):
    assert ha.clean_track(raw) == title


@pytest.mark.parametrize("title, note, skip", [
    ("שיר", "", False),
    ("שיר", "(רצועת בונוס)", True),
    ("שיר", "קטע כלי", True),
    ("פתיח", "", True),
    ("מבוא", "", True),
    ("מחרוזת: אחת ויחידה, אל תשכחי", "", True),
    ("ליבי ליבי/סחרחורת", "", True),
    ("3/4", "", False),
    ("שיר", "(שירה: יזהר אשדות)", True),  # sung by someone else
    ("שיר", "(שירה: תיסלם)", False),
    ("שרה שרה", "", False),  # a title, not a singer credit
])
def test_skip_track(title, note, skip):
    assert ha._skip_track(title, note, "תיסלם") is skip


@pytest.mark.parametrize("box, artist", [
    ({"מאת": "[[כוורת (להקה)|כוורת]]"}, "כוורת"),
    ({"מאת": "[[שלמה יידוב]], [[יצחק קלפטר]], [[יוני רכטר]] ו[[מתי כספי]]"}, None),
    ({"מאת": "אמנים שונים"}, None),
    ({"מאת": "[[אילן ואילנית]]"}, "אילן ואילנית"),
    ({"כרונולוגיה": "{{כרונולוגיה|מאת=[[שרית חדד]]|יצירה קודמת=x}}"}, "שרית חדד"),
    ({}, None),
])
def test_performer(box, artist):
    assert ha.performer(box) == artist


# --- Merging, the walk and collect() ---------------------------------------------------------------


def test_an_album_row_merges_into_the_chart_row_of_the_same_song():
    row = parsed("רדיו חזק")["rows"][0]
    chart = {"title": "רדיו חזק", "artist": "תיסלם", "credit": "תיסלם", "chart_year": 1981,
             "origin": "IL", "source": "hewiki-il", "position": 3}
    (only,) = merge_candidates([chart, row])
    assert only["source"] == "hewiki-il" and only["sources"] == ["hewiki-albums", "hewiki-il"]
    assert only["extended"] is False and "album" not in only


WALK = load_fixture("hewiki_albums_walk.json")
EMPTY = {"query": {"categorymembers": []}}


@pytest.fixture
def calls(monkeypatch):
    out = []

    def get_json(url, params=None, fresh=False):
        out.append(params)
        if params.get("list") == "categorymembers":
            return WALK.get(f"{params['cmtitle']}|{params['cmtype']}|{params.get('cmcontinue', '')}", EMPTY)
        assert params["prop"] == "revisions|categories" and len(params["titles"].split("|")) <= hs.BATCH
        return load_fixture("hewiki_albums_pages.json")

    monkeypatch.setattr(hs.http, "get_json", get_json)
    return out


def test_the_walk_skips_compilation_and_live_categories_and_follows_continuations(calls):
    titles, walked = ha.album_pages()
    assert walked[:3] == [ha.ROOT_CATEGORY, "קטגוריה:מיני-אלבומים בעברית", "קטגוריה:אלבומי כוורת"]
    assert not any(c.get("cmtitle") == "קטגוריה:אלבומי אוסף בעברית" for c in calls)
    assert "הלב הזה, המשומש" in titles  # from the continued answer
    assert titles == sorted(set(titles)) and len(titles) == 11


@pytest.mark.parametrize("category, skipped", [
    ("קטגוריה:אלבומי אוסף בעברית", True), ("קטגוריה:אלבומי הופעה של משינה", True),
    ("קטגוריה:מיקסטייפים בעברית", True), ("קטגוריה:אלבומי כוורת", False),
])
def test_skip_categories(category, skipped):
    assert bool(ha.SKIP_CATEGORY_RE.match(category)) is skipped


def test_collect_reports_every_page(calls, monkeypatch):
    monkeypatch.setattr(ha, "album_pages", lambda: (sorted(PAGES), [ha.ROOT_CATEGORY]))
    rows, report, albums = ha.collect()
    assert report["pages"] == 21 and sum(map(len, report["reasons"].values())) == 21
    assert list(report["reasons"]) == list(ha.REASONS)
    assert len(report["reasons"][ha.CANDIDATE]) == 11
    assert report["forms"] == {ha.TEMPLATE: 6, ha.NUMBERED: 2, ha.TABLE: 2, ha.BULLETED: 1}
    assert all(r["source"] == ha.SOURCE and r["extended"] for r in rows)
    assert len(albums) == 20  # every page but the redirect


# --- Round 2: foreign-language, children's and instrumental albums give no rows -------------------

def album_page(categories, tracks, box_extra="", title="אלבום"):
    lines = "\n".join(f"# {t}" for t in tracks)
    hebrew = [] if any(ha._LANGUAGE_CATEGORY_RE.match(c) for c in categories) else ["קטגוריה:אלבומים בעברית"]
    return {"categories": [*hebrew, "קטגוריה:אלבומי 1985", *categories],
            "wikitext": "{{אלבום\n| מאת = [[זמר]]\n" + box_extra + "}}\n== רשימת השירים ==\n" + lines + "\n"}


HEBREW = ["שיר אחד", "שיר שני", "שיר שלישי"]


@pytest.mark.parametrize("categories, tracks, box_extra, title, reason", [
    ([], HEBREW, "", "אלבום", ha.CANDIDATE),
    (["קטגוריה:אלבומים ביידיש"], HEBREW, "", "אלבום", ha.FOREIGN),  # Yiddish in Hebrew letters
    (["קטגוריה:אלבומים באנגלית"], HEBREW, "", "אלבום", ha.FOREIGN),
    (["קטגוריה:מיני-אלבומים בערבית"], HEBREW, "", "אלבום", ha.FOREIGN),
    (["קטגוריה:אלבומים בהוצאה עצמית"], HEBREW, "", "אלבום", ha.CANDIDATE),  # self-released, not a language
    # a Hebrew album with a song in Arabic: its Hebrew songs stay
    (["קטגוריה:אלבומים בעברית", "קטגוריה:אלבומים בערבית"], HEBREW, "", "אלבום", ha.CANDIDATE),
    ([], ["Song One", "Song Two", "שיר"], "", "אלבום", ha.FOREIGN),  # mostly Latin titles
    (["קטגוריה:אלבומי מוזיקה לילדים"], HEBREW, "", "אלבום", ha.CHILDREN),
    (["קטגוריה:קלטות ילדים ישראליות"], HEBREW, "", "אלבום", ha.CHILDREN),
    ([], HEBREW, "", "אילנית בשירי חגים לילדים", ha.CHILDREN),
    ([], HEBREW, "", "הגדה של פסח", ha.CHILDREN),
    ([], HEBREW, "", "פסטיגל 1987", ha.CHILDREN),
    ([], HEBREW, "", "לא לילדים", ha.CANDIDATE),
    (["קטגוריה:אלבומי איפה הילד?"], HEBREW, "", "אלבום", ha.CANDIDATE),  # a band's name, not children
    (["קטגוריה:אלבומי ג'אז"], HEBREW, "", "אלבום", ha.INSTRUMENTAL),
    ([], HEBREW, "| סוגה = [[מוזיקה אינסטרומנטלית]]\n", "אלבום", ha.INSTRUMENTAL),
    ([], HEBREW, "| סוגה = אינסטרומנטלי, רוק\n", "אלבום", ha.INSTRUMENTAL),
    ([], HEBREW, "| סוגה = [[ג'אז]]\n", "אלבום", ha.INSTRUMENTAL),
    ([], HEBREW, "| סוגה = [[מוזיקת רוק|רוק]], [[ג'אז]]\n", "40:06", ha.CANDIDATE),  # a sung album with jazz in it
    (["קטגוריה:אלבומי ג'אז"], HEBREW, "| סוגה = פופ ישראלי ג'אז ווקאלי\n", "אלבום", ha.CANDIDATE),
    ([], HEBREW, "", "ירוסלב מנגן שלום חנוך", ha.INSTRUMENTAL),
    ([], HEBREW, "", "ממש עכשיו - מנגנים יוני רכטר", ha.INSTRUMENTAL),
])
def test_albums_that_are_not_hebrew_songs(categories, tracks, box_extra, title, reason):
    out = ha.parse_page(title, album_page(categories, tracks, box_extra, title))
    assert out["reason"] == reason
    if reason != ha.CANDIDATE:
        assert out["rows"] == [] and out["album"]["kind"] == reason


def test_a_latin_titled_track_of_a_hebrew_album_gives_no_row():
    out = parsed("השטרודל האנושי")  # 14 tracks, 3 of them with English titles
    titles = [r["title"] for r in out["rows"]]
    assert "Big Big Blue Wave" not in titles and len(titles) == 11 and out["latin"] == 3
    assert "Big Big Blue Wave" in out["album"]["tracks"]  # famous_acts still matches Deezer's titles against it
