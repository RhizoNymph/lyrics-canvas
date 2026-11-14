"""Configuration, auth header, token secrecy and the read commands."""

from __future__ import annotations

import io
import stat
from pathlib import Path

from conftest import ALBUM, DRAFTS, S1, S2, S3, Env
from fake_server import TOKEN

from lcxlib.config import DEFAULT_API_URL, load_config, read_env_file
from lcxlib.errors import ConfigError


def test_missing_token_is_a_usage_error(lc: Env) -> None:
    r = lc.run("whoami", env={"LYRICS_API_URL": lc.url, "LCX_ENV_FILE": str(lc.tmp / "none.env")})
    assert r.code == 2
    assert "LYRICS_API_TOKEN" in r.err and "API tokens" in r.err
    assert lc.backend.requests == []


def test_env_file_is_read(lc: Env) -> None:
    f = lc.tmp / "agent.env"
    f.write_text(f"# lcx\nexport LYRICS_API_URL={lc.url}\nLYRICS_API_TOKEN='{TOKEN}'\n")
    f.chmod(stat.S_IRUSR | stat.S_IWUSR)
    r = lc.run("whoami", env={"LCX_ENV_FILE": str(f)})
    assert r.code == 0, r.err
    assert r.json()["email"] == "me@example.com"


def test_env_file_readable_by_others_warns(lc: Env) -> None:
    f = lc.tmp / "agent.env"
    f.write_text(f"LYRICS_API_URL={lc.url}\nLYRICS_API_TOKEN={TOKEN}\n")
    f.chmod(0o644)
    r = lc.run("whoami", env={"LCX_ENV_FILE": str(f)})
    assert r.code == 0
    assert "config.env_file_readable_by_others" in r.err and "chmod 600" in r.err and TOKEN not in r.err


def test_environment_overrides_env_file(tmp_path: Path) -> None:
    f = tmp_path / "agent.env"
    f.write_text("LYRICS_API_URL=http://file:1\nLYRICS_API_TOKEN=lct_file\n")
    cfg = load_config({"LCX_ENV_FILE": str(f), "LYRICS_API_TOKEN": "lct_env"})
    assert cfg.api_url == "http://file:1" and cfg.token == "lct_env"


def test_default_url_and_trailing_slash(tmp_path: Path) -> None:
    none = str(tmp_path / "none.env")
    assert load_config({"LCX_ENV_FILE": none, "LYRICS_API_TOKEN": "lct_x"}).api_url == DEFAULT_API_URL
    assert load_config({"LCX_ENV_FILE": none, "LYRICS_API_TOKEN": "lct_x", "LYRICS_API_URL": "http://h:1/"}).api_url == "http://h:1"


def test_bad_url_rejected(tmp_path: Path) -> None:
    try:
        load_config({"LCX_ENV_FILE": str(tmp_path / "x"), "LYRICS_API_TOKEN": "lct_x", "LYRICS_API_URL": "ftp://x"})
    except ConfigError as exc:
        assert "http://" in str(exc)
    else:
        raise AssertionError("expected ConfigError")


def test_read_env_file_ignores_comments_and_junk(tmp_path: Path) -> None:
    f = tmp_path / "e"
    f.write_text('\n# c\njunk\nA="1"\n B = 2 \n')
    assert read_env_file(f) == {"A": "1", "B": "2"}


def test_config_repr_hides_token(tmp_path: Path) -> None:
    cfg = load_config({"LCX_ENV_FILE": str(tmp_path / "x"), "LYRICS_API_TOKEN": TOKEN})
    assert TOKEN not in repr(cfg) and TOKEN not in str(cfg)


def test_bearer_header_sent(lc: Env) -> None:
    assert lc.run("whoami").code == 0
    assert lc.backend.auth_headers == [f"Bearer {TOKEN}"]


def test_token_never_printed_even_verbose_or_failing(lc: Env) -> None:
    r = lc.run("-v", "canvases")
    assert r.code == 0 and TOKEN not in r.out + r.err
    assert "http.response method=GET path=/api/canvases status=200" in r.err
    bad = lc.run("-v", "whoami", env=lc.env(LYRICS_API_TOKEN="lct_wrong_but_secret"))
    assert bad.code == 3
    assert "lct_wrong_but_secret" not in bad.out + bad.err
    assert "401" in bad.err and "Invalid token" in bad.err


def test_unreachable_server(lc: Env) -> None:
    r = lc.run("whoami", env=lc.env(LYRICS_API_URL="http://127.0.0.1:9"))
    assert r.code == 3 and "unreachable" in r.err


def test_canvases_and_pretty(lc: Env) -> None:
    r = lc.run("canvases", "--pretty")
    assert r.code == 0
    assert "\n  " in r.out
    assert [c["title"] for c in r.json()] == ["Drafts", "Album", "Dup", "Dup"]


def test_canvas_by_title_is_compact_in_list_order(lc: Env) -> None:
    r = lc.run("canvas", "Drafts")
    assert r.code == 0, r.err
    data = r.json()
    assert data["id"] == DRAFTS
    assert [s["id"] for s in data["snippets"]] == [S2, S1, S3]  # ranks a0, a1, a2
    s1 = data["snippets"][1]
    assert s1["tags"] == ["draft"] and s1["version"] == 3 and s1["list_rank"] == "a1"
    assert s1["text"].startswith("Midnight") and s1["type"] == "verse"
    assert set(s1["layout"]) == {"position_x", "position_y", "width", "height", "z_index", "group_id"}
    s3 = data["snippets"][2]
    assert {c["title"] for c in s3["canvases"]} == {"Drafts", "Album"}


def test_canvas_by_id_full(lc: Env) -> None:
    r = lc.run("canvas", ALBUM, "--full")
    assert r.code == 0
    assert "groups" in r.json() and r.json()["snippets"][0]["placement_id"]


def test_canvas_unknown_and_ambiguous(lc: Env) -> None:
    unknown = lc.run("canvas", "Nope")
    assert unknown.code == 2 and "no canvas" in unknown.err
    dup = lc.run("canvas", "Dup")
    assert dup.code == 2 and "2 canvases" in dup.err


def test_tags_presets_bank_dump(lc: Env) -> None:
    assert [t["name"] for t in lc.run("tags").json()] == ["draft", "Hook"]
    assert lc.run("presets").json() == []
    assert [w["text"] for w in lc.run("bank").json()] == ["boulevard", "low"]
    dump = lc.run("dump").json()
    assert set(dump) == {"server_time", "canvases", "placements", "snippets", "tags", "tag_presets", "word_bank"}


def test_search_passes_query_and_limit(lc: Env) -> None:
    r = lc.run("search", "second thoughts", "--limit", "5")
    assert r.code == 0
    assert [h["snippet_id"] for h in r.json()["snippets"]] == [S2]
    req = lc.backend.requests[-1]
    assert req.path == "/api/search" and req.query == {"q": "second thoughts", "limit": "5"}


def test_reads_never_write(lc: Env) -> None:
    for argv in (["whoami"], ["canvases"], ["canvas", "Drafts"], ["tags"], ["presets"], ["bank"], ["search", "x"], ["dump"]):
        assert lc.run(*argv).code == 0
    assert lc.backend.writes() == []


def test_usage_error_exit_code() -> None:
    err = io.StringIO()
    from lcxlib.cli import main

    assert main(["frobnicate"], env={}, stdout=io.StringIO(), stderr=err) == 2
