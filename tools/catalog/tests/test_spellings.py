import json

import pytest

from roadhit_catalog import spellings
from roadhit_catalog.spellings import credit_of, load_mizrahi, load_spellings, title_of, work_lists


def write(directory, name, data):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / name).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")


def artist(he="שלמה ארצי", en="Shlomo Artzi", aliases=()):
    return {"he": he, "en": en, "aliases": list(aliases)}


def title(he="שיר", en="Shir", aliases=(), script="HE"):
    return {"he": he, "en": en, "aliases": list(aliases), "script": script}


def row(origin="IL", title="שיר", artist="שלמה ארצי", **extra):
    base = dict(
        key=f"k-{origin}-{title}-{artist}", title=title, artist=artist, credit=artist, origin=origin,
        title_latin=None, artist_latin=None, deezer_title="", deezer_artist="", year=2000,
    )  # fmt: skip
    base.update(extra)
    return base


# ---- loader ----

def test_loader_merges_every_artists_and_titles_file(tmp_path):
    write(tmp_path, "artists-a.json", {"A": artist()})
    write(tmp_path, "artists-b.json", {"B": artist()})
    write(tmp_path, "titles-x.json", {"k1": title()})
    write(tmp_path, "titles-y.json", {"k2": title()})
    result = load_spellings(tmp_path)
    assert result.errors == []
    assert set(result.artists) == {"A", "B"}
    assert set(result.titles) == {"k1", "k2"}


def test_duplicate_key_across_files_names_key_and_both_files(tmp_path):
    write(tmp_path, "artists-a.json", {"A": artist()})
    write(tmp_path, "artists-b.json", {"A": artist()})
    errors = load_spellings(tmp_path).errors
    assert len(errors) == 1
    assert "'A'" in errors[0] and "artists-a.json" in errors[0] and "artists-b.json" in errors[0]


def test_geresh_and_gershayim_are_converted_and_spaces_collapsed(tmp_path):
    write(tmp_path, "artists-a.json", {"A": artist(he="ג׳ו  נח״ל", aliases=["ג׳ו"])})
    result = load_spellings(tmp_path)
    assert result.errors == []
    assert result.artists["A"]["he"] == "ג'ו נח\"ל"
    assert result.artists["A"]["aliases"] == ["ג'ו"]


@pytest.mark.parametrize("bad_he", ["שִׁיר", "שיר (חי)", "shir", "שיר שלי!", "שיר.", ""])
def test_he_character_rules(tmp_path, bad_he):
    write(tmp_path, "titles-a.json", {"key1": title(he=bad_he)})
    errors = load_spellings(tmp_path).errors
    assert len(errors) == 1
    assert "titles-a.json" in errors[0] and "'key1'" in errors[0] and "he" in errors[0]


def test_he_allows_digits_apostrophe_quote_and_hyphen(tmp_path):
    write(tmp_path, "titles-a.json", {"k": title(he="99 בלונים נח\"ל ג'ו-בן")})
    assert load_spellings(tmp_path).errors == []


@pytest.mark.parametrize("bad_en", ["שיר", "Shir שיר", "", "  "])
def test_en_rules(tmp_path, bad_en):
    write(tmp_path, "artists-a.json", {"A": artist(en=bad_en)})
    errors = load_spellings(tmp_path).errors
    assert len(errors) == 1 and "artists-a.json" in errors[0] and "'A'" in errors[0] and "en" in errors[0]


@pytest.mark.parametrize(
    "aliases",
    [[""], ["x", "x"], ["שיר"], ["Shir"], ["  "]],
)
def test_alias_rules(tmp_path, aliases):
    write(tmp_path, "titles-a.json", {"key1": title(he="שיר", en="Shir", aliases=aliases)})
    errors = load_spellings(tmp_path).errors
    assert len(errors) == 1 and "titles-a.json" in errors[0] and "'key1'" in errors[0]
    assert "alias" in errors[0]


