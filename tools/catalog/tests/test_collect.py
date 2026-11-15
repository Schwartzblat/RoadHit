import csv

import pytest

from conftest import load_fixture
from roadhit_catalog import collect_billboard as bb
from roadhit_catalog import collect_hewiki as hw
from roadhit_catalog import collect_uk as uk
from roadhit_catalog.candidates import (
    find_gaps,
    load_classics,
    merge_candidates,
)
from roadhit_catalog.keys import song_key


def wikitext(name):
    return load_fixture(name)["parse"]["wikitext"]


def html(name):
    return load_fixture(name)["parse"]["text"]


def by_year(rows):
    out = {}
    for row in rows:
        out.setdefault(row["chart_year"], []).append(row)
    return out


# --- Brief test 1: the ה'תש"ל–ה'תשל"ט page --------------------------------------


def core(rows):
    """Today's rows: the top 20 of each year's main charts (not the extended rest)."""
    return [r for r in rows if not r["extended"]]


def test_hebrew_1970s_page_yields_ten_years_of_twenty_rows():
    rows = core(hw.parse_il_page(wikitext("hewiki_il_1970s.json")))
    years = by_year(rows)
    assert sorted(years) == list(range(1970, 1980))
    assert all(len(year_rows) == 20 for year_rows in years.values())
    assert all(r["origin"] == "IL" and r["source"] == "hewiki-il" for r in rows)


def test_hebrew_rows_are_clean_title_and_artist():
    rows = hw.parse_il_page(wikitext("hewiki_il_1970s.json"))
    first_1970 = [r for r in rows if r["chart_year"] == 1970 and r["position"] == 1]
    titles = {r["title"]: r["artist"] for r in first_1970}
    # Kol Israel's and Galei Tzahal's number ones are both kept at position 1.
    assert titles["פתאום עכשיו, פתאום היום"] == "שלמה ארצי"
    assert titles["למה לי לקחת ללב"] == "אריק איינשטיין ושלום חנוך"
    for r in rows:
        for text in (r["title"], r["artist"]):
            assert text and text == text.strip()
            assert not any(mark in text for mark in ("[[", "]]", "{{", "}}", "'''", "<ref"))
            assert text[0] != '"' and text[-1] != '"', r  # inner gershayim (נח"ל) are fine
        assert not r["artist"].endswith(")"), r  # lyricist/composer credits stripped
        assert not r["title"].rstrip(".").isdigit(), r  # no positions as titles


# --- Multi-chart years: union rule and chart choice --------------------------------


def entry(position, title, artist):
    return hw.Entry(position, title, artist, artist)


def test_union_takes_distinct_songs_by_best_position_up_to_limit():
    chart_a = [entry(1, "A", "x"), entry(2, "B", "x"), entry(3, "C", "x")]
    chart_b = [entry(1, "B", "x"), entry(2, "D", "x"), entry(3, "E", "x")]
    top = hw.union_top([chart_a, chart_b], limit=4)
    assert [(e.position, e.title) for e in top] == [(1, "A"), (1, "B"), (2, "D"), (3, "C")]


def test_union_keys_on_title_and_artist():
    chart_a = [entry(1, "שיר", "זמרת א"), entry(2, "נחכה לך", "נתן גושן וישי ריבו")]
    chart_b = [entry(1, "שיר", "להקה ב"), entry(2, "נחכה לך", "ישי ריבו ונתן גושן")]
    top = hw.union_top([chart_a, chart_b], limit=10)
    # Same title by different artists: two songs. Same song, credits reordered: one.
    assert [(e.title, e.artist) for e in top] == [
        ("שיר", "זמרת א"), ("שיר", "להקה ב"), ("נחכה לך", "נתן גושן וישי ריבו")
    ]


@pytest.mark.parametrize(
    "label, accepted",
    [
        ("'''המצעד השנתי של רשת ג':'''", True),
        ("'''המצעד השנתי של גלגלצ ואתר וואלה:'''", True),
        ("במצעד השנתי של כאן גימל זכתה נטע ברזילי בתואר", True),
        ("הדירוג של גלי צה\"ל לשנה זו:", True),
        ("'''המצעד השנתי של מאקו ותחנות הרדיו האזוריות:'''", False),
        ("במצעד כאן מורשת השנתי שנערך לראשונה בתחנה", False),
        ("'''המצעד השנתי של מדיה פורסט:'''", False),
        ("'''המצעד השנתי של וואלה:'''", False),
        ("'''המצעד השנתי של מאקו ורדיו תל אביב''' ... שזכו גם בגלגלצ", False),
    ],
)
def test_only_reshet_gimel_and_galgalatz_charts_are_used(label, accepted):
    assert hw.is_main_chart(label) is accepted


def test_multi_chart_years_on_the_2010s_page():
    rows = core(hw.parse_il_page(wikitext("hewiki_il_2010s_part.json")))
    years = by_year(rows)
    # The decade chart at the end of the page is not a year.
    assert sorted(years) == [2010, 2017, 2018]
    assert all(len(v) == 20 for v in years.values())
    titles_2018 = {r["title"] for r in years[2018]}
    assert {"Toy", "שני משוגעים"} <= titles_2018
    # "Toy" is Kan Gimel's #1 and "שני משוגעים" Galgalatz's #1.
    assert {r["position"] for r in years[2018] if r["title"] in {"Toy", "שני משוגעים"}} == {1}


