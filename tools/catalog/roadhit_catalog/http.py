"""The one place that talks to the network.

Every request goes through :func:`get_json` (or :func:`get_bytes` for a
download): it sends a fixed, anonymous User-Agent, caches each response on disk
(keyed by URL + params) so re-runs never re-fetch, rate-limits per host
(Wikipedia ~1/s, Deezer 8/s, MusicBrainz 1/s, Wikidata 2/s) and retries 429/5xx
with backoff (MusicBrainz answers 503 when a client goes too fast). Deezer
answers HTTP 200 with an ``{"error": ...}`` body when over quota: that is
retried too; a MusicBrainz or Wikidata error body
raises. No error body is ever cached.
In offline mode a cache miss is an error instead of a fetch. A ``fresh`` request
(the preview sweep) skips the cache: it is always fetched and never stored.
"""

from __future__ import annotations

import hashlib
import json
import sys
import time
from pathlib import Path
from urllib.parse import urlsplit

import httpx

USER_AGENT = "RoadHitCatalog/0.1 (personal project)"
CACHE_DIR = Path(__file__).resolve().parent.parent / ".cache"
# At most 4 requests per second per host is the hard ceiling; in practice
# Wikimedia answers 429 to anonymous clients well below that, so stay at ~1/s.
WIKIPEDIA_MIN_INTERVAL = 1.0
# Deezer allows 50 requests per 5 s; stay at 8 per second (40 per 5 s).
DEEZER_HOST = "api.deezer.com"
DEEZER_MIN_INTERVAL = 1 / 8
# MusicBrainz allows one request per second per client; never go faster.
MUSICBRAINZ_HOST = "musicbrainz.org"
MUSICBRAINZ_MIN_INTERVAL = 1.0
# Wikidata: at most two requests per second.
WIKIDATA_HOST = "www.wikidata.org"
WIKIDATA_MIN_INTERVAL = 0.5
# Deezer error codes that mean "try again later": 4 quota, 700 service busy.
DEEZER_RETRYABLE_CODES = {4, 700}
MAX_ATTEMPTS = 8


class ApiError(RuntimeError):
    """An API answered with an error body (never cached)."""


class CacheMiss(RuntimeError):
    """Raised in offline mode when a request is not in the cache."""


_offline = False
_last_request: dict[str, float] = {}
_client: httpx.Client | None = None


def set_offline(offline: bool) -> None:
    global _offline
    _offline = offline


