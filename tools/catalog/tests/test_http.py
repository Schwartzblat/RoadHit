import json

import pytest

from roadhit_catalog import http


def test_cached_response_is_returned_without_fetching(tmp_path, monkeypatch):
    monkeypatch.setattr(http, "CACHE_DIR", tmp_path)
    params = {"action": "parse", "page": "X"}
    path = http._cache_path(http.cache_key(http.EN_API, params))
    path.parent.mkdir(parents=True)
    path.write_text(json.dumps({"parse": {"title": "X"}}), encoding="utf-8")
    assert http.get_json(http.EN_API, params) == {"parse": {"title": "X"}}


def test_offline_cache_miss_fails_instead_of_fetching(tmp_path, monkeypatch):
    monkeypatch.setattr(http, "CACHE_DIR", tmp_path)
    http.set_offline(True)
    try:
        with pytest.raises(http.CacheMiss):
            http.get_json(http.EN_API, {"action": "parse", "page": "Nope"})
    finally:
        http.set_offline(False)


def test_cache_key_ignores_param_order_and_user_agent_is_anonymous():
    assert http.cache_key("u", {"a": "1", "b": "2"}) == http.cache_key("u", {"b": "2", "a": "1"})
    assert http.USER_AGENT == "RoadHitCatalog/0.1 (personal project)"