def test_2020s_page_skips_airplay_and_other_station_charts():
    rows = core(hw.parse_il_page(wikitext("hewiki_il_2020s_part.json")))
    years = by_year(rows)
    assert sorted(years) == [2021, 2026]
    assert all(len(v) == 20 for v in years.values())
    titles_2021 = {r["title"] for r in years[2021]}
    assert "בית משוגעים" in titles_2021  # #1 of Kan Gimel and of Galgalatz


def year_charts(name, year):
    return [c for c in hw.parse_charts(wikitext(name), hw._IL_YEAR_RE)
            if hw.hebrew_year_to_gregorian(hw._IL_YEAR_RE.match(c.year_label).group(1)) == year]


@pytest.mark.parametrize("name", ["hewiki_il_1970s.json", "hewiki_il_2010s_part.json", "hewiki_il_2020s_part.json"])
def test_whole_charts_start_with_todays_top_twenty_unchanged(name):
    rows = hw.parse_il_page(wikitext(name))
    for year, year_rows in by_year(rows).items():
        main = [c.entries for c in year_charts(name, year)
                if len(c.entries) >= hw.MIN_CHART_SONGS and hw.is_main_chart("\n".join(c.label))]
        today = hw.union_top(main, hw.IL_LIMIT)
        assert [(r["title"], r["artist"], r["position"]) for r in year_rows[:20]] == [
            (e.title, e.artist, e.position) for e in today]
        assert [r["extended"] for r in year_rows] == [False] * 20 + [True] * (len(year_rows) - 20)


@pytest.mark.parametrize("name, year", [("hewiki_il_2020s_part.json", 2021), ("hewiki_il_2020s_part.json", 2026),
                                        ("hewiki_il_2010s_part.json", 2018), ("hewiki_il_1970s.json", 1979)])
def test_whole_charts_take_every_song_of_every_full_chart_once(name, year):
    rows = by_year(hw.parse_il_page(wikitext(name)))[year]
    charts = [c for c in year_charts(name, year) if len(c.entries) >= hw.MIN_CHART_SONGS]
    for chart in charts:
        for e in chart.entries:
            assert any(hw.same_song(e.title, e.artist, r["title"], r["artist"]) for r in rows), e
    for i, a in enumerate(rows):
        assert not any(hw.same_song(a["title"], a["artist"], b["title"], b["artist"]) for b in rows[i + 1:]), a
    # The main charts' songs come first, then the other stations' songs not already there.
    main = [c for c in charts if hw.is_main_chart("\n".join(c.label))]
    in_main = [any(hw.same_song(r["title"], r["artist"], e.title, e.artist) for c in main for e in c.entries)
               for r in rows]
    assert in_main == sorted(in_main, reverse=True)


def test_whole_charts_of_2021_hold_more_than_the_two_main_charts():
    rows = by_year(hw.parse_il_page(wikitext("hewiki_il_2020s_part.json")))[2021]
    main_songs = sum(1 for r in rows if r["position"] <= 40)
    assert len(rows) > 40 and main_songs >= 40
    # An other-station-only song keeps its own chart position.
    other = [r for r in rows if not any(
        hw.same_song(r["title"], r["artist"], e.title, e.artist)
        for c in year_charts("hewiki_il_2020s_part.json", 2021) if hw.is_main_chart("\n".join(c.label))
        for e in c.entries)]
    assert other and all(1 <= r["position"] <= 30 for r in other)


def test_a_chart_under_ten_songs_is_not_taken():
    rows = by_year(hw.parse_il_page(wikitext("hewiki_il_2020s_part.json")))[2026]
    short = [c for c in year_charts("hewiki_il_2020s_part.json", 2026) if len(c.entries) < hw.MIN_CHART_SONGS]
    assert short
    full = [c for c in year_charts("hewiki_il_2020s_part.json", 2026) if len(c.entries) >= hw.MIN_CHART_SONGS]
    only_short = [e for e in short[0].entries if not any(
        hw.same_song(e.title, e.artist, f.title, f.artist) for c in full for f in c.entries)]
    for e in only_short:
        assert not any(hw.same_song(e.title, e.artist, r["title"], r["artist"]) for r in rows)


def test_hebrew_year_to_gregorian():
    assert hw.hebrew_year_to_gregorian('תשכ"ג') == 1963
    assert hw.hebrew_year_to_gregorian('תש"ל') == 1970
    assert hw.hebrew_year_to_gregorian('תשמ"ה') == 1985
    assert hw.hebrew_year_to_gregorian('תש"ס') == 2000
    assert hw.hebrew_year_to_gregorian('תשפ"ה') == 2025
    assert hw.hebrew_year_to_gregorian('ה\'תש"ף') == 2020


def test_decade_pages_are_found_from_the_main_page_links():
    links = load_fixture("hewiki_il_main_links.json")["parse"]["links"]
    pages = hw.decade_pages(links, hw.IL_MAIN)
    assert len(pages) == 7
    assert pages[0] == 'מצעד הפזמונים העברי השנתי (ה\'תשכ"ג–ה\'תשכ"ט)'
    assert pages[-1] == 'מצעד הפזמונים העברי השנתי (ה\'תש"ף ואילך)'


# --- Hebrew Wikipedia's international chart ----------------------------------------


