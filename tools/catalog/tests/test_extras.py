"""Task 20b: hand-picked songs (data/extras.csv) and exclusions (data/exclude.txt)."""

import pytest

from roadhit_catalog import deezer, latin
from roadhit_catalog.candidates import (
    EXTRAS_CSV,
    ExtrasError,
    load_extras,
    merge_candidates,
)
from roadhit_catalog.keys import song_key
from test_deezer import FLOORS, LATIN_NAMES, FakeDeezer, candidate, matched_row

HEADER = "origin,title,artist,year,note\n"


def write_extras(tmp_path, *lines):
    path = tmp_path / "extras.csv"
    path.write_text(HEADER + "".join(line + "\n" for line in lines), encoding="utf-8")
    return path


def chart_row(title, artist, year, source, origin="INTL", position=5):
    return {"title": title, "artist": artist, "chart_year": year, "origin": origin, "source": source,
            "position": position}  # fmt: skip


# --- collect -----------------------------------------------------------------------


def test_an_extras_row_becomes_a_candidate_with_source_extras(tmp_path):
    path = write_extras(tmp_path, "IL,עוף גוזל,אריק איינשטיין,1987,", "INTL,Thriller,Michael Jackson,1982,a note")
    extras = load_extras(path)
    assert extras == [
        chart_row("עוף גוזל", "אריק איינשטיין", 1987, "extras", origin="IL", position=0),
        chart_row("Thriller", "Michael Jackson", 1982, "extras", position=0),
    ]
    merged = merge_candidates(extras)
    assert [(c["title"], c["source"], c["sources"], c["chart_year"], c["position"]) for c in merged] == [
        ("עוף גוזל", "extras", ["extras"], 1987, 0),
        ("Thriller", "extras", ["extras"], 1982, 0),
    ]
    thriller = merged[1]
    assert (thriller["title_latin"], thriller["artist_latin"], thriller["needs_latin"]) == (
        "Thriller", "Michael Jackson", False,
    )  # fmt: skip


def test_an_extras_row_with_the_same_key_merges_into_the_chart_row_whose_fields_stay(tmp_path):
    chart = chart_row("שוב", "שמוליק קראוס", 1977, "hewiki-il", origin="IL", position=4)
    extras = load_extras(write_extras(tmp_path, "IL,שוב,שמוליק קראוס,1976,"))
    for rows in ([chart, *extras], [*extras, chart]):
        (merged,) = merge_candidates(rows)
        assert merged["sources"] == ["extras", "hewiki-il"]
        assert (merged["source"], merged["chart_year"], merged["position"]) == ("hewiki-il", 1977, 4)
        assert merged["key"] == song_key("שוב", "שמוליק קראוס")


def test_an_extras_row_agreeing_with_a_chart_row_by_title_and_act_merges(tmp_path):
    chart = chart_row("Under Pressure", "Queen & David Bowie", 1982, "hewiki-intl", position=9)
    chart.update(title_latin="Under Pressure", artist_latin="Queen & David Bowie", needs_latin=False)
    extras = load_extras(write_extras(tmp_path, "INTL,Under Pressure,Queen,1981,"))
    (merged,) = merge_candidates([chart, *extras])
    assert merged["sources"] == ["extras", "hewiki-intl"]
    assert (merged["artist"], merged["chart_year"], merged["position"]) == ("Queen & David Bowie", 1982, 9)


@pytest.mark.parametrize(
    "line, problem",
    [
        ("XX,Thriller,Michael Jackson,1982,", "origin"),
        ("INTL,Thriller,Michael Jackson,198x,", "year"),
        ("INTL, ,Michael Jackson,1982,", "title"),
        ("INTL,Thriller,,1982,", "artist"),
    ],
)
def test_a_malformed_extras_row_fails_with_its_line_number(tmp_path, line, problem):
    path = write_extras(tmp_path, "IL,עוף גוזל,אריק איינשטיין,1987,", line)
    with pytest.raises(ExtrasError, match=rf"line 3\b.*{problem}"):
        load_extras(path)


def test_real_extras_csv_is_well_formed():
    extras = load_extras(EXTRAS_CSV)
    assert len(extras) >= 300
    assert len({song_key(r["title"], r["artist"]) for r in extras}) == len(extras)
    for r in extras:
        text = r["title"] + r["artist"]
        if r["origin"] == "IL":
            assert deezer.is_hebrew(r["title"]) and deezer.is_hebrew(r["artist"]), r
        else:
            assert latin.is_latin(text), r


# --- deezer ------------------------------------------------------------------------


def test_an_extras_row_below_the_floor_is_kept():
    row = matched_row(1982, "INTL", 500, source="extras")
    kept, dropped = deezer.apply_floor([row], FLOORS, keep=set())
    assert kept == [row] and dropped == []


def test_a_merged_chart_row_with_extras_in_its_sources_is_exempt():
    row = matched_row(1977, "IL", 500, source="hewiki-il", sources=["extras", "hewiki-il"])
    kept, dropped = deezer.apply_floor([row], FLOORS, keep=set())
    assert kept == [row] and dropped == []


def test_exclude_txt_lists_keys_with_reasons(tmp_path):
    path = tmp_path / "exclude.txt"
    path.write_text("# unfair clips\n\nabc123  # a 1984 re-recording\ndef456 # a remake\n", encoding="utf-8")
    assert deezer.load_exclude(path) == {"abc123": "a 1984 re-recording", "def456": "a remake"}


def test_an_excluded_key_without_a_reason_is_an_error(tmp_path):
    path = tmp_path / "exclude.txt"
    path.write_text("abc123\n", encoding="utf-8")
    with pytest.raises(deezer.ExclusionError, match="abc123"):
        deezer.load_exclude(path)


def test_an_excluded_key_is_dropped_before_matching():
    yesterday = candidate("Yesterday", "Paul McCartney", 1984)
    api = FakeDeezer()
    _, kept, dropped = deezer.run([yesterday], deezer.Matcher(api, LATIN_NAMES), FLOORS, set(),
                                  exclude={yesterday["key"]: "the 1984 re-recording"})  # fmt: skip
    assert kept == [] and api.queries == []
    (row,) = dropped
    assert row["key"] == yesterday["key"] and row["reason"] == "excluded"
    assert row["note"] == "the 1984 re-recording"


def test_an_excluded_key_that_is_not_a_candidate_is_an_error():
    yesterday = candidate("Yesterday", "Paul McCartney", 1984)
    with pytest.raises(deezer.ExclusionError, match="0123456789abcdef"):
        deezer.run([yesterday], deezer.Matcher(FakeDeezer(), LATIN_NAMES), FLOORS, set(),
                   exclude={"0123456789abcdef": "no such song"})  # fmt: skip


def test_real_exclude_file_loads():
    excluded = deezer.load_exclude(deezer.EXCLUDE_TXT)
    assert excluded and all(reason for reason in excluded.values())