def test_title_script_must_be_he_or_en(tmp_path):
    write(tmp_path, "titles-a.json", {"k": title(script="FR")})
    errors = load_spellings(tmp_path).errors
    assert len(errors) == 1 and "script" in errors[0] and "'k'" in errors[0]


def test_missing_fields_and_unknown_fields_are_errors(tmp_path):
    write(tmp_path, "artists-a.json", {"A": {"he": "שלמה"}, "B": {**artist(), "extra": 1}})
    errors = load_spellings(tmp_path).errors
    assert len(errors) == 2
    assert any("'A'" in e and "en" in e for e in errors)
    assert any("'B'" in e and "extra" in e for e in errors)


def test_invalid_entries_are_left_out_and_every_error_is_reported(tmp_path):
    write(tmp_path, "artists-a.json", {"good": artist(), "bad1": artist(en="שיר"), "bad2": artist(he="x")})
    result = load_spellings(tmp_path)
    assert set(result.artists) == {"good"}
    assert len(result.errors) == 2


def test_malformed_json_and_wrong_shape_are_errors_not_tracebacks(tmp_path):
    (tmp_path / "artists-a.json").write_text("{not json", encoding="utf-8")
    write(tmp_path, "titles-a.json", [1, 2])
    errors = load_spellings(tmp_path).errors
    assert len(errors) == 2
    assert any("artists-a.json" in e for e in errors) and any("titles-a.json" in e for e in errors)


def test_missing_directory_is_empty(tmp_path):
    result = load_spellings(tmp_path / "nope")
    assert result.artists == {} and result.titles == {} and result.errors == []


def test_mizrahi_file(tmp_path):
    path = tmp_path / "m.txt"
    path.write_text("# comment\nזוהר ארגוב\n\n  עומר אדם  # inline\nנח׳\n", encoding="utf-8")
    assert load_mizrahi(path) == {"זוהר ארגוב", "עומר אדם", "נח'"}
    assert load_mizrahi(tmp_path / "missing.txt") == set()


# ---- work lists ----

def test_credit_and_title_of_rows():
    il = row("IL", title="שיר", artist="עומר אדם")
    assert credit_of(il) == "עומר אדם" and title_of(il) == "שיר"
    intl = row("INTL", title="שיר", artist="קולדפליי", title_latin="Yellow", artist_latin="Coldplay")
    assert credit_of(intl) == "Coldplay" and title_of(intl) == "Yellow"
    plain = row("INTL", title="Help!", artist="The Beatles")
    assert credit_of(plain) == "The Beatles" and title_of(plain) == "Help!"


def empty():
    return spellings.Spellings({}, {}, [])


def test_il_artist_list_has_latin_names_most_common_first_and_skips_hebrew():
    rows = [
        row(title="א", deezer_artist="Shlomo Artzi"),
        row(title="ב", deezer_artist="Artzi"),
        row(title="ג", deezer_artist="Artzi"),
        row(title="ד", deezer_artist="שלמה ארצי"),
        row(title="ה", deezer_artist=""),
    ]
    lists = work_lists(rows, empty())
    assert lists["artists-il.todo.json"] == [
        {"n": 1, "credit": "שלמה ארצי", "en_seen": ["Artzi", "Shlomo Artzi"], "songs": 5,
         "titles": ["א", "ב", "ג"]}
    ]


def test_intl_artist_list_has_hebrew_names_seen():
    rows = [
        row("INTL", title="Yellow", artist="קולדפליי", artist_latin="Coldplay"),
        row("INTL", title="Clocks", artist="קולדפליי", artist_latin="Coldplay"),
        row("INTL", title="Help!", artist="The Beatles"),
    ]
    lists = work_lists(rows, empty())
    assert lists["artists-intl.todo.json"] == [
        {"n": 1, "credit": "Coldplay", "he_seen": ["קולדפליי"], "songs": 2, "titles": ["Clocks", "Yellow"]},
        {"n": 2, "credit": "The Beatles", "he_seen": [], "songs": 1, "titles": ["Help!"]},
    ]


