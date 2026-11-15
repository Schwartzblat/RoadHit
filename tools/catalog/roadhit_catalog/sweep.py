"""Preview sweep: does every song in the app's catalog still have a Deezer preview today?

For each song it fetches ``/track/{deezerId}`` fresh (never from the cache) and checks that the
``preview`` field is not empty; for a random sample it also downloads the preview and checks
that it is audio of a sensible size. Failures are matched to their ``work/dated.json`` key, so
they can be added to ``data/exclude.txt``.
"""

from __future__ import annotations

import random
from collections.abc import Callable, Iterable

import httpx

from . import http
from .deezer import TRACK_URL

DOWNLOAD_SAMPLE = 30
# More failures than this share means something is wrong beyond a few withdrawn tracks.
MAX_FAILURE_SHARE = 0.025
# A 30-second preview at Deezer's 128 kbit/s is about 480 KB.
MIN_PREVIEW_BYTES = 150_000
MAX_PREVIEW_BYTES = 2_000_000

NO_PREVIEW = "no preview"


def preview_problem(track: dict) -> str | None:
    """Why ``track`` (a /track answer) cannot be played, or None."""
    if not (track.get("preview") or "").strip():
        return NO_PREVIEW
    return None


def audio_problem(body: bytes, content_type: str) -> str | None:
    """Why a downloaded preview is not a playable clip, or None."""
    if not content_type.startswith("audio/"):
        return f"not audio ({content_type or 'no content type'})"
    if not (body.startswith(b"ID3") or (len(body) > 1 and body[0] == 0xFF and body[1] & 0xE0 == 0xE0)):
        return "not an MP3 stream"
    if not MIN_PREVIEW_BYTES <= len(body) <= MAX_PREVIEW_BYTES:
        return f"unexpected size {len(body)} bytes"
    return None


def fetch_track(deezer_id: int) -> dict:
    return http.get_json(TRACK_URL.format(deezer_id), fresh=True)


def run(
    songs: list[dict],
    fetch: Callable[[int], dict],
    download: Callable[[str], tuple[bytes, str]],
    sample: Iterable[str],
    progress: Callable[[int, int], None] | None = None,
) -> tuple[list[dict], list[dict]]:
    """Checks every song's preview, and downloads it for the song ids in ``sample``.

    Returns (failures, downloads): each failure is ``{id, deezerId, reason}``; each download is
    ``{id, bytes, problem}`` (problem None when it is a good clip; a download that fails is that
    song's problem, with 0 bytes, and the run goes on). A download comes right after its track
    answer, because preview URLs are signed and expire.
    """
    wanted = set(sample)
    failures: list[dict] = []
    downloads: list[dict] = []
    for i, song in enumerate(songs, 1):
        try:
            track = fetch(song["deezerId"])
            reason = preview_problem(track)
        except http.ApiError as error:
            reason = f"Deezer error: {error}"
        if reason:
            failures.append({"id": song["id"], "deezerId": song["deezerId"], "reason": reason})
        elif song["id"] in wanted:
            try:
                body, content_type = download(track["preview"])
            except httpx.HTTPError as error:
                downloads.append({"id": song["id"], "bytes": 0, "problem": f"download failed: {error}"})
            else:
                downloads.append({"id": song["id"], "bytes": len(body), "problem": audio_problem(body, content_type)})
        if progress:
            progress(i, len(songs))
    return failures, downloads


def pick_sample(songs: list[dict], seed: int | None) -> list[str]:
    ids = [s["id"] for s in songs]
    return random.Random(seed).sample(ids, min(DOWNLOAD_SAMPLE, len(ids)))


def too_many(failures: list[dict], total: int) -> bool:
    return total > 0 and len(failures) / total > MAX_FAILURE_SHARE


def exclude_lines(failures: list[dict], dated: list[dict], today: str) -> list[str]:
    """``data/exclude.txt`` lines for the failures, found by Deezer id in ``work/dated.json``."""
    by_track = {row["deezer_id"]: row for row in dated}
    lines = []
    for failure in failures:
        row = by_track.get(failure["deezerId"])
        if row is None:
            lines.append(f"# {failure['id']} (Deezer {failure['deezerId']}): not in work/dated.json")
            continue
        lines.append(f"{row['key']}  # {row['title']} – {row['artist']}: {failure['reason']} (preview sweep {today})")
    return lines