def test_intl_rows_are_intl_unless_listed_from_a_mixed_chart():
    page = wikitext("hewiki_intl_1960s_part.json")
    rows = hw.parse_intl_page(page)
    assert {r["chart_year"] for r in rows} == {1965}
    sway = [r for r in rows if r["title"] == "Sway"]
    assert sway and sway[0]["artist"] == "קליף ריצ'רד" and sway[0]["position"] == 1
    assert all(r["origin"] == "INTL" for r in rows)  # Hebrew script alone proves nothing
    assert all(r["source"] == "hewiki-intl" for r in rows)

    listed = hw.load_il_from_mixed()
    rows = hw.parse_intl_page(page, il_from_mixed=listed)
    origins = {r["title"]: r["origin"] for r in rows}
    assert origins["איילת החן"] == "IL" and origins["סימן שאתה צעיר"] == "IL"
    assert origins["Sway"] == "INTL"


def test_listed_song_outside_the_mixed_chart_years_stays_intl():
    listed = {(hw.normalize("פופקורן"), hw.normalize("הוט באטר"), 1972)}
    page = '=== 1972 ===\nהמצעד:\n' + "\n".join(
        ['* 1. [[Hot Butter|הוט באטר]] – "[[פופקורן (מנגינה)|פופקורן]]"']
        + [f'* {n}. Artist {n} – "Song {n}"' for n in range(2, 12)]
    )
    rows = hw.parse_intl_page(page, il_from_mixed=listed)
    assert rows[0]["title"] == "פופקורן" and rows[0]["origin"] == "INTL"
    assert (rows[0]["title_link"], rows[0]["artist_link"]) == ("פופקורן (מנגינה)", "Hot Butter")


def test_il_from_mixed_charts_csv_covers_only_1962_to_1968():
    with hw.IL_FROM_MIXED_CSV.open(encoding="utf-8") as f:
        lines = list(csv.DictReader(f))
    assert lines and all(1962 <= int(line["chart_year"]) <= 1968 for line in lines)


def test_intl_1970s_artist_first_and_decade_chart_excluded():
    rows = hw.parse_intl_page(wikitext("hewiki_intl_1970s_part.json"))
    assert {r["chart_year"] for r in rows} == {1970}
    assert len(rows) == 23  # 23 entries that year (two shared places)
    first = [r for r in rows if r["position"] == 1][0]
    assert (first["title"], first["artist"]) == ("Let It Be", "הביטלס")
    tied = {r["title"]: r["position"] for r in rows if r["position"] == 14}
    assert len(tied) == 2


def test_intl_2010s_numbered_list_with_quoted_title_first():
    rows = hw.parse_intl_page(wikitext("hewiki_intl_2010s_part.json"))
    assert {r["chart_year"] for r in rows} == {2010}
    assert len(rows) == 25
    assert [r["position"] for r in rows] == list(range(1, 26))
    assert all('"' not in r["title"] for r in rows)


# --- Brief test 2: Billboard ------------------------------------------------------


def test_billboard_1985_year_end_yields_25_rows_careless_whisper_first():
    rows = bb.parse_year_end(html("enwiki_billboard_1985.json"), 1985)
    assert len(rows) == 25
    assert rows[0]["position"] == 1
    assert rows[0]["title"] == "Careless Whisper"
    assert rows[0]["artist"] == "George Michael"
    assert [r["position"] for r in rows] == list(range(1, 26))
    assert all(r["origin"] == "INTL" and r["chart_year"] == 1985 for r in rows)
    assert all(r["source"] == "billboard-year-end" for r in rows)
    assert not any('"' in r["title"] for r in rows)


def test_billboard_1946_rank_column_with_parenthesised_rank():
    rows = bb.parse_year_end(html("enwiki_billboard_1946.json"), 1946)
    assert len(rows) == 25
    assert (rows[0]["title"], rows[0]["artist"]) == ("Prisoner of Love", "Perry Como")
    assert rows[6]["artist"] == "Johnny Mercer and The Pied Pipers"


def test_billboard_numero_sign_position_header():
    page = (
        '<table class="wikitable"><tr><th>№</th><th>Title</th><th>Artist(s)</th></tr>'
        '<tr><td>1</td><td>"End of the Road"</td><td>Boyz II Men</td></tr></table>'
    )
    rows = bb.parse_year_end(page, 1992)
    assert [(r["position"], r["title"], r["artist"]) for r in rows] == [(1, "End of the Road", "Boyz II Men")]


def test_number_ones_1942_distinct_in_order_of_first_appearance():
    rows = bb.parse_number_ones(html("enwiki_numberones_1942.json"), 1942)
    assert rows[0]["title"] == "Chattanooga Choo Choo"
    assert rows[1]["title"] == "A String of Pearls"
    assert [r["position"] for r in rows] == list(range(1, len(rows) + 1))
    assert len({r["title"] for r in rows}) == len(rows)
    assert all(r["source"] == "billboard-number-ones" for r in rows)


def test_number_ones_1944_combined_song_and_artist_cell():
    rows = bb.parse_number_ones(html("enwiki_numberones_1944.json"), 1944)
    assert (rows[0]["title"], rows[0]["artist"]) == ("Paper Doll", "The Mills Brothers")
    # The best-selling retail column is used, not the juke box one (Shoo-Shoo Baby).
    assert rows[1]["title"] == "My Heart Tells Me (Should I Believe My Heart?)"
    assert len({r["title"] for r in rows}) == len(rows)


def test_year_end_title_is_picked_from_search_results():
    titles = [h["title"] for h in load_fixture("enwiki_search_1950.json")["query"]["search"]]
    assert bb.pick_year_end_title(titles, 1950) == "Billboard year-end top 30 singles of 1950"
    assert bb.pick_year_end_title(["Billboard year-end top 50 R&B singles of 1958"], 1958) is None


