"""The duplicate/cover listing (``roadhit-catalog duplicates``), on a small made-up list."""

from roadhit_catalog import cli, duplicates


def row(key, title, artist, year, deezer_artist="X", sources=("hewiki-albums",), origin="IL"):
    return {"key": key, "title": title, "artist": artist, "credit": artist, "year": year, "origin": origin,
            "deezer_artist": deezer_artist, "sources": list(sources)}


ROWS = [
    row("a1", "שיר של אחרי מלחמה", "אריק איינשטיין", 1972, "Arik Einstein", ("hewiki-il",)),
    row("a2", "שיר של אחרי המלחמה", "גידי גוב", 1995, "Gidi Gov", ("famous-acts",)),  # one edit apart
    row("b1", "מה אומרות עינייך", "אריק איינשטיין", 1973, "Arik Einstein"),
    row("b2", "מה אומרות עינייך (שני אורות בליל)", "טיפקס", 1996, "Teapacks"),  # a bracketed part
    row("c1", "שדמתי", "הפרברים", 1966),
    row("c2", "שדמתי!", "הדודאים", 1967),  # the same after normalisation
    row("c3", "שדמתי", "עפרה חזה", 1984),
    row("d1", "לב", "זמר", 1980),
    row("d2", "לך", "זמרת", 1981),  # one edit apart, but too short to say anything
    row("e1", "Yesterday", "The Beatles", 1965, origin="INTL"),
    row("e2", "Yesterday", "זמר", 1990),  # only one Israeli row
    row("f1", "שיר יחיד", "זמר", 1990),
]


def test_groups_join_titles_that_normalise_alike_differ_by_one_edit_or_by_a_bracketed_part():
    groups = duplicates.find_groups(ROWS)
    assert [[r["key"] for r in g] for g in groups] == [["b1", "b2"], ["c1", "c2", "c3"], ["a1", "a2"]]


def test_a_group_lists_its_rows_by_year():
    groups = duplicates.find_groups([ROWS[1], ROWS[0]])
    assert [r["year"] for r in groups[0]] == [1972, 1995]


def test_the_report_names_year_credit_deezer_artist_and_source():
    text = duplicates.report(duplicates.find_groups(ROWS))
    assert "3 groups, 7 rows" in text
    assert "| a2 | שיר של אחרי המלחמה | גידי גוב | 1995 | Gidi Gov | famous-acts |" in text
    assert "| c3 | שדמתי | עפרה חזה | 1984 | X | hewiki-albums |" in text


def test_the_command_writes_work_duplicates_md(tmp_path, monkeypatch, capsys):
    import json

    (tmp_path / "dated.json").write_text(json.dumps(ROWS, ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(cli, "WORK_DIR", tmp_path)
    monkeypatch.setattr(cli, "DATED_JSON", tmp_path / "dated.json")
    assert cli.main(["duplicates"]) == 0
    assert "| b2 | מה אומרות עינייך (שני אורות בליל) | טיפקס | 1996 | Teapacks |" in (
        tmp_path / "duplicates.md"
    ).read_text(encoding="utf-8")
    assert "3 groups" in capsys.readouterr().out


# --- International rows (stage il-3000 C) --------------------------------------------------------

INTL_ROWS = [
    row("i1", "Bird Dog / Devoted to You", "The Everly Brothers", 1958, "The Everly Brothers",
        ("billboard-year-end",), origin="INTL"),
    row("i2", "Bird Dog", "האחים אברלי", 1958, "The Everly Brothers", ("hewiki-intl",), origin="INTL"),  # one side
    row("i3", "Yesterday", "The Beatles", 1965, origin="INTL"),
    {**row("i4", "אתמול", "הביטלס", 1966, origin="INTL"), "title_latin": "Yesterday"},  # its Latin title
    row("i5", "Kiss", "Prince", 1986, origin="INTL"),
    row("i6", "Kiss Me", "Sixpence None the Richer", 1999, origin="INTL"),  # another song
    *ROWS,
]


def test_international_groups_join_latin_titles_and_each_side_of_a_double_a_side():
    groups = duplicates.find_groups(INTL_ROWS, origin="INTL")
    assert [[r["key"] for r in g] for g in groups] == [["i1", "i2"], ["e1", "i3", "i4"]]


def test_israeli_groups_are_as_they_were():
    assert duplicates.find_groups(INTL_ROWS) == duplicates.find_groups(ROWS)


def test_the_command_lists_international_groups_after_the_israeli_ones(tmp_path, monkeypatch, capsys):
    import json

    (tmp_path / "dated.json").write_text(json.dumps(INTL_ROWS, ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(cli, "WORK_DIR", tmp_path)
    assert cli.main(["duplicates"]) == 0
    text = (tmp_path / "duplicates.md").read_text(encoding="utf-8")
    assert text.index("# Israeli rows") < text.index("# International rows")
    assert "| i2 | Bird Dog | האחים אברלי | 1958 | The Everly Brothers | hewiki-intl |" in text
    out = capsys.readouterr().out
    assert "3 groups, 7 Israeli rows" in out and "2 groups, 5 international rows" in out
