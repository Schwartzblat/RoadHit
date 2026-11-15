import json
import re
import unicodedata
from collections import Counter

import pytest

from roadhit_catalog import assemble, cli, spellings
from roadhit_catalog.assemble import AssembleError, build_songs, genre_of, slug
from roadhit_catalog.spellings import Spellings

HE_OK = re.compile(r"[א-ת0-9 '\"\-]+")
HEBREW = re.compile(r"[א-ת]")


def row(key="k1", title="שיר", artist="שלמה ארצי", origin="IL", year=2000, deezer_id=1, genre_id=132, **extra):
    base = dict(
        key=key, title=title, artist=artist, credit=artist, origin=origin, title_latin=None, artist_latin=None,
        deezer_id=deezer_id, popularity=500, genre_id=genre_id, year=year, flagged=False, chart_year=year,
    )  # fmt: skip
    base.update(extra)
    return base


def artist(he="שלמה ארצי", en="Shlomo Artzi", aliases=()):
    return {"he": he, "en": en, "aliases": list(aliases)}


def title(he="שיר", en="Shir", aliases=(), script="HE"):
    return {"he": he, "en": en, "aliases": list(aliases), "script": script}


def sp(artists=None, titles=None):
    return Spellings(artists or {"שלמה ארצי": artist()}, titles or {"k1": title()}, [])


# ---- slugs and ids ----

@pytest.mark.parametrize(
    "text, expected",
    [
        ("Beyoncé", "beyonce"),
        ("Static & Ben El Tavori", "static-and-ben-el-tavori"),
        ("Ani Ve'ata", "ani-veata"),
        ("Don’t Stop", "dont-stop"),
        ("99 Luftballons", "99-luftballons"),
        ("Help!", "help"),
        ("Guns N' Roses", "guns-n-roses"),
        ("  Hello,  World -- 2 ", "hello-world-2"),
    ],
)
def test_slug(text, expected):
    assert slug(text) == expected


def test_id_is_artist_slug_dash_title_slug():
    [song] = build_songs([row()], sp())
    assert song["id"] == "shlomo-artzi-shir"


def test_id_clash_appends_year_to_both():
    rows = [row("k1", year=1990, deezer_id=1), row("k2", year=2005, deezer_id=2)]
    songs = build_songs(rows, sp(titles={"k1": title(), "k2": title()}))
    assert [s["id"] for s in songs] == ["shlomo-artzi-shir-1990", "shlomo-artzi-shir-2005"]


def test_id_clash_with_same_year_fails_naming_the_ids():
    rows = [row("k1", deezer_id=1), row("k2", deezer_id=2)]
    with pytest.raises(AssembleError, match="shlomo-artzi-shir-2000"):
        build_songs(rows, sp(titles={"k1": title(), "k2": title()}))


# ---- genres ----

@pytest.mark.parametrize(
    "genre_id, expected",
    [(132, "POP"), (152, "ROCK"), (85, "ROCK"), (464, "ROCK"), (116, "HIPHOP"), (113, "DANCE"), (106, "DANCE"),
     (165, "RNB"), (169, "RNB"), (-1, "OTHER"), (466, "OTHER"), (0, "OTHER")],
)  # fmt: skip
def test_genre_mapping(genre_id, expected):
    assert genre_of(genre_id, "שלמה ארצי", set()) == expected


def test_mizrahi_artist_overrides_genre():
    assert genre_of(152, "שלמה ארצי", {"שלמה ארצי"}) == "MIZRAHI"
    [song] = build_songs([row(genre_id=152)], sp(), mizrahi={"שלמה ארצי"})
    assert song["genre"] == "MIZRAHI"


# ---- songs ----

def test_song_has_exactly_the_app_schema_fields():
    [song] = build_songs([row(deezer_id=77)], sp(titles={"k1": title(aliases=["Sheer"])}))
    assert list(song) == [
        "id", "title", "artist", "titleAliases", "artistAliases", "script", "year", "origin", "genre",
        "deezerId", "rank",
    ]
    assert song["title"] == {"he": "שיר", "en": "Shir"}
    assert song["artist"] == {"he": "שלמה ארצי", "en": "Shlomo Artzi"}
    assert song["titleAliases"] == ["Sheer"] and song["artistAliases"] == []
    assert (song["script"], song["year"], song["origin"], song["deezerId"], song["rank"]) == ("HE", 2000, "IL", 77, 500)


def test_artist_comes_from_the_credits_spelling_and_other_acts_arrive_as_aliases():
    r = row("k1", "Crazy in Love", "בייונסה וג'יי זי", "INTL", title_latin="Crazy in Love",
            artist_latin="Beyoncé featuring Jay-Z")  # fmt: skip
    spelling = sp(
        {"Beyoncé featuring Jay-Z": artist("ביונסה", "Beyoncé", ["Jay-Z", "ג'יי זי"])},
        {"k1": title("קרייזי אין לאב", "Crazy in Love", script="EN")},
    )
    [song] = build_songs([r], spelling)
    assert song["artist"] == {"he": "ביונסה", "en": "Beyoncé"}
    assert song["artistAliases"] == ["Jay-Z", "ג'יי זי"]
    assert song["id"] == "beyonce-crazy-in-love"