# --- Brief test 3 and merging -----------------------------------------------------


def row(title, artist, year, source, origin="INTL", position=1):
    return {
        "title": title,
        "artist": artist,
        "chart_year": year,
        "origin": origin,
        "source": source,
        "position": position,
    }


def test_duplicate_title_artist_across_sources_keeps_earliest_chart_year():
    merged = merge_candidates(
        [
            row("Careless Whisper", "George Michael", 1985, "billboard-year-end", position=1),
            row("careless whisper", "George Michael!", 1984, "hewiki-intl", position=3),
        ]
    )
    assert len(merged) == 1
    only = merged[0]
    assert only["chart_year"] == 1984
    assert only["position"] == 3
    assert only["source"] == "hewiki-intl"
    assert only["sources"] == ["billboard-year-end", "hewiki-intl"]
    assert only["key"] == song_key("Careless Whisper", "George Michael")


def hewiki_row(title, artist, year, artist_latin, title_latin=None, position=2):
    r = row(title, artist, year, "hewiki-intl", position=position)
    title_latin = title_latin or title
    r.update(title_latin=title_latin, artist_latin=artist_latin,
             needs_latin=artist_latin is None or title_latin is None)
    return r


def test_hebrew_credit_with_resolved_artist_merges_with_its_billboard_twin():
    merged = merge_candidates(
        [
            row("Careless Whisper", "George Michael", 1985, "billboard-year-end"),
            hewiki_row("Careless Whisper", "ג'ורג' מייקל", 1984, "George Michael"),
        ]
    )
    assert len(merged) == 1
    assert merged[0]["chart_year"] == 1984
    assert merged[0]["artist"] == "George Michael"  # the Latin credit is shown
    assert merged[0]["sources"] == ["billboard-year-end", "hewiki-intl"]
    assert merged[0]["needs_latin"] is False


def test_unresolved_hebrew_credit_is_kept_separate_with_needs_latin():
    merged = merge_candidates(
        [
            row("Careless Whisper", "George Michael", 1985, "billboard-year-end"),
            hewiki_row("Careless Whisper", "ג'ורג' מייקל", 1985, None),
        ]
    )
    assert len(merged) == 2
    hebrew = [c for c in merged if c["artist"] == "ג'ורג' מייקל"][0]
    assert hebrew["needs_latin"] is True and hebrew["artist_latin"] is None


def test_same_title_by_different_artists_across_sources_is_not_merged():
    merged = merge_candidates(
        [
            hewiki_row("Because Of You", "קלי קלרקסון", 2005, "Kelly Clarkson"),
            row("Because of You", "Ne-Yo", 2007, "billboard-year-end"),
            row("Let Me Love You", "Mario", 2005, "billboard-year-end"),
            hewiki_row("Let Me Love You", "ניו-יו", 2007, "Ne-Yo"),
        ]
    )
    assert len(merged) == 4


def test_rihanna_stay_ends_as_one_2013_candidate():
    from roadhit_catalog.latin import add_latin, parse_langlinks

    hebrew = hw.parse_intl_page(wikitext("hewiki_intl_2013.json"))
    english = parse_langlinks(load_fixture("hewiki_langlinks_2013.json"))
    hebrew = add_latin(hebrew, english)
    stay = [r for r in hebrew if r["title"] == "Stay"][0]
    assert stay["artist"] == "ריהאנה בשיתוף מיקי אקו" and stay["artist_latin"] == "Rihanna"
    billboard = bb.parse_year_end(html("enwiki_billboard_2013.json"), 2013)
    merged = merge_candidates(hebrew + billboard)
    stays = [c for c in merged if c["title_latin"] and c["title_latin"].lower() == "stay"]
    assert len(stays) == 1
    assert stays[0]["chart_year"] == 2013
    assert stays[0]["artist"] == "Rihanna featuring Mikky Ekko"
    assert stays[0]["sources"] == ["billboard-year-end", "hewiki-intl"]
    assert len({c["key"] for c in merged}) == len(merged)


def test_langlinks_follow_normalisation_and_redirects():
    from roadhit_catalog.latin import add_latin, parse_langlinks, strip_disambiguation

    response = {"query": {
        "normalized": [{"from": "ריהאנה_", "to": "ריהאנה"}],
        "redirects": [{"from": "ריהנה", "to": "ריהאנה"}],
        "pages": [{"title": "ריהאנה", "langlinks": [{"lang": "en", "title": "Rihanna"}]},
                  {"title": "פופקורן (מנגינה)", "langlinks": [{"lang": "en", "title": "Popcorn (instrumental)"}]},
                  {"title": "אמן עלום"}]}}  # fmt: skip
    english = parse_langlinks(response)
    assert english["ריהנה"] == "Rihanna" and english["אמן עלום"] is None
    assert strip_disambiguation("Popcorn (instrumental)") == "Popcorn"
    rows = add_latin([{**row("פופקורן", "הוט באטר", 1972, "hewiki-intl"),
                       "title_link": "פופקורן (מנגינה)", "artist_link": "Hot Butter"}], english)
    assert (rows[0]["title_latin"], rows[0]["artist_latin"], rows[0]["needs_latin"]) == (
        "Popcorn", "Hot Butter", False)


