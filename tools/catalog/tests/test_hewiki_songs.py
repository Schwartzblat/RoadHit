import pytest

from conftest import load_fixture
from roadhit_catalog import hewiki_songs as hs
from roadhit_catalog.candidates import merge_candidates


def pages():
    out = {}
    hs.merge_page_batch(out, load_fixture("hewiki_songs_pages.json"))
    return out


def test_pages_of_one_answer_are_read_with_categories_and_wikitext():
    got = pages()
    assert len(got) == 12
    assert {"קטגוריה:סינגלים מ-1987", "קטגוריה:שירי 1987"} <= set(got["שלל שרב"]["categories"])
    assert got["שלל שרב"]["wikitext"].startswith("{{סינגל")


def test_a_continued_answer_adds_categories_to_the_same_page():
    answers = load_fixture("hewiki_songs_continued.json")
    out = {}
    hs.merge_page_batch(out, answers["first"])
    title = "א יידישע ראסטהמאן"
    assert hs.category_year(out[title]["categories"]) is None  # its year categories come in the continuation
    wikitext = out[title]["wikitext"]
    hs.merge_page_batch(out, answers["continued"])
    assert out[title]["wikitext"] == wikitext  # the continuation has no revisions: the text stays
    assert hs.category_year(out[title]["categories"]) == 1982
    assert "קטגוריה:דואטים" in out[title]["categories"]


@pytest.mark.parametrize("categories, year", [
    (["קטגוריה:סינגלים מ-1987", "קטגוריה:שירי 1987"], 1987),
    (["קטגוריה:שירי 1986", "קטגוריה:סינגלים מ-1985"], 1985),  # several: the earliest
    (["קטגוריה:סינגלי בכורה מ-2004", "קטגוריה:שירי 2004"], 2004),
    (["קטגוריה:שירי פסטיבל הזמר והפזמון 1969"], None),  # a festival, not a year category
    (["קטגוריה:שירי אהבה בעברית"], None),
])
def test_category_year(categories, year):
    assert hs.category_year(categories) == year


@pytest.mark.parametrize("page, title", [
    ("פראג (שיר)", "פראג"), ("17 (שיר של בן ארצי)", "17"), ("שלל שרב", "שלל שרב"),
])
def test_title_drops_the_bracketed_disambiguation(page, title):
    assert hs.song_title(page) == title


@pytest.mark.parametrize("page, performer", [
    ("עד שתבין אותי", "שירי מימון"),
    ("שלל שרב", "גידי גוב"),  # infobox, though the lead names the lyricist and composer first
    ("17 (שיר של בן ארצי)", "בן ארצי בהשתתפות שלמה ארצי"),
    ("פראג (שיר)", "אריק איינשטיין"),
])
def test_infobox_names_the_performer(page, performer):
    assert hs.infobox_performer(pages()[page]["wikitext"]) == performer


@pytest.mark.parametrize("page, performer", [
    ("שדות של אירוסים", "שלמה ארצי"),  # "שכתב, הלחין וביצע [[שלמה ארצי]]"
    ("דוד ושאול (שיר)", "אהוד בנאי"),  # "שיר מאלבומו השלישי של [[אהוד בנאי]]"
    ("את לא נשארת לבד", "עידן רייכל"),  # "הוקלט על ידי ה[[זמר]] וה[[מוזיקאי]] ה[[ישראלים|ישראלי]] [[עידן רייכל]]"
    ("קרן שמש מאוחרת", "שלום חנוך"),
    ("עובר מה שעובר", "אברהם פריד"),  # "[[הזמר החסידי]] ה[[אמריקאי]] [[אברהם פריד]]"; not the composer
])
def test_the_first_sentence_names_the_performer_when_the_infobox_does_not(page, performer):
    wikitext = pages()[page]["wikitext"]
    assert hs.sentence_performer(wikitext) == performer
    assert hs.page_performer(wikitext) == performer