def test_built_songs_respect_the_character_rules():
    songs = build_songs([row()], sp())
    for s in songs:
        for name in (s["title"]["he"], s["artist"]["he"]):
            assert HE_OK.fullmatch(name)
        for name in (s["title"]["en"], s["artist"]["en"]):
            assert not HEBREW.search(name)


def test_missing_spellings_fail_listing_all_of_them():
    rows = [row("k1", artist="א"), row("k2", artist="ב", deezer_id=2), row("k3", artist="ג", deezer_id=3)]
    with pytest.raises(AssembleError) as error:
        build_songs(rows, Spellings({"ג": artist()}, {"k1": title()}, []))
    message = str(error.value)
    for needle in ("'א'", "'ב'", "k2", "k3"):
        assert needle in message


def test_year_out_of_range_fails():
    for year in (1939, 2027):
        with pytest.raises(AssembleError, match=str(year)):
            build_songs([row(year=year)], sp())
    build_songs([row(year=1940)], sp())
    build_songs([row(year=2026)], sp())


def test_duplicate_deezer_id_fails():
    rows = [row("k1", deezer_id=5), row("k2", title="אחר", deezer_id=5)]
    with pytest.raises(AssembleError, match="5"):
        build_songs(rows, sp(titles={"k1": title(), "k2": title(en="Acher")}))


def test_deezer_id_must_be_positive():
    with pytest.raises(AssembleError, match="deezer"):
        build_songs([row(deezer_id=0)], sp())


def test_all_problems_are_reported_together():
    rows = [row("k1", year=1800, deezer_id=0)]
    with pytest.raises(AssembleError) as error:
        build_songs(rows, sp())
    assert "1800" in str(error.value) and "deezer" in str(error.value)


# ---- output ----

def test_catalog_text_is_sorted_one_song_per_line_unescaped():
    rows = [row("k1", deezer_id=1), row("k2", deezer_id=2)]
    songs = build_songs(
        rows, sp({"שלמה ארצי": artist()}, {"k1": title(en="Zed"), "k2": title(en="Alef")})
    )
    text = assemble.catalog_text(songs)
    lines = text.splitlines()
    assert lines[0] == "{" and '"version": 1' in lines[1]
    parsed = json.loads(text)
    assert parsed["version"] == 1
    assert [s["id"] for s in parsed["songs"]] == ["shlomo-artzi-alef", "shlomo-artzi-zed"]
    song_lines = [l for l in lines if l.strip().startswith('{"id"')]
    assert len(song_lines) == 2
    assert "שלמה ארצי" in text and "\\u" not in text
    assert text.endswith("\n")


# ---- assemble() end to end on small files ----

def test_run_assemble_writes_catalog_and_report(tmp_path):
    work = tmp_path / "work"
    work.mkdir()
    (work / "dated.json").write_text(json.dumps([row(flagged=True)]), encoding="utf-8")
    (work / "dropped.json").write_text(
        json.dumps([{"key": "x", "reason": "no_match", "origin": "IL", "chart_year": 1950}]), encoding="utf-8"
    )
    sdir = tmp_path / "spellings"
    sdir.mkdir()
    (sdir / "artists-a.json").write_text(json.dumps({"שלמה ארצי": artist()}, ensure_ascii=False), encoding="utf-8")
    (sdir / "titles-a.json").write_text(json.dumps({"k1": title()}, ensure_ascii=False), encoding="utf-8")
    out = tmp_path / "catalog.json"
    count = assemble.run_assemble(out, work_dir=work, spellings_dir=sdir, mizrahi_path=tmp_path / "none.txt")
    assert count == 1
    assert json.loads(out.read_text(encoding="utf-8"))["songs"][0]["id"] == "shlomo-artzi-shir"
    report = (work / "report.md").read_text(encoding="utf-8")
    for needle in ("2000s", "no_match", "Flagged", "2,000", "1,200", "before 1963"):
        assert needle in report


def test_run_assemble_missing_input_is_a_clear_error(tmp_path):
    with pytest.raises(AssembleError, match="dated.json"):
        assemble.run_assemble(tmp_path / "c.json", work_dir=tmp_path, spellings_dir=tmp_path)


def test_run_assemble_stops_on_loader_errors(tmp_path):
    work = tmp_path / "work"
    work.mkdir()
    (work / "dated.json").write_text(json.dumps([row()]), encoding="utf-8")
    (work / "dropped.json").write_text("[]", encoding="utf-8")
    (tmp_path / "artists-a.json").write_text(json.dumps({"A": artist(en="שיר")}, ensure_ascii=False), encoding="utf-8")
    with pytest.raises(AssembleError, match="artists-a.json"):
        assemble.run_assemble(tmp_path / "c.json", work_dir=work, spellings_dir=tmp_path)
    assert not (tmp_path / "c.json").exists()


