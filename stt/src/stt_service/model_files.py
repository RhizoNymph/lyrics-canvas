"""Locate model weights: cache first, download only on a cache miss.

Checking the cache with `local_files_only=True` first means a pod whose cache is
populated starts without any network access (no revision lookup against the
Hub), whatever the reason the network is unavailable.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from huggingface_hub.errors import LocalEntryNotFoundError


class ModelFilesUnavailable(Exception):
    """The weights are neither cached nor downloadable."""


class Download(Protocol):
    def __call__(self, size_or_id: str, *, local_files_only: bool, cache_dir: str | None) -> str: ...


@dataclass(frozen=True, slots=True)
class ResolvedModel:
    path: str
    downloaded: bool


def _hub_download(size_or_id: str, *, local_files_only: bool, cache_dir: str | None) -> str:
    from faster_whisper.utils import download_model

    return download_model(size_or_id, local_files_only=local_files_only, cache_dir=cache_dir)


def _is_local_dir(spec: str) -> bool:
    # Aliases and repo ids are never looked up in the working directory, which
    # may not even be readable by the service user.
    if not spec.startswith(("/", "./", "../")):
        return False
    try:
        return Path(spec).is_dir()
    except OSError:
        return False


def resolve_model(
    spec: str, cache_dir: Path | None, *, offline: bool, download: Download = _hub_download
) -> ResolvedModel:
    """`spec` is a faster-whisper size alias (`large-v3-turbo`), a Hub repo id, or a local
    directory given as an absolute or `./`-relative path."""
    if _is_local_dir(spec):
        return ResolvedModel(path=spec, downloaded=False)
    cache = str(cache_dir) if cache_dir is not None else None
    try:
        return ResolvedModel(path=download(spec, local_files_only=True, cache_dir=cache), downloaded=False)
    except LocalEntryNotFoundError as exc:
        if offline:
            raise ModelFilesUnavailable(
                f"model {spec!r} is not in the cache {cache or '(default)'} and offline mode is on"
            ) from exc
    try:
        return ResolvedModel(path=download(spec, local_files_only=False, cache_dir=cache), downloaded=True)
    except (OSError, ValueError) as exc:
        # requests/hub network and HTTP errors derive from OSError; bad ids from ValueError.
        raise ModelFilesUnavailable(
            f"could not download model {spec!r}: {type(exc).__name__}: {exc}"
        ) from exc