def test_a_poem_names_no_performer():
    assert hs.page_performer(pages()["יום בשורה"]["wikitext"]) is None


@pytest.mark.parametrize("page, reason", [
    ("שלל שרב", hs.CANDIDATE),
    ("שחרחורת", hs.NO_YEAR),  # a Ladino folk song: no year category
    ("יום בשורה", hs.YEAR_OUT_OF_RANGE),  # a 1927 poem
    ("אם הייתי (שיר של ברי סחרוף)", hs.REDIRECT),
])
def test_page_verdicts(page, reason):
    assert hs.parse_page(page, pages()[page])[1] == reason


def test_a_candidate_row():
    row, _ = hs.parse_page("פראג (שיר)", pages()["פראג (שיר)"])
    assert {k: row[k] for k in ("title", "artist", "credit", "chart_year", "origin", "source", "extended")} == {
        "title": "פראג", "artist": "אריק איינשטיין", "credit": "אריק איינשטיין", "chart_year": 1969,
        "origin": "IL", "source": "hewiki-songs", "extended": True,
    }


def test_a_song_row_merges_into_the_chart_row_of_the_same_song():
    row, _ = hs.parse_page("שלל שרב", pages()["שלל שרב"])
    chart = {"title": "שלל שרב", "artist": "גידי גוב", "credit": "גידי גוב", "chart_year": 1987,
             "origin": "IL", "source": "hewiki-il", "position": 1}
    (only,) = merge_candidates([chart, row])
    assert only["source"] == "hewiki-il" and only["sources"] == ["hewiki-il", "hewiki-songs"]
    assert only["extended"] is False


# --- Fetching, offline: http.get_json answers from trimmed real answers ------------------------

WALK = load_fixture("hewiki_songs_walk.json")


def fake_get_json(calls):
    pages = load_fixture("hewiki_songs_pages.json")
    continued = load_fixture("hewiki_songs_continued.json")

    def get_json(url, params=None, fresh=False):
        calls.append(params)
        if params.get("list") == "categorymembers":
            return WALK[f"{params['cmtitle']}|{params['cmtype']}|{params.get('cmcontinue', '')}"]
        assert params["prop"] == "revisions|categories" and len(params["titles"].split("|")) <= hs.BATCH
        if "א יידישע ראסטהמאן" in params["titles"]:
            return continued["continued"] if "clcontinue" in params else continued["first"]
        return pages

    return get_json


@pytest.fixture
def calls(monkeypatch):
    out = []
    monkeypatch.setattr(hs.http, "get_json", fake_get_json(out))
    return out


def test_category_members_follow_the_continuation(calls):
    titles = hs.category_members(hs.ROOT_CATEGORY, "page")
    assert titles[:2] == ["עד שתבין אותי", "שלל שרב"] and len(titles) == 12
    assert [c.get("cmcontinue") for c in calls] == [None, "page|d790d7a320d790d797d7aa|1757880"]


def test_the_walk_skips_poem_categories_and_walks_each_category_once(calls):
    pages, walked = hs.song_pages()
    assert walked == [hs.ROOT_CATEGORY, "קטגוריה:שירי רוק בעברית", "קטגוריה:שירי כוורת"]
    assert not any(c.get("cmtitle") == "קטגוריה:פואמות בעברית" for c in calls)
    assert pages == sorted(set(pages)) and len(pages) == 16  # 12 + 3 + 2, one page listed twice
    assert "אם הייתי (שיר של ברי סחרוף)" in pages and "אופרת פוגי" in pages


def test_fetch_pages_batches_fifty_titles_and_follows_category_continuations(calls):
    got = hs.fetch_pages(["א יידישע ראסטהמאן"] + [f"דף {i}" for i in range(60)])
    assert [len(c["titles"].split("|")) for c in calls] == [50, 50, 11]
    assert calls[1]["clcontinue"].startswith("2576786|")
    assert hs.category_year(got["א יידישע ראסטהמאן"]["categories"]) == 1982
    assert "שלל שרב" in got


