"""The preview sweep, on canned Deezer answers only.

``deezer_sweep_tracks.json``: {track id: /track answer}, real answers trimmed to the fields used;
track 555 is an edited copy with its preview emptied, track 1 is Deezer's real "no data" answer.
"""

import json

import httpx
import pytest

from conftest import load_fixture
from roadhit_catalog import cli, http, sweep

TRACKS = load_fixture("deezer_sweep_tracks.json")
MP3 = b"ID3" + bytes(300_000)


def fake_fetch(deezer_id):
    answer = TRACKS[str(deezer_id)]
    if "error" in answer:
        raise http.ApiError(f"{sweep.TRACK_URL.format(deezer_id)} None: {answer['error']}")
    return answer


def song(song_id, deezer_id):
    return {"id": song_id, "deezerId": deezer_id}


SONGS = [song("10cc", 23494861), song("netta-toy", 3782953802), song("gone", 555), song("missing", 1)]


def test_songs_without_a_preview_or_with_a_deezer_error_fail():
    failures, downloads = sweep.run(SONGS, fake_fetch, lambda url: (MP3, "audio/mpeg"), sample=[])
    assert failures == [
        {"id": "gone", "deezerId": 555, "reason": "no preview"},
        {"id": "missing", "deezerId": 1, "reason": failures[1]["reason"]},
    ]
    assert failures[1]["reason"].startswith("Deezer error:") and "no data" in failures[1]["reason"]
    assert downloads == []


def test_sampled_songs_are_downloaded_from_their_fresh_preview_url():
    fetched = []
    fetched_urls = []

    def fetch(deezer_id):
        fetched.append(deezer_id)
        return fake_fetch(deezer_id)

    def download(url):
        fetched_urls.append(url)
        return MP3, "audio/mpeg"

    _, downloads = sweep.run(SONGS, fetch, download, sample=["netta-toy", "gone"])
    assert fetched == [23494861, 3782953802, 555, 1]  # one track request per song
    assert fetched_urls == [TRACKS["3782953802"]["preview"]]  # a failed song is not downloaded
    assert downloads == [{"id": "netta-toy", "bytes": len(MP3), "problem": None}]


def test_a_failed_download_is_that_songs_problem_and_the_run_goes_on():
    def download(url):
        raise httpx.ConnectError("connection reset")

    failures, downloads = sweep.run(SONGS, fake_fetch, download, sample=["10cc", "netta-toy"])
    assert [d["id"] for d in downloads] == ["10cc", "netta-toy"]  # the second song is still checked
    assert all(d["bytes"] == 0 and d["problem"] == "download failed: connection reset" for d in downloads)
    assert [f["id"] for f in failures] == ["gone", "missing"]  # songs after the failures were checked too


@pytest.mark.parametrize(
    "body, content_type, problem",
    [
        (MP3, "audio/mpeg", None),
        (b"\xff\xfb" + bytes(300_000), "audio/mpeg", None),
        (MP3, "text/html; charset=utf-8", "not audio (text/html; charset=utf-8)"),
        (b"<html>" + bytes(300_000), "audio/mpeg", "not an MP3 stream"),
        (b"ID3" + bytes(1_000), "audio/mpeg", "unexpected size 1003 bytes"),
    ],
)
def test_a_download_must_be_an_mp3_of_sensible_size(body, content_type, problem):
    assert sweep.audio_problem(body, content_type) == problem


def test_the_failure_bar_is_two_and_a_half_percent():
    assert not sweep.too_many([{}] * 5, 200)
    assert sweep.too_many([{}] * 6, 200)
    assert not sweep.too_many([], 0)


def test_the_sample_is_fixed_by_its_seed():
    songs = [song(f"s{i}", i) for i in range(100)]
    first = sweep.pick_sample(songs, seed=7)
    assert len(first) == sweep.DOWNLOAD_SAMPLE == len(set(first))
    assert sweep.pick_sample(songs, seed=7) == first


