"""Backend sources must parse on the oldest Python the image runs (the Dockerfile's base image).

Tests run on a newer interpreter, so syntax added later (PEP 695 type parameter lists and ``type``
aliases) would pass here and crash the deployed backend at import. ``ast.parse`` with
``feature_version`` rejects that syntax; it does not catch PEP 701 f-string quote reuse.
"""

import ast
import re
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parents[1]
SOURCE_DIRS = ("app", "scripts", "alembic", "tests")


def _dockerfile_python() -> tuple[int, int]:
    text = (BACKEND / "Dockerfile").read_text(encoding="utf-8")
    match = re.search(r"^FROM python:(\d+)\.(\d+)", text, re.MULTILINE)
    assert match, "Dockerfile has no python:<major>.<minor> base image"
    return int(match.group(1)), int(match.group(2))


def _sources() -> list[Path]:
    return sorted(p for d in SOURCE_DIRS for p in (BACKEND / d).rglob("*.py"))


def test_requires_python_matches_the_image():
    major, minor = _dockerfile_python()
    pyproject = (BACKEND / "pyproject.toml").read_text(encoding="utf-8")
    assert f'requires-python = ">={major}.{minor}"' in pyproject


@pytest.mark.parametrize("path", _sources(), ids=lambda p: str(p.relative_to(BACKEND)))
def test_source_parses_on_the_image_python(path: Path):
    ast.parse(path.read_text(encoding="utf-8"), filename=str(path), feature_version=_dockerfile_python())