def test_artist_entries_list_at_most_three_titles_most_popular_first():
    rows = [row(title=t, popularity=p) for t, p in [("א", 1), ("ב", 4), ("ג", 3), ("ד", 2)]]
    entry = work_lists(rows, empty())["artists-il.todo.json"][0]
    assert entry["titles"] == ["ב", "ג", "ד"] and entry["songs"] == 4 and "example" not in entry


def test_credits_and_keys_with_spellings_are_left_out():
    done = row(title="א", artist="עומר אדם")
    todo = row(title="ב", artist="שלמה ארצי")
    sp = spellings.Spellings({"עומר אדם": artist()}, {done["key"]: title()}, [])
    lists = work_lists([done, todo], sp)
    assert [e["credit"] for e in lists["artists-il.todo.json"]] == ["שלמה ארצי"]
    assert [e["key"] for e in lists["titles-il.todo.json"]] == [todo["key"]]


def test_il_title_entries():
    r = row(title="שיר", artist="שלמה ארצי", year=1990, deezer_title="Shir Latin", deezer_artist="Shlomo Artzi")
    r2 = row(title="עוד", artist="שלמה ארצי", year=1991, deezer_title="עוד שיר (Live)", deezer_artist="")
    entries = work_lists([r2, r], empty())["titles-il.todo.json"]
    assert entries == [
        {"n": 1, "key": r2["key"], "title": "עוד", "artist": "שלמה ארצי", "year": 1991,
         "deezer_title": "עוד שיר (Live)", "deezer_artist": ""},
        {"n": 2, "key": r["key"], "title": "שיר", "artist": "שלמה ארצי", "year": 1990,
         "deezer_title": "Shir Latin", "deezer_artist": "Shlomo Artzi"},
    ]


def test_intl_titles_sorted_and_split_into_four_equal_files():
    rows = [row("INTL", title=f"Song {chr(ord('a') + i)}", artist="Band") for i in range(10)]
    lists = work_lists(list(reversed(rows)), empty())
    names = [f"titles-intl-{n}.todo.json" for n in (1, 2, 3, 4)]
    assert [len(lists[n]) for n in names] == [2, 3, 2, 3]
    flat = [e["title"] for n in names for e in lists[n]]
    assert flat == [f"Song {chr(ord('a') + i)}" for i in range(10)]
    assert set(lists[names[0]][0]) == {"n", "key", "title", "artist", "year", "deezer_title", "deezer_artist"}
    assert [[e["n"] for e in lists[n]] for n in names] == [[1, 2], [1, 2, 3], [1, 2], [1, 2, 3]]


def test_intl_title_list_uses_latin_title_and_credit():
    r = row("INTL", title="צהוב", artist="קולדפליי", title_latin="Yellow", artist_latin="Coldplay", year=2000)
    entry = next(e for name, es in work_lists([r], empty()).items() if "intl-" in name for e in es)
    assert entry["title"] == "Yellow" and entry["artist"] == "Coldplay"


def test_write_work_lists_and_summary(tmp_path):
    rows = [row(title="א"), row("INTL", title="Help!", artist="The Beatles")]
    sp = spellings.Spellings({"שלמה ארצי": artist()}, {rows[0]["key"]: title()}, ["boom"])
    lists = work_lists(rows, sp)
    spellings.write_work_lists(lists, tmp_path)
    assert json.loads((tmp_path / "artists-intl.todo.json").read_text(encoding="utf-8"))[0]["credit"] == "The Beatles"
    assert json.loads((tmp_path / "titles-il.todo.json").read_text(encoding="utf-8")) == []
    text = "\n".join(spellings.summary(rows, sp))
    assert "IL" in text and "INTL" in text and "boom" in text


# ---- seed spellings ----