def test_collect_gives_rows_and_a_report_of_every_page(calls):
    rows, report = hs.collect()
    assert set(report) == {"categories", "pages", "reasons"} and report["pages"] == 16
    assert list(report["reasons"]) == list(hs.REASONS)
    assert sum(map(len, report["reasons"].values())) == 16
    assert {r["title"] for r in rows} >= {"שלל שרב", "פראג", "17"}
    assert report["reasons"][hs.CANDIDATE] == sorted(report["reasons"][hs.CANDIDATE])
    assert "יום בשורה" in report["reasons"][hs.YEAR_OUT_OF_RANGE]
    assert "אופרת פוגי" in report["reasons"][hs.NO_YEAR]  # not in the page answer: no categories
    assert all(r["source"] == hs.SOURCE and r["extended"] for r in rows)


# --- The first sentence's rules ----------------------------------------------------------------

LEAD = "\"'''ש'''\" הוא שיר "


@pytest.mark.parametrize("sentence, performer", [
    ("שביצעה, לאחר מותו, [[יפה ירקוני]].", None),  # a comma before the name stops the search
    ("שבוצע על ידי להקה שכתב [[אהוד מנור]].", None),  # a writer word before the name stops it
    ("שכתב [[נתן אלתרמן]] והלחין [[משה וילנסקי]].", None),  # writers only
    ("שביצע ה[[זמר]] ה[[ישראלים|ישראלי]] [[שלמה ארצי]].", "שלמה ארצי"),  # ה-prefixed links are skipped
    ("שביצעה ה[[להקה]] [[כוורת (להקה)|כוורת]].", "כוורת"),
])
def test_sentence_rules(sentence, performer):
    assert hs.sentence_performer(LEAD + sentence) == performer


def test_the_lyricist_named_first_is_not_the_performer():
    # "שכתב [[מאיר אריאל]], הלחין [[יהודה פוליקר]], וביצע [[גידי גוב]]"
    assert hs.sentence_performer(pages()["שלל שרב"]["wikitext"]) == "גידי גוב"


@pytest.mark.parametrize("infobox, performer", [
    ("{{שיר\n| מאת = [[אריק איינשטיין]]\n}}", "אריק איינשטיין"),  # the שיר infobox counts too
    ("{{סינגל\n| מאת = [[גלי עטרי]]<br />[[חלב ודבש]]\n}}", "גלי עטרי"),  # one name per line: the first
    ("{{סינגל\n| מאת =\n* [[גלי עטרי]]\n* [[חלב ודבש]]\n}}", "גלי עטרי"),  # a bulleted list: the first
    ("{{סינגל\n| מאת = [[שושנה דמארי]] <small>(1948)</small>\n}}", "שושנה דמארי"),
    ("{{סינגל\n| מאת = <small>(1965)</small>\n}}", None),
])
def test_infobox_lists(infobox, performer):
    assert hs.infobox_performer(infobox) == performer


def test_a_comma_list_in_the_infobox_stays_one_credit():
    # Decided: commas also join real duets ("דולי ופן, נועה קירל ופלד"), so a comma list is kept
    # whole and reviewed by hand; this one ("original, later performers") is excluded in data.
    wikitext = "{{סינגל\n| שם = הנערים הספנים\n| מאת = מרים פריש, [[אהלה הלוי]], [[עפרה חזה]]\n}}"
    assert hs.infobox_performer(wikitext) == "מרים פריש, אהלה הלוי, עפרה חזה"


def test_a_page_with_a_year_and_no_performer_is_no_performer():
    page = {"categories": ["קטגוריה:שירי 1965"], "wikitext": "\"'''ש'''\" הוא שיר עם שכתב [[נתן אלתרמן]]."}
    assert hs.parse_page("ש (שיר)", page) == (None, hs.NO_PERFORMER)
