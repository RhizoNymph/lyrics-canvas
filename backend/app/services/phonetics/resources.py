"""NLTK data needed by g2p_en, installed into a private directory without nltk.download.

g2p_en looks for ``taggers/averaged_perceptron_tagger`` (legacy pickle name, presence only),
``corpora/cmudict`` and, through nltk.pos_tag, ``taggers/averaged_perceptron_tagger_eng``.
The packages are fetched as zips from the nltk_data GitHub pages tree and unpacked next to
the zip, which is the layout ``nltk.data.find`` expects.
"""

from __future__ import annotations

import io
import logging
import os
import zipfile
from dataclasses import dataclass
from pathlib import Path

import httpx

from app.services.phonetics.errors import ResourceError

logger = logging.getLogger(__name__)

BASE_URL = "https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/"


@dataclass(frozen=True, slots=True)
class Package:
    subdir: str
    name: str
    required: bool = True


PACKAGES: tuple[Package, ...] = (
    Package("taggers", "averaged_perceptron_tagger_eng"),
    Package("corpora", "cmudict"),
    Package("taggers", "averaged_perceptron_tagger", required=False),
)


def default_data_dir() -> Path:
    env = os.environ.get("NLTK_DATA_DIR") or os.environ.get("NLTK_DATA")
    if env:
        return Path(env.split(os.pathsep)[0])
    return Path(__file__).resolve().parents[3] / ".nltk_data"


def _installed(data_dir: Path, package: Package) -> bool:
    base = data_dir / package.subdir
    return (base / f"{package.name}.zip").is_file() and (base / package.name).is_dir()


def _install(data_dir: Path, package: Package, base_url: str, timeout: float) -> None:
    url = f"{base_url}{package.subdir}/{package.name}.zip"
    try:
        response = httpx.get(url, follow_redirects=True, timeout=timeout)
        response.raise_for_status()
    except httpx.HTTPError as exc:
        raise ResourceError(f"could not download {url}: {exc}") from exc
    target = data_dir / package.subdir
    target.mkdir(parents=True, exist_ok=True, mode=0o700)
    try:
        with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
            archive.extractall(target)
    except zipfile.BadZipFile as exc:
        raise ResourceError(f"corrupt archive at {url}") from exc
    (target / f"{package.name}.zip").write_bytes(response.content)


def ensure_resources(
    data_dir: Path | None = None,
    *,
    download: bool = True,
    base_url: str = BASE_URL,
    timeout: float = 120.0,
) -> Path:
    """Install the NLTK packages g2p_en needs into ``data_dir`` and register it with nltk.

    Returns the directory. Raises ResourceError when a required package is missing and
    cannot be downloaded.
    """
    data_dir = (data_dir or default_data_dir()).resolve()
    data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    for package in PACKAGES:
        if _installed(data_dir, package):
            continue
        if not download:
            if package.required:
                raise ResourceError(f"missing NLTK package {package.subdir}/{package.name} in {data_dir}")
            continue
        try:
            _install(data_dir, package, base_url, timeout)
            logger.info("phonetics.resources.installed package=%s dir=%s", package.name, data_dir)
        except ResourceError:
            if package.required:
                raise
            logger.warning("phonetics.resources.optional_missing package=%s", package.name)
    import nltk

    path = str(data_dir)
    if path not in nltk.data.path:
        nltk.data.path.insert(0, path)
    return data_dir
