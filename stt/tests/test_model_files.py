from pathlib import Path

import pytest
from huggingface_hub.errors import LocalEntryNotFoundError

from stt_service.model_files import ModelFilesUnavailable, resolve_model


class FakeDownload:
    def __init__(self, cached: bool, network_error: Exception | None = None) -> None:
        self.cached = cached
        self.network_error = network_error
        self.calls: list[dict] = []

    def __call__(self, size_or_id: str, *, local_files_only: bool, cache_dir: str | None) -> str:
        self.calls.append({"model": size_or_id, "local_files_only": local_files_only, "cache_dir": cache_dir})
        if local_files_only and not self.cached:
            raise LocalEntryNotFoundError("not in cache")
        if not local_files_only and self.network_error is not None:
            raise self.network_error
        return f"/cache/{size_or_id}/snapshot"


def test_cached_model_never_touches_network() -> None:
    dl = FakeDownload(cached=True)
    resolved = resolve_model("large-v3-turbo", Path("/models/hub"), offline=False, download=dl)
    assert resolved.path == "/cache/large-v3-turbo/snapshot"
    assert resolved.downloaded is False
    assert dl.calls == [{"model": "large-v3-turbo", "local_files_only": True, "cache_dir": "/models/hub"}]


def test_cache_miss_downloads() -> None:
    dl = FakeDownload(cached=False)
    resolved = resolve_model("tiny", None, offline=False, download=dl)
    assert resolved.downloaded is True
    assert [c["local_files_only"] for c in dl.calls] == [True, False]
    assert dl.calls[1]["cache_dir"] is None


def test_offline_cache_miss_fails_without_download() -> None:
    dl = FakeDownload(cached=False)
    with pytest.raises(ModelFilesUnavailable, match="offline"):
        resolve_model("tiny", Path("/m"), offline=True, download=dl)
    assert [c["local_files_only"] for c in dl.calls] == [True]


def test_download_failure_is_typed() -> None:
    dl = FakeDownload(cached=False, network_error=OSError("name resolution failed"))
    with pytest.raises(ModelFilesUnavailable, match="name resolution failed"):
        resolve_model("tiny", Path("/m"), offline=False, download=dl)


def test_local_directory_used_as_is(tmp_path: Path) -> None:
    dl = FakeDownload(cached=False)
    resolved = resolve_model(str(tmp_path), None, offline=True, download=dl)
    assert resolved.path == str(tmp_path)
    assert resolved.downloaded is False
    assert dl.calls == []


def test_alias_is_not_resolved_against_working_directory(tmp_path: Path, monkeypatch) -> None:
    (tmp_path / "tiny").mkdir()
    monkeypatch.chdir(tmp_path)
    dl = FakeDownload(cached=True)
    resolved = resolve_model("tiny", None, offline=True, download=dl)
    assert resolved.path == "/cache/tiny/snapshot"
    assert len(dl.calls) == 1


def test_unreadable_working_directory_is_harmless(tmp_path: Path, monkeypatch) -> None:
    locked = tmp_path / "locked"
    locked.mkdir()
    monkeypatch.chdir(locked)
    locked.chmod(0)
    try:
        dl = FakeDownload(cached=True)
        assert resolve_model("tiny", None, offline=True, download=dl).path == "/cache/tiny/snapshot"
    finally:
        locked.chmod(0o700)


def test_missing_absolute_path_falls_back_to_hub_lookup() -> None:
    dl = FakeDownload(cached=False)
    with pytest.raises(ModelFilesUnavailable):
        resolve_model("/does/not/exist", None, offline=True, download=dl)