def cache_key(url: str, params: dict | None) -> str:
    canonical = json.dumps([url, sorted((params or {}).items())], ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _cache_path(key: str) -> Path:
    return CACHE_DIR / key[:2] / f"{key}.json"


def min_interval(host: str) -> float:
    """Seconds to leave between two requests to ``host`` (0: no limit)."""
    if host.endswith("wikipedia.org"):
        return WIKIPEDIA_MIN_INTERVAL
    if host == DEEZER_HOST:
        return DEEZER_MIN_INTERVAL
    if host == MUSICBRAINZ_HOST:
        return MUSICBRAINZ_MIN_INTERVAL
    if host == WIKIDATA_HOST:
        return WIKIDATA_MIN_INTERVAL
    return 0.0


def _throttle(host: str) -> None:
    interval = min_interval(host)
    if not interval:
        return
    last = _last_request.get(host)
    if last is not None:
        wait = interval - (time.monotonic() - last)
        if wait > 0:
            time.sleep(wait)
    _last_request[host] = time.monotonic()


def _get_client() -> httpx.Client:
    global _client
    if _client is None:
        _client = httpx.Client(
            headers={"User-Agent": USER_AGENT}, timeout=30.0, follow_redirects=True
        )
    return _client


def get_json(url: str, params: dict | None = None, fresh: bool = False) -> dict:
    """GET ``url`` with ``params`` and return the decoded JSON, via the disk cache.

    ``fresh`` bypasses the cache both ways: it always fetches and never stores the answer, so a
    check of today's state leaves the cached inputs of the other stages as they were.
    """
    key = cache_key(url, params)
    path = _cache_path(key)
    if fresh:
        if _offline:
            raise CacheMiss(f"a fresh request cannot be offline: {url} {params}")
    elif path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    elif _offline:
        raise CacheMiss(f"not cached (offline): {url} {params}")

    host = urlsplit(url).hostname or ""
    delay = 1.0
    for attempt in range(1, MAX_ATTEMPTS + 1):
        _throttle(host)
        try:
            response = _get_client().get(url, params=params)
        except httpx.TransportError:
            if attempt == MAX_ATTEMPTS:
                raise
        else:
            if response.status_code == 429 or response.status_code >= 500:
                if attempt == MAX_ATTEMPTS:
                    response.raise_for_status()
                print(f"  HTTP {response.status_code}, retrying: {url}", file=sys.stderr)
                retry_after = response.headers.get("Retry-After", "")
                if retry_after.isdigit():
                    delay = max(delay, float(retry_after))
            else:
                response.raise_for_status()
                data = response.json()
                error = data.get("error") if host == DEEZER_HOST and isinstance(data, dict) else None
                if error:
                    code = error.get("code") if isinstance(error, dict) else None
                    if code not in DEEZER_RETRYABLE_CODES:
                        raise ApiError(f"{url} {params}: {error}")
                    if attempt == MAX_ATTEMPTS:
                        raise ApiError(f"still over quota after {attempt} attempts: {url} {params}: {error}")
                    print(f"  Deezer error {code}, retrying: {url}", file=sys.stderr)
                    delay = max(delay, 5.0)  # Deezer's quota window is 5 seconds
                    time.sleep(delay)
                    delay = min(delay * 2, 60.0)
                    continue
                if host in (MUSICBRAINZ_HOST, WIKIDATA_HOST) and isinstance(data, dict) and "error" in data:
                    raise ApiError(f"{url} {params}: {data['error']}")
                if fresh:
                    return data
                path.parent.mkdir(parents=True, exist_ok=True)
                tmp = path.with_suffix(".tmp")
                tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
                tmp.replace(path)
                return data
        time.sleep(delay)
        delay = min(delay * 2, 60.0)
    raise RuntimeError("unreachable")


def get_bytes(url: str) -> tuple[bytes, str]:
    """GET ``url`` uncached and return (body, content type); throttled and retried like :func:`get_json`."""
    if _offline:
        raise CacheMiss(f"a download cannot be offline: {url}")
    host = urlsplit(url).hostname or ""
    delay = 1.0
    for attempt in range(1, MAX_ATTEMPTS + 1):
        _throttle(host)
        try:
            response = _get_client().get(url)
        except httpx.TransportError:
            if attempt == MAX_ATTEMPTS:
                raise
        else:
            if response.status_code != 429 and response.status_code < 500:
                response.raise_for_status()
                return response.content, response.headers.get("Content-Type", "")
            if attempt == MAX_ATTEMPTS:
                response.raise_for_status()
            print(f"  HTTP {response.status_code}, retrying: {url}", file=sys.stderr)
        time.sleep(delay)
        delay = min(delay * 2, 60.0)
    raise RuntimeError("unreachable")


# --- MediaWiki helpers -------------------------------------------------------

HE_API = "https://he.wikipedia.org/w/api.php"
EN_API = "https://en.wikipedia.org/w/api.php"


def parse_page(api: str, title: str, prop: str = "wikitext") -> dict:
    """``action=parse`` for one page; returns the ``parse`` object (or raises)."""
    data = get_json(
        api,
        {
            "action": "parse",
            "page": title,
            "prop": prop,
            "redirects": "1",
            "format": "json",
            "formatversion": "2",
        },
    )
    if "error" in data:
        raise PageMissing(f"{title}: {data['error'].get('info')}")
    return data["parse"]


def search_titles(api: str, query: str, limit: int = 20) -> list[str]:
    data = get_json(
        api,
        {
            "action": "query",
            "list": "search",
            "srsearch": query,
            "srlimit": str(limit),
            "format": "json",
            "formatversion": "2",
        },
    )
    return [hit["title"] for hit in data.get("query", {}).get("search", [])]


def prefix_titles(api: str, prefix: str, namespace: int = 0) -> list[str]:
    data = get_json(
        api,
        {
            "action": "query",
            "list": "allpages",
            "apprefix": prefix,
            "apnamespace": str(namespace),
            "aplimit": "500",
            "format": "json",
            "formatversion": "2",
        },
    )
    return [p["title"] for p in data.get("query", {}).get("allpages", [])]


class PageMissing(LookupError):
    pass