def test_cli_assemble_reports_errors_without_traceback(tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(cli, "WORK_DIR", tmp_path / "nowork")
    assert cli.main(["assemble", "--out", str(tmp_path / "c.json")]) == 1
    assert "dated.json" in capsys.readouterr().err


def test_cli_spellings_missing_input(tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(cli, "WORK_DIR", tmp_path / "nowork")
    assert cli.main(["spellings"]) == 1
    assert "dated.json" in capsys.readouterr().err


# ---- seed ids from the real data ----

REAL_DATED = assemble.ROOT / "work" / "dated.json"


@pytest.mark.skipif(not REAL_DATED.exists(), reason="work/dated.json is absent")
def test_the_24_seed_songs_come_out_with_their_seed_ids(tmp_path):
    seed = json.loads(spellings.SEED_CATALOG.read_text(encoding="utf-8"))["songs"]
    rows = json.loads(REAL_DATED.read_text(encoding="utf-8"))
    for name in ("artists-seed.json", "titles-seed.json"):  # only the seed files
        (tmp_path / name).write_bytes((spellings.SPELLINGS_DIR / name).read_bytes())
    loaded = spellings.load_spellings(tmp_path)
    assert loaded.errors == []
    seed_keys = set(loaded.titles)
    seed_rows = [r for r in rows if r["key"] in seed_keys]
    songs = build_songs(seed_rows, loaded, spellings.load_mizrahi())
    assert sorted(s["id"] for s in songs) == sorted(s["id"] for s in seed)
    by_id = {s["id"]: s for s in songs}
    for s in seed:
        built = by_id[s["id"]]
        assert built["title"] == s["title"] and built["script"] == s["script"] and built["origin"] == s["origin"]
        assert built["artist"]["en"] == s["artist"]["en"]
        multi_act = {"beyonce-crazy-in-love": {"Beyoncé feat. Jay-Z"}}  # whole credit replaced by the acts
        assert set(s.get("artistAliases", [])) - multi_act.get(s["id"], set()) <= set(built["artistAliases"])


def test_build_seed_spellings_feed_build_songs_on_a_fixture():
    seed = {"songs": [{"id": "shlomo-artzi-shir", "title": {"he": "שיר", "en": "Shir"},
                       "artist": {"he": "שלמה ארצי", "en": "Shlomo Artzi"}, "script": "HE"}]}  # fmt: skip
    rows = [row()]
    artists, titles = spellings.build_seed_spellings(seed, rows, {"shlomo-artzi-shir": ("שיר", "שלמה ארצי")})
    [song] = build_songs(rows, Spellings(artists, titles, []))
    assert song["id"] == "shlomo-artzi-shir"


# ---- the full catalog from the real data ----


@pytest.fixture(scope="module")
def real_catalog():
    rows = json.loads(REAL_DATED.read_text(encoding="utf-8"))
    loaded = spellings.load_spellings()
    assert loaded.errors == []
    return build_songs(rows, loaded, spellings.load_mizrahi())


def app_style(text: str) -> str:
    """Like the app's normalizer: lower case, no accents, no bracketed parts or punctuation, no leading "the"."""
    text = re.sub(r"\([^)]*\)|\[[^\]]*]", " ", text.lower())
    text = "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.category(c).startswith("M"))
    words = re.sub(r"[^\w\s]|_", " ", re.sub(r"['’]", "", text)).split()
    return " ".join(words[1:] if len(words) > 1 and words[0] == "the" else words)


def test_app_style_normalisation():
    assert app_style("The House of the Rising Sun") == app_style("House of the Rising Sun")
    assert app_style("Sugar, Sugar") == "sugar sugar"
    assert app_style("Candle in the Wind (1997)") == "candle in the wind"
    assert app_style("Can't Stop") == "cant stop"


real_data = pytest.mark.skipif(not REAL_DATED.exists(), reason="work/dated.json is absent")


@real_data
def test_real_catalog_has_at_least_3800_songs(real_catalog):
    assert len(real_catalog) >= 3800


@real_data
def test_real_catalog_keeps_the_24_seed_ids(real_catalog):
    seed_ids = {s["id"] for s in json.loads(spellings.SEED_CATALOG.read_text(encoding="utf-8"))["songs"]}
    assert len(seed_ids) == 24
    assert seed_ids <= {s["id"] for s in real_catalog}


@real_data
def test_real_catalog_has_no_song_twice(real_catalog):
    keys = Counter((app_style(s["artist"]["en"]), app_style(s["title"]["en"])) for s in real_catalog)
    assert [k for k, n in keys.items() if n > 1] == []


@real_data
def test_real_catalog_genres_are_app_values(real_catalog):
    assert {s["genre"] for s in real_catalog} <= set(assemble.ALL_GENRES)
    assert len(assemble.ALL_GENRES) == 7


@real_data
def test_real_despacito_is_the_original_recording(real_catalog):
    [song] = [s for s in real_catalog if s["title"]["en"] == "Despacito"]
    assert song["title"] == {"he": "דספסיטו", "en": "Despacito"}
    assert song["artist"] == {"he": "לואיס פונסי", "en": "Luis Fonsi"}
    assert "Daddy Yankee" in song["artistAliases"]
    assert "bieber" not in json.dumps(song, ensure_ascii=False).lower()
    assert "ביבר" not in json.dumps(song, ensure_ascii=False)
