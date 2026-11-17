"""Fixtures: a seeded fake backend served over HTTP, and a runner for lcx's ``main``."""

from __future__ import annotations

import io
import json
import sys
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest

AGENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(AGENT_DIR))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from fake_server import TOKEN, FakeBackend, FakeServer, uid  # noqa: E402
from lcxlib.cli import main  # noqa: E402

# Seed ids
DRAFTS, ALBUM, DUP1, DUP2 = uid(100), uid(101), uid(102), uid(103)
S1, S2, S3, S4, S5 = uid(201), uid(202), uid(203), uid(204), uid(205)
T_DRAFT, T_HOOK = uid(301), uid(302)
W1, W2 = uid(401), uid(402)


def seed(b: FakeBackend) -> None:
    b.add_canvas(DRAFTS, "Drafts")
    b.add_canvas(ALBUM, "Album")
    b.add_canvas(DUP1, "Dup")
    b.add_canvas(DUP2, "Dup")
    b.add_tag(T_DRAFT, "draft")
    b.add_tag(T_HOOK, "Hook")
    b.add_snippet(S1, "Midnight on the boulevard\nlights are low", [DRAFTS], version=3, tags=[T_DRAFT], ranks=["a1"])
    b.add_snippet(S2, "Second thoughts", [DRAFTS], ranks=["a0"])
    b.add_snippet(S3, "Third verse here", [DRAFTS, ALBUM], ranks=["a2", "a0"])
    b.add_snippet(S4, "Album opener", [ALBUM], ranks=["a1"], snippet_type="chorus")
    b.add_snippet(S5, "Dup only", [DUP1], ranks=["a0"])
    b.add_word(W1, "boulevard", [T_DRAFT])
    b.add_word(W2, "low")


@dataclass
class Result:
    code: int
    out: str
    err: str

    def json(self) -> Any:
        return json.loads(self.out)


@dataclass
class Env:
    backend: FakeBackend
    url: str
    tmp: Path

    def env(self, **extra: str) -> dict[str, str]:
        return {"LYRICS_API_URL": self.url, "LYRICS_API_TOKEN": TOKEN, "LCX_ENV_FILE": str(self.tmp / "none.env"), **extra}

    def run(self, *argv: str, env: dict[str, str] | None = None) -> Result:
        out, err = io.StringIO(), io.StringIO()
        code = main(list(argv), env=self.env() if env is None else env, stdout=out, stderr=err)
        return Result(int(code), out.getvalue(), err.getvalue())

    def plan_file(self, ops: list[dict[str, Any]], description: str = "test plan", name: str = "plan.json") -> str:
        path = self.tmp / name
        path.write_text(json.dumps({"version": 1, "description": description, "ops": ops}))
        return str(path)


@pytest.fixture
def lc(tmp_path: Path) -> Iterator[Env]:
    backend = FakeBackend()
    seed(backend)
    with FakeServer(backend) as server:
        yield Env(backend, server.url, tmp_path)