def test_same_title_by_different_artists_in_one_source_stays_separate():
    merged = merge_candidates(
        [
            row("The Gypsy", "The Ink Spots", 1946, "billboard-year-end", position=3),
            row("The Gypsy", "Dinah Shore", 1946, "billboard-year-end", position=9),
        ]
    )
    assert len(merged) == 2


def test_same_title_years_apart_across_sources_stays_separate():
    merged = merge_candidates(
        [
            row("Hello", "Lionel Richie", 1984, "billboard-year-end"),
            row("Hello", "אדל", 2015, "hewiki-intl"),
        ]
    )
    assert len(merged) == 2


def test_candidates_are_sorted_and_have_exactly_the_output_fields():
    merged = merge_candidates(
        [
            row("B", "x", 1990, "billboard-year-end", position=2),
            row("שיר", "זמר", 1980, "hewiki-il", origin="IL", position=5),
            row("A", "x", 1990, "billboard-year-end", position=1),
        ]
    )
    assert [(c["origin"], c["chart_year"], c["position"]) for c in merged] == [
        ("IL", 1980, 5),
        ("INTL", 1990, 1),
        ("INTL", 1990, 2),
    ]
    assert set(merged[0]) == {
        "key", "title", "artist", "credit", "chart_year", "origin", "source", "position",
        "sources", "title_latin", "artist_latin", "needs_latin", "extended",
    }
    il = merged[0]
    assert (il["credit"], il["title_latin"], il["artist_latin"], il["needs_latin"]) == ("זמר", None, None, False)
    assert not any(c["extended"] for c in merged)
    assert (merged[1]["title_latin"], merged[1]["artist_latin"]) == ("A", "x")  # Billboard: own text


def test_classics_csv_rows_become_il_candidates(tmp_path):
    path = tmp_path / "classics_il.csv"
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["title", "artist", "year", "source_url"])
        writer.writerow(["ערב של שושנים", "יפה ירקוני", "1957", "https://he.wikipedia.org/wiki/x"])
    classics = load_classics(path)
    assert classics == [row("ערב של שושנים", "יפה ירקוני", 1957, "classics", origin="IL", position=0)]
    merged = merge_candidates(classics + [row("Careless Whisper", "George Michael", 1985, "billboard-year-end")])
    il = [c for c in merged if c["origin"] == "IL"]
    assert il[0]["source"] == "classics" and il[0]["sources"] == ["classics"]
    assert il[0]["chart_year"] == 1957 and il[0]["position"] == 0


def test_real_classics_csv_is_well_formed():
    from roadhit_catalog.candidates import CLASSICS_CSV

    classics = load_classics(CLASSICS_CSV)
    assert 40 <= len(classics) <= 130
    assert all(1940 <= c["chart_year"] < 1963 for c in classics)
    assert len({song_key(c["title"], c["artist"]) for c in classics}) == len(classics)
    with CLASSICS_CSV.open(encoding="utf-8") as f:
        for line in csv.DictReader(f):
            assert line["source_url"].startswith("https://he.wikipedia.org/wiki/")
    assert "כושי כלב קט" not in {c["title"] for c in classics}


def test_every_classic_is_within_its_performers_active_years():
    from roadhit_catalog.candidates import CLASSICS_CSV, ROOT

    with (ROOT / "data" / "performers.csv").open(encoding="utf-8") as f:
        active_from = {line["performer"]: int(line["active_from"]) for line in csv.DictReader(f)}
    for c in load_classics(CLASSICS_CSV):
        assert c["artist"] in active_from, f"{c['artist']} missing from performers.csv"
        assert c["chart_year"] >= active_from[c["artist"]], c


def test_excluded_classics_keep_a_reason_and_do_not_overlap():
    from roadhit_catalog.candidates import CLASSICS_CSV, ROOT

    with (ROOT / "data" / "classics_il_excluded.csv").open(encoding="utf-8") as f:
        excluded = list(csv.DictReader(f))
    assert excluded and all(line["reason"].strip() for line in excluded)
    kept = {c["title"] for c in load_classics(CLASSICS_CSV)}
    assert not kept & {line["title"] for line in excluded}
    reasons = {line["title"]: line["reason"] for line in excluded}
    assert "offensive" in reasons["כושי כלב קט"]
    assert sum("before 1940" in r for r in reasons.values()) == 9


def test_rows_outside_1940_to_2026_fail_loudly():
    from roadhit_catalog.candidates import YearOutOfRange, check_years

    check_years([row("A", "x", 1940, "classics"), row("B", "x", 2026, "hewiki-il")])
    with pytest.raises(YearOutOfRange, match="1938 פטיש מסמר"):
        check_years([row("פטיש מסמר", "יפה ירקוני", 1938, "classics", origin="IL")])


def test_gaps_list_years_with_fewer_rows_than_expected():
    rows = [row("A", "x", 1990, "billboard-year-end")] * 25 + [row("B", "x", 1991, "billboard-year-end")] * 3
    gaps = find_gaps(rows, {"billboard-year-end": (range(1990, 1993), 25)})
    assert gaps == [("billboard-year-end", 1991, 3, 25), ("billboard-year-end", 1992, 0, 25)]