def test_exclude_lines_name_the_dated_key_and_the_reason():
    dated = [{"key": "abc123", "deezer_id": 555, "title": "Song", "artist": "Singer"}]
    failures = [{"id": "gone", "deezerId": 555, "reason": "no preview"},
                {"id": "stray", "deezerId": 9, "reason": "no preview"}]
    assert sweep.exclude_lines(failures, dated, "2026-11-14") == [
        "abc123  # Song – Singer: no preview (preview sweep 2026-11-14)",
        "# stray (Deezer 9): not in work/dated.json",
    ]


def test_a_fresh_request_skips_the_cache_both_ways(tmp_path, monkeypatch):
    monkeypatch.setattr(http, "CACHE_DIR", tmp_path)
    url = sweep.TRACK_URL.format(555)
    path = http._cache_path(http.cache_key(url, None))
    path.parent.mkdir(parents=True)
    path.write_text(json.dumps({"preview": "stale"}), encoding="utf-8")
    requests = []

    class Response:
        status_code = 200
        headers = {}

        def raise_for_status(self):
            pass

        def json(self):
            return {"preview": ""}

    class Client:
        def get(self, url, params=None):
            requests.append(url)
            return Response()

    monkeypatch.setattr(http, "_get_client", lambda: Client())
    monkeypatch.setattr(http, "_throttle", lambda host: None)
    assert http.get_json(url, fresh=True) == {"preview": ""}
    assert requests == [url]
    assert json.loads(path.read_text(encoding="utf-8")) == {"preview": "stale"}  # not overwritten
    assert http.get_json(url) == {"preview": "stale"}  # the other stages still read the cache


def test_a_fresh_request_is_never_offline():
    http.set_offline(True)
    try:
        with pytest.raises(http.CacheMiss):
            http.get_json(sweep.TRACK_URL.format(1), fresh=True)
    finally:
        http.set_offline(False)


def test_the_sweep_command_reports_failures_and_exits_non_zero(tmp_path, monkeypatch, capsys):
    catalog = tmp_path / "catalog.json"
    catalog.write_text(json.dumps({"version": 1, "songs": SONGS}), encoding="utf-8")
    dated = tmp_path / "dated.json"
    dated.write_text(json.dumps([{"key": "k555", "deezer_id": 555, "title": "T", "artist": "A"}]), encoding="utf-8")
    monkeypatch.setattr(cli, "DATED_JSON", dated)
    monkeypatch.setattr(cli, "SWEEP_JSON", tmp_path / "sweep.json")
    monkeypatch.setattr(sweep, "fetch_track", fake_fetch)
    monkeypatch.setattr(http, "get_bytes", lambda url: (MP3, "audio/mpeg"))
    assert cli.main(["sweep", "--catalog", str(catalog), "--seed", "1"]) == 1
    out = capsys.readouterr().out
    assert "4 songs checked, 2 without a preview" in out
    assert "More than 2.5%" in out
    # Above the bar the stop note replaces the exclude lines.
    assert "Lines for data/exclude.txt" not in out
    assert "k555  # T – A: no preview" not in out
    report = json.loads((tmp_path / "sweep.json").read_text(encoding="utf-8"))
    assert [f["id"] for f in report["failures"]] == ["gone", "missing"]


def test_below_the_bar_the_sweep_prints_the_exclude_lines(tmp_path, monkeypatch, capsys):
    songs = [song(f"ok-{i}", 23494861) for i in range(40)] + [song("gone", 555)]  # 1 of 41 < 2.5%
    catalog = tmp_path / "catalog.json"
    catalog.write_text(json.dumps({"version": 1, "songs": songs}), encoding="utf-8")
    dated = tmp_path / "dated.json"
    dated.write_text(json.dumps([{"key": "k555", "deezer_id": 555, "title": "T", "artist": "A"}]), encoding="utf-8")
    monkeypatch.setattr(cli, "DATED_JSON", dated)
    monkeypatch.setattr(cli, "SWEEP_JSON", tmp_path / "sweep.json")
    monkeypatch.setattr(sweep, "fetch_track", fake_fetch)
    monkeypatch.setattr(http, "get_bytes", lambda url: (MP3, "audio/mpeg"))
    assert cli.main(["sweep", "--catalog", str(catalog), "--seed", "1"]) == 1
    out = capsys.readouterr().out
    assert "k555  # T – A: no preview" in out
    assert "More than 2.5%" not in out