SEED = {
    "songs": [
        {
            "id": "a-b", "title": {"he": "שיר", "en": "Shir"}, "artist": {"he": "נינה", "en": "Nena"},
            "titleAliases": ["Sheer"], "artistAliases": ["Nena Band"], "script": "EN",
        },
        {
            "id": "c-d", "title": {"he": "עוד", "en": "Od"}, "artist": {"he": "הדג נחש", "en": "Hadag Nahash"},
            "script": "HE",
        },
    ]
}  # fmt: skip


def test_seed_builder_matches_rows_and_applies_corrections():
    rows = [
        row("INTL", title="Shir", artist="Nena Band", artist_latin="Nena Band"),
        row("IL", title="עוד", artist="הדג נחש ואהובה עוזרי"),
        row("IL", title="לא קשור"),
    ]
    mapping = {"a-b": ("Shir", "Nena Band"), "c-d": ("עוד", "הדג נחש ואהובה עוזרי")}
    artists, titles = spellings.build_seed_spellings(
        SEED, rows, mapping, he_corrections={"a-b": ("ננה", "נינה")},
        other_acts={"הדג נחש ואהובה עוזרי": ["אהובה עוזרי", "Ahuva Ozeri"]},
    )
    assert artists["Nena Band"] == {"he": "ננה", "en": "Nena", "aliases": ["Nena Band", "נינה"]}
    assert artists["הדג נחש ואהובה עוזרי"] == {
        "he": "הדג נחש", "en": "Hadag Nahash", "aliases": ["אהובה עוזרי", "Ahuva Ozeri"]
    }
    assert titles[rows[0]["key"]] == {"he": "שיר", "en": "Shir", "aliases": ["Sheer"], "script": "EN"}
    assert titles[rows[1]["key"]]["aliases"] == []
    assert len(titles) == 2


def test_seed_builder_fails_when_a_row_is_missing():
    with pytest.raises(spellings.SpellingError, match="a-b"):
        spellings.build_seed_spellings(SEED, [], {"a-b": ("Shir", "X"), "c-d": ("עוד", "Y")})


def load_seed_files(tmp_path):
    """Only the committed seed files (other agents' data files sit in the same directory)."""
    import shutil

    for name in ("artists-seed.json", "titles-seed.json"):
        shutil.copy(spellings.SPELLINGS_DIR / name, tmp_path / name)
    return load_spellings(tmp_path)


def test_seed_spellings_files_in_repo_pass_the_loader(tmp_path):
    result = load_seed_files(tmp_path)
    assert result.errors == []
    assert len(result.titles) == 24 and len(result.artists) == 24


def test_multi_act_seed_credit_gets_other_acts_and_not_the_whole_credit():
    seed = {"songs": [{
        "id": "b-c", "title": {"he": "שיר", "en": "Shir"}, "artist": {"he": "ביונסה", "en": "Beyoncé"},
        "artistAliases": ["Beyonce", "Beyoncé feat. Jay-Z"], "script": "EN",
    }]}  # fmt: skip
    credit = "Beyoncé featuring Jay-Z"
    rows = [row("INTL", title="Shir", artist=credit, artist_latin=credit)]
    artists, _ = spellings.build_seed_spellings(
        seed, rows, {"b-c": ("Shir", credit)}, other_acts={credit: ["Jay-Z", "ג'יי זי"]}
    )
    assert artists[credit]["aliases"] == ["Beyonce", "Jay-Z", "ג'יי זי"]


def test_real_seed_file_has_other_acts_not_whole_credits(tmp_path):
    artists = load_seed_files(tmp_path).artists
    beyonce = artists["Beyoncé featuring Jay-Z"]["aliases"]
    assert "Jay-Z" in beyonce and "ג'יי זי" in beyonce
    assert not any("featuring" in a or "feat." in a for a in beyonce)
    hadag = artists["הדג נחש ואהובה עוזרי"]["aliases"]
    assert "אהובה עוזרי" in hadag and "הדג נחש ואהובה עוזרי" not in hadag