def test_intl_unquoted_lines_follow_the_charts_quoted_order_and_trailing_numbers():
    # Real lines from the 1997 section: some are 'artist – "title"', some are
    # unquoted with the place written after the title (an RTL typo).
    page = "\n".join(
        [
            "=== 1997 ===",
            "המצעד:",
            '* 1. [[אקווה (להקה)|אקווה]] – "[[Barbie Girl]]"',
            '* 2. צ\'ארלי דורה – "Time Goes Bye"',
            "* [[הנסון (להקה)|Hanson]] – [[MMMBop]] .3",
            "* [[מייקל ג'קסון|Michael Jackson]] – History .4",
        ]
        + [f'* {n}. Artist {n} – "Song {n}"' for n in range(5, 12)]
    )
    rows = hw.parse_intl_page(page)
    got = {r["position"]: (r["title"], r["artist"]) for r in rows}
    assert got[3] == ("MMMBop", "Hanson")
    assert got[4] == ("History", "Michael Jackson")


def test_links_inside_templates_keep_their_text():
    from roadhit_catalog.wikitext import clean

    assert clean('{{משמאל לימין|[[Electric Light Orchestra|ELO]]}}') == "ELO"
    assert clean('{{משמאל לימין|"[[Im Nin\'alu (שיר של עפרה חזה)|אם ננעלו]]"}}') == '"אם ננעלו"'
    assert clean("[[קובץ:X.jpg|שמאל|ממוזער|כיתוב [[קישור]]]]שיר") == "שיר"


def test_quotes_stay_balanced():
    from roadhit_catalog.wikitext import strip_quotes

    assert strip_quotes('"Love Theme From "The Godfather""') == 'Love Theme From "The Godfather"'
    assert strip_quotes('"מתחילים מחדש') == "מתחילים מחדש"  # dangling quote from a dash split
    assert strip_quotes('להקת הנח"ל') == 'להקת הנח"ל'
    assert hw.parse_item('נעימת הנושא של הסרט "Shaft" – אייזק הייז') == (
        'נעימת הנושא של הסרט "Shaft"', "אייזק הייז")
    assert bb._title('"Love Theme From "The Godfather""') == 'Love Theme From "The Godfather"'
    assert bb._title('"Hound Dog" / "Don\'t Be Cruel"') == "Hound Dog / Don't Be Cruel"


def test_role_text_moves_to_credit():
    rows = hw.parse_il_page(wikitext("hewiki_il_1970s.json"))
    shir = [r for r in rows if r["title"] == "שיר לשלום"][0]
    assert shir["artist"] == 'להקת הנח"ל'
    assert shir["credit"] == 'להקת הנח"ל, סולנית: מירי אלוני'
    assert not any("סולן" in r["artist"] for r in rows)
    assert hw.split_role("להקת פיקוד צפון, סולן:יגאל בשן") == "להקת פיקוד צפון"


def test_both_sides_quoted_follow_the_charts_order():
    page = "\n".join(
        ["=== 1989 ===", "המצעד:", '* 1. "אוולאנש" – "[[Johnny, Johnny Come Home]]"']
        + [f'* {n}. Artist {n} – "Song {n}"' for n in range(2, 12)]
    )
    rows = hw.parse_intl_page(page)
    assert (rows[0]["title"], rows[0]["artist"]) == ("Johnny, Johnny Come Home", "אוולאנש")


def test_inner_quotes_and_dangling_quotes_from_real_lines():
    assert hw.parse_item(
        'אנדי ויליאמס – "Love Theme From "The Godfather" (Speak Softly Love)"', "right"
    ) == ('Love Theme From "The Godfather" (Speak Softly Love)', "אנדי ויליאמס")
    assert hw.parse_item('"יא" 2" – טונה (איתי זבולון)') == ('יא" 2', "טונה")
    assert hw.parse_item('"Bomfunk Mc\'s – "Freestyler') == ("Freestyler", "Bomfunk Mc's")
    assert hw.parse_item('"תמיד אוהב אותי" (גרסת כיסוי) – ששון איפרם שאולוב') == (
        "תמיד אוהב אותי", "ששון איפרם שאולוב")


def test_tied_positions_with_a_letter_after_the_dot():
    rows = by_year(hw.parse_il_page(wikitext("hewiki_il_1970s.json")))[1976]
    tied = {r["title"]: r for r in rows if r["position"] == 25}
    assert set(tied) == {"מדינה קטנה", "וידוי"}
    assert tied["מדינה קטנה"]["artist"] == "כוורת"


def il_row(title, artist, year, source="hewiki-il", position=1, extended=False, credit=None, **extra):
    r = row(title, artist, year, source, origin="IL", position=position)
    r.update(extended=extended, credit=credit or artist, **extra)
    return r


def test_an_extended_row_never_changes_the_existing_row_of_its_song():
    merged = merge_candidates([
        il_row("שיר", "זמר", 1991, position=5, credit="זמר, סולן: מישהו"),
        il_row("שיר", "זמר", 1990, position=30, extended=True),  # earlier year, from the chart's tail
        il_row("שיר", "זמר", 1989, source="hewiki-songs", position=0, extended=True),
    ])
    (only,) = merged
    assert (only["chart_year"], only["position"], only["source"], only["credit"]) == (
        1991, 5, "hewiki-il", "זמר, סולן: מישהו")
    assert only["sources"] == ["hewiki-il", "hewiki-songs"]
    assert only["extended"] is False


def test_an_extended_chart_row_does_not_replace_a_hand_picked_row():
    merged = merge_candidates([
        il_row("שיר", "זמר", 1975, source="extras", position=0),
        il_row("שיר", "זמר", 1974, position=25, extended=True),
    ])
    (only,) = merged
    assert (only["chart_year"], only["source"], only["extended"]) == (1975, "extras", False)
    assert only["sources"] == ["extras", "hewiki-il"]


def test_two_extended_rows_merge_as_rows_always_did():
    merged = merge_candidates([
        il_row("שיר", "זמר", 1991, position=25, extended=True),
        il_row("שיר", "זמר", 1990, source="hewiki-songs", position=0, extended=True),
    ])
    (only,) = merged
    assert (only["chart_year"], only["source"], only["extended"]) == (1990, "hewiki-songs", True)


def test_an_extended_israeli_row_of_an_international_song_leaves_it_as_it_was():
    intl = row("Flowers", "Miley Cyrus", 2023, "billboard-year-end", position=1)
    merged = merge_candidates([intl, il_row("Flowers", "Miley Cyrus", 2023, position=12, extended=True)])
    (only,) = merged
    assert (only["origin"], only["source"], only["sources"], only["extended"]) == (
        "INTL", "billboard-year-end", ["billboard-year-end"], False)


# --- Stage B: album rows (hewiki-albums, famous-acts) ------------------------------------------


def test_album_rows_carry_their_album_and_other_rows_do_not_gain_the_field():
    merged = merge_candidates([
        il_row("שיר", "זמר", 1975, source="hewiki-albums", position=0, extended=True, album="אלבום"),
        il_row("אחר", "זמר", 1975, position=3),
    ])
    by_title = {c["title"]: c for c in merged}
    assert by_title["שיר"]["album"] == "אלבום"
    assert "album" not in by_title["אחר"] and "deezer_hint" not in by_title["אחר"]


@pytest.mark.parametrize("sources, winner", [
    (("hewiki-albums", "hewiki-songs"), "hewiki-songs"),
    (("hewiki-albums", "hewiki-il"), "hewiki-il"),
    (("famous-acts", "hewiki-albums"), "hewiki-albums"),
])
def test_an_album_row_loses_a_merge_to_a_chart_or_article_row_even_when_earlier(sources, winner):
    rows = [il_row("שיר", "זמר", 1970 + i * 5, source=s, position=0, extended=True,
                   **({"album": f"אלבום {i}"} if s in ("hewiki-albums", "famous-acts") else {}))
            for i, s in enumerate(sources)]
    (only,) = merge_candidates(rows)
    assert only["source"] == winner and only["sources"] == sorted(sources)
    assert only["chart_year"] == 1975


def test_two_famous_rows_of_one_song_keep_the_earlier_album_and_its_track():
    (only,) = merge_candidates([
        il_row("שיר", "זמר", 1985, source="famous-acts", position=0, extended=True, album="מאוחר", deezer_hint=2),
        il_row("שיר", "זמר", 1975, source="famous-acts", position=0, extended=True, album="מוקדם", deezer_hint=1),
    ])
    assert (only["chart_year"], only["album"], only["deezer_hint"]) == (1975, "מוקדם", 1)


def test_an_album_page_row_that_absorbs_a_famous_acts_row_keeps_its_deezer_track():
    (only,) = merge_candidates([
        il_row("שיר", "זמר", 1975, source="famous-acts", position=0, extended=True, album="אלבום", deezer_hint=7),
        il_row("שיר", "זמר", 1975, source="hewiki-albums", position=0, extended=True, album="אלבום"),
    ])
    assert (only["source"], only["deezer_hint"]) == ("hewiki-albums", 7)
    (chart,) = merge_candidates([
        il_row("שיר", "זמר", 1975, source="famous-acts", position=0, extended=True, album="אלבום", deezer_hint=7),
        il_row("שיר", "זמר", 1975, position=30, extended=True),
    ])
    assert "deezer_hint" not in chart  # a chart row is matched by search as always


# --- Stage C: international charts whole for 1950–1990 ---------------------------------------


def test_billboard_year_end_whole_list_takes_every_row():
    rows = bb.parse_year_end(html("enwiki_billboard_1985_whole.json"), 1985, limit=None)
    assert len(rows) == 100
    assert (rows[25]["title"], rows[25]["artist"], rows[25]["position"]) == ("Cool It Now", "New Edition", 26)
    assert (rows[-1]["title"], rows[-1]["artist"], rows[-1]["position"]) == ("Sugar Walls", "Sheena Easton", 100)


@pytest.mark.parametrize("name, year, count", [
    ("enwiki_billboard_1985_whole.json", 1985, 100),
    ("enwiki_billboard_1958_whole.json", 1958, 51),  # a tie at 50: both rows
])
def test_billboard_whole_years_keep_todays_top_25_and_mark_the_rest_extended(name, year, count):
    page = html(name)
    rows = bb.year_end_rows(page, year)
    assert len(rows) == count
    today = bb.parse_year_end(page, year)
    assert [{k: r[k] for k in today[0]} for r in rows[:25]] == today
    assert [r["extended"] for r in rows] == [False] * 25 + [True] * (count - 25)


@pytest.mark.parametrize("year", [1946, 1949, 1991, 2013])
def test_billboard_years_outside_1950_to_1990_stay_at_their_top_25(year):
    rows = bb.year_end_rows(html("enwiki_billboard_1985_whole.json"), year)
    assert len(rows) == 25 and not any(r["extended"] for r in rows)


def test_billboard_whole_years_are_1950_to_1990():
    assert (bb.WHOLE_YEARS.start, bb.WHOLE_YEARS.stop) == (1950, 1991)


def test_intl_whole_years_take_every_song_after_todays_top_25():
    page = wikitext("hewiki_intl_1976.json")
    rows = hw.parse_intl_page(page)
    assert len(rows) == 40
    today = hw.parse_intl_page(page, whole_years=range(0))
    assert len(today) == 25 and not any(r["extended"] for r in today)
    assert rows[:25] == today
    assert [r["extended"] for r in rows] == [False] * 25 + [True] * 15
    last = rows[-1]
    assert (last["title"], last["artist"], last["position"], last["origin"]) == ("Sixteen Bar", "סטייליסטיקס", 40, "INTL")


def test_intl_whole_year_with_two_charts_takes_each_song_once():
    rows = by_year(hw.parse_intl_page(wikitext("hewiki_intl_1968_1969.json")))
    charts = [c for c in hw.parse_charts(wikitext("hewiki_intl_1968_1969.json"), hw._INTL_YEAR_RE)
              if c.year_label == "1969"]
    assert len(charts) == 2
    songs = rows[1969]
    for chart in charts:
        for e in chart.entries:
            assert sum(hw.same_song(e.title, e.artist, r["title"], r["artist"]) for r in songs) == 1, e
    assert len(songs) > 25 and [r["extended"] for r in songs[:25]] == [False] * 25
    assert all(r["extended"] for r in songs[25:])
    assert [r["title"] for r in songs[25:]] == ["Boom Pam", "Boom Bang a Bang", "Maxwell's Silver Hammer",
                                           "Michael and The Slipper Tree"]


def test_intl_mixed_chart_years_stay_at_their_top_25():
    rows = by_year(hw.parse_intl_page(wikitext("hewiki_intl_1968_1969.json"), il_from_mixed=hw.load_il_from_mixed()))
    assert len(rows[1968]) == 25 and not any(r["extended"] for r in rows[1968])  # 33 on the chart


def test_intl_years_after_1990_stay_at_their_top_25():
    rows = by_year(hw.parse_intl_page(wikitext("hewiki_intl_1990_1991.json")))
    assert len(rows[1990]) == 42 and sum(r["extended"] for r in rows[1990]) == 17
    assert len(rows[1991]) == 25 and not any(r["extended"] for r in rows[1991])


def test_intl_whole_years_are_1969_to_1990():
    assert (hw.WHOLE_INTL_YEARS.start, hw.WHOLE_INTL_YEARS.stop) == (1969, 1991)


# --- Stage C: the UK's year-end best-selling singles ("YYYY in British music") ---------------


def test_uk_year_end_takes_the_best_selling_singles_table_top_40():
    rows = uk.parse_year_end(html("enwiki_uk_1975.json"), 1975)
    assert len(rows) == 40
    assert [r["position"] for r in rows] == list(range(1, 41))
    assert (rows[0]["title"], rows[0]["artist"]) == ("Bye Bye Baby", "Bay City Rollers")
    assert (rows[21]["title"], rows[21]["artist"]) == ("Bohemian Rhapsody", "Queen")
    assert all(r["source"] == "uk-year-end" and r["origin"] == "INTL" and r["chart_year"] == 1975 for r in rows)
    assert all(r["extended"] for r in rows)  # every UK row is new
    assert not any('"' in r["title"] for r in rows)


def test_uk_year_end_double_sides_and_medleys_read_as_two_titles():
    rows = {r["position"]: r for r in uk.parse_year_end(html("enwiki_uk_1975.json"), 1975)}
    assert rows[19]["title"] == "Funky Moped / The Magic Roundabout"
    assert rows[25]["title"] == "The Way We Were / Try to Remember"


def test_uk_year_end_of_a_short_list_and_of_a_page_without_one():
    rows = uk.parse_year_end(html("enwiki_uk_1972.json"), 1972)
    assert len(rows) == 20 and rows[0]["position"] == 1
    assert uk.parse_year_end(html("enwiki_uk_1965.json"), 1965) == []  # no year-end list before 1970


def test_uk_collect_reads_1952_to_1990(monkeypatch):
    asked = []

    def parse_page(api, title, prop="wikitext"):
        asked.append(title)
        if title == "1975 in British music":
            return load_fixture("enwiki_uk_1975.json")["parse"]
        if title == "1953 in British music":
            raise uk.http.PageMissing(title)
        return load_fixture("enwiki_uk_1965.json")["parse"]

    monkeypatch.setattr(uk.http, "parse_page", parse_page)
    rows, pages = uk.collect()
    assert asked == [f"{y} in British music" for y in range(1952, 1991)]
    assert len(rows) == 40 and {r["chart_year"] for r in rows} == {1975}
    assert len(pages) == 38


@pytest.mark.parametrize("extended_row", [
    {**row("Knowing Me, Knowing You", "ABBA", 1977, "uk-year-end", position=17), "extended": True},  # same key
    {**row("Knowing Me Knowing You", "ABBA", 1977, "billboard-year-end", position=40), "extended": True},
])
def test_an_extended_row_does_not_change_which_existing_row_leads_a_merge(extended_row):
    existing = [
        row("Knowing Me, Knowing You", "ABBA", 1977, "extras", position=0),
        hewiki_row("Knowing Me Knowing You", "אבבא", 1977, "ABBA", position=22),
    ]
    (before,) = merge_candidates(existing)
    (after,) = merge_candidates(existing + [extended_row])
    assert before["source"] == "hewiki-intl"  # a chart row leads a hand-picked row
    assert {k: v for k, v in after.items() if k != "sources"} == {k: v for k, v in before.items() if k != "sources"}
    assert after["sources"] == sorted({*before["sources"], extended_row["source"]})
